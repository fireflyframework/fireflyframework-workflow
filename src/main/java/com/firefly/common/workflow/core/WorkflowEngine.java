/*
 * Copyright 2025 Firefly Software Solutions Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.firefly.common.workflow.core;

import com.firefly.common.workflow.event.WorkflowEventPublisher;
import com.firefly.common.workflow.exception.StepExecutionException;
import com.firefly.common.workflow.exception.WorkflowNotFoundException;
import com.firefly.common.workflow.metrics.WorkflowMetrics;
import com.firefly.common.workflow.model.*;
import com.firefly.common.workflow.properties.WorkflowProperties;
import com.firefly.common.workflow.state.StepStateStore;
import com.firefly.common.workflow.state.WorkflowStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Main facade for workflow operations.
 * <p>
 * The WorkflowEngine provides a high-level API for:
 * <ul>
 *   <li>Starting workflow instances</li>
 *   <li>Triggering individual steps (step-level choreography)</li>
 *   <li>Querying workflow and step status</li>
 *   <li>Cancelling workflows</li>
 *   <li>Retrying failed workflows</li>
 *   <li>Registering workflow definitions</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * // Start a workflow
 * workflowEngine.startWorkflow("order-processing", Map.of("orderId", "123"))
 *     .subscribe(instance -> log.info("Started: {}", instance.instanceId()));
 *
 * // Get status
 * workflowEngine.getStatus("order-processing", instanceId)
 *     .subscribe(instance -> log.info("Status: {}", instance.status()));
 *
 * // Get result when completed
 * workflowEngine.collectResult("order-processing", instanceId, Order.class)
 *     .subscribe(order -> log.info("Result: {}", order));
 * }
 * </pre>
 */
@Slf4j
public class WorkflowEngine {

    private final WorkflowRegistry registry;
    private final WorkflowExecutor executor;
    private final WorkflowStateStore stateStore;
    private final WorkflowEventPublisher eventPublisher;
    private final WorkflowProperties properties;
    private final StepStateStore stepStateStore;
    private final WorkflowMetrics workflowMetrics;

    public WorkflowEngine(
            WorkflowRegistry registry,
            WorkflowExecutor executor,
            WorkflowStateStore stateStore,
            WorkflowEventPublisher eventPublisher,
            WorkflowProperties properties) {
        this(registry, executor, stateStore, eventPublisher, properties, null, null);
    }

    public WorkflowEngine(
            WorkflowRegistry registry,
            WorkflowExecutor executor,
            WorkflowStateStore stateStore,
            WorkflowEventPublisher eventPublisher,
            WorkflowProperties properties,
            @Nullable StepStateStore stepStateStore) {
        this(registry, executor, stateStore, eventPublisher, properties, stepStateStore, null);
    }

    public WorkflowEngine(
            WorkflowRegistry registry,
            WorkflowExecutor executor,
            WorkflowStateStore stateStore,
            WorkflowEventPublisher eventPublisher,
            WorkflowProperties properties,
            @Nullable StepStateStore stepStateStore,
            @Nullable WorkflowMetrics workflowMetrics) {
        this.registry = registry;
        this.executor = executor;
        this.stateStore = stateStore;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.stepStateStore = stepStateStore;
        this.workflowMetrics = workflowMetrics;
    }

    /**
     * Starts a new workflow instance.
     *
     * @param workflowId the workflow ID
     * @param input the input data
     * @return the created workflow instance
     */
    public Mono<WorkflowInstance> startWorkflow(String workflowId, Map<String, Object> input) {
        return startWorkflow(workflowId, input, null, "api");
    }

