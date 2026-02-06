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

package org.fireflyframework.workflow.state;

import org.fireflyframework.workflow.model.WorkflowInstance;
import org.fireflyframework.workflow.model.WorkflowStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * Interface for persisting and retrieving workflow state.
 * <p>
 * Implementations of this interface handle the storage and retrieval of
 * workflow instances, enabling workflow state to survive restarts and
 * be shared across multiple instances.
 */
public interface WorkflowStateStore {

    /**
     * Saves a workflow instance.
     *
     * @param instance the workflow instance to save
     * @return a Mono that completes when saved
     */
    Mono<WorkflowInstance> save(WorkflowInstance instance);

    /**
     * Saves a workflow instance with a specific TTL.
     *
     * @param instance the workflow instance to save
     * @param ttl time-to-live for the instance
     * @return a Mono that completes when saved
     */
    Mono<WorkflowInstance> save(WorkflowInstance instance, Duration ttl);

    /**
     * Finds a workflow instance by its ID.
     *
     * @param instanceId the instance ID
     * @return a Mono containing the instance if found
     */
    Mono<WorkflowInstance> findById(String instanceId);

    /**
     * Finds a workflow instance by workflow ID and instance ID.
     *
     * @param workflowId the workflow ID
     * @param instanceId the instance ID
     * @return a Mono containing the instance if found
     */
    Mono<WorkflowInstance> findByWorkflowAndInstanceId(String workflowId, String instanceId);

    /**
     * Finds all instances of a specific workflow.
     *
     * @param workflowId the workflow ID
     * @return a Flux of workflow instances
     */
    Flux<WorkflowInstance> findByWorkflowId(String workflowId);

    /**
     * Finds all instances with a specific status.
     *
     * @param status the status to filter by
     * @return a Flux of workflow instances
     */
    Flux<WorkflowInstance> findByStatus(WorkflowStatus status);

    /**
     * Finds all instances of a workflow with a specific status.
     *
     * @param workflowId the workflow ID
     * @param status the status to filter by
     * @return a Flux of workflow instances
     */
    Flux<WorkflowInstance> findByWorkflowIdAndStatus(String workflowId, WorkflowStatus status);

    /**
     * Finds instances by correlation ID.
     *
     * @param correlationId the correlation ID
     * @return a Flux of workflow instances
     */
    Flux<WorkflowInstance> findByCorrelationId(String correlationId);

    /**
     * Deletes a workflow instance.
     *
     * @param instanceId the instance ID to delete
     * @return a Mono that completes when deleted
     */
    Mono<Boolean> delete(String instanceId);

    /**
     * Deletes all instances of a specific workflow.
     *
     * @param workflowId the workflow ID
     * @return a Mono containing the number of deleted instances
     */
    Mono<Long> deleteByWorkflowId(String workflowId);

    /**
     * Checks if an instance exists.
     *
     * @param instanceId the instance ID
     * @return a Mono containing true if exists
     */
    Mono<Boolean> exists(String instanceId);

    /**
     * Counts all instances of a specific workflow.
     *
     * @param workflowId the workflow ID
     * @return a Mono containing the count
     */
    Mono<Long> countByWorkflowId(String workflowId);

    /**
     * Counts instances by workflow ID and status.
     *
     * @param workflowId the workflow ID
     * @param status the status
     * @return a Mono containing the count
     */
    Mono<Long> countByWorkflowIdAndStatus(String workflowId, WorkflowStatus status);

    /**
     * Finds all active (non-terminal) instances.
     *
     * @return a Flux of active instances
     */
    Flux<WorkflowInstance> findActiveInstances();

    /**
     * Finds stale instances that have been running longer than the specified duration.
     *
     * @param maxAge maximum age for an instance to be considered active
     * @return a Flux of stale instances
     */
    Flux<WorkflowInstance> findStaleInstances(Duration maxAge);

    /**
     * Updates the status of a workflow instance atomically.
     *
     * @param instanceId the instance ID
     * @param expectedStatus the expected current status
     * @param newStatus the new status to set
     * @return a Mono containing true if updated successfully
     */
    Mono<Boolean> updateStatus(String instanceId, WorkflowStatus expectedStatus, WorkflowStatus newStatus);

    /**
     * Checks if this store is available and healthy.
     *
     * @return a Mono containing true if healthy
     */
    Mono<Boolean> isHealthy();
}
