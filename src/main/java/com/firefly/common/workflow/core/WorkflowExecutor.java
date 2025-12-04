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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firefly.common.workflow.aspect.WorkflowAspect;
import com.firefly.common.workflow.event.WorkflowEventPublisher;
import com.firefly.common.workflow.exception.StepExecutionException;
import com.firefly.common.workflow.exception.WorkflowException;
import com.firefly.common.workflow.metrics.WorkflowMetrics;
import com.firefly.common.workflow.model.*;
import com.firefly.common.workflow.properties.WorkflowProperties;
import com.firefly.common.workflow.resilience.WorkflowResilience;
import com.firefly.common.workflow.state.StepStateStore;
import com.firefly.common.workflow.state.WorkflowStateStore;
import com.firefly.common.workflow.tracing.WorkflowTracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes workflow steps and manages workflow progression.
 * <p>
 * The WorkflowExecutor is responsible for:
 * <ul>
 *   <li>Executing individual steps independently (step-level choreography)</li>
 *   <li>Managing step transitions</li>
 *   <li>Handling retries and failures</li>
 *   <li>Publishing step events</li>
 *   <li>Persisting step and workflow state independently in cache</li>
 *   <li>Supporting event-driven step execution</li>
 * </ul>
 */
@Slf4j
public class WorkflowExecutor {

    private final WorkflowStateStore stateStore;
    private final StepStateStore stepStateStore;
    private final WorkflowEventPublisher eventPublisher;
    private final WorkflowProperties properties;
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final WorkflowAspect workflowAspect;
    private final WorkflowTracer workflowTracer;
    private final WorkflowMetrics workflowMetrics;
    private final WorkflowResilience workflowResilience;

    private final ExpressionParser spelParser = new SpelExpressionParser();
    private final Map<String, WorkflowTopology> topologyCache = new ConcurrentHashMap<>();

    public WorkflowExecutor(
            WorkflowStateStore stateStore,
            @Nullable StepStateStore stepStateStore,
            WorkflowEventPublisher eventPublisher,
            WorkflowProperties properties,
            ApplicationContext applicationContext,
            ObjectMapper objectMapper,
            WorkflowAspect workflowAspect,
            @Nullable WorkflowTracer workflowTracer,
            @Nullable WorkflowMetrics workflowMetrics,
            @Nullable WorkflowResilience workflowResilience) {
        this.stateStore = stateStore;
        this.stepStateStore = stepStateStore;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.applicationContext = applicationContext;
        this.objectMapper = objectMapper;
        this.workflowAspect = workflowAspect;
        this.workflowTracer = workflowTracer;
        this.workflowMetrics = workflowMetrics;
        this.workflowResilience = workflowResilience;
    }

    /**
     * Executes all steps in a workflow using topology-based execution.
     * <p>
     * Steps are organized into execution layers based on their dependencies.
     * Steps within the same layer can execute in parallel if marked async.
     *
     * @param definition the workflow definition
     * @param instance the workflow instance
     * @return the completed workflow instance
     */
    public Mono<WorkflowInstance> executeWorkflow(WorkflowDefinition definition, WorkflowInstance instance) {
        return initializeWorkflowState(definition, instance, "workflow")
                .then(Mono.defer(() -> {
                    if (definition.steps().isEmpty()) {
                        log.warn("Workflow {} has no steps to execute", definition.workflowId());
                        return Mono.just(instance.complete(null));
                    }

                    // Get or create topology for this workflow
                    WorkflowTopology topology = getOrCreateTopology(definition);
                    List<List<WorkflowStepDefinition>> layers = topology.buildExecutionLayers();

                    log.debug("Executing workflow {} with {} layers",
                            definition.workflowId(), layers.size());

                    // Execute layers sequentially
                    return executeLayersSequentially(definition, instance, layers, 0, "workflow");
                }));
    }

    /**
     * Gets or creates a topology for the given workflow definition.
     *
     * @param definition the workflow definition
     * @return the workflow topology
     */
    private WorkflowTopology getOrCreateTopology(WorkflowDefinition definition) {
        return topologyCache.computeIfAbsent(definition.workflowId(),
                id -> new WorkflowTopology(definition));
    }

