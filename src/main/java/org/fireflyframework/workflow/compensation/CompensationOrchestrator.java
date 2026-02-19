/*
 * Copyright 2024-2026 Firefly Software Solutions Inc
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

package org.fireflyframework.workflow.compensation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fireflyframework.workflow.core.StepHandler;
import org.fireflyframework.workflow.core.WorkflowContext;
import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.eventsourcing.store.EventSourcedWorkflowStateStore;
import org.fireflyframework.workflow.model.WorkflowDefinition;
import org.fireflyframework.workflow.model.WorkflowInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates compensation (saga rollback) for failed workflow steps.
 * <p>
 * When a workflow step fails, the CompensationOrchestrator is responsible for
 * invoking compensation handlers on previously completed steps in reverse order,
 * according to the specified {@link CompensationPolicy}.
 * <p>
 * The orchestrator records compensation events on the workflow aggregate for
 * full auditability via event sourcing:
 * <ul>
 *   <li>{@code CompensationStartedEvent} — recorded when compensation begins</li>
 *   <li>{@code CompensationStepCompletedEvent} — recorded for each compensation step</li>
 * </ul>
 * <p>
 * Step handlers are resolved by step ID from a Spring-managed bean map, where
 * the bean name matches the step ID. If no handler is found for a step, it is
 * skipped (not treated as an error).
 *
 * @see CompensationPolicy
 * @see CompensationResult
 * @see StepHandler#compensate(WorkflowContext)
 */
@Slf4j
@RequiredArgsConstructor
public class CompensationOrchestrator {

    private final EventSourcedWorkflowStateStore stateStore;
    private final Map<String, StepHandler<?>> stepHandlers;

