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

package org.fireflyframework.workflow.dlq;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Interface for persisting and retrieving Dead Letter Queue entries.
 * <p>
 * Implementations of this interface handle the storage and retrieval of
 * failed workflow/step execution records for later analysis and replay.
 */
public interface DeadLetterStore {

    /**
     * Saves a DLQ entry.
     *
     * @param entry the entry to save
     * @return the saved entry
     */
    Mono<DeadLetterEntry> save(DeadLetterEntry entry);

    /**
     * Finds a DLQ entry by ID.
     *
     * @param id the entry ID
     * @return the entry if found
     */
    Mono<DeadLetterEntry> findById(String id);

    /**
     * Finds all DLQ entries.
     *
     * @return flux of all entries
     */
    Flux<DeadLetterEntry> findAll();

    /**
     * Finds DLQ entries by workflow ID.
     *
     * @param workflowId the workflow ID
     * @return flux of entries for the workflow
     */
    Flux<DeadLetterEntry> findByWorkflowId(String workflowId);

    /**
     * Finds DLQ entries by workflow instance ID.
     *
     * @param instanceId the instance ID
     * @return flux of entries for the instance
     */
    Flux<DeadLetterEntry> findByInstanceId(String instanceId);

    /**
     * Finds DLQ entries by step ID.
     *
     * @param workflowId the workflow ID
     * @param stepId the step ID
     * @return flux of entries for the step
     */
    Flux<DeadLetterEntry> findByStep(String workflowId, String stepId);

    /**
     * Counts all DLQ entries.
     *
     * @return the total count
     */
    Mono<Long> count();

    /**
     * Counts DLQ entries by workflow ID.
     *
     * @param workflowId the workflow ID
     * @return the count for the workflow
     */
    Mono<Long> countByWorkflowId(String workflowId);

    /**
     * Deletes a DLQ entry by ID.
     *
     * @param id the entry ID
     * @return true if deleted
     */
    Mono<Boolean> delete(String id);

    /**
     * Deletes all DLQ entries for a workflow.
     *
     * @param workflowId the workflow ID
     * @return the number of entries deleted
     */
    Mono<Long> deleteByWorkflowId(String workflowId);

    /**
     * Deletes all DLQ entries.
     *
     * @return the number of entries deleted
     */
    Mono<Long> deleteAll();

    /**
     * Checks if the store is healthy.
     *
     * @return true if healthy
     */
    Mono<Boolean> isHealthy();
}
