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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Repository for querying the workflow instances projection table.
 * <p>
 * This is the query layer for the read-side projection. It returns instance IDs
 * ({@link UUID}) for query methods and counts for count methods. The caller
 * (typically {@code EventSourcedWorkflowStateStore}) loads the full aggregate
 * from the event store to reconstruct a {@code WorkflowInstance}.
 */
@Slf4j
@RequiredArgsConstructor
public class WorkflowProjectionRepository {

    private final DatabaseClient databaseClient;

    /**
     * Finds instance IDs for a given workflow definition.
     */
    public Flux<UUID> findInstanceIdsByWorkflowId(String workflowId) {
        return databaseClient.sql("""
                    SELECT instance_id FROM workflow_instances_projection
                    WHERE workflow_id = :workflowId AND deleted = FALSE
                    """)
                .bind("workflowId", workflowId)
                .map(row -> row.get("instance_id", UUID.class))
                .all();
    }

    /**
     * Finds instance IDs with a given status.
     */
    public Flux<UUID> findInstanceIdsByStatus(String status) {
        return databaseClient.sql("""
                    SELECT instance_id FROM workflow_instances_projection
                    WHERE status = :status AND deleted = FALSE
                    """)
                .bind("status", status)
                .map(row -> row.get("instance_id", UUID.class))
                .all();
    }

    /**
     * Finds instance IDs for a workflow with a given status.
     */
    public Flux<UUID> findInstanceIdsByWorkflowIdAndStatus(String workflowId, String status) {
        return databaseClient.sql("""
                    SELECT instance_id FROM workflow_instances_projection
                    WHERE workflow_id = :workflowId AND status = :status AND deleted = FALSE
                    """)
                .bind("workflowId", workflowId)
                .bind("status", status)
                .map(row -> row.get("instance_id", UUID.class))
                .all();
    }

    /**
     * Finds instance IDs with a given correlation ID.
     */
    public Flux<UUID> findInstanceIdsByCorrelationId(String correlationId) {
        return databaseClient.sql("""
                    SELECT instance_id FROM workflow_instances_projection
                    WHERE correlation_id = :correlationId AND deleted = FALSE
                    """)
                .bind("correlationId", correlationId)
                .map(row -> row.get("instance_id", UUID.class))
                .all();
    }

    /**
     * Finds instance IDs that are in an active state (PENDING, RUNNING, or WAITING).
     */
    public Flux<UUID> findActiveInstanceIds() {
        return databaseClient.sql("""
                    SELECT instance_id FROM workflow_instances_projection
                    WHERE status IN ('PENDING', 'RUNNING', 'WAITING') AND deleted = FALSE
                    """)
                .map(row -> row.get("instance_id", UUID.class))
                .all();
    }

    /**
     * Finds instance IDs that are stale — RUNNING or WAITING with started_at older than maxAge.
     */
    public Flux<UUID> findStaleInstanceIds(Duration maxAge) {
        Instant cutoff = Instant.now().minus(maxAge);
        return databaseClient.sql("""
                    SELECT instance_id FROM workflow_instances_projection
                    WHERE status IN ('RUNNING', 'WAITING')
                      AND started_at < :cutoff
                      AND deleted = FALSE
                    """)
                .bind("cutoff", cutoff)
                .map(row -> row.get("instance_id", UUID.class))
                .all();
    }

    /**
     * Counts instances for a given workflow definition.
     */
    public Mono<Long> countByWorkflowId(String workflowId) {
        return databaseClient.sql("""
                    SELECT COUNT(*) AS cnt FROM workflow_instances_projection
                    WHERE workflow_id = :workflowId AND deleted = FALSE
                    """)
                .bind("workflowId", workflowId)
                .map(row -> row.get("cnt", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    /**
     * Counts instances for a workflow with a given status.
     */
    public Mono<Long> countByWorkflowIdAndStatus(String workflowId, String status) {
        return databaseClient.sql("""
                    SELECT COUNT(*) AS cnt FROM workflow_instances_projection
                    WHERE workflow_id = :workflowId AND status = :status AND deleted = FALSE
                    """)
                .bind("workflowId", workflowId)
                .bind("status", status)
                .map(row -> row.get("cnt", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    /**
     * Soft-deletes a single instance by marking it as deleted.
     *
     * @return true if a row was updated, false otherwise
     */
    public Mono<Boolean> softDelete(UUID instanceId) {
        return databaseClient.sql("""
                    UPDATE workflow_instances_projection
                    SET deleted = TRUE, last_updated = NOW()
                    WHERE instance_id = :instanceId AND deleted = FALSE
                    """)
                .bind("instanceId", instanceId)
                .fetch()
                .rowsUpdated()
                .map(count -> count > 0)
                .defaultIfEmpty(false);
    }

    /**
     * Soft-deletes all instances for a given workflow definition.
     *
     * @return the number of rows affected
     */
    public Mono<Long> softDeleteByWorkflowId(String workflowId) {
        return databaseClient.sql("""
                    UPDATE workflow_instances_projection
                    SET deleted = TRUE, last_updated = NOW()
                    WHERE workflow_id = :workflowId AND deleted = FALSE
                    """)
                .bind("workflowId", workflowId)
                .fetch()
                .rowsUpdated();
    }
}
