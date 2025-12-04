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
        
        log.info("Starting workflow: workflowId={}, correlationId={}, waitForCompletion={}",
                workflowId, correlationId, waitForCompletion);
        
        return workflowEngine.startWorkflow(workflowId, input, correlationId, triggeredBy)
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

