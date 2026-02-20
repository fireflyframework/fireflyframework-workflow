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

package org.fireflyframework.workflow.eventsourcing.projection;

import org.fireflyframework.eventsourcing.domain.StoredEventEnvelope;
import org.fireflyframework.eventsourcing.projection.ProjectionService;
import org.fireflyframework.eventsourcing.store.EventStore;
import org.fireflyframework.workflow.eventsourcing.event.*;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Read-side projection that maintains the {@code workflow_instances_projection} table
 * by processing the global event stream.
 * <p>
 * Handles 9 event types that affect queryable workflow instance metadata:
 * <ul>
 *   <li>{@link WorkflowStartedEvent} — inserts/upserts the projection row</li>
 *   <li>{@link WorkflowCompletedEvent} — sets status to COMPLETED</li>
 *   <li>{@link WorkflowFailedEvent} — sets status to FAILED with error details</li>
 *   <li>{@link WorkflowCancelledEvent} — sets status to CANCELLED</li>
 *   <li>{@link WorkflowSuspendedEvent} — sets status to SUSPENDED</li>
 *   <li>{@link WorkflowResumedEvent} — sets status back to RUNNING</li>
 *   <li>{@link StepStartedEvent} — updates current_step_id</li>
 *   <li>{@link StepCompletedEvent} — updates current_step_id</li>
 *   <li>{@link ContinueAsNewEvent} — sets status to COMPLETED</li>
 * </ul>
 * All other event types (signals, timers, heartbeats, etc.) are ignored since
 * they do not affect queryable columns.
 */
@Slf4j
public class WorkflowInstanceProjection extends ProjectionService<Void> {

    private static final String PROJECTION_NAME = "workflow-instances-projection";
    private static final String AGGREGATE_TYPE = "workflow";

    private final DatabaseClient databaseClient;
    private final EventStore eventStore;

    public WorkflowInstanceProjection(DatabaseClient databaseClient,
                                      EventStore eventStore,
                                      MeterRegistry meterRegistry) {
        super(meterRegistry);
        this.databaseClient = databaseClient;
        this.eventStore = eventStore;
    }

    @Override
    public String getProjectionName() {
        return PROJECTION_NAME;
    }

    @Override
    public Mono<Void> handleEvent(StoredEventEnvelope envelope) {
        if (!AGGREGATE_TYPE.equals(envelope.getAggregateType())) {
            return Mono.empty();
        }

        return Mono.defer(() -> {
            if (envelope.getEvent() instanceof WorkflowStartedEvent event) {
                return handleWorkflowStarted(event, envelope);
            } else if (envelope.getEvent() instanceof WorkflowCompletedEvent) {
                return handleStatusUpdate(envelope, "COMPLETED", true);
            } else if (envelope.getEvent() instanceof WorkflowFailedEvent event) {
                return handleWorkflowFailed(event, envelope);
            } else if (envelope.getEvent() instanceof WorkflowCancelledEvent) {
                return handleStatusUpdate(envelope, "CANCELLED", true);
            } else if (envelope.getEvent() instanceof WorkflowSuspendedEvent) {
                return handleStatusUpdate(envelope, "SUSPENDED", false);
            } else if (envelope.getEvent() instanceof WorkflowResumedEvent) {
                return handleStatusUpdate(envelope, "RUNNING", false);
            } else if (envelope.getEvent() instanceof StepStartedEvent event) {
                return handleStepUpdate(event.getStepId(), envelope);
            } else if (envelope.getEvent() instanceof StepCompletedEvent event) {
                return handleStepUpdate(event.getStepId(), envelope);
            } else if (envelope.getEvent() instanceof ContinueAsNewEvent) {
                return handleStatusUpdate(envelope, "COMPLETED", true);
            }
            return Mono.empty();
        });
    }