    /**
     * Executes workflow layers sequentially.
     * <p>
     * Each layer contains steps that can execute in parallel (if async).
     * Layers are executed in order to respect dependencies.
     */
    private Mono<WorkflowInstance> executeLayersSequentially(
            WorkflowDefinition definition,
            WorkflowInstance instance,
            List<List<WorkflowStepDefinition>> layers,
            int layerIndex,
            String triggeredBy) {

        if (layerIndex >= layers.size()) {
            // All layers completed
            return completeWorkflow(definition, instance);
        }

        if (instance.status().isTerminal()) {
            return Mono.just(instance);
        }

        if (instance.status() == WorkflowStatus.WAITING) {
            return Mono.just(instance);
        }

        List<WorkflowStepDefinition> currentLayer = layers.get(layerIndex);
        log.debug("Executing layer {} with {} steps for workflow {}",
                layerIndex, currentLayer.size(), definition.workflowId());

        // Execute all steps in the current layer
        return executeLayer(definition, instance, currentLayer, triggeredBy)
                .flatMap(updatedInstance -> {
                    if (updatedInstance.status().isTerminal() ||
                        updatedInstance.status() == WorkflowStatus.WAITING) {
                        return Mono.just(updatedInstance);
                    }
                    // Continue to next layer
                    return executeLayersSequentially(definition, updatedInstance, layers,
                            layerIndex + 1, triggeredBy);
                });
    }

    /**
     * Executes all steps in a layer.
     * <p>
     * If multiple steps are in the layer and at least one is async,
     * they execute in parallel. Otherwise, they execute sequentially.
     */
    private Mono<WorkflowInstance> executeLayer(
            WorkflowDefinition definition,
            WorkflowInstance instance,
            List<WorkflowStepDefinition> steps,
            String triggeredBy) {

        if (steps.isEmpty()) {
            return Mono.just(instance);
        }

        if (steps.size() == 1) {
            // Single step - execute directly
            return executeStepWithTracking(definition, instance, steps.get(0), triggeredBy);
        }

        // Check if any step is async - if so, execute in parallel
        boolean hasAsyncSteps = steps.stream().anyMatch(WorkflowStepDefinition::async);

        if (hasAsyncSteps) {
            return executeParallelSteps(definition, instance, steps, triggeredBy);
        }

        // Execute sequentially within the layer
        return executeStepsSequentially(definition, instance, steps, 0, triggeredBy);
    }

    /**
     * Executes steps sequentially within a layer.
     */
    private Mono<WorkflowInstance> executeStepsSequentially(
            WorkflowDefinition definition,
            WorkflowInstance instance,
            List<WorkflowStepDefinition> steps,
            int stepIndex,
            String triggeredBy) {

        if (stepIndex >= steps.size()) {
            return Mono.just(instance);
        }

        if (instance.status().isTerminal() || instance.status() == WorkflowStatus.WAITING) {
            return Mono.just(instance);
        }

        return executeStepWithTracking(definition, instance, steps.get(stepIndex), triggeredBy)
                .flatMap(updatedInstance ->
                        executeStepsSequentially(definition, updatedInstance, steps,
                                stepIndex + 1, triggeredBy));
    }

    /**
     * Executes a single step by its ID.
     * <p>
     * This method is used for step-level choreography where individual steps
     * are triggered independently via events or API calls.
     *
     * @param definition the workflow definition
     * @param instance the workflow instance
     * @param stepId the ID of the step to execute
     * @param triggeredBy how this step was triggered (e.g., "api", "event:order.created")
     * @param input optional input data for this step
     * @return the updated workflow instance
     */
    public Mono<WorkflowInstance> executeSingleStep(
            WorkflowDefinition definition,
            WorkflowInstance instance,
            String stepId,
            String triggeredBy,
            Map<String, Object> input) {
        
        return definition.findStep(stepId)
                .map(stepDef -> {
                    log.info("Executing single step: workflowId={}, instanceId={}, stepId={}, triggeredBy={}",
                            definition.workflowId(), instance.instanceId(), stepId, triggeredBy);
                    
                    // Add input to the instance context if provided
                    WorkflowInstance withInput = input != null && !input.isEmpty()
                            ? instance.withContext(input)
                            : instance;
                    
                    return executeStepWithTracking(definition, withInput, stepDef, triggeredBy);
                })
                .orElseGet(() -> Mono.error(new StepExecutionException(
                        "Step not found: " + stepId + " in workflow: " + definition.workflowId())));
    }

