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

import org.fireflyframework.workflow.core.WorkflowEngine;
import org.fireflyframework.workflow.model.WorkflowInstance;
import org.fireflyframework.workflow.properties.WorkflowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Service for managing Dead Letter Queue (DLQ) entries.
 * <p>
 * Provides functionality to:
 * <ul>
 *   <li>List and query DLQ entries</li>
 *   <li>Replay failed workflows/steps</li>
 *   <li>Delete DLQ entries</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class DeadLetterService {

    private final DeadLetterStore deadLetterStore;
    private final WorkflowEngine workflowEngine;
    private final WorkflowProperties properties;

    /**
     * Saves a new DLQ entry.
     *
     * @param entry the entry to save
     * @return the saved entry
     */
    public Mono<DeadLetterEntry> save(DeadLetterEntry entry) {
        return deadLetterStore.save(entry)
                .doOnSuccess(saved -> log.info("DLQ_ENTRY_SAVED: id={}, workflowId={}, stepId={}",
                        saved.id(), saved.workflowId(), saved.stepId()));
    }

    /**
     * Gets all DLQ entries.
     *
     * @return flux of all entries
     */
    public Flux<DeadLetterEntry> getAllEntries() {
        return deadLetterStore.findAll();
    }

    /**
     * Gets a DLQ entry by ID.
     *
     * @param id the entry ID
     * @return the entry if found
     */
    public Mono<DeadLetterEntry> getEntry(String id) {
        return deadLetterStore.findById(id);
    }

    /**
     * Gets DLQ entries by workflow ID.
     *
     * @param workflowId the workflow ID
     * @return flux of entries
     */
    public Flux<DeadLetterEntry> getEntriesByWorkflowId(String workflowId) {
        return deadLetterStore.findByWorkflowId(workflowId);
    }

    /**
     * Gets DLQ entries by instance ID.
     *
     * @param instanceId the instance ID
     * @return flux of entries
     */
    public Flux<DeadLetterEntry> getEntriesByInstanceId(String instanceId) {
        return deadLetterStore.findByInstanceId(instanceId);
    }

    /**
     * Gets the count of DLQ entries.
     *
     * @return the count
     */
    public Mono<Long> getCount() {
        return deadLetterStore.count();
    }

    /**
     * Replays a DLQ entry by restarting the workflow from the failed step.
     *
     * @param id the entry ID
     * @return the result of the replay attempt
     */
    public Mono<ReplayResult> replay(String id) {
        return deadLetterStore.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("DLQ entry not found: " + id)))
                .flatMap(this::doReplay);
    }

    /**
     * Replays a DLQ entry with modified input.
     *
     * @param id the entry ID
     * @param modifiedInput modified input data
     * @return the result of the replay attempt
     */
    public Mono<ReplayResult> replay(String id, Map<String, Object> modifiedInput) {
        return deadLetterStore.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("DLQ entry not found: " + id)))
                .flatMap(entry -> doReplay(entry, modifiedInput));
    }

    /**
     * Replays all DLQ entries for a workflow.
     *
     * @param workflowId the workflow ID
     * @return flux of replay results
     */
    public Flux<ReplayResult> replayByWorkflowId(String workflowId) {
        return deadLetterStore.findByWorkflowId(workflowId)
                .flatMap(this::doReplay);
    }

    /**
     * Performs the actual replay operation.
     */
    private Mono<ReplayResult> doReplay(DeadLetterEntry entry) {
        return doReplay(entry, entry.payload());
    }

    /**
     * Performs the actual replay operation with custom input.
     */
    private Mono<ReplayResult> doReplay(DeadLetterEntry entry, Map<String, Object> input) {
        log.info("DLQ_REPLAY_START: id={}, workflowId={}, stepId={}",
                entry.id(), entry.workflowId(), entry.stepId());

        // Update the entry to track replay attempt
        DeadLetterEntry updated = entry.withRetryAttempt();

        return deadLetterStore.save(updated)
                .flatMap(savedEntry -> {
                    if (entry.stepId() != null) {
                        // Replay a specific step
                        return replayStep(savedEntry, input);
                    } else {
                        // Replay entire workflow
                        return replayWorkflow(savedEntry, input);
                    }
                })
                .flatMap(result -> {
                    if (result.success()) {
                        // Delete from DLQ on success
                        return deadLetterStore.delete(entry.id())
                                .thenReturn(result);
                    }
                    return Mono.just(result);
                })
                .doOnSuccess(result -> {
                    if (result.success()) {
                        log.info("DLQ_REPLAY_SUCCESS: id={}, instanceId={}",
                                entry.id(), result.instanceId());
                    } else {
                        log.warn("DLQ_REPLAY_FAILED: id={}, error={}",
                                entry.id(), result.errorMessage());
                    }
                });
    }

    /**
     * Replays a workflow by starting a new instance.
     */
    private Mono<ReplayResult> replayWorkflow(DeadLetterEntry entry, Map<String, Object> input) {
        return workflowEngine.startWorkflow(
                        entry.workflowId(),
                        input,
                        entry.correlationId(),
                        "dlq-replay:" + entry.id()
                )
                .map(instance -> ReplayResult.success(entry.id(), instance.instanceId()))
                .onErrorResume(error -> {
                    log.error("Failed to replay workflow from DLQ: {}", entry.id(), error);
                    return Mono.just(ReplayResult.failure(entry.id(), error.getMessage()));
                });
    }

    /**
     * Replays a specific step using the retry mechanism.
     */
    private Mono<ReplayResult> replayStep(DeadLetterEntry entry, Map<String, Object> input) {
        // Try to retry the workflow from the failed step
        return workflowEngine.retryWorkflow(entry.workflowId(), entry.instanceId())
                .map(instance -> ReplayResult.success(entry.id(), instance.instanceId()))
                .onErrorResume(error -> {
                    // If retry fails, try starting a new workflow
                    log.warn("Could not retry step, starting new workflow: {}", error.getMessage());
                    return replayWorkflow(entry, input);
                });
    }

    /**
     * Deletes a DLQ entry.
     *
     * @param id the entry ID
     * @return true if deleted
     */
    public Mono<Boolean> delete(String id) {
        return deadLetterStore.delete(id)
                .doOnSuccess(deleted -> {
                    if (deleted) {
                        log.info("DLQ_ENTRY_DELETED: id={}", id);
                    }
                });
    }

    /**
     * Deletes all DLQ entries for a workflow.
     *
     * @param workflowId the workflow ID
     * @return the number of entries deleted
     */
    public Mono<Long> deleteByWorkflowId(String workflowId) {
        return deadLetterStore.deleteByWorkflowId(workflowId)
                .doOnSuccess(count -> log.info("DLQ_ENTRIES_DELETED: workflowId={}, count={}", workflowId, count));
    }

    /**
     * Deletes all DLQ entries.
     *
     * @return the number of entries deleted
     */
    public Mono<Long> deleteAll() {
        return deadLetterStore.deleteAll()
                .doOnSuccess(count -> log.info("DLQ_ALL_ENTRIES_DELETED: count={}", count));
    }

    /**
     * Result of a replay operation.
     */
    public record ReplayResult(
            String entryId,
            boolean success,
            String instanceId,
            String errorMessage
    ) {
        public static ReplayResult success(String entryId, String instanceId) {
            return new ReplayResult(entryId, true, instanceId, null);
        }

        public static ReplayResult failure(String entryId, String errorMessage) {
            return new ReplayResult(entryId, false, null, errorMessage);
        }
    }
}
