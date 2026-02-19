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

package org.fireflyframework.workflow.eventsourcing.store;

import org.fireflyframework.eventsourcing.domain.Event;
import org.fireflyframework.eventsourcing.domain.EventStream;
import org.fireflyframework.eventsourcing.store.EventStore;
import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.model.StepExecution;
import org.fireflyframework.workflow.model.WorkflowInstance;
import org.fireflyframework.workflow.model.WorkflowStatus;
import org.fireflyframework.workflow.state.WorkflowStateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Event-sourced implementation of {@link WorkflowStateStore}.
 * <p>
 * This store bridges the workflow engine's state store interface with the event
 * sourcing infrastructure. It loads and saves {@link WorkflowAggregate} instances
 * via the {@link EventStore} and converts between aggregate state and
 * {@link WorkflowInstance} records.
 * <p>
 * Key design decisions:
 * <ul>
 *   <li>State is derived from event history, not stored directly</li>
 *   <li>Query methods return empty results (will be implemented via projections later)</li>
 *   <li>Delete operations return false (event-sourced data is immutable)</li>
 *   <li>Count operations return 0 (projection-dependent)</li>
 * </ul>
 *
 * @see WorkflowStateStore
 * @see WorkflowAggregate
 * @see EventStore
 */
@Slf4j
@RequiredArgsConstructor
public class EventSourcedWorkflowStateStore implements WorkflowStateStore {

    private static final String AGGREGATE_TYPE = "workflow";

    private final EventStore eventStore;

    // ========================================================================
    // Aggregate Operations (new, not in interface)
    // ========================================================================

    /**
     * Loads a {@link WorkflowAggregate} from the event store by replaying its event stream.
     * <p>
     * Creates a new aggregate instance, loads the event stream from the event store,
     * and calls {@code loadFromHistory()} to reconstruct the aggregate state.
     *
     * @param aggregateId the unique identifier of the aggregate to load
     * @return a Mono that emits the reconstructed aggregate, or empty if not found
     */
    public Mono<WorkflowAggregate> loadAggregate(UUID aggregateId) {
        log.debug("Loading workflow aggregate: {}", aggregateId);

        return eventStore.loadEventStream(aggregateId, AGGREGATE_TYPE)
                .filter(stream -> !stream.isEmpty())
                .map(stream -> {
                    WorkflowAggregate aggregate = new WorkflowAggregate(aggregateId);
                    aggregate.loadFromHistory(stream.getEvents());
                    log.debug("Loaded workflow aggregate: id={}, version={}, status={}",
                            aggregateId, aggregate.getCurrentVersion(), aggregate.getStatus());
                    return aggregate;
                });
    }

    /**
     * Saves a {@link WorkflowAggregate} by appending its uncommitted events to the event store.
     * <p>
     * If the aggregate has uncommitted events, they are appended to the event store
     * with optimistic concurrency control. The expected version is calculated from the
     * current version minus the number of uncommitted events. After successful persistence,
     * {@code markEventsAsCommitted()} is called.
     *
     * @param aggregate the aggregate to save
     * @return a Mono that emits the saved aggregate
     */
    public Mono<WorkflowAggregate> saveAggregate(WorkflowAggregate aggregate) {
        if (!aggregate.hasUncommittedEvents()) {
            log.debug("No uncommitted events for aggregate: {}", aggregate.getId());
            return Mono.just(aggregate);
        }

        List<Event> uncommittedEvents = List.copyOf(aggregate.getUncommittedEvents());
        long expectedVersion = aggregate.getCurrentVersion() - uncommittedEvents.size();

        log.debug("Saving workflow aggregate: id={}, uncommittedEvents={}, expectedVersion={}",
                aggregate.getId(), uncommittedEvents.size(), expectedVersion);

        return eventStore.appendEvents(
                        aggregate.getId(),
                        AGGREGATE_TYPE,
                        uncommittedEvents,
                        expectedVersion,
                        Map.of())
                .doOnSuccess(stream -> {
                    aggregate.markEventsAsCommitted();
                    log.debug("Saved workflow aggregate: id={}, newVersion={}",
                            aggregate.getId(), aggregate.getCurrentVersion());
                })
                .thenReturn(aggregate);
    }

    // ========================================================================
    // WorkflowStateStore Interface — Core Operations
    // ========================================================================

    /**
     * Returns the instance as-is.
     * <p>
     * In the event-sourced model, state is managed via aggregate events, not direct saves.
     * The caller is expected to use aggregate operations for actual persistence.
     */
    @Override
    public Mono<WorkflowInstance> save(WorkflowInstance instance) {
        log.debug("Save called for instance: {} (event-sourced — returning as-is)", instance.instanceId());
        return Mono.just(instance);
    }