    /**
     * Continues workflow execution from a waiting step after receiving an event.
     *
     * @param definition the workflow definition
     * @param instance the workflow instance
     * @param stepId the ID of the waiting step
     * @param eventType the event type that triggered this step
     * @param eventPayload the event payload to use as input
     * @return the updated workflow instance
     */
    public Mono<WorkflowInstance> resumeFromWaitingStep(
            WorkflowDefinition definition,
            WorkflowInstance instance,
            String stepId,
            String eventType,
            Map<String, Object> eventPayload) {
        
        String triggeredBy = "event:" + eventType;
        log.info("Resuming step from event: workflowId={}, instanceId={}, stepId={}, eventType={}",
                definition.workflowId(), instance.instanceId(), stepId, eventType);
        
        return executeSingleStep(definition, instance, stepId, triggeredBy, eventPayload);
    }

    /**
     * Executes multiple steps in parallel.
     */
    private Mono<WorkflowInstance> executeParallelSteps(
            WorkflowDefinition definition,
            WorkflowInstance instance,
            List<WorkflowStepDefinition> steps,
            String triggeredBy) {

        log.info("Executing {} steps in parallel for workflow {}",
                steps.size(), definition.workflowId());

        // Execute all steps in parallel and collect results
        return Flux.fromIterable(steps)
                .flatMap(step -> executeStepWithTracking(definition, instance, step, triggeredBy)
                        .map(result -> Map.entry(step.stepId(), result)))
                .collectList()
                .map(results -> {
                    // Merge all step executions into a single instance
                    WorkflowInstance merged = instance;
                    for (Map.Entry<String, WorkflowInstance> entry : results) {
                        WorkflowInstance stepResult = entry.getValue();
                        // Get the step execution from the result
                        stepResult.getStepExecution(entry.getKey())
                                .ifPresent(exec -> merged.withStepExecution(exec));
                    }
                    return merged;
                });
    }

    /**
     * Completes the workflow and updates workflow state.
     */
    private Mono<WorkflowInstance> completeWorkflow(WorkflowDefinition definition, WorkflowInstance instance) {
        Object output = getLastStepOutput(instance);
        WorkflowInstance completed = instance.complete(output);
        
        return saveAndPublish(completed, () -> 
                eventPublisher.publishWorkflowCompleted(completed))
                .flatMap(saved -> updateWorkflowStateOnCompletion(definition, saved));
    }

    /**
     * Initializes the workflow state for a new workflow execution.
     */
    private Mono<Void> initializeWorkflowState(
            WorkflowDefinition definition,
            WorkflowInstance instance,
            String triggeredBy) {
        
        if (stepStateStore == null) {
            return Mono.empty();
        }
        
        WorkflowState state = WorkflowState.create(
                definition,
                instance.instanceId(),
                instance.input() instanceof Map ? (Map<String, Object>) instance.input() : Map.of(),
                instance.correlationId(),
                triggeredBy);
        
        return stepStateStore.saveWorkflowState(state).then();
    }

    /**
     * Updates workflow state when the workflow completes.
     */
    private Mono<WorkflowInstance> updateWorkflowStateOnCompletion(
            WorkflowDefinition definition,
            WorkflowInstance instance) {
        
        if (stepStateStore == null) {
            return Mono.just(instance);
        }
        
        return stepStateStore.getWorkflowState(definition.workflowId(), instance.instanceId())
                .map(state -> state.complete(instance.output()))
                .flatMap(stepStateStore::saveWorkflowState)
                .thenReturn(instance)
                .onErrorResume(e -> {
                    log.warn("Failed to update workflow state on completion", e);
                    return Mono.just(instance);
                });
    }

    /**
     * Executes a single step with step-level state tracking.
     * <p>
     * This method persists both the step state and workflow state independently in cache.
     */
    private Mono<WorkflowInstance> executeStepWithTracking(
            WorkflowDefinition definition,
            WorkflowInstance instance,
            WorkflowStepDefinition stepDef,
            String triggeredBy) {
        
        String stepId = stepDef.stepId();
        
        // Create and save initial step state
        return initializeStepState(definition, instance, stepDef, triggeredBy)
                .then(executeStep(definition, instance, stepDef, triggeredBy));
    }