    @Override
    public Mono<Long> getCurrentPosition() {
        return databaseClient.sql(
                        "SELECT position FROM projection_positions WHERE projection_name = :projectionName")
                .bind("projectionName", PROJECTION_NAME)
                .map(row -> row.get("position", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    @Override
    public Mono<Void> updatePosition(long position) {
        return databaseClient.sql("""
                    INSERT INTO projection_positions (projection_name, position, last_updated)
                    VALUES (:projectionName, :position, :lastUpdated)
                    ON CONFLICT (projection_name)
                    DO UPDATE SET position = :position, last_updated = :lastUpdated
                    """)
                .bind("projectionName", PROJECTION_NAME)
                .bind("position", position)
                .bind("lastUpdated", Instant.now())
                .then()
                .doOnSuccess(v -> getMetrics().updatePosition(position, 0L));
    }

    @Override
    protected Mono<Void> clearProjectionData() {
        log.info("Clearing workflow instances projection data");
        return databaseClient.sql("DELETE FROM workflow_instances_projection")
                .fetch()
                .rowsUpdated()
                .doOnSuccess(count -> log.info("Deleted {} workflow instance projection rows", count))
                .then();
    }

    @Override
    protected Mono<Long> getLatestGlobalSequenceFromEventStore() {
        return eventStore.getCurrentGlobalSequence();
    }

    // ========================================================================
    // Event Handlers
    // ========================================================================

    private Mono<Void> handleWorkflowStarted(WorkflowStartedEvent event, StoredEventEnvelope envelope) {
        log.debug("Projecting WorkflowStartedEvent: instanceId={}, workflowId={}",
                envelope.getAggregateId(), event.getWorkflowId());

        return databaseClient.sql("""
                    INSERT INTO workflow_instances_projection
                        (instance_id, workflow_id, workflow_name, workflow_version,
                         status, correlation_id, triggered_by, started_at,
                         last_updated, version)
                    VALUES
                        (:instanceId, :workflowId, :workflowName, :workflowVersion,
                         'RUNNING', :correlationId, :triggeredBy, :startedAt,
                         NOW(), :version)
                    ON CONFLICT (instance_id) DO UPDATE SET
                        workflow_id = :workflowId,
                        workflow_name = :workflowName,
                        workflow_version = :workflowVersion,
                        status = 'RUNNING',
                        correlation_id = :correlationId,
                        triggered_by = :triggeredBy,
                        started_at = :startedAt,
                        completed_at = NULL,
                        error_message = NULL,
                        error_type = NULL,
                        current_step_id = NULL,
                        last_updated = NOW(),
                        version = :version,
                        deleted = FALSE
                    """)
                .bind("instanceId", envelope.getAggregateId())
                .bind("workflowId", event.getWorkflowId())
                .bind("workflowName", event.getWorkflowName() != null ? event.getWorkflowName() : "")
                .bind("workflowVersion", event.getWorkflowVersion() != null ? event.getWorkflowVersion() : "")
                .bind("correlationId", event.getCorrelationId() != null ? event.getCorrelationId() : "")
                .bind("triggeredBy", event.getTriggeredBy() != null ? event.getTriggeredBy() : "")
                .bind("startedAt", envelope.getCreatedAt())
                .bind("version", envelope.getGlobalSequence())
                .then();
    }

    private Mono<Void> handleWorkflowFailed(WorkflowFailedEvent event, StoredEventEnvelope envelope) {
        log.debug("Projecting WorkflowFailedEvent: instanceId={}", envelope.getAggregateId());

        return databaseClient.sql("""
                    UPDATE workflow_instances_projection SET
                        status = 'FAILED',
                        error_message = :errorMessage,
                        error_type = :errorType,
                        completed_at = :completedAt,
                        last_updated = NOW(),
                        version = :version
                    WHERE instance_id = :instanceId
                    """)
                .bind("instanceId", envelope.getAggregateId())
                .bind("errorMessage", event.getErrorMessage() != null ? event.getErrorMessage() : "")
                .bind("errorType", event.getErrorType() != null ? event.getErrorType() : "")
                .bind("completedAt", envelope.getCreatedAt())
                .bind("version", envelope.getGlobalSequence())
                .then();
    }

    private Mono<Void> handleStatusUpdate(StoredEventEnvelope envelope, String status, boolean setCompletedAt) {
        log.debug("Projecting status update to {}: instanceId={}", status, envelope.getAggregateId());

        if (setCompletedAt) {
            return databaseClient.sql("""
                        UPDATE workflow_instances_projection SET
                            status = :status,
                            completed_at = :completedAt,
                            last_updated = NOW(),
                            version = :version
                        WHERE instance_id = :instanceId
                        """)
                    .bind("instanceId", envelope.getAggregateId())
                    .bind("status", status)
                    .bind("completedAt", envelope.getCreatedAt())
                    .bind("version", envelope.getGlobalSequence())
                    .then();
        }

        return databaseClient.sql("""
                    UPDATE workflow_instances_projection SET
                        status = :status,
                        last_updated = NOW(),
                        version = :version
                    WHERE instance_id = :instanceId
                    """)
                .bind("instanceId", envelope.getAggregateId())
                .bind("status", status)
                .bind("version", envelope.getGlobalSequence())
                .then();
    }

    private Mono<Void> handleStepUpdate(String stepId, StoredEventEnvelope envelope) {
        log.debug("Projecting step update: instanceId={}, stepId={}", envelope.getAggregateId(), stepId);

        return databaseClient.sql("""
                    UPDATE workflow_instances_projection SET
                        current_step_id = :stepId,
                        last_updated = NOW(),
                        version = :version
                    WHERE instance_id = :instanceId
                    """)
                .bind("instanceId", envelope.getAggregateId())
                .bind("stepId", stepId)
                .bind("version", envelope.getGlobalSequence())
                .then();
    }
}