    /**
     * Returns the instance as-is.
     * <p>
     * TTL is not applicable in the event-sourced model since events are immutable
     * and preserved indefinitely.
     */
    @Override
    public Mono<WorkflowInstance> save(WorkflowInstance instance, Duration ttl) {
        log.debug("Save with TTL called for instance: {} (event-sourced — returning as-is)", instance.instanceId());
        return Mono.just(instance);
    }

    /**
     * Finds a workflow instance by loading the aggregate from the event store
     * and converting it to a {@link WorkflowInstance}.
     */
    @Override
    public Mono<WorkflowInstance> findById(String instanceId) {
        log.debug("Finding workflow instance by ID: {}", instanceId);
        try {
            UUID aggregateId = UUID.fromString(instanceId);
            return loadAggregate(aggregateId)
                    .map(this::toWorkflowInstance);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid instance ID format: {}", instanceId);
            return Mono.empty();
        }
    }

    /**
     * Delegates to {@link #findById(String)}.
     * <p>
     * In the event-sourced model, the instance ID is the aggregate ID and is globally
     * unique, so the workflow ID is not needed for lookup.
     */
    @Override
    public Mono<WorkflowInstance> findByWorkflowAndInstanceId(String workflowId, String instanceId) {
        return findById(instanceId);
    }

    /**
     * Checks if a workflow aggregate exists in the event store.
     */
    @Override
    public Mono<Boolean> exists(String instanceId) {
        log.debug("Checking existence of workflow instance: {}", instanceId);
        try {
            UUID aggregateId = UUID.fromString(instanceId);
            return eventStore.aggregateExists(aggregateId, AGGREGATE_TYPE);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid instance ID format: {}", instanceId);
            return Mono.just(false);
        }
    }

    /**
     * Updates the status of a workflow instance atomically.
     * <p>
     * Loads the aggregate, validates that the current status matches the expected status,
     * applies the appropriate status transition command, saves the aggregate, and returns
     * true on success.
     */
    @Override
    public Mono<Boolean> updateStatus(String instanceId, WorkflowStatus expectedStatus, WorkflowStatus newStatus) {
        log.debug("Updating status for instance: {} from {} to {}", instanceId, expectedStatus, newStatus);
        try {
            UUID aggregateId = UUID.fromString(instanceId);
            return loadAggregate(aggregateId)
                    .flatMap(aggregate -> {
                        if (aggregate.getStatus() != expectedStatus) {
                            log.warn("Status mismatch for instance: {}. Expected: {}, actual: {}",
                                    instanceId, expectedStatus, aggregate.getStatus());
                            return Mono.just(false);
                        }

                        applyStatusTransition(aggregate, newStatus);
                        return saveAggregate(aggregate)
                                .thenReturn(true);
                    })
                    .defaultIfEmpty(false);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid instance ID format: {}", instanceId);
            return Mono.just(false);
        }
    }

    /**
     * Delegates to {@link EventStore#isHealthy()}.
     */
    @Override
    public Mono<Boolean> isHealthy() {
        return eventStore.isHealthy();
    }

    // ========================================================================
    // WorkflowStateStore Interface — Query Operations (projection-dependent)
    // ========================================================================

    /**
     * Returns empty. Will be implemented via projections in a future task.
     */
    @Override
    public Flux<WorkflowInstance> findByWorkflowId(String workflowId) {
        log.debug("findByWorkflowId not yet implemented for event-sourced store (requires projections)");
        return Flux.empty();
    }

    /**
     * Returns empty. Will be implemented via projections in a future task.
     */
    @Override
    public Flux<WorkflowInstance> findByStatus(WorkflowStatus status) {
        log.debug("findByStatus not yet implemented for event-sourced store (requires projections)");
        return Flux.empty();
    }

    /**
     * Returns empty. Will be implemented via projections in a future task.
     */
    @Override
    public Flux<WorkflowInstance> findByWorkflowIdAndStatus(String workflowId, WorkflowStatus status) {
        log.debug("findByWorkflowIdAndStatus not yet implemented for event-sourced store (requires projections)");
        return Flux.empty();
    }

    /**
     * Returns empty. Will be implemented via projections in a future task.
     */
    @Override
    public Flux<WorkflowInstance> findByCorrelationId(String correlationId) {
        log.debug("findByCorrelationId not yet implemented for event-sourced store (requires projections)");
        return Flux.empty();
    }

    /**
     * Returns empty. Will be implemented via projections in a future task.
     */
    @Override
    public Flux<WorkflowInstance> findActiveInstances() {
        log.debug("findActiveInstances not yet implemented for event-sourced store (requires projections)");
        return Flux.empty();
    }

    /**
     * Returns empty. Will be implemented via projections in a future task.
     */
    @Override
    public Flux<WorkflowInstance> findStaleInstances(Duration maxAge) {
        log.debug("findStaleInstances not yet implemented for event-sourced store (requires projections)");
        return Flux.empty();
    }

    // ========================================================================
    // WorkflowStateStore Interface — Delete Operations (immutable events)
    // ========================================================================