    /**
     * Initializes step state before execution.
     */
    private Mono<StepState> initializeStepState(
            WorkflowDefinition definition,
            WorkflowInstance instance,
            WorkflowStepDefinition stepDef,
            String triggeredBy) {
        
        if (stepStateStore == null) {
            return Mono.empty();
        }
        
        int maxRetries = stepDef.retryPolicy() != null 
                ? stepDef.retryPolicy().maxAttempts() 
                : (definition.retryPolicy() != null ? definition.retryPolicy().maxAttempts() : 3);
        
        StepState state = StepState.create(
                stepDef.stepId(),
                stepDef.name(),
                definition.workflowId(),
                instance.instanceId(),
                instance.correlationId(),
                maxRetries);
        
        return stepStateStore.saveStepState(state);
    }

    /**
     * Executes a single step with triggeredBy tracking.
     */
    public Mono<WorkflowInstance> executeStep(
            WorkflowDefinition definition,
            WorkflowInstance instance,
            WorkflowStepDefinition stepDef,
            String triggeredBy) {

        String stepId = stepDef.stepId();
        String stepName = stepDef.name();
        Instant stepStartTime = Instant.now();

        log.info("STEP_START: workflowId={}, instanceId={}, stepId={}, stepName={}, triggeredBy={}, async={}",
                definition.workflowId(), instance.instanceId(), stepId, stepName, triggeredBy, stepDef.async());

        // Record step started metric
        if (workflowMetrics != null) {
            workflowMetrics.recordStepStarted(definition.workflowId(), stepId);
        }

        // Create context for condition evaluation
        WorkflowContext preContext = new WorkflowContext(
                definition, instance, stepId, objectMapper);

        // Evaluate condition if present
        if (stepDef.condition() != null && !stepDef.condition().isEmpty()) {
            boolean conditionResult = evaluateCondition(stepDef.condition(), preContext);
            log.info("STEP_CONDITION: workflowId={}, instanceId={}, stepId={}, condition='{}', result={}",
                    definition.workflowId(), instance.instanceId(), stepId, stepDef.condition(), conditionResult);

            if (!conditionResult) {
                log.info("STEP_SKIPPED: workflowId={}, instanceId={}, stepId={}, reason=condition_false, durationMs={}",
                        definition.workflowId(), instance.instanceId(), stepId,
                        Duration.between(stepStartTime, Instant.now()).toMillis());
                // Record step skipped metric
                if (workflowMetrics != null) {
                    workflowMetrics.recordStepSkipped(definition.workflowId(), stepId, "condition_false");
                }
                StepExecution skipped = StepExecution.create(stepId, stepDef.name(), null).skip();
                WorkflowInstance skippedInstance = instance.withStepExecution(skipped);
                return saveStepAndWorkflowState(definition, skippedInstance, stepDef, skipped, triggeredBy, false)
                        .then(stateStore.save(skippedInstance));
            }
        }

        // Create step execution - preserve attempt number if retrying
        StepExecution existingExecution = instance.getStepExecution(stepId).orElse(null);
        int attemptNumber = (existingExecution != null && existingExecution.attemptNumber() > 1)
                ? existingExecution.attemptNumber()
                : 1;
        StepExecution execution = StepExecution.create(stepId, stepDef.name(), null, attemptNumber);
        WorkflowInstance runningInstance = instance
                .withCurrentStep(stepId)
                .withStepExecution(execution.start());

        // Save state (including step state) and publish event
        Mono<WorkflowInstance> stepExecution = saveStepRunningState(definition, runningInstance, stepDef, triggeredBy)
                .then(saveAndPublish(runningInstance, () ->
                        eventPublisher.publishStepStarted(runningInstance, execution.start())))
                .flatMap(saved -> {
                    // Create context
                    WorkflowContext context = new WorkflowContext(
                            definition, saved, stepId, objectMapper);

                    // Get step handler
                    return getStepHandler(definition.workflowId(), stepDef)
                            .flatMap(handler -> {
                                // Check if should skip via handler
                                if (handler.shouldSkip(context)) {
                                    log.info("STEP_SKIPPED: workflowId={}, instanceId={}, stepId={}, reason=handler_skip, durationMs={}",
                                            definition.workflowId(), instance.instanceId(), stepId,
                                            Duration.between(stepStartTime, Instant.now()).toMillis());
                                    // Record step skipped metric
                                    if (workflowMetrics != null) {
                                        workflowMetrics.recordStepSkipped(definition.workflowId(), stepId, "handler_skip");
                                    }
                                    StepExecution skipped = execution.skip();
                                    WorkflowInstance skippedInstance = saved.withStepExecution(skipped);
                                    return saveStepAndWorkflowState(definition, skippedInstance, stepDef, skipped, triggeredBy, false)
                                            .then(saveAndPublish(skippedInstance, () -> Mono.empty()));
                                }

                                // Execute step with timeout
                                Duration timeout = stepDef.timeout() != null
                                        ? stepDef.timeout()
                                        : properties.getDefaultStepTimeout();

                                log.debug("STEP_HANDLER_INVOKE: workflowId={}, instanceId={}, stepId={}, handler={}, timeout={}",
                                        definition.workflowId(), instance.instanceId(), stepId,
                                        handler.getClass().getSimpleName(), timeout);

                                // Execute step and handle both value and empty results
                                // Resilience patterns (circuit breaker, rate limiter, bulkhead, time limiter) are applied here
                                Mono<Object> stepResult = executeStepHandler(handler, context, timeout, definition.workflowId(), stepId);

                                return stepResult
                                        .map(result -> completeStep(execution, saved, context, stepDef, result))
                                        .switchIfEmpty(Mono.defer(() ->
                                                Mono.just(completeStep(execution, saved, context, stepDef, null))))
                                        .doOnSuccess(completedInstance -> {
                                            Duration stepDuration = Duration.between(stepStartTime, Instant.now());
                                            log.info("STEP_COMPLETE: workflowId={}, instanceId={}, stepId={}, stepName={}, status=SUCCESS, durationMs={}",
                                                    definition.workflowId(), instance.instanceId(), stepId, stepName, stepDuration.toMillis());
                                            // Record step completed metric
                                            if (workflowMetrics != null) {
                                                workflowMetrics.recordStepCompleted(definition.workflowId(), stepId, StepStatus.COMPLETED, stepDuration);
                                            }
                                        })
                                        .flatMap(completedInstance -> {
                                            StepExecution completedExec = completedInstance.getStepExecution(stepId).orElse(null);
                                            return saveStepAndWorkflowState(definition, completedInstance, stepDef, completedExec, triggeredBy, true)
                                                    .then(saveAndPublish(completedInstance, () ->
                                                            eventPublisher.publishStepCompleted(
                                                                    completedInstance,
                                                                    completedExec
                                                            )))
                                                    .flatMap(inst -> publishStepOutputEvent(
                                                            stepDef,
                                                            inst.getStepExecution(stepId).orElse(null),
                                                            inst));
                                        })
                                        .onErrorResume(error -> {
                                            Duration stepDuration = Duration.between(stepStartTime, Instant.now());
                                            log.error("STEP_FAILED: workflowId={}, instanceId={}, stepId={}, stepName={}, error={}, durationMs={}",
                                                    definition.workflowId(), instance.instanceId(), stepId, stepName,
                                                    error.getMessage(), stepDuration.toMillis());
                                            // Record step failed metric
                                            if (workflowMetrics != null) {
                                                workflowMetrics.recordStepCompleted(definition.workflowId(), stepId, StepStatus.FAILED, stepDuration);
                                            }
                                            return handleStepError(definition, saved, stepDef, execution, error, triggeredBy);
                                        });
                            });
                });

        // Apply tracing if available
        if (workflowTracer != null) {
            return workflowTracer.traceStep(instance, stepId, stepName, triggeredBy, stepExecution);
        }
        return stepExecution;
    }

