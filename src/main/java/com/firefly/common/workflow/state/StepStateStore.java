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

package com.firefly.common.workflow.state;

import com.firefly.common.workflow.model.StepState;
import com.firefly.common.workflow.model.StepStatus;
import com.firefly.common.workflow.model.WorkflowState;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Interface for persisting and retrieving step states.
 * <p>
 * Each step in a workflow instance has its own independent state entry,
 * allowing for step-level choreography and event-driven execution.
 */
public interface StepStateStore {

    /**
     * Saves a step state.
     *
     * @param state the step state to save
     * @return the saved state
     */
    Mono<StepState> saveStepState(StepState state);

    /**
     * Saves a step state with a specific TTL.
     *
     * @param state the step state to save
     * @param ttl time-to-live duration
     * @return the saved state
     */
    Mono<StepState> saveStepState(StepState state, Duration ttl);

    /**
     * Gets a step state by its identifiers.
     *
     * @param workflowId the workflow ID
     * @param instanceId the workflow instance ID
     * @param stepId the step ID
     * @return the step state if found
     */
    Mono<StepState> getStepState(String workflowId, String instanceId, String stepId);

    /**
     * Gets all step states for a workflow instance.
     *
     * @param workflowId the workflow ID
     * @param instanceId the workflow instance ID
     * @return flux of step states
     */
    Flux<StepState> getStepStates(String workflowId, String instanceId);

    /**
     * Finds steps waiting for a specific event type.
     *
     * @param eventType the event type
     * @return flux of step states waiting for this event
     */
    Flux<StepState> findStepsWaitingForEvent(String eventType);

    /**
     * Finds steps in a specific status.
     *
     * @param status the step status
     * @return flux of matching step states
     */
    Flux<StepState> findStepsByStatus(StepStatus status);

    /**
     * Finds steps by workflow and status.
     *
     * @param workflowId the workflow ID
     * @param instanceId the workflow instance ID
     * @param status the step status
     * @return flux of matching step states
     */
    Flux<StepState> findStepsByStatus(String workflowId, String instanceId, StepStatus status);

    /**
     * Deletes a step state.
     *
     * @param workflowId the workflow ID
     * @param instanceId the workflow instance ID
     * @param stepId the step ID
     * @return true if deleted
     */
    Mono<Boolean> deleteStepState(String workflowId, String instanceId, String stepId);

    /**
     * Deletes all step states for a workflow instance.
     *
     * @param workflowId the workflow ID
     * @param instanceId the workflow instance ID
     * @return count of deleted states
     */
    Mono<Long> deleteStepStates(String workflowId, String instanceId);

    /**
     * Saves the overall workflow state.
     *
     * @param state the workflow state to save
     * @return the saved state
     */
    Mono<WorkflowState> saveWorkflowState(WorkflowState state);

    /**
     * Saves the workflow state with a specific TTL.
     *
     * @param state the workflow state to save
     * @param ttl time-to-live duration
     * @return the saved state
     */
    Mono<WorkflowState> saveWorkflowState(WorkflowState state, Duration ttl);

    /**
     * Gets the workflow state.
     *
     * @param workflowId the workflow ID
     * @param instanceId the workflow instance ID
     * @return the workflow state if found
     */
    Mono<WorkflowState> getWorkflowState(String workflowId, String instanceId);

    /**
     * Finds workflow states by status.
     *
     * @param status the workflow status
     * @return flux of matching workflow states
     */
    Flux<WorkflowState> findWorkflowStatesByStatus(com.firefly.common.workflow.model.WorkflowStatus status);

    /**
     * Finds workflow states waiting for a specific event.
     *
     * @param eventType the event type
     * @return flux of workflow states waiting for this event
     */
    Flux<WorkflowState> findWorkflowStatesWaitingForEvent(String eventType);

    /**
     * Checks if the store is healthy.
     *
     * @return true if healthy
     */
    Mono<Boolean> isHealthy();
}