    /**
     * Starts a new workflow instance with correlation ID.
     *
     * @param workflowId the workflow ID
     * @param input the input data
     * @param correlationId optional correlation ID
     * @param triggeredBy identifier of the trigger source
     * @return the created workflow instance
     */
    public Mono<WorkflowInstance> startWorkflow(
            String workflowId,
            Map<String, Object> input,
            String correlationId,
            String triggeredBy) {

        return Mono.defer(() -> {
            Instant workflowStartTime = Instant.now();

            WorkflowDefinition definition = registry.get(workflowId)
                    .orElseThrow(() -> new WorkflowNotFoundException(workflowId));

            log.info("WORKFLOW_START: workflowId={}, correlationId={}, triggeredBy={}, stepCount={}",
                    workflowId, correlationId, triggeredBy, definition.steps().size());

            // Record workflow started metric
            if (workflowMetrics != null) {
                workflowMetrics.recordWorkflowStarted(workflowId, triggeredBy);
            }

            // Create instance
            WorkflowInstance instance = WorkflowInstance.create(
                    definition, input, correlationId, triggeredBy);

            // Get first step
            Optional<WorkflowStepDefinition> firstStep = definition.getFirstStep();
            if (firstStep.isEmpty()) {
                log.warn("WORKFLOW_EMPTY: workflowId={}, instanceId={}, reason=no_steps",
                        workflowId, instance.instanceId());
                WorkflowInstance completed = instance.complete(null);
                // Record workflow completed metric for empty workflow
                if (workflowMetrics != null) {
                    workflowMetrics.recordWorkflowCompleted(workflowId, WorkflowStatus.COMPLETED,
                            Duration.between(workflowStartTime, Instant.now()));
                }
                return stateStore.save(completed);
            }

            log.debug("WORKFLOW_FIRST_STEP: workflowId={}, instanceId={}, firstStepId={}, firstStepName={}",
                    workflowId, instance.instanceId(), firstStep.get().stepId(), firstStep.get().name());

            // Start workflow
            WorkflowInstance started = instance.start(firstStep.get().stepId());

            return stateStore.save(started)
                    .flatMap(saved -> eventPublisher.publishWorkflowStarted(saved).thenReturn(saved))
                    .flatMap(saved -> executor.executeWorkflow(definition, saved))
                    .doOnSuccess(completed -> {
                            Duration workflowDuration = Duration.between(workflowStartTime, Instant.now());
                            log.info("WORKFLOW_COMPLETE: workflowId={}, instanceId={}, status={}, durationMs={}",
                                    workflowId, completed.instanceId(), completed.status(), workflowDuration.toMillis());
                            // Record workflow completed metric
                            if (workflowMetrics != null) {
                                workflowMetrics.recordWorkflowCompleted(workflowId, completed.status(), workflowDuration);
                            }
                    })
                    .doOnError(error -> {
                            Duration workflowDuration = Duration.between(workflowStartTime, Instant.now());
                            log.error("WORKFLOW_ERROR: workflowId={}, instanceId={}, error={}",
                                    workflowId, instance.instanceId(), error.getMessage());
                            // Record workflow failed metric
                            if (workflowMetrics != null) {
                                workflowMetrics.recordWorkflowFailed(workflowId, workflowDuration);
                            }
                    });
        });
    }

    /**
     * Gets the status of a workflow instance.
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @return the workflow instance
     */
    public Mono<WorkflowInstance> getStatus(String workflowId, String instanceId) {
        return stateStore.findByWorkflowAndInstanceId(workflowId, instanceId)
                .switchIfEmpty(Mono.error(new WorkflowNotFoundException(
                        "Instance not found: workflowId=" + workflowId + ", instanceId=" + instanceId)));
    }

    /**
     * Gets the status of a workflow instance by instance ID only.
     *
     * @param instanceId the instance ID
     * @return the workflow instance
     */
    public Mono<WorkflowInstance> getStatus(String instanceId) {
        return stateStore.findById(instanceId)
                .switchIfEmpty(Mono.error(new WorkflowNotFoundException(
                        "Instance not found: instanceId=" + instanceId)));
    }

    /**
     * Collects the result of a completed workflow.
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @param resultType the expected result type
     * @param <T> the result type parameter
     * @return the workflow result
     */
    public <T> Mono<T> collectResult(String workflowId, String instanceId, Class<T> resultType) {
        return getStatus(workflowId, instanceId)
                .flatMap(instance -> {
                    if (!instance.status().isTerminal()) {
                        return Mono.error(new IllegalStateException(
                                "Workflow is not completed: status=" + instance.status()));
                    }
                    
                    if (instance.status() == WorkflowStatus.FAILED) {
                        return Mono.error(new IllegalStateException(
                                "Workflow failed: " + instance.errorMessage()));
                    }
                    
                    if (instance.status() == WorkflowStatus.CANCELLED) {
                        return Mono.error(new IllegalStateException("Workflow was cancelled"));
                    }
                    
                    Object output = instance.output();
                    if (output == null) {
                        return Mono.empty();
                    }
                    
                    if (resultType.isInstance(output)) {
                        return Mono.just(resultType.cast(output));
                    }
                    
                    return Mono.error(new IllegalStateException(
                            "Cannot convert output to " + resultType.getName()));
                });
    }