    /**
     * Saves step state when step starts running.
     */
    private Mono<Void> saveStepRunningState(
            WorkflowDefinition definition,
            WorkflowInstance instance,
            WorkflowStepDefinition stepDef,
            String triggeredBy) {
        
        if (stepStateStore == null) {
            return Mono.empty();
        }
        
        return stepStateStore.getStepState(definition.workflowId(), instance.instanceId(), stepDef.stepId())
                .map(state -> state.start(triggeredBy, getStepInput(instance, stepDef)))
                .switchIfEmpty(Mono.defer(() -> {
                    int maxRetries = stepDef.retryPolicy() != null 
                            ? stepDef.retryPolicy().maxAttempts() 
                            : 3;
                    return Mono.just(StepState.create(
                            stepDef.stepId(),
                            stepDef.name(),
                            definition.workflowId(),
                            instance.instanceId(),
                            instance.correlationId(),
                            maxRetries).start(triggeredBy, getStepInput(instance, stepDef)));
                }))
                .flatMap(stepStateStore::saveStepState)
                .then(updateWorkflowStateStepStarted(definition, instance, stepDef.stepId()));
    }

    /**
     * Gets step input from the instance.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getStepInput(WorkflowInstance instance, WorkflowStepDefinition stepDef) {
        Object input = instance.input();
        if (input instanceof Map) {
            return (Map<String, Object>) input;
        }
        return Map.of();
    }

    /**
     * Saves step and workflow state after step completion or skip.
     */
    private Mono<Void> saveStepAndWorkflowState(
            WorkflowDefinition definition,
            WorkflowInstance instance,
            WorkflowStepDefinition stepDef,
            StepExecution execution,
            String triggeredBy,
            boolean completed) {
        
        if (stepStateStore == null || execution == null) {
            return Mono.empty();
        }
        
        return stepStateStore.getStepState(definition.workflowId(), instance.instanceId(), stepDef.stepId())
                .switchIfEmpty(Mono.defer(() -> {
                    int maxRetries = stepDef.retryPolicy() != null 
                            ? stepDef.retryPolicy().maxAttempts() 
                            : 3;
                    return Mono.just(StepState.create(
                            stepDef.stepId(),
                            stepDef.name(),
                            definition.workflowId(),
                            instance.instanceId(),
                            instance.correlationId(),
                            maxRetries));
                }))
                .map(state -> {
                    if (execution.status() == StepStatus.COMPLETED) {
                        return state.start(triggeredBy, getStepInput(instance, stepDef)).complete(execution.output());
                    } else if (execution.status() == StepStatus.SKIPPED) {
                        return state.skip("Condition not met");
                    } else if (execution.status() == StepStatus.FAILED) {
                        String errorMsg = execution.errorMessage() != null ? execution.errorMessage() : "Step failed";
                        String errorType = execution.errorType() != null ? execution.errorType() : "RuntimeException";
                        return state.start(triggeredBy, getStepInput(instance, stepDef))
                                .fail(errorMsg, errorType);
                    } else {
                        return state;
                    }
                })
                .flatMap(stepStateStore::saveStepState)
                .then(updateWorkflowStateStepCompleted(definition, instance, stepDef.stepId(), execution, triggeredBy));
    }

