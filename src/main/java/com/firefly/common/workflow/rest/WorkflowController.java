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

import com.firefly.common.workflow.core.WorkflowEngine;
import com.firefly.common.workflow.exception.StepExecutionException;
import com.firefly.common.workflow.exception.WorkflowNotFoundException;
import com.firefly.common.workflow.model.*;
import com.firefly.common.workflow.rest.dto.StartWorkflowRequest;
import com.firefly.common.workflow.rest.dto.StepStateResponse;
import com.firefly.common.workflow.rest.dto.TriggerStepRequest;
import com.firefly.common.workflow.rest.dto.WorkflowStateResponse;
import com.firefly.common.workflow.rest.dto.WorkflowStatusResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * REST controller for workflow operations.
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

    private final WorkflowEngine workflowEngine;

    /**
     * Lists all registered workflows.
     */
    @GetMapping
    public Mono<ResponseEntity<List<WorkflowSummary>>> listWorkflows() {
        List<WorkflowSummary> summaries = workflowEngine.getAllWorkflows().stream()
                .map(WorkflowSummary::from)
                .toList();
        return Mono.just(ResponseEntity.ok(summaries));
    }

    /**
     * Gets details of a specific workflow definition.
     */
    @GetMapping("/{workflowId}")
    public Mono<ResponseEntity<WorkflowDefinition>> getWorkflow(@PathVariable String workflowId) {
        return Mono.justOrEmpty(workflowEngine.getWorkflowDefinition(workflowId))
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

        StartWorkflowRequest finalRequest = request;
        return workflowEngine.startWorkflow(
                workflowId,
                request.getInput(),
                request.getCorrelationId(),
                "api"
        )
        .flatMap(instance -> {
            if (finalRequest.isWaitForCompletion()) {
                return waitForCompletion(instance, finalRequest.getWaitTimeoutMs());
            }
            return Mono.just(instance);
        })
        .map(instance -> {
            int totalSteps = workflowEngine.getWorkflowDefinition(workflowId)
                    .map(def -> def.steps().size())
                    .orElse(0);
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(WorkflowStatusResponse.from(instance, totalSteps));
        })
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
        
        return workflowEngine.getStatus(workflowId, instanceId)
                .map(instance -> {
                    int totalSteps = workflowEngine.getWorkflowDefinition(workflowId)
                            .map(def -> def.steps().size())
                            .orElse(0);
                    return ResponseEntity.ok(WorkflowStatusResponse.from(instance, totalSteps));
                })
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
        
        return workflowEngine.getStatus(workflowId, instanceId)
                .flatMap(instance -> {
                    if (!instance.status().isTerminal()) {
                        return Mono.just(ResponseEntity
                                .status(HttpStatus.ACCEPTED)
                                .body((Object) Map.of(
                                        "status", instance.status(),
                                        "message", "Workflow is still running"
                                )));
                    }
                    
                    if (instance.status() == WorkflowStatus.COMPLETED) {
                        return Mono.just(ResponseEntity.ok((Object) Map.of(
                                "status", "COMPLETED",
                                "result", instance.output() != null ? instance.output() : Map.of()
                        )));
                    }
                    
                    return Mono.just(ResponseEntity
                            .status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body((Object) Map.of(
                                    "status", instance.status(),
                                    "error", instance.errorMessage() != null ? instance.errorMessage() : "Unknown error"
                            )));
                })
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
        
        return workflowEngine.cancelWorkflow(workflowId, instanceId)
                .map(instance -> {
                    int totalSteps = workflowEngine.getWorkflowDefinition(workflowId)
                            .map(def -> def.steps().size())
                            .orElse(0);
                    return ResponseEntity.ok(WorkflowStatusResponse.from(instance, totalSteps));
                })
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
        
        return workflowEngine.retryWorkflow(workflowId, instanceId)
                .map(instance -> {
                    int totalSteps = workflowEngine.getWorkflowDefinition(workflowId)
                            .map(def -> def.steps().size())
                            .orElse(0);
                    return ResponseEntity.ok(WorkflowStatusResponse.from(instance, totalSteps));
                })
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
        
        Flux<WorkflowInstance> instances = status != null
                ? workflowEngine.findInstances(workflowId, status)
                : workflowEngine.findInstances(workflowId);
        
        int totalSteps = workflowEngine.getWorkflowDefinition(workflowId)
                .map(def -> def.steps().size())
                .orElse(0);
        
        return instances.map(instance -> WorkflowStatusResponse.from(instance, totalSteps));
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
        
        return workflowEngine.triggerStep(workflowId, instanceId, stepId, input, "api")
                .map(instance -> {
                    int totalSteps = workflowEngine.getWorkflowDefinition(workflowId)
                            .map(def -> def.steps().size())
                            .orElse(0);
                    return ResponseEntity.ok(WorkflowStatusResponse.from(instance, totalSteps));
                })
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
        
        return workflowEngine.getStepState(workflowId, instanceId, stepId)
                .map(state -> ResponseEntity.ok(StepStateResponse.from(state)))
                .onErrorResume(UnsupportedOperationException.class, e ->
                        Mono.just(ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build()))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /**
     * Gets all step states for a workflow instance.
     */
    @GetMapping("/{workflowId}/instances/{instanceId}/steps")
    public Flux<StepStateResponse> getStepStates(
            @PathVariable String workflowId,
            @PathVariable String instanceId) {
        
        return workflowEngine.getStepStates(workflowId, instanceId)
                .map(StepStateResponse::from)
                .onErrorResume(UnsupportedOperationException.class, e -> Flux.empty());
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
        
        return workflowEngine.getWorkflowState(workflowId, instanceId)
                .map(state -> ResponseEntity.ok(WorkflowStateResponse.from(state)))
                .onErrorResume(UnsupportedOperationException.class, e ->
                        Mono.just(ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build()))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /**
     * Waits for workflow completion.
     */
    private Mono<WorkflowInstance> waitForCompletion(WorkflowInstance instance, long timeoutMs) {
        if (instance.status().isTerminal()) {
            return Mono.just(instance);
        }
        
        return Mono.defer(() -> workflowEngine.getStatus(instance.instanceId()))
                .repeatWhen(flux -> flux.delayElements(Duration.ofMillis(500)))
                .filter(i -> i.status().isTerminal())
                .next()
                .timeout(Duration.ofMillis(timeoutMs))
                .onErrorResume(e -> workflowEngine.getStatus(instance.instanceId()));
    }

    /**
     * Summary DTO for workflow listing.
     */
    public record WorkflowSummary(
            String workflowId,
            String name,
            String description,
            String version,
            String triggerMode,
            int stepCount
    ) {
        public static WorkflowSummary from(WorkflowDefinition def) {
            return new WorkflowSummary(
                    def.workflowId(),
                    def.name(),
                    def.description(),
                    def.version(),
                    def.triggerMode().name(),
                    def.steps().size()
            );
        }
    }
}
