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

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.fireflyframework.eventsourcing.domain.Event;
import org.fireflyframework.eventsourcing.domain.StoredEventEnvelope;
import org.fireflyframework.eventsourcing.store.EventStore;
import org.fireflyframework.workflow.eventsourcing.event.*;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WorkflowInstanceProjection}.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowInstanceProjectionTest {

    private static final UUID INSTANCE_ID = UUID.randomUUID();

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private EventStore eventStore;

    @Mock
    private GenericExecuteSpec executeSpec;

    @Captor
    private ArgumentCaptor<String> sqlCaptor;

    private MeterRegistry meterRegistry;
    private WorkflowInstanceProjection projection;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        projection = new WorkflowInstanceProjection(databaseClient, eventStore, meterRegistry);
    }

    private StoredEventEnvelope createEnvelope(Event event, String aggregateType) {
        return StoredEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .event(event)
                .aggregateId(INSTANCE_ID)
                .aggregateType(aggregateType)
                .aggregateVersion(1L)
                .globalSequence(42L)
                .eventType(event.getEventType())
                .createdAt(Instant.now())
                .build();
    }

    private void setupDatabaseClientForSql() {
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.then()).thenReturn(Mono.empty());
    }

    private String capturedSql() {
        verify(databaseClient).sql(sqlCaptor.capture());
        return sqlCaptor.getValue();
    }

    // ========================================================================
    // Projection Metadata
    // ========================================================================

    @Test
    @DisplayName("should return correct projection name")
    void getProjectionName_shouldReturnCorrectName() {
        assertThat(projection.getProjectionName()).isEqualTo("workflow-instances-projection");
    }

    // ========================================================================
    // Event Filtering
    // ========================================================================

    @Nested
    @DisplayName("Event filtering")
    class EventFilteringTests {

        @Test
        @DisplayName("should ignore events from non-workflow aggregate types")
        void handleEvent_shouldIgnoreNonWorkflowAggregateType() {
            WorkflowStartedEvent event = WorkflowStartedEvent.builder()
                    .aggregateId(INSTANCE_ID)
                    .workflowId("test-wf")
                    .build();
            StoredEventEnvelope envelope = createEnvelope(event, "account");

            StepVerifier.create(projection.handleEvent(envelope))
                    .verifyComplete();

            verifyNoInteractions(databaseClient);
        }

        @Test
        @DisplayName("should ignore unhandled event types")
        void handleEvent_shouldIgnoreUnhandledEventTypes() {
            SignalReceivedEvent event = SignalReceivedEvent.builder()
                    .aggregateId(INSTANCE_ID)
                    .signalName("test-signal")
                    .build();
            StoredEventEnvelope envelope = createEnvelope(event, "workflow");

            StepVerifier.create(projection.handleEvent(envelope))
                    .verifyComplete();

            verifyNoInteractions(databaseClient);
        }
    }

    // ========================================================================
    // Event Handlers
    // ========================================================================

    @Nested
    @DisplayName("WorkflowStartedEvent handler")
    class WorkflowStartedEventTests {

        @Test
        @DisplayName("should upsert projection row")
        void handleEvent_workflowStarted_shouldUpsert() {
            setupDatabaseClientForSql();

            WorkflowStartedEvent event = WorkflowStartedEvent.builder()
                    .aggregateId(INSTANCE_ID)
                    .workflowId("order-processing")
                    .workflowName("Order Processing")
                    .workflowVersion("1.0.0")
                    .correlationId("corr-123")
                    .triggeredBy("api-gateway")
                    .build();
            StoredEventEnvelope envelope = createEnvelope(event, "workflow");

            StepVerifier.create(projection.handleEvent(envelope))
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("INSERT INTO workflow_instances_projection");
            assertThat(sql).contains("ON CONFLICT (instance_id) DO UPDATE");
        }
    }

    @Nested
    @DisplayName("Status update event handlers")
    class StatusUpdateEventTests {

        @Test
        @DisplayName("WorkflowCompletedEvent should set COMPLETED with completed_at")
        void handleEvent_workflowCompleted_shouldUpdateStatus() {
            setupDatabaseClientForSql();

            WorkflowCompletedEvent event = WorkflowCompletedEvent.builder()
                    .aggregateId(INSTANCE_ID)
                    .output(Map.of("result", "success"))
                    .build();
            StoredEventEnvelope envelope = createEnvelope(event, "workflow");

            StepVerifier.create(projection.handleEvent(envelope))
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("status = :status");
            assertThat(sql).contains("completed_at");
        }

        @Test
        @DisplayName("WorkflowFailedEvent should set FAILED with error details")
        void handleEvent_workflowFailed_shouldUpdateWithError() {
            setupDatabaseClientForSql();

            WorkflowFailedEvent event = WorkflowFailedEvent.builder()
                    .aggregateId(INSTANCE_ID)
                    .errorMessage("Something broke")
                    .errorType("RuntimeException")
                    .failedStepId("step-2")
                    .build();
            StoredEventEnvelope envelope = createEnvelope(event, "workflow");

            StepVerifier.create(projection.handleEvent(envelope))
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("error_message");
            assertThat(sql).contains("error_type");
        }

        @Test
        @DisplayName("WorkflowCancelledEvent should set CANCELLED with completed_at")
        void handleEvent_workflowCancelled_shouldUpdateStatus() {
            setupDatabaseClientForSql();

            WorkflowCancelledEvent event = WorkflowCancelledEvent.builder()
                    .aggregateId(INSTANCE_ID)
                    .reason("User cancelled")
                    .build();
            StoredEventEnvelope envelope = createEnvelope(event, "workflow");

            StepVerifier.create(projection.handleEvent(envelope))
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("status = :status");
            assertThat(sql).contains("completed_at");
        }

        @Test
        @DisplayName("WorkflowSuspendedEvent should set SUSPENDED without completed_at")
        void handleEvent_workflowSuspended_shouldUpdateStatus() {
            setupDatabaseClientForSql();

            WorkflowSuspendedEvent event = WorkflowSuspendedEvent.builder()
                    .aggregateId(INSTANCE_ID)
                    .reason("Waiting for signal")
                    .build();
            StoredEventEnvelope envelope = createEnvelope(event, "workflow");

            StepVerifier.create(projection.handleEvent(envelope))
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("status = :status");
            assertThat(sql).doesNotContain("completed_at");
        }

        @Test
        @DisplayName("WorkflowResumedEvent should set RUNNING without completed_at")
        void handleEvent_workflowResumed_shouldUpdateStatus() {
            setupDatabaseClientForSql();

            WorkflowResumedEvent event = WorkflowResumedEvent.builder()
                    .aggregateId(INSTANCE_ID)
                    .build();
            StoredEventEnvelope envelope = createEnvelope(event, "workflow");

            StepVerifier.create(projection.handleEvent(envelope))
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("status = :status");
            assertThat(sql).doesNotContain("completed_at");
        }

        @Test
        @DisplayName("ContinueAsNewEvent should set COMPLETED with completed_at")
        void handleEvent_continueAsNew_shouldSetCompleted() {
            setupDatabaseClientForSql();

            ContinueAsNewEvent event = ContinueAsNewEvent.builder()
                    .aggregateId(INSTANCE_ID)
                    .newInput(Map.of("iteration", 2))
                    .build();
            StoredEventEnvelope envelope = createEnvelope(event, "workflow");

            StepVerifier.create(projection.handleEvent(envelope))
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("status = :status");
            assertThat(sql).contains("completed_at");
        }
    }

    @Nested
    @DisplayName("Step event handlers")
    class StepEventTests {

        @Test
        @DisplayName("StepStartedEvent should update current_step_id")
        void handleEvent_stepStarted_shouldUpdateStepId() {
            setupDatabaseClientForSql();

            StepStartedEvent event = StepStartedEvent.builder()
                    .aggregateId(INSTANCE_ID)
                    .stepId("validate-order")
                    .stepName("Validate Order")
                    .attemptNumber(1)
                    .build();
            StoredEventEnvelope envelope = createEnvelope(event, "workflow");

            StepVerifier.create(projection.handleEvent(envelope))
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("current_step_id");
        }

        @Test
        @DisplayName("StepCompletedEvent should update current_step_id")
        void handleEvent_stepCompleted_shouldUpdateStepId() {
            setupDatabaseClientForSql();

            StepCompletedEvent event = StepCompletedEvent.builder()
                    .aggregateId(INSTANCE_ID)
                    .stepId("validate-order")
                    .output(Map.of("valid", true))
                    .build();
            StoredEventEnvelope envelope = createEnvelope(event, "workflow");

            StepVerifier.create(projection.handleEvent(envelope))
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("current_step_id");
        }
    }

    // ========================================================================
    // Position Tracking
    // ========================================================================

    @Nested
    @DisplayName("Position tracking")
    class PositionTrackingTests {

        @Test
        @DisplayName("updatePosition should upsert projection_positions table")
        void updatePosition_shouldUpsert() {
            setupDatabaseClientForSql();

            StepVerifier.create(projection.updatePosition(100L))
                    .verifyComplete();

            String sql = capturedSql();
            assertThat(sql).contains("INSERT INTO projection_positions");
            assertThat(sql).contains("ON CONFLICT (projection_name)");
        }
    }
}