    /**
     * Updates workflow state when a step starts.
     */
    private Mono<Void> updateWorkflowStateStepStarted(
            WorkflowDefinition definition,
            WorkflowInstance instance,
            String stepId) {
        
        if (stepStateStore == null) {
            return Mono.empty();
        }
        
        return stepStateStore.getWorkflowState(definition.workflowId(), instance.instanceId())
                .switchIfEmpty(Mono.defer(() -> Mono.just(WorkflowState.create(
                        definition,
                        instance.instanceId(),
                        instance.input() instanceof Map ? (Map<String, Object>) instance.input() : Map.of(),
                        instance.correlationId(),
                        "workflow"))))
                .map(state -> state.stepStarted(stepId))
                .flatMap(stepStateStore::saveWorkflowState)
                .then()
                .onErrorResume(e -> {
                    log.warn("Failed to update workflow state on step start", e);
                    return Mono.empty();
                });
    }

    /**
     * Updates workflow state when a step completes.
     */
    private Mono<Void> updateWorkflowStateStepCompleted(
            WorkflowDefinition definition,
            WorkflowInstance instance,
            String stepId,
            StepExecution execution,
            String triggeredBy) {
        
        if (stepStateStore == null || execution == null) {
            return Mono.empty();
        }
        
        return stepStateStore.getWorkflowState(definition.workflowId(), instance.instanceId())
                .switchIfEmpty(Mono.defer(() -> Mono.just(WorkflowState.create(
                        definition,
                        instance.instanceId(),
                        instance.input() instanceof Map ? (Map<String, Object>) instance.input() : Map.of(),
                        instance.correlationId(),
                        "workflow"))))
                .map(state -> {
                    StepHistory history = StepHistory.from(execution, triggeredBy);
                    if (execution.status() == StepStatus.COMPLETED) {
                        return state.stepCompleted(stepId, history);
                    } else if (execution.status() == StepStatus.SKIPPED) {
                        return state.stepSkipped(stepId, history);
                    } else if (execution.status() == StepStatus.FAILED) {
                        return state.stepFailed(stepId, history);
                    }
                    return state;
                })
                .flatMap(stepStateStore::saveWorkflowState)
                .then()
                .onErrorResume(e -> {
                    log.warn("Failed to update workflow state on step completion", e);
                    return Mono.empty();
                });
    }
    
