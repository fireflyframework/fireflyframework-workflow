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

package com.firefly.common.workflow.service;

import com.firefly.common.workflow.core.WorkflowEngine;
import com.firefly.common.workflow.model.*;
import com.firefly.common.workflow.rest.dto.StepStateResponse;
import com.firefly.common.workflow.rest.dto.WorkflowStateResponse;
import com.firefly.common.workflow.rest.dto.WorkflowStatusResponse;
import com.firefly.common.workflow.rest.dto.WorkflowTopologyResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service layer for workflow operations.
 * <p>
 * This service encapsulates business logic for workflow management,
 * providing a clean separation between the REST controller and the
 * underlying workflow engine.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Orchestrating workflow operations</li>
 *   <li>Converting domain models to DTOs</li>
 *   <li>Handling wait-for-completion logic</li>
 *   <li>Collecting workflow results</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowEngine workflowEngine;

    /**
     * Lists all registered workflow definitions as summaries.
     *
     * @return list of workflow summaries
     */
    public List<WorkflowSummary> listWorkflows() {
        return workflowEngine.getAllWorkflows().stream()
                .map(WorkflowSummary::from)
                .toList();
    }

    /**
     * Gets a workflow definition by ID.
     *
     * @param workflowId the workflow ID
     * @return optional containing the definition
     */
    public Optional<WorkflowDefinition> getWorkflowDefinition(String workflowId) {
        return workflowEngine.getWorkflowDefinition(workflowId);
    }

    /**
     * Gets the workflow topology (DAG) for visualization.
     *
     * @param workflowId the workflow ID
     * @return optional containing the topology response
     */
    public Optional<WorkflowTopologyResponse> getTopology(String workflowId) {
        return workflowEngine.getWorkflowDefinition(workflowId)
                .map(WorkflowTopologyResponse::from);
    }

    /**
     * Gets the workflow topology with instance status information.
     * <p>
     * This is useful for visualizing the progress of a running workflow.
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @return mono containing the topology response with status info
     */
    public Mono<WorkflowTopologyResponse> getTopology(String workflowId, String instanceId) {
        return workflowEngine.getWorkflowDefinition(workflowId)
                .map(definition -> workflowEngine.getStatus(workflowId, instanceId)
                        .map(instance -> WorkflowTopologyResponse.from(definition, instance)))
                .orElse(Mono.empty());
    }

    /**
     * Starts a new workflow instance.
     *
     * @param workflowId the workflow ID
     * @param input input data for the workflow
     * @param correlationId optional correlation ID
     * @param triggeredBy source that triggered the workflow
     * @return the started workflow instance as a status response
     */
    public Mono<WorkflowStatusResponse> startWorkflow(
            String workflowId,
            Map<String, Object> input,
            String correlationId,
            String triggeredBy) {
        
        log.info("Starting workflow: workflowId={}, correlationId={}, triggeredBy={}",
                workflowId, correlationId, triggeredBy);
        
        return workflowEngine.startWorkflow(workflowId, input, correlationId, triggeredBy)
                .map(instance -> toStatusResponse(workflowId, instance));
    }

    /**
     * Starts a workflow and optionally waits for completion.
     *
     * @param workflowId the workflow ID
     * @param input input data for the workflow
     * @param correlationId optional correlation ID
     * @param triggeredBy source that triggered the workflow
     * @param waitForCompletion whether to wait for completion
     * @param waitTimeoutMs timeout in milliseconds when waiting
     * @return the workflow instance as a status response
     */
    public Mono<WorkflowStatusResponse> startWorkflow(
            String workflowId,
            Map<String, Object> input,
            String correlationId,
            String triggeredBy,
            boolean waitForCompletion,
            long waitTimeoutMs) {
        return startWorkflow(workflowId, input, correlationId, triggeredBy, waitForCompletion, waitTimeoutMs, false);
    }

    /**
     * Starts a workflow with full options including dry-run mode.
     *
     * @param workflowId the workflow ID
     * @param input input data for the workflow
     * @param correlationId optional correlation ID
     * @param triggeredBy source that triggered the workflow
     * @param waitForCompletion whether to wait for completion
     * @param waitTimeoutMs timeout in milliseconds when waiting
     * @param dryRun whether to run in dry-run mode (no side effects)
     * @return the workflow instance as a status response
     */
    public Mono<WorkflowStatusResponse> startWorkflow(
            String workflowId,
            Map<String, Object> input,
            String correlationId,
            String triggeredBy,
            boolean waitForCompletion,
            long waitTimeoutMs,
            boolean dryRun) {
        
        log.info("Starting workflow: workflowId={}, correlationId={}, waitForCompletion={}, dryRun={}",
                workflowId, correlationId, waitForCompletion, dryRun);
        
        return workflowEngine.startWorkflow(workflowId, input, correlationId, triggeredBy, dryRun)
                .flatMap(instance -> {
                    if (waitForCompletion) {
                        return waitForCompletion(instance, waitTimeoutMs);
                    }
                    return Mono.just(instance);
                })
                .map(instance -> toStatusResponse(workflowId, instance));
    }

    /**
     * Gets the status of a workflow instance.
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @return the workflow status response
     */
    public Mono<WorkflowStatusResponse> getStatus(String workflowId, String instanceId) {
        return workflowEngine.getStatus(workflowId, instanceId)
                .map(instance -> toStatusResponse(workflowId, instance));
    }

    /**
     * Collects the result of a workflow.
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @return the result containing status and output/error
     */
    public Mono<WorkflowResult> collectResult(String workflowId, String instanceId) {
        return workflowEngine.getStatus(workflowId, instanceId)
                .map(this::toWorkflowResult);
    }

    /**
     * Cancels a running workflow instance.
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @return the cancelled workflow status response
     */
    public Mono<WorkflowStatusResponse> cancelWorkflow(String workflowId, String instanceId) {
        log.info("Cancelling workflow: workflowId={}, instanceId={}", workflowId, instanceId);
        
        return workflowEngine.cancelWorkflow(workflowId, instanceId)
                .map(instance -> toStatusResponse(workflowId, instance));
    }

    /**
     * Retries a failed workflow instance.
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @return the retried workflow status response
     */
    public Mono<WorkflowStatusResponse> retryWorkflow(String workflowId, String instanceId) {
        log.info("Retrying workflow: workflowId={}, instanceId={}", workflowId, instanceId);

        return workflowEngine.retryWorkflow(workflowId, instanceId)
                .map(instance -> toStatusResponse(workflowId, instance));
    }

    /**
     * Suspends a running workflow instance.
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @param reason optional reason for suspension
     * @return the suspended workflow status response
     */
    public Mono<WorkflowStatusResponse> suspendWorkflow(String workflowId, String instanceId, String reason) {
        log.info("Suspending workflow: workflowId={}, instanceId={}, reason={}", workflowId, instanceId, reason);

        return workflowEngine.suspendWorkflow(workflowId, instanceId, reason)
                .map(instance -> toStatusResponse(workflowId, instance));
    }

    /**
     * Resumes a suspended workflow instance.
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @return the resumed workflow status response
     */
    public Mono<WorkflowStatusResponse> resumeWorkflow(String workflowId, String instanceId) {
        log.info("Resuming workflow: workflowId={}, instanceId={}", workflowId, instanceId);

        return workflowEngine.resumeWorkflow(workflowId, instanceId)
                .map(instance -> toStatusResponse(workflowId, instance));
    }

    /**
     * Finds all suspended workflow instances.
     *
     * @return flux of suspended workflow status responses
     */
    public Flux<WorkflowStatusResponse> findSuspendedInstances() {
        return workflowEngine.findSuspendedInstances()
                .map(instance -> toStatusResponse(instance.workflowId(), instance));
    }

    /**
     * Triggers a specific step in a workflow.
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @param stepId the step ID to trigger
     * @param input input data for the step
     * @param triggeredBy source that triggered the step
     * @return the workflow status response after step execution
     */
    public Mono<WorkflowStatusResponse> triggerStep(
            String workflowId,
            String instanceId,
            String stepId,
            Map<String, Object> input,
            String triggeredBy) {

        log.info("Triggering step: workflowId={}, instanceId={}, stepId={}, triggeredBy={}",
                workflowId, instanceId, stepId, triggeredBy);

        return workflowEngine.triggerStep(workflowId, instanceId, stepId, input, triggeredBy)
                .map(instance -> toStatusResponse(workflowId, instance));
    }

    /**
     * Gets the state of a specific step.
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @param stepId the step ID
     * @return the step state response
     */
    public Mono<StepStateResponse> getStepState(String workflowId, String instanceId, String stepId) {
        return workflowEngine.getStepState(workflowId, instanceId, stepId)
                .map(StepStateResponse::from);
    }

    /**
     * Gets all step states for a workflow instance.
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @return flux of step state responses
     */
    public Flux<StepStateResponse> getStepStates(String workflowId, String instanceId) {
        return workflowEngine.getStepStates(workflowId, instanceId)
                .map(StepStateResponse::from);
    }

    /**
     * Gets the comprehensive workflow state.
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @return the workflow state response
     */
    public Mono<WorkflowStateResponse> getWorkflowState(String workflowId, String instanceId) {
        return workflowEngine.getWorkflowState(workflowId, instanceId)
                .map(WorkflowStateResponse::from);
    }

    /**
     * Finds all instances of a workflow.
     *
     * @param workflowId the workflow ID
     * @return flux of workflow status responses
     */
    public Flux<WorkflowStatusResponse> findInstances(String workflowId) {
        return workflowEngine.findInstances(workflowId)
                .map(instance -> toStatusResponse(workflowId, instance));
    }

    /**
     * Finds instances of a workflow by status.
     *
     * @param workflowId the workflow ID
     * @param status the status to filter by
     * @return flux of workflow status responses
     */
    public Flux<WorkflowStatusResponse> findInstances(String workflowId, WorkflowStatus status) {
        return workflowEngine.findInstances(workflowId, status)
                .map(instance -> toStatusResponse(workflowId, instance));
    }

    /**
     * Checks if step state tracking is enabled.
     *
     * @return true if step state tracking is enabled
     */
    public boolean isStepStateTrackingEnabled() {
        return workflowEngine.isStepStateTrackingEnabled();
    }

    // ========== Private Helper Methods ==========

    /**
     * Waits for a workflow to complete with polling.
     */
    private Mono<WorkflowInstance> waitForCompletion(WorkflowInstance instance, long timeoutMs) {
        if (instance.status().isTerminal()) {
            return Mono.just(instance);
        }

        Duration timeout = Duration.ofMillis(timeoutMs);
        Duration pollInterval = Duration.ofMillis(100);

        return Mono.defer(() -> workflowEngine.getStatus(instance.workflowId(), instance.instanceId()))
                .repeatWhen(flux -> flux.delayElements(pollInterval))
                .filter(i -> i.status().isTerminal())
                .next()
                .timeout(timeout)
                .onErrorResume(e -> workflowEngine.getStatus(instance.workflowId(), instance.instanceId()));
    }

    /**
     * Converts a WorkflowInstance to a WorkflowStatusResponse.
     */
    private WorkflowStatusResponse toStatusResponse(String workflowId, WorkflowInstance instance) {
        int totalSteps = getTotalSteps(workflowId);
        return WorkflowStatusResponse.from(instance, totalSteps);
    }

    /**
     * Gets the total number of steps for a workflow.
     */
    private int getTotalSteps(String workflowId) {
        return workflowEngine.getWorkflowDefinition(workflowId)
                .map(def -> def.steps().size())
                .orElse(0);
    }

    /**
     * Converts a WorkflowInstance to a WorkflowResult.
     */
    private WorkflowResult toWorkflowResult(WorkflowInstance instance) {
        return new WorkflowResult(
                instance.instanceId(),
                instance.workflowId(),
                instance.status(),
                instance.output(),
                instance.errorMessage(),
                instance.errorType()
        );
    }

    /**
     * Summary of a workflow definition.
     */
    public record WorkflowSummary(
            String workflowId,
            String name,
            String version,
            String description,
            int stepCount
    ) {
        public static WorkflowSummary from(WorkflowDefinition def) {
            return new WorkflowSummary(
                    def.workflowId(),
                    def.name(),
                    def.version(),
                    def.description(),
                    def.steps().size()
            );
        }
    }

    /**
     * Result of a workflow execution.
     */
    public record WorkflowResult(
            String instanceId,
            String workflowId,
            WorkflowStatus status,
            Object output,
            String errorMessage,
            String errorType
    ) {
        public boolean isCompleted() {
            return status == WorkflowStatus.COMPLETED;
        }

        public boolean isFailed() {
            return status == WorkflowStatus.FAILED;
        }

        public boolean isTerminal() {
            return status.isTerminal();
        }
    }
}