    /**
     * Cancels a running workflow instance.
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @return the cancelled instance
     */
    public Mono<WorkflowInstance> cancelWorkflow(String workflowId, String instanceId) {
        return getStatus(workflowId, instanceId)
                .flatMap(instance -> {
                    if (instance.status().isTerminal()) {
                        return Mono.error(new IllegalStateException(
                                "Cannot cancel workflow in status: " + instance.status()));
                    }
                    
                    WorkflowInstance cancelled = instance.cancel();
                    return stateStore.save(cancelled)
                            .flatMap(saved -> eventPublisher.publishWorkflowCancelled(saved)
                                    .thenReturn(saved));
                });
    }

    /**
     * Triggers execution of a specific step.
     * <p>
     * This method supports step-level choreography where individual steps
     * are triggered independently via events or API calls.
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @param stepId the step ID to trigger
     * @param input optional input data for this step
     * @param triggeredBy how this step was triggered (e.g., "api", "event:order.created")
     * @return the updated workflow instance
     */
    public Mono<WorkflowInstance> triggerStep(
            String workflowId,
            String instanceId,
            String stepId,
            Map<String, Object> input,
            String triggeredBy) {

        return Mono.defer(() -> {
            WorkflowDefinition definition = registry.get(workflowId)
                    .orElseThrow(() -> new WorkflowNotFoundException(workflowId));

            WorkflowStepDefinition stepDef = definition.findStep(stepId)
                    .orElseThrow(() -> new StepExecutionException(
                            "Step not found: " + stepId + " in workflow: " + workflowId));

            log.info("STEP_TRIGGER: workflowId={}, instanceId={}, stepId={}, stepName={}, triggeredBy={}, hasInput={}",
                    workflowId, instanceId, stepId, stepDef.name(), triggeredBy, input != null && !input.isEmpty());

            return stateStore.findByWorkflowAndInstanceId(workflowId, instanceId)
                    .switchIfEmpty(Mono.error(new WorkflowNotFoundException(
                            "Instance not found: workflowId=" + workflowId + ", instanceId=" + instanceId)))
                    .flatMap(instance -> executor.executeSingleStep(
                            definition, instance, stepId, triggeredBy, input));
        });
    }

    /**
     * Gets the state of a specific step.
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @param stepId the step ID
     * @return the step state
     */
    public Mono<StepState> getStepState(String workflowId, String instanceId, String stepId) {
        if (stepStateStore == null) {
            return Mono.error(new UnsupportedOperationException(
                    "Step-level state tracking is not enabled"));
        }
        return stepStateStore.getStepState(workflowId, instanceId, stepId);
    }

    /**
     * Gets all step states for a workflow instance.
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @return flux of step states
     */
    public Flux<StepState> getStepStates(String workflowId, String instanceId) {
        if (stepStateStore == null) {
            return Flux.error(new UnsupportedOperationException(
                    "Step-level state tracking is not enabled"));
        }
        return stepStateStore.getStepStates(workflowId, instanceId);
    }

    /**
     * Gets the comprehensive workflow state (dashboard view).
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @return the workflow state with tracking info
     */
    public Mono<WorkflowState> getWorkflowState(String workflowId, String instanceId) {
        if (stepStateStore == null) {
            return Mono.error(new UnsupportedOperationException(
                    "Step-level state tracking is not enabled"));
        }
        return stepStateStore.getWorkflowState(workflowId, instanceId);
    }

    /**
     * Finds steps that are waiting for a specific event type.
     *
     * @param eventType the event type
     * @return flux of step states waiting for this event
     */
    public Flux<StepState> findStepsWaitingForEvent(String eventType) {
        if (stepStateStore == null) {
            return Flux.empty();
        }
        return stepStateStore.findStepsWaitingForEvent(eventType);
    }

    /**
     * Finds workflow definitions that have a step matching the given input event type.
     *
     * @param eventType the event type
     * @return list of matching workflow definitions with step info
     */
    public List<WorkflowStepMatch> findStepsByInputEvent(String eventType) {
        return registry.getAll().stream()
                .flatMap(workflow -> workflow.steps().stream()
                        .filter(step -> eventType.equals(step.inputEventType()))
                        .map(step -> new WorkflowStepMatch(workflow, step)))
                .toList();
    }

    /**
     * Record to hold a matching workflow and step pair.
     */
    public record WorkflowStepMatch(WorkflowDefinition workflow, WorkflowStepDefinition step) {}