    /**
     * Completes a step with the given result.
     */
    private WorkflowInstance completeStep(
            StepExecution execution,
            WorkflowInstance instance,
            WorkflowContext context,
            WorkflowStepDefinition stepDef,
            Object result) {
        StepExecution completed = execution.start().complete(result);
        return instance
                .withStepExecution(completed)
                .withContext(context.getAllData());
    }
    
    /**
     * Evaluates a SpEL condition.
     */
    private boolean evaluateCondition(String condition, WorkflowContext context) {
        try {
            EvaluationContext evalContext = new StandardEvaluationContext();
            ((StandardEvaluationContext) evalContext).setVariable("ctx", context);
            ((StandardEvaluationContext) evalContext).setVariable("input", context.getAllInputs());
            ((StandardEvaluationContext) evalContext).setVariable("data", context.getAllData());
            
            Expression expression = spelParser.parseExpression(condition);
            Boolean result = expression.getValue(evalContext, Boolean.class);
            return result != null && result;
        } catch (Exception e) {
            log.warn("Failed to evaluate condition '{}': {}", condition, e.getMessage());
            return true; // Default to executing the step if condition evaluation fails
        }
    }
    
    /**
     * Publishes the step output event if outputEventType is configured.
     */
    private Mono<WorkflowInstance> publishStepOutputEvent(
            WorkflowStepDefinition stepDef,
            StepExecution execution,
            WorkflowInstance instance) {
        
        if (stepDef.outputEventType() == null || stepDef.outputEventType().isEmpty()) {
            return Mono.just(instance);
        }
        
        log.debug("Publishing step output event: type={}", stepDef.outputEventType());
        
        Map<String, Object> headers = Map.of(
                "workflowId", instance.workflowId(),
                "instanceId", instance.instanceId(),
                "stepId", stepDef.stepId(),
                "correlationId", instance.correlationId() != null ? instance.correlationId() : ""
        );
        
        return eventPublisher.publishCustomEvent(
                stepDef.outputEventType(),
                execution.output(),
                null,
                headers
        ).thenReturn(instance);
    }

    /**
     * Executes the step handler with timeout and resilience patterns.
     */
    @SuppressWarnings("unchecked")
    private Mono<Object> executeStepHandler(
            StepHandler<?> handler,
            WorkflowContext context,
            Duration timeout,
            String workflowId,
            String stepId) {

        Mono<Object> execution = ((Mono<Object>) handler.execute(context))
                .timeout(timeout)
                .doOnSubscribe(s -> log.debug("Step handler execution started"))
                .doOnSuccess(result -> log.debug("Step handler execution completed"));

        // Apply resilience patterns if available
        if (workflowResilience != null && workflowResilience.isEnabled()) {
            execution = workflowResilience.decorateStep(workflowId, stepId, execution);
        }

        return execution;
    }

