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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WorkflowProjectionRepository}.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowProjectionRepositoryTest {

    private static final UUID INSTANCE_ID_1 = UUID.randomUUID();
    private static final UUID INSTANCE_ID_2 = UUID.randomUUID();
    private static final String WORKFLOW_ID = "order-processing";

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private GenericExecuteSpec executeSpec;

    @Mock
    private RowsFetchSpec<UUID> uuidRowsFetchSpec;

    @Mock
    private RowsFetchSpec<Long> longRowsFetchSpec;

    @Mock
    @SuppressWarnings("rawtypes")
    private FetchSpec fetchSpec;

    @Captor
    private ArgumentCaptor<String> sqlCaptor;

    private WorkflowProjectionRepository repository;

    @BeforeEach
    void setUp() {
        repository = new WorkflowProjectionRepository(databaseClient);
    }

    private String capturedSql() {
        verify(databaseClient).sql(sqlCaptor.capture());
        return sqlCaptor.getValue();
    }

    @SuppressWarnings("unchecked")
    private void setupQueryReturning(UUID... ids) {
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        lenient().when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.map(any(Function.class))).thenReturn(uuidRowsFetchSpec);
        when(uuidRowsFetchSpec.all()).thenReturn(Flux.just(ids));
    }

    @SuppressWarnings("unchecked")
    private void setupCountReturning(long count) {
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.map(any(Function.class))).thenReturn(longRowsFetchSpec);
        when(longRowsFetchSpec.one()).thenReturn(Mono.just(count));
    }

    @SuppressWarnings("unchecked")
    private void setupUpdateReturning(long rowsUpdated) {
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.fetch()).thenReturn(fetchSpec);
        when(fetchSpec.rowsUpdated()).thenReturn(Mono.just(rowsUpdated));
    }

    // ========================================================================
    // Query Methods
    // ========================================================================

    @Nested
    @DisplayName("Query methods")
    class QueryMethodTests {

        @Test
        @DisplayName("findInstanceIdsByWorkflowId should query with correct SQL")
        void findByWorkflowId_shouldUseCorrectSql() {
            setupQueryReturning(INSTANCE_ID_1, INSTANCE_ID_2);

            StepVerifier.create(repository.findInstanceIdsByWorkflowId(WORKFLOW_ID))
                    .expectNext(INSTANCE_ID_1, INSTANCE_ID_2)
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("workflow_id = :workflowId");
            assertThat(sql).contains("deleted = FALSE");
        }

        @Test
        @DisplayName("findInstanceIdsByStatus should query with correct SQL")
        void findByStatus_shouldUseCorrectSql() {
            setupQueryReturning(INSTANCE_ID_1);

            StepVerifier.create(repository.findInstanceIdsByStatus("RUNNING"))
                    .expectNext(INSTANCE_ID_1)
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("status = :status");
            assertThat(sql).contains("deleted = FALSE");
        }

        @Test
        @DisplayName("findInstanceIdsByWorkflowIdAndStatus should query with correct SQL")
        void findByWorkflowIdAndStatus_shouldUseCorrectSql() {
            setupQueryReturning(INSTANCE_ID_1);

            StepVerifier.create(repository.findInstanceIdsByWorkflowIdAndStatus(WORKFLOW_ID, "COMPLETED"))
                    .expectNext(INSTANCE_ID_1)
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("workflow_id = :workflowId");
            assertThat(sql).contains("status = :status");
            assertThat(sql).contains("deleted = FALSE");
        }

        @Test
        @DisplayName("findInstanceIdsByCorrelationId should query with correct SQL")
        void findByCorrelationId_shouldUseCorrectSql() {
            setupQueryReturning(INSTANCE_ID_1);

            StepVerifier.create(repository.findInstanceIdsByCorrelationId("corr-123"))
                    .expectNext(INSTANCE_ID_1)
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("correlation_id = :correlationId");
            assertThat(sql).contains("deleted = FALSE");
        }

        @Test
        @DisplayName("findActiveInstanceIds should query for PENDING/RUNNING/WAITING")
        void findActiveInstanceIds_shouldUseCorrectSql() {
            setupQueryReturning(INSTANCE_ID_1, INSTANCE_ID_2);

            StepVerifier.create(repository.findActiveInstanceIds())
                    .expectNext(INSTANCE_ID_1, INSTANCE_ID_2)
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("'PENDING'");
            assertThat(sql).contains("'RUNNING'");
            assertThat(sql).contains("'WAITING'");
            assertThat(sql).contains("deleted = FALSE");
        }

        @Test
        @DisplayName("findStaleInstanceIds should query with cutoff time")
        void findStaleInstanceIds_shouldUseCorrectSql() {
            setupQueryReturning(INSTANCE_ID_1);

            StepVerifier.create(repository.findStaleInstanceIds(Duration.ofHours(1)))
                    .expectNext(INSTANCE_ID_1)
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("started_at < :cutoff");
            assertThat(sql).contains("'RUNNING'");
            assertThat(sql).contains("'WAITING'");
            assertThat(sql).contains("deleted = FALSE");
        }
    }

    // ========================================================================
    // Count Methods
    // ========================================================================

    @Nested
    @DisplayName("Count methods")
    class CountMethodTests {

        @Test
        @DisplayName("countByWorkflowId should return count")
        void countByWorkflowId_shouldReturnCount() {
            setupCountReturning(5L);

            StepVerifier.create(repository.countByWorkflowId(WORKFLOW_ID))
                    .expectNext(5L)
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("COUNT(*)");
            assertThat(sql).contains("workflow_id = :workflowId");
        }

        @Test
        @DisplayName("countByWorkflowIdAndStatus should return count")
        void countByWorkflowIdAndStatus_shouldReturnCount() {
            setupCountReturning(3L);

            StepVerifier.create(repository.countByWorkflowIdAndStatus(WORKFLOW_ID, "COMPLETED"))
                    .expectNext(3L)
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("COUNT(*)");
            assertThat(sql).contains("workflow_id = :workflowId");
            assertThat(sql).contains("status = :status");
        }
    }

    // ========================================================================
    // Soft-Delete Methods
    // ========================================================================

    @Nested
    @DisplayName("Soft-delete methods")
    class SoftDeleteMethodTests {

        @Test
        @DisplayName("softDelete should mark row as deleted and return true")
        void softDelete_shouldReturnTrue_whenRowUpdated() {
            setupUpdateReturning(1L);

            StepVerifier.create(repository.softDelete(INSTANCE_ID_1))
                    .expectNext(true)
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("deleted = TRUE");
            assertThat(sql).contains("instance_id = :instanceId");
        }

        @Test
        @DisplayName("softDelete should return false when no row found")
        void softDelete_shouldReturnFalse_whenNoRowUpdated() {
            setupUpdateReturning(0L);

            StepVerifier.create(repository.softDelete(INSTANCE_ID_1))
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("softDeleteByWorkflowId should return count of deleted rows")
        void softDeleteByWorkflowId_shouldReturnCount() {
            setupUpdateReturning(3L);

            StepVerifier.create(repository.softDeleteByWorkflowId(WORKFLOW_ID))
                    .expectNext(3L)
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("deleted = TRUE");
            assertThat(sql).contains("workflow_id = :workflowId");
        }
    }
}
