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

package org.fireflyframework.workflow.rest;

import org.fireflyframework.workflow.exception.StepExecutionException;
import org.fireflyframework.workflow.exception.WorkflowNotFoundException;
import org.fireflyframework.workflow.model.WorkflowDefinition;
import org.fireflyframework.workflow.model.WorkflowStatus;
import org.fireflyframework.workflow.query.WorkflowQueryService;
import org.fireflyframework.workflow.rest.dto.SendSignalRequest;
import org.fireflyframework.workflow.rest.dto.StartWorkflowRequest;
import org.fireflyframework.workflow.rest.dto.StepStateResponse;
import org.fireflyframework.workflow.rest.dto.SuspendWorkflowRequest;
import org.fireflyframework.workflow.rest.dto.TriggerStepRequest;
import org.fireflyframework.workflow.rest.dto.WorkflowStateResponse;
import org.fireflyframework.workflow.rest.dto.WorkflowStatusResponse;
import org.fireflyframework.workflow.rest.dto.WorkflowTopologyResponse;
import org.fireflyframework.workflow.service.WorkflowService;
import org.fireflyframework.workflow.signal.SignalResult;
import org.fireflyframework.workflow.signal.SignalService;
import org.fireflyframework.workflow.service.WorkflowService.WorkflowResult;
import org.fireflyframework.workflow.service.WorkflowService.WorkflowSummary;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
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
 *   <li>Sending signals to workflow instances</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("${firefly.workflow.api.base-path:/api/v1/workflows}")
public class WorkflowController {

    private final WorkflowService workflowService;
    @Nullable
    private final SignalService signalService;
    @Nullable
    private final WorkflowQueryService queryService;

