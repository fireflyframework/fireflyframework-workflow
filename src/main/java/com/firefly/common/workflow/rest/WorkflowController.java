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

package com.firefly.common.workflow.rest;

import com.firefly.common.workflow.exception.StepExecutionException;
import com.firefly.common.workflow.exception.WorkflowNotFoundException;
import com.firefly.common.workflow.model.WorkflowDefinition;
import com.firefly.common.workflow.model.WorkflowStatus;
import com.firefly.common.workflow.rest.dto.StartWorkflowRequest;
import com.firefly.common.workflow.rest.dto.StepStateResponse;
import com.firefly.common.workflow.rest.dto.TriggerStepRequest;
import com.firefly.common.workflow.rest.dto.WorkflowStateResponse;
import com.firefly.common.workflow.rest.dto.WorkflowStatusResponse;
import com.firefly.common.workflow.service.WorkflowService;
import com.firefly.common.workflow.service.WorkflowService.WorkflowResult;
import com.firefly.common.workflow.service.WorkflowService.WorkflowSummary;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * REST controller for workflow operations.
 * <p>
 * This controller is a thin layer that handles HTTP request/response mapping
 * and delegates all business logic to the {@link WorkflowService}.
 * <p>
 * Provides endpoints for:
 * <ul>
 *   <li>Starting workflows</li>
 *   <li>Querying workflow and step status</li>
 *   <li>Triggering individual steps (step-level choreography)</li>
 *   <li>Collecting workflow results</li>
 *   <li>Cancelling workflows</li>
 *   <li>Retrying failed workflows</li>
 *   <li>Listing workflows</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("${firefly.workflow.api.base-path:/api/v1/workflows}")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    /**
     * Lists all registered workflows.
     */
    @GetMapping
    public Mono<ResponseEntity<List<WorkflowSummary>>> listWorkflows() {
        return Mono.just(ResponseEntity.ok(workflowService.listWorkflows()));
    }

    /**
     * Gets details of a specific workflow definition.
     */
    @GetMapping("/{workflowId}")
    public Mono<ResponseEntity<WorkflowDefinition>> getWorkflow(@PathVariable String workflowId) {
        return Mono.justOrEmpty(workflowService.getWorkflowDefinition(workflowId))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /**
     * Starts a new workflow instance.
     */
    @PostMapping("/{workflowId}/start")
    public Mono<ResponseEntity<WorkflowStatusResponse>> startWorkflow(
            @PathVariable String workflowId,
            @Valid @RequestBody(required = false) StartWorkflowRequest request) {

        if (request == null) {
            request = new StartWorkflowRequest();
        }

        log.info("Starting workflow via API: workflowId={}, correlationId={}",
                workflowId, request.getCorrelationId());

        return workflowService.startWorkflow(
                workflowId,
                request.getInput(),
                request.getCorrelationId(),
                "api",
                request.isWaitForCompletion(),
                request.getWaitTimeoutMs()
        )
        .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response))
        .onErrorResume(WorkflowNotFoundException.class, e ->
                Mono.just(ResponseEntity.notFound().build()));
    }

    /**
     * Gets the status of a workflow instance.
     */
    @GetMapping("/{workflowId}/instances/{instanceId}/status")
    public Mono<ResponseEntity<WorkflowStatusResponse>> getStatus(
            @PathVariable String workflowId,
            @PathVariable String instanceId) {

        return workflowService.getStatus(workflowId, instanceId)
                .map(ResponseEntity::ok)
                .onErrorResume(WorkflowNotFoundException.class, e ->
                        Mono.just(ResponseEntity.notFound().build()));
    }

    /**
     * Collects the result of a completed workflow.
     */
    @GetMapping("/{workflowId}/instances/{instanceId}/collect")
    public Mono<ResponseEntity<Object>> collectResult(
            @PathVariable String workflowId,
            @PathVariable String instanceId) {

        return workflowService.collectResult(workflowId, instanceId)
                .map(this::mapResultToResponse)
                .onErrorResume(WorkflowNotFoundException.class, e ->
                        Mono.just(ResponseEntity.notFound().build()));
    }

    /**
     * Cancels a running workflow instance.
     */
    @PostMapping("/{workflowId}/instances/{instanceId}/cancel")
    public Mono<ResponseEntity<WorkflowStatusResponse>> cancelWorkflow(
            @PathVariable String workflowId,
            @PathVariable String instanceId) {

        log.info("Cancelling workflow via API: workflowId={}, instanceId={}", workflowId, instanceId);

        return workflowService.cancelWorkflow(workflowId, instanceId)
                .map(ResponseEntity::ok)
                .onErrorResume(WorkflowNotFoundException.class, e ->
                        Mono.just(ResponseEntity.notFound().build()))
                .onErrorResume(IllegalStateException.class, e ->
                        Mono.just(ResponseEntity.badRequest().build()));
    }

    /**
     * Retries a failed workflow instance.
     */
    @PostMapping("/{workflowId}/instances/{instanceId}/retry")
    public Mono<ResponseEntity<WorkflowStatusResponse>> retryWorkflow(
            @PathVariable String workflowId,
            @PathVariable String instanceId) {

        log.info("Retrying workflow via API: workflowId={}, instanceId={}", workflowId, instanceId);

        return workflowService.retryWorkflow(workflowId, instanceId)
                .map(ResponseEntity::ok)
                .onErrorResume(WorkflowNotFoundException.class, e ->
                        Mono.just(ResponseEntity.notFound().build()))
                .onErrorResume(IllegalStateException.class, e ->
                        Mono.just(ResponseEntity.badRequest().build()));
    }

    /**
     * Lists all instances of a workflow.
     */
    @GetMapping("/{workflowId}/instances")
    public Flux<WorkflowStatusResponse> listInstances(
            @PathVariable String workflowId,
            @RequestParam(required = false) WorkflowStatus status) {

        return status != null
                ? workflowService.findInstances(workflowId, status)
                : workflowService.findInstances(workflowId);
    }

    // ==================== Step-Level Choreography Endpoints ====================

    /**
     * Triggers execution of a specific step.
     * <p>
     * This endpoint supports step-level choreography where individual steps
     * can be triggered independently via API calls.
     */
    @PostMapping("/{workflowId}/instances/{instanceId}/steps/{stepId}/trigger")
    public Mono<ResponseEntity<WorkflowStatusResponse>> triggerStep(
            @PathVariable String workflowId,
            @PathVariable String instanceId,
            @PathVariable String stepId,
            @RequestBody(required = false) TriggerStepRequest request) {

        log.info("Triggering step via API: workflowId={}, instanceId={}, stepId={}",
                workflowId, instanceId, stepId);

        Map<String, Object> input = request != null ? request.getInput() : Map.of();

        return workflowService.triggerStep(workflowId, instanceId, stepId, input, "api")
                .map(ResponseEntity::ok)
                .onErrorResume(WorkflowNotFoundException.class, e ->
                        Mono.just(ResponseEntity.notFound().build()))
                .onErrorResume(StepExecutionException.class, e ->
                        Mono.just(ResponseEntity.badRequest().build()));
    }

    /**
     * Gets the state of a specific step.
     */
    @GetMapping("/{workflowId}/instances/{instanceId}/steps/{stepId}")
    public Mono<ResponseEntity<StepStateResponse>> getStepState(
            @PathVariable String workflowId,
            @PathVariable String instanceId,
            @PathVariable String stepId) {

        if (!workflowService.isStepStateTrackingEnabled()) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build());
        }

        return workflowService.getStepState(workflowId, instanceId, stepId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /**
     * Gets all step states for a workflow instance.
     */
    @GetMapping("/{workflowId}/instances/{instanceId}/steps")
    public Flux<StepStateResponse> getStepStates(
            @PathVariable String workflowId,
            @PathVariable String instanceId) {

        if (!workflowService.isStepStateTrackingEnabled()) {
            return Flux.empty();
        }

        return workflowService.getStepStates(workflowId, instanceId);
    }

    /**
     * Gets the comprehensive workflow state (dashboard view).
     * <p>
     * This provides a complete view of workflow progress including:
     * - Which steps have completed, failed, or been skipped
     * - Current and next steps
     * - Full execution history
     */
    @GetMapping("/{workflowId}/instances/{instanceId}/state")
    public Mono<ResponseEntity<WorkflowStateResponse>> getWorkflowState(
            @PathVariable String workflowId,
            @PathVariable String instanceId) {

        if (!workflowService.isStepStateTrackingEnabled()) {
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build());
        }

        return workflowService.getWorkflowState(workflowId, instanceId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    // ==================== Private Helper Methods ====================

    /**
     * Maps a WorkflowResult to an appropriate HTTP response.
     */
    private ResponseEntity<Object> mapResultToResponse(WorkflowResult result) {
        if (!result.isTerminal()) {
            return ResponseEntity
                    .status(HttpStatus.ACCEPTED)
                    .body(Map.of(
                            "status", result.status(),
                            "message", "Workflow is still running"
                    ));
        }

        if (result.isCompleted()) {
            return ResponseEntity.ok(Map.of(
                    "status", "COMPLETED",
                    "result", result.output() != null ? result.output() : Map.of()
            ));
        }

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "status", result.status(),
                        "error", result.errorMessage() != null ? result.errorMessage() : "Unknown error"
                ));
    }
}