    /**
     * Retries a failed workflow from the failed step.
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @return the restarted instance
     */
    public Mono<WorkflowInstance> retryWorkflow(String workflowId, String instanceId) {
        return getStatus(workflowId, instanceId)
                .flatMap(instance -> {
                    if (instance.status() != WorkflowStatus.FAILED) {
                        return Mono.error(new IllegalStateException(
                                "Can only retry failed workflows: status=" + instance.status()));
                    }
                    
                    WorkflowDefinition definition = registry.get(workflowId)
                            .orElseThrow(() -> new WorkflowNotFoundException(workflowId));
                    
                    // Find the failed step
                    String failedStepId = instance.currentStepId();
                    if (failedStepId == null) {
                        return Mono.error(new IllegalStateException("No failed step to retry"));
                    }
                    
                    WorkflowStepDefinition stepDef = definition.findStep(failedStepId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Step not found: " + failedStepId));
                    
                    // Reset instance to running state
                    WorkflowInstance retrying = new WorkflowInstance(
                            instance.instanceId(),
                            instance.workflowId(),
                            instance.workflowName(),
                            instance.workflowVersion(),
                            WorkflowStatus.RUNNING,
                            failedStepId,
                            instance.context(),
                            instance.input(),
                            null,
                            instance.stepExecutions(),
                            null, null,
                            instance.correlationId(),
                            instance.triggeredBy(),
                            instance.createdAt(),
                            instance.startedAt(),
                            null
                    );
                    
                    return stateStore.save(retrying)
                            .flatMap(saved -> executor.executeStep(definition, saved, stepDef, "retry"))
                            .flatMap(result -> {
                                if (!result.status().isTerminal()) {
                                    // Continue with remaining steps
                                    return executor.executeWorkflow(definition, result);
                                }
                                return Mono.just(result);
                            });
                });
    }

    /**
     * Finds all instances of a workflow.
     *
     * @param workflowId the workflow ID
     * @return flux of instances
     */
    public Flux<WorkflowInstance> findInstances(String workflowId) {
        return stateStore.findByWorkflowId(workflowId);
    }

    /**
     * Finds all instances with a specific status.
     *
     * @param workflowId the workflow ID
     * @param status the status
     * @return flux of instances
     */
    public Flux<WorkflowInstance> findInstances(String workflowId, WorkflowStatus status) {
        return stateStore.findByWorkflowIdAndStatus(workflowId, status);
    }

    /**
     * Finds all active (running/waiting) instances.
     *
     * @return flux of active instances
     */
    public Flux<WorkflowInstance> findActiveInstances() {
        return stateStore.findActiveInstances();
    }

    /**
     * Finds workflows by correlation ID.
     *
     * @param correlationId the correlation ID
     * @return flux of instances
     */
    public Flux<WorkflowInstance> findByCorrelationId(String correlationId) {
        return stateStore.findByCorrelationId(correlationId);
    }

    /**
     * Finds workflow definitions that can be triggered by an event type.
     *
     * @param eventType the event type
     * @return flux of matching workflow definitions
     */
    public Flux<WorkflowDefinition> findWorkflowsByTriggerEvent(String eventType) {
        return Flux.fromIterable(registry.findByTriggerEvent(eventType));
    }

    /**
     * Registers a workflow definition.
     *
     * @param workflow the workflow definition
     */
    public void registerWorkflow(WorkflowDefinition workflow) {
        registry.register(workflow);
    }

    /**
     * Unregisters a workflow definition.
     *
     * @param workflowId the workflow ID
     * @return true if removed
     */
    public boolean unregisterWorkflow(String workflowId) {
        return registry.unregister(workflowId);
    }

    /**
     * Gets a workflow definition by ID.
     *
     * @param workflowId the workflow ID
     * @return optional containing the definition
     */
    public Optional<WorkflowDefinition> getWorkflowDefinition(String workflowId) {
        return registry.get(workflowId);
    }

    /**
     * Gets all registered workflow definitions.
     *
     * @return collection of all workflows
     */
    public Collection<WorkflowDefinition> getAllWorkflows() {
        return registry.getAll();
    }

    /**
     * Checks if step state tracking is enabled.
     *
     * @return true if step state tracking is enabled
     */
    public boolean isStepStateTrackingEnabled() {
        return stepStateStore != null;
    }

    /**
     * Checks if the workflow engine is healthy.
     *
     * @return true if healthy
     */
    public Mono<Boolean> isHealthy() {
        return stateStore.isHealthy();
    }
}