    public WorkflowController(WorkflowService workflowService,
                              @Nullable SignalService signalService,
                              @Nullable WorkflowQueryService queryService) {
        this.workflowService = workflowService;
        this.signalService = signalService;
        this.queryService = queryService;
    }

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
     * Gets the workflow topology (DAG) for visualization.
     * <p>
     * Returns the nodes (steps) and edges (dependencies) in a format
     * compatible with frontend libraries like React Flow or Mermaid.js.
     */
    @GetMapping("/{workflowId}/topology")
    public Mono<ResponseEntity<WorkflowTopologyResponse>> getTopology(@PathVariable String workflowId) {
        return Mono.justOrEmpty(workflowService.getTopology(workflowId))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /**
     * Gets the workflow topology with instance status information.
     * <p>
     * Returns the topology with step execution statuses populated,
     * useful for visualizing workflow progress.
     */
    @GetMapping("/{workflowId}/instances/{instanceId}/topology")
    public Mono<ResponseEntity<WorkflowTopologyResponse>> getInstanceTopology(
            @PathVariable String workflowId,
            @PathVariable String instanceId) {

        return workflowService.getTopology(workflowId, instanceId)
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

        log.info("Starting workflow via API: workflowId={}, correlationId={}, dryRun={}",
                workflowId, request.getCorrelationId(), request.isDryRun());

        return workflowService.startWorkflow(
                workflowId,
                request.getInput(),
                request.getCorrelationId(),
                "api",
                request.isWaitForCompletion(),
                request.getWaitTimeoutMs(),
                request.isDryRun()
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
     * Suspends a running workflow instance.
     * <p>
     * A suspended workflow will not execute any further steps until resumed.
     * This is useful during incidents or when downstream services are unavailable.
     */
    @PostMapping("/{workflowId}/instances/{instanceId}/suspend")
    public Mono<ResponseEntity<WorkflowStatusResponse>> suspendWorkflow(
            @PathVariable String workflowId,
            @PathVariable String instanceId,
            @RequestBody(required = false) SuspendWorkflowRequest request) {

        String reason = request != null ? request.getReason() : null;
        log.info("Suspending workflow via API: workflowId={}, instanceId={}, reason={}",
                workflowId, instanceId, reason);

        return workflowService.suspendWorkflow(workflowId, instanceId, reason)
                .map(ResponseEntity::ok)
                .onErrorResume(WorkflowNotFoundException.class, e ->
                        Mono.just(ResponseEntity.notFound().build()))
                .onErrorResume(IllegalStateException.class, e ->
                        Mono.just(ResponseEntity.badRequest().build()));
    }

    /**
     * Resumes a suspended workflow instance.
     * <p>
     * The workflow will continue execution from where it was suspended.
     */
    @PostMapping("/{workflowId}/instances/{instanceId}/resume")
    public Mono<ResponseEntity<WorkflowStatusResponse>> resumeWorkflow(
            @PathVariable String workflowId,
            @PathVariable String instanceId) {

        log.info("Resuming workflow via API: workflowId={}, instanceId={}", workflowId, instanceId);

        return workflowService.resumeWorkflow(workflowId, instanceId)
                .map(ResponseEntity::ok)
                .onErrorResume(WorkflowNotFoundException.class, e ->
                        Mono.just(ResponseEntity.notFound().build()))
                .onErrorResume(IllegalStateException.class, e ->
                        Mono.just(ResponseEntity.badRequest().build()));
    }

    /**
     * Lists all suspended workflow instances.
     */
    @GetMapping("/suspended")
    public Flux<WorkflowStatusResponse> listSuspendedInstances() {
        return workflowService.findSuspendedInstances();
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

    // ==================== Signal Endpoints ====================

    /**
     * Sends an external signal to a workflow instance.
     * <p>
     * Signals allow external systems to communicate data or trigger state
     * transitions in running workflows. If a step is waiting for this signal,
     * it will be resumed.
     * <p>
     * Requires the event-sourced signal service to be configured.
     */
    @PostMapping("/{workflowId}/instances/{instanceId}/signal")
    public Mono<ResponseEntity<SignalResult>> sendSignal(
            @PathVariable String workflowId,
            @PathVariable String instanceId,
            @Valid @RequestBody SendSignalRequest request) {

        if (signalService == null) {
            log.warn("Signal endpoint called but SignalService is not configured");
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build());
        }

        log.info("Sending signal via API: workflowId={}, instanceId={}, signalName={}",
                workflowId, instanceId, request.getSignalName());

        return signalService.sendSignal(instanceId, request.getSignalName(), request.getPayload())
                .map(ResponseEntity::ok)
                .onErrorResume(WorkflowNotFoundException.class, e ->
                        Mono.just(ResponseEntity.notFound().build()));
    }

    // ==================== Query Endpoints ====================

    /**
     * Executes a built-in query against a workflow instance.
     * <p>
     * Queries allow external systems to inspect the internal state of a running
     * workflow without affecting its execution. Supported queries include:
     * getStatus, getCurrentStep, getStepHistory, getContext, getSearchAttributes,
     * getInput, getOutput, getPendingSignals, getActiveTimers, getChildWorkflows.
     * <p>
     * Requires the event-sourced query service to be configured.
     */
    @GetMapping("/{workflowId}/instances/{instanceId}/query/{queryName}")
    public Mono<ResponseEntity<Object>> executeQuery(
            @PathVariable String workflowId,
            @PathVariable String instanceId,
            @PathVariable String queryName) {

        if (queryService == null) {
            log.warn("Query endpoint called but WorkflowQueryService is not configured");
            return Mono.just(ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build());
        }

        log.info("Executing query via API: workflowId={}, instanceId={}, queryName={}",
                workflowId, instanceId, queryName);

        return queryService.executeQuery(instanceId, queryName)
                .map(result -> ResponseEntity.ok((Object) result))
                .onErrorResume(WorkflowNotFoundException.class, e ->
                        Mono.just(ResponseEntity.notFound().build()))
                .onErrorResume(IllegalArgumentException.class, e ->
                        Mono.just(ResponseEntity.badRequest().body(
                                Map.of("error", e.getMessage()))));
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