    /**
     * Executes compensation for a failed workflow instance.
     * <p>
     * The compensation process:
     * <ol>
     *   <li>If policy is {@link CompensationPolicy#SKIP}, returns immediately</li>
     *   <li>Loads the workflow aggregate from the event store</li>
     *   <li>Records a {@code CompensationStartedEvent}</li>
     *   <li>Iterates over completed steps in reverse order</li>
     *   <li>For each step, invokes the step handler's {@code compensate()} method</li>
     *   <li>Records a {@code CompensationStepCompletedEvent} per step</li>
     *   <li>Saves the aggregate with all compensation events</li>
     *   <li>Returns a {@link CompensationResult} summarizing the outcome</li>
     * </ol>
     *
     * @param instanceId   the workflow instance ID
     * @param failedStepId the step ID whose failure triggered compensation
     * @param policy       the compensation policy to apply
     * @return a Mono emitting the compensation result
     */
    public Mono<CompensationResult> compensate(UUID instanceId, String failedStepId, CompensationPolicy policy) {
        if (policy == CompensationPolicy.SKIP) {
            log.info("Compensation skipped for workflow {}, policy=SKIP", instanceId);
            return Mono.just(new CompensationResult(
                    instanceId.toString(),
                    CompensationPolicy.SKIP,
                    failedStepId,
                    List.of(),
                    true,
                    List.of()
            ));
        }

        log.info("Starting compensation for workflow {}, failedStep={}, policy={}",
                instanceId, failedStepId, policy);

        return stateStore.loadAggregate(instanceId)
                .flatMap(aggregate -> executeCompensation(aggregate, failedStepId, policy))
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Workflow instance not found for compensation: {}", instanceId);
                    return Mono.just(new CompensationResult(
                            instanceId.toString(),
                            policy,
                            failedStepId,
                            List.of(),
                            false,
                            List.of("Workflow instance " + instanceId + " not found")
                    ));
                }));
    }

    /**
     * Executes the compensation logic on the loaded aggregate.
     */
    private Mono<CompensationResult> executeCompensation(
            WorkflowAggregate aggregate, String failedStepId, CompensationPolicy policy) {

        // Record compensation start
        aggregate.startCompensation(failedStepId, policy.name());

        // Get completed steps in reverse order
        List<String> completedSteps = new ArrayList<>(aggregate.getCompletedStepOrder());
        Collections.reverse(completedSteps);

        log.debug("Compensating {} steps in reverse order: {}", completedSteps.size(), completedSteps);

        // Build a minimal WorkflowContext for compensation handlers
        WorkflowInstance workflowInstance = stateStore.toWorkflowInstance(aggregate);
        WorkflowDefinition minimalDefinition = WorkflowDefinition.builder()
                .workflowId(aggregate.getWorkflowId() != null ? aggregate.getWorkflowId() : "unknown")
                .name(aggregate.getWorkflowName() != null ? aggregate.getWorkflowName() : "unknown")
                .version(aggregate.getWorkflowVersion() != null ? aggregate.getWorkflowVersion() : "1.0.0")
                .build();

        List<CompensationResult.CompensatedStep> compensatedSteps = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        AtomicBoolean stopped = new AtomicBoolean(false);

        // Execute compensation steps sequentially using Flux
        return Flux.fromIterable(completedSteps)
                .concatMap(stepId -> {
                    if (stopped.get()) {
                        return Mono.empty();
                    }
                    return compensateStep(stepId, aggregate, workflowInstance, minimalDefinition,
                            compensatedSteps, errors, policy, stopped);
                })
                .then(Mono.defer(() -> {
                    // Save the aggregate with all compensation events
                    return stateStore.saveAggregate(aggregate)
                            .thenReturn(new CompensationResult(
                                    aggregate.getId().toString(),
                                    policy,
                                    failedStepId,
                                    List.copyOf(compensatedSteps),
                                    errors.isEmpty(),
                                    List.copyOf(errors)
                            ));
                }));
    }

    /**
     * Compensates a single step.
     * <p>
     * If the handler is not found for the step, it is treated as a successful
     * skip (not an error). If compensation fails and the policy is
     * {@code STRICT_SEQUENTIAL}, the stopped flag is set to prevent further
     * compensation steps from executing.
     */
    private Mono<Boolean> compensateStep(
            String stepId,
            WorkflowAggregate aggregate,
            WorkflowInstance workflowInstance,
            WorkflowDefinition definition,
            List<CompensationResult.CompensatedStep> compensatedSteps,
            List<String> errors,
            CompensationPolicy policy,
            AtomicBoolean stopped) {

        StepHandler<?> handler = stepHandlers.get(stepId);

        if (handler == null) {
            log.debug("No handler found for step '{}', skipping compensation", stepId);
            aggregate.completeCompensationStep(stepId, true, null);
            compensatedSteps.add(new CompensationResult.CompensatedStep(stepId, true, null));
            return Mono.just(true);
        }

        WorkflowContext context = new WorkflowContext(
                definition,
                workflowInstance,
                stepId,
                new ObjectMapper()
        );

        Mono<Void> compensateMono = handler.compensate(context);
        if (compensateMono == null) {
            compensateMono = Mono.empty();
        }

        return compensateMono
                .then(Mono.defer(() -> {
                    log.debug("Compensation succeeded for step '{}'", stepId);
                    aggregate.completeCompensationStep(stepId, true, null);
                    compensatedSteps.add(new CompensationResult.CompensatedStep(stepId, true, null));
                    return Mono.just(true);
                }))
                .onErrorResume(error -> {
                    String errorMessage = error.getMessage();
                    log.warn("Compensation failed for step '{}': {}", stepId, errorMessage);
                    aggregate.completeCompensationStep(stepId, false, errorMessage);
                    compensatedSteps.add(new CompensationResult.CompensatedStep(stepId, false, errorMessage));
                    errors.add(errorMessage);

                    if (policy == CompensationPolicy.STRICT_SEQUENTIAL) {
                        // Stop processing further steps
                        stopped.set(true);
                    }

                    return Mono.just(false);
                });
    }
}