    /**
     * Returns false. Event-sourced data is immutable and cannot be deleted.
     */
    @Override
    public Mono<Boolean> delete(String instanceId) {
        log.debug("Delete not supported for event-sourced store (events are immutable)");
        return Mono.just(false);
    }

    /**
     * Returns 0L. Event-sourced data is immutable and cannot be deleted.
     */
    @Override
    public Mono<Long> deleteByWorkflowId(String workflowId) {
        log.debug("DeleteByWorkflowId not supported for event-sourced store (events are immutable)");
        return Mono.just(0L);
    }

    // ========================================================================
    // WorkflowStateStore Interface — Count Operations (projection-dependent)
    // ========================================================================

    /**
     * Returns 0L. Will be implemented via projections in a future task.
     */
    @Override
    public Mono<Long> countByWorkflowId(String workflowId) {
        log.debug("countByWorkflowId not yet implemented for event-sourced store (requires projections)");
        return Mono.just(0L);
    }

    /**
     * Returns 0L. Will be implemented via projections in a future task.
     */
    @Override
    public Mono<Long> countByWorkflowIdAndStatus(String workflowId, WorkflowStatus status) {
        log.debug("countByWorkflowIdAndStatus not yet implemented for event-sourced store (requires projections)");
        return Mono.just(0L);
    }

    // ========================================================================
    // Conversion Methods
    // ========================================================================

    /**
     * Converts a {@link WorkflowAggregate} to a {@link WorkflowInstance} record.
     * <p>
     * Maps all aggregate state fields to the corresponding WorkflowInstance fields,
     * including step states converted to {@link StepExecution} records.
     *
     * @param aggregate the aggregate to convert
     * @return the corresponding WorkflowInstance record
     */
    public WorkflowInstance toWorkflowInstance(WorkflowAggregate aggregate) {
        List<StepExecution> stepExecutions = new ArrayList<>();
        for (WorkflowAggregate.StepState stepState : aggregate.getStepStates().values()) {
            stepExecutions.add(toStepExecution(stepState));
        }

        return new WorkflowInstance(
                aggregate.getId().toString(),
                aggregate.getWorkflowId(),
                aggregate.getWorkflowName(),
                aggregate.getWorkflowVersion(),
                aggregate.getStatus(),
                aggregate.getCurrentStepId(),
                aggregate.getContext() != null ? Map.copyOf(aggregate.getContext()) : Map.of(),
                aggregate.getInput() != null ? Map.copyOf(aggregate.getInput()) : Map.of(),
                aggregate.getOutput(),
                stepExecutions,
                null, // errorMessage — not tracked at aggregate level in current events
                null, // errorType — not tracked at aggregate level in current events
                aggregate.getCorrelationId(),
                aggregate.getTriggeredBy(),
                null, // createdAt — not tracked separately from startedAt in aggregate
                aggregate.getStartedAt(),
                aggregate.getCompletedAt()
        );
    }

    // ========================================================================
    // Private Helpers
    // ========================================================================

    /**
     * Converts a {@link WorkflowAggregate.StepState} to a {@link StepExecution} record.
     */
    private StepExecution toStepExecution(WorkflowAggregate.StepState stepState) {
        // Determine errorMessage and errorType from errorOrReason
        String errorMessage = null;
        String errorType = null;
        if (stepState.status() == org.fireflyframework.workflow.model.StepStatus.FAILED && stepState.errorOrReason() != null) {
            errorMessage = stepState.errorOrReason();
            errorType = "StepExecutionException";
        }

        return new StepExecution(
                stepState.stepId(),       // executionId — use stepId as a stable identifier
                stepState.stepId(),
                stepState.stepName(),
                stepState.status(),
                stepState.input(),
                stepState.output(),
                errorMessage,
                errorType,
                stepState.attemptNumber(),
                stepState.startedAt(),
                stepState.completedAt()
        );
    }

    /**
     * Applies the appropriate status transition command to the aggregate based on
     * the desired new status.
     *
     * @param aggregate the aggregate to transition
     * @param newStatus the target status
     * @throws IllegalStateException if the transition is not supported
     */
    private void applyStatusTransition(WorkflowAggregate aggregate, WorkflowStatus newStatus) {
        switch (newStatus) {
            case COMPLETED -> aggregate.complete(null);
            case FAILED -> aggregate.fail("Status updated to FAILED", "StatusTransition", aggregate.getCurrentStepId());
            case CANCELLED -> aggregate.cancel("Status updated to CANCELLED");
            case SUSPENDED -> aggregate.suspend("Status updated to SUSPENDED");
            case RUNNING -> {
                if (aggregate.getStatus() == WorkflowStatus.SUSPENDED) {
                    aggregate.resume();
                } else {
                    throw new IllegalStateException(
                            "Cannot transition to RUNNING from " + aggregate.getStatus());
                }
            }
            default -> throw new IllegalStateException(
                    "Unsupported status transition to: " + newStatus);
        }
    }
}