    /**
     * Handles step execution errors with state tracking.
     */
    private Mono<WorkflowInstance> handleStepError(
            WorkflowDefinition definition,
            WorkflowInstance instance,
            WorkflowStepDefinition stepDef,
            StepExecution execution,
            Throwable error,
            String triggeredBy) {
        
        log.error("Step execution failed: workflowId={}, instanceId={}, stepId={}, error={}",
                definition.workflowId(), instance.instanceId(), stepDef.stepId(), error.getMessage());

        StepExecution failed = execution.start().fail(error);
        WorkflowInstance failedInstance = instance.withStepExecution(failed);

        // Update step state to failed
        Mono<Void> updateStepState = Mono.empty();
        if (stepStateStore != null) {
            int maxRetries = stepDef.retryPolicy() != null
                    ? stepDef.retryPolicy().maxAttempts()
                    : 3;
            updateStepState = stepStateStore.getStepState(
                            definition.workflowId(), instance.instanceId(), stepDef.stepId())
                    .switchIfEmpty(Mono.defer(() -> Mono.just(StepState.create(
                            stepDef.stepId(),
                            stepDef.name(),
                            definition.workflowId(),
                            instance.instanceId(),
                            instance.correlationId(),
                            maxRetries).start(triggeredBy, getStepInput(instance, stepDef)))))
                    .map(state -> state.fail(error))
                    .flatMap(stepStateStore::saveStepState)
                    .then();
        }

        // Check if should retry
        RetryPolicy retryPolicy = stepDef.retryPolicy() != null 
                ? stepDef.retryPolicy() 
                : definition.retryPolicy();

        if (failed.canRetry(retryPolicy)) {
            log.info("Retrying step: stepId={}, attempt={}",
                    stepDef.stepId(), failed.attemptNumber() + 1);

            // Record step retry metric
            if (workflowMetrics != null) {
                workflowMetrics.recordStepRetry(definition.workflowId(), stepDef.stepId(), failed.attemptNumber() + 1);
            }

            StepExecution retrying = failed.retry();
            WorkflowInstance retryingInstance = failedInstance.withStepExecution(retrying);

            // Update step state to retrying
            Mono<Void> updateRetryState = Mono.empty();
            if (stepStateStore != null) {
                updateRetryState = stepStateStore.getStepState(
                                definition.workflowId(), instance.instanceId(), stepDef.stepId())
                        .map(StepState::retry)
                        .flatMap(stepStateStore::saveStepState)
                        .then();
            }

            return updateStepState
                    .then(updateRetryState)
                    .then(saveAndPublish(retryingInstance, () ->
                            eventPublisher.publishStepRetrying(retryingInstance, retrying)))
                    .delayElement(retryPolicy.getDelayForAttempt(retrying.attemptNumber()))
                    .flatMap(saved -> executeStep(definition, saved, stepDef, triggeredBy));
        }

        // Fail the workflow
        WorkflowInstance finalFailed = failedInstance.fail(error);
        
        return updateStepState
                .then(saveStepAndWorkflowState(definition, finalFailed, stepDef, failed, triggeredBy, false))
                .then(saveAndPublish(finalFailed, () -> {
                    eventPublisher.publishStepFailed(failedInstance, failed).subscribe();
                    return eventPublisher.publishWorkflowFailed(finalFailed);
                }));
    }

    /**
     * Gets the step handler.
     * <p>
     * First checks the WorkflowAspect registry for annotation-based handlers,
     * then falls back to Spring bean lookup for programmatic handlers.
     */
    @SuppressWarnings("unchecked")
    private Mono<StepHandler<?>> getStepHandler(String workflowId, WorkflowStepDefinition stepDef) {
        // First, try to get handler from WorkflowAspect (annotation-based workflows)
        if (workflowAspect != null) {
            StepHandler<?> aspectHandler = workflowAspect.getStepHandler(workflowId, stepDef.stepId());
            if (aspectHandler != null) {
                return Mono.just(aspectHandler);
            }
        }
        
        // Fall back to Spring bean lookup (programmatic workflows)
        String beanName = stepDef.handlerBeanName();
        
        if (beanName == null || beanName.isEmpty()) {
            return Mono.error(new StepExecutionException(
                    "No handler found for step: " + stepDef.stepId() + 
                    " in workflow: " + workflowId));
        }
        
        try {
            Object bean = applicationContext.getBean(beanName);
            if (bean instanceof StepHandler) {
                return Mono.just((StepHandler<?>) bean);
            } else {
                return Mono.error(new StepExecutionException(
                        "Bean is not a StepHandler: " + beanName));
            }
        } catch (Exception e) {
            return Mono.error(new StepExecutionException(
                    "Failed to get step handler bean: " + beanName, e));
        }
    }

    /**
     * Saves instance and publishes event.
     */
    private Mono<WorkflowInstance> saveAndPublish(
            WorkflowInstance instance, 
            java.util.function.Supplier<Mono<Void>> eventSupplier) {
        
        return stateStore.save(instance)
                .flatMap(saved -> eventSupplier.get().thenReturn(saved))
                .onErrorResume(e -> {
                    log.error("Failed to save/publish workflow state", e);
                    return Mono.just(instance);
                });
    }

    /**
     * Gets the output from the last completed step.
     */
    private Object getLastStepOutput(WorkflowInstance instance) {
        return instance.getLatestStepExecution()
                .filter(e -> e.status() == StepStatus.COMPLETED)
                .map(StepExecution::output)
                .orElse(null);
    }
}
