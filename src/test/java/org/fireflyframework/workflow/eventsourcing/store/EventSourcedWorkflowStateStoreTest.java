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
import org.fireflyframework.eventsourcing.domain.StoredEventEnvelope;
import org.fireflyframework.eventsourcing.store.EventStore;
import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.eventsourcing.event.WorkflowStartedEvent;
import org.fireflyframework.workflow.eventsourcing.event.StepStartedEvent;
import org.fireflyframework.workflow.eventsourcing.event.StepCompletedEvent;
import org.fireflyframework.workflow.eventsourcing.projection.WorkflowProjectionRepository;
import org.fireflyframework.workflow.model.StepExecution;
import org.fireflyframework.workflow.model.StepStatus;
import org.fireflyframework.workflow.model.WorkflowInstance;
import org.fireflyframework.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EventSourcedWorkflowStateStore}.
 * <p>
 * Tests cover aggregate operations, WorkflowStateStore interface methods,
 * and conversion between aggregate state and WorkflowInstance records.
 */
@ExtendWith(MockitoExtension.class)
class EventSourcedWorkflowStateStoreTest {

    private static final UUID AGGREGATE_ID = UUID.randomUUID();
    private static final String INSTANCE_ID = AGGREGATE_ID.toString();
    private static final String WORKFLOW_ID = "order-processing";
    private static final String WORKFLOW_NAME = "Order Processing";
    private static final String WORKFLOW_VERSION = "1.0.0";
    private static final Map<String, Object> INPUT = Map.of("orderId", "ORD-123");
    private static final String CORRELATION_ID = "corr-456";
    private static final String TRIGGERED_BY = "api-gateway";

    @Mock
    private EventStore eventStore;

    @Captor
    private ArgumentCaptor<List<Event>> eventsCaptor;

    @Captor
    private ArgumentCaptor<Long> versionCaptor;

    private EventSourcedWorkflowStateStore store;

    @BeforeEach
    void setUp() {
        store = new EventSourcedWorkflowStateStore(eventStore);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Creates a WorkflowAggregate that has been started (with one uncommitted event).
     */
    private WorkflowAggregate createStartedAggregate() {
        WorkflowAggregate aggregate = new WorkflowAggregate(AGGREGATE_ID);
        aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                INPUT, CORRELATION_ID, TRIGGERED_BY, false);
        return aggregate;
    }

    /**
     * Creates a WorkflowAggregate that has been started and committed
     * (simulating an aggregate loaded from the event store).
     */
    private WorkflowAggregate createCommittedStartedAggregate() {
        WorkflowAggregate aggregate = createStartedAggregate();
        aggregate.markEventsAsCommitted();
        return aggregate;
    }

    /**
     * Creates a mock event stream with a WorkflowStartedEvent for the given aggregate ID.
     */
    private EventStream createStartedEventStream(UUID aggregateId) {
        WorkflowStartedEvent event = WorkflowStartedEvent.builder()
                .aggregateId(aggregateId)
                .workflowId(WORKFLOW_ID)
                .workflowName(WORKFLOW_NAME)
                .workflowVersion(WORKFLOW_VERSION)
                .input(INPUT)
                .correlationId(CORRELATION_ID)
                .triggeredBy(TRIGGERED_BY)
                .dryRun(false)
                .build();

        StoredEventEnvelope envelope = StoredEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .event(event)
                .aggregateId(aggregateId)
                .aggregateType("workflow")
                .aggregateVersion(1L)
                .globalSequence(1L)
                .eventType("workflow.started")
                .createdAt(Instant.now())
                .build();

        return EventStream.builder()
                .aggregateId(aggregateId)
                .aggregateType("workflow")
                .currentVersion(1L)
                .events(List.of(envelope))
                .build();
    }

    /**
     * Creates a mock event stream with started + step events.
     */
    private EventStream createEventStreamWithStep(UUID aggregateId) {
        WorkflowStartedEvent startEvent = WorkflowStartedEvent.builder()
                .aggregateId(aggregateId)
                .workflowId(WORKFLOW_ID)
                .workflowName(WORKFLOW_NAME)
                .workflowVersion(WORKFLOW_VERSION)
                .input(INPUT)
                .correlationId(CORRELATION_ID)
                .triggeredBy(TRIGGERED_BY)
                .dryRun(false)
                .build();

        StepStartedEvent stepStartedEvent = StepStartedEvent.builder()
                .aggregateId(aggregateId)
                .stepId("step-1")
                .stepName("Validate Order")
                .input(Map.of("orderId", "ORD-123"))
                .attemptNumber(1)
                .build();

        StepCompletedEvent stepCompletedEvent = StepCompletedEvent.builder()
                .aggregateId(aggregateId)
                .stepId("step-1")
                .output(Map.of("valid", true))
                .durationMs(150L)
                .build();

        StoredEventEnvelope envelope1 = StoredEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .event(startEvent)
                .aggregateId(aggregateId)
                .aggregateType("workflow")
                .aggregateVersion(1L)
                .globalSequence(1L)
                .eventType("workflow.started")
                .createdAt(Instant.now())
                .build();

        StoredEventEnvelope envelope2 = StoredEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .event(stepStartedEvent)
                .aggregateId(aggregateId)
                .aggregateType("workflow")
                .aggregateVersion(2L)
                .globalSequence(2L)
                .eventType("step.started")
                .createdAt(Instant.now())
                .build();

        StoredEventEnvelope envelope3 = StoredEventEnvelope.builder()
                .eventId(UUID.randomUUID())
                .event(stepCompletedEvent)
                .aggregateId(aggregateId)
                .aggregateType("workflow")
                .aggregateVersion(3L)
                .globalSequence(3L)
                .eventType("step.completed")
                .createdAt(Instant.now())
                .build();

        return EventStream.builder()
                .aggregateId(aggregateId)
                .aggregateType("workflow")
                .currentVersion(3L)
                .events(List.of(envelope1, envelope2, envelope3))
                .build();
    }

    // ========================================================================
    // Save Aggregate Tests
    // ========================================================================

    @Nested
    @DisplayName("saveAggregate")
    class SaveAggregateTests {

        @Test
        @DisplayName("should append uncommitted events to event store")
        void save_shouldAppendUncommittedEvents() {
            WorkflowAggregate aggregate = createStartedAggregate();
            assertThat(aggregate.hasUncommittedEvents()).isTrue();
            assertThat(aggregate.getUncommittedEventCount()).isEqualTo(1);

            EventStream resultStream = EventStream.builder()
                    .aggregateId(AGGREGATE_ID)
                    .aggregateType("workflow")
                    .currentVersion(1L)
                    .events(List.of())
                    .build();

            when(eventStore.appendEvents(eq(AGGREGATE_ID), eq("workflow"),
                    anyList(), eq(-1L), eq(Map.of())))
                    .thenReturn(Mono.just(resultStream));

            StepVerifier.create(store.saveAggregate(aggregate))
                    .assertNext(saved -> {
                        assertThat(saved.getId()).isEqualTo(AGGREGATE_ID);
                        assertThat(saved.hasUncommittedEvents()).isFalse();
                        assertThat(saved.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
                    })
                    .verifyComplete();

            verify(eventStore).appendEvents(eq(AGGREGATE_ID), eq("workflow"),
                    eventsCaptor.capture(), eq(-1L), eq(Map.of()));

            List<Event> capturedEvents = eventsCaptor.getValue();
            assertThat(capturedEvents).hasSize(1);
            assertThat(capturedEvents.get(0)).isInstanceOf(WorkflowStartedEvent.class);
        }

        @Test
        @DisplayName("should not call event store when no uncommitted events")
        void save_withNoUncommittedEvents_shouldNotCallEventStore() {
            WorkflowAggregate aggregate = createCommittedStartedAggregate();
            assertThat(aggregate.hasUncommittedEvents()).isFalse();

            StepVerifier.create(store.saveAggregate(aggregate))
                    .assertNext(saved -> {
                        assertThat(saved.getId()).isEqualTo(AGGREGATE_ID);
                        assertThat(saved.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
                    })
                    .verifyComplete();

            verifyNoInteractions(eventStore);
        }

        @Test
        @DisplayName("should calculate correct expected version with multiple events")
        void save_shouldCalculateCorrectExpectedVersion() {
            WorkflowAggregate aggregate = createCommittedStartedAggregate();
            // Now apply two more events — step start + step complete
            aggregate.startStep("step-1", "Validate", Map.of(), 1);
            aggregate.completeStep("step-1", Map.of("valid", true), 100L);
            // aggregate version is now 2, with 2 uncommitted events
            // expected version = 2 - 2 = 0
            assertThat(aggregate.getUncommittedEventCount()).isEqualTo(2);
            assertThat(aggregate.getCurrentVersion()).isEqualTo(2L);

            EventStream resultStream = EventStream.builder()
                    .aggregateId(AGGREGATE_ID)
                    .aggregateType("workflow")
                    .currentVersion(2L)
                    .events(List.of())
                    .build();

            when(eventStore.appendEvents(eq(AGGREGATE_ID), eq("workflow"),
                    anyList(), eq(0L), eq(Map.of())))
                    .thenReturn(Mono.just(resultStream));

            StepVerifier.create(store.saveAggregate(aggregate))
                    .assertNext(saved -> {
                        assertThat(saved.hasUncommittedEvents()).isFalse();
                    })
                    .verifyComplete();

            verify(eventStore).appendEvents(eq(AGGREGATE_ID), eq("workflow"),
                    eventsCaptor.capture(), eq(0L), eq(Map.of()));
            assertThat(eventsCaptor.getValue()).hasSize(2);
        }
    }

    // ========================================================================
    // toWorkflowInstance Conversion Tests
    // ========================================================================

    @Nested
    @DisplayName("toWorkflowInstance")
    class ConversionTests {

        @Test
        @DisplayName("should convert aggregate fields correctly")
        void toWorkflowInstance_shouldConvertCorrectly() {
            WorkflowAggregate aggregate = createStartedAggregate();
            aggregate.markEventsAsCommitted();

            // Add a step
            aggregate.startStep("step-1", "Validate Order", Map.of("key", "value"), 1);
            aggregate.completeStep("step-1", Map.of("valid", true), 200L);
            aggregate.markEventsAsCommitted();

            WorkflowInstance instance = store.toWorkflowInstance(aggregate);

            assertThat(instance.instanceId()).isEqualTo(AGGREGATE_ID.toString());
            assertThat(instance.workflowId()).isEqualTo(WORKFLOW_ID);
            assertThat(instance.workflowName()).isEqualTo(WORKFLOW_NAME);
            assertThat(instance.workflowVersion()).isEqualTo(WORKFLOW_VERSION);
            assertThat(instance.status()).isEqualTo(WorkflowStatus.RUNNING);
            assertThat(instance.currentStepId()).isEqualTo("step-1");
            assertThat(instance.correlationId()).isEqualTo(CORRELATION_ID);
            assertThat(instance.triggeredBy()).isEqualTo(TRIGGERED_BY);
            assertThat(instance.startedAt()).isNotNull();
            assertThat(instance.input()).containsEntry("orderId", "ORD-123");
        }

        @Test
        @DisplayName("should convert step states to step executions")
        void toWorkflowInstance_shouldConvertStepExecutions() {
            WorkflowAggregate aggregate = createCommittedStartedAggregate();
            aggregate.startStep("step-1", "Validate Order", Map.of("orderId", "ORD-123"), 1);
            aggregate.completeStep("step-1", Map.of("valid", true), 200L);
            aggregate.markEventsAsCommitted();

            WorkflowInstance instance = store.toWorkflowInstance(aggregate);

            assertThat(instance.stepExecutions()).hasSize(1);
            StepExecution step = instance.stepExecutions().get(0);
            assertThat(step.stepId()).isEqualTo("step-1");
            assertThat(step.stepName()).isEqualTo("Validate Order");
            assertThat(step.status()).isEqualTo(StepStatus.COMPLETED);
            assertThat(step.attemptNumber()).isEqualTo(1);
            assertThat(step.output()).isEqualTo(Map.of("valid", true));
            assertThat(step.startedAt()).isNotNull();
            assertThat(step.completedAt()).isNotNull();
        }

        @Test
        @DisplayName("should handle aggregate with no steps")
        void toWorkflowInstance_shouldHandleNoSteps() {
            WorkflowAggregate aggregate = createCommittedStartedAggregate();

            WorkflowInstance instance = store.toWorkflowInstance(aggregate);

            assertThat(instance.stepExecutions()).isEmpty();
        }

        @Test
        @DisplayName("should set error fields for failed steps")
        void toWorkflowInstance_shouldSetErrorFieldsForFailedSteps() {
            WorkflowAggregate aggregate = createCommittedStartedAggregate();
            aggregate.startStep("step-1", "Validate Order", Map.of(), 1);
            aggregate.failStep("step-1", "Validation failed", "ValidationException", 1, false);
            aggregate.markEventsAsCommitted();

            WorkflowInstance instance = store.toWorkflowInstance(aggregate);

            assertThat(instance.stepExecutions()).hasSize(1);
            StepExecution step = instance.stepExecutions().get(0);
            assertThat(step.status()).isEqualTo(StepStatus.FAILED);
            assertThat(step.errorMessage()).isEqualTo("Validation failed");
        }
    }

    // ========================================================================
    // findById Tests
    // ========================================================================

    @Nested
    @DisplayName("findById")
    class FindByIdTests {

        @Test
        @DisplayName("should load aggregate and convert to WorkflowInstance")
        void findById_shouldLoadAndConvert() {
            EventStream eventStream = createStartedEventStream(AGGREGATE_ID);
            when(eventStore.loadEventStream(AGGREGATE_ID, "workflow"))
                    .thenReturn(Mono.just(eventStream));

            StepVerifier.create(store.findById(INSTANCE_ID))
                    .assertNext(instance -> {
                        assertThat(instance.instanceId()).isEqualTo(INSTANCE_ID);
                        assertThat(instance.workflowId()).isEqualTo(WORKFLOW_ID);
                        assertThat(instance.workflowName()).isEqualTo(WORKFLOW_NAME);
                        assertThat(instance.status()).isEqualTo(WorkflowStatus.RUNNING);
                        assertThat(instance.correlationId()).isEqualTo(CORRELATION_ID);
                    })
                    .verifyComplete();

            verify(eventStore).loadEventStream(AGGREGATE_ID, "workflow");
        }

        @Test
        @DisplayName("should return empty for non-existent aggregate")
        void findById_shouldReturnEmptyForNonExistent() {
            EventStream emptyStream = EventStream.empty(AGGREGATE_ID, "workflow");
            when(eventStore.loadEventStream(AGGREGATE_ID, "workflow"))
                    .thenReturn(Mono.just(emptyStream));

            StepVerifier.create(store.findById(INSTANCE_ID))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty for invalid UUID format")
        void findById_shouldReturnEmptyForInvalidUuid() {
            StepVerifier.create(store.findById("not-a-uuid"))
                    .verifyComplete();

            verifyNoInteractions(eventStore);
        }

        @Test
        @DisplayName("should load aggregate with steps and convert")
        void findById_shouldLoadWithStepsAndConvert() {
            EventStream eventStream = createEventStreamWithStep(AGGREGATE_ID);
            when(eventStore.loadEventStream(AGGREGATE_ID, "workflow"))
                    .thenReturn(Mono.just(eventStream));

            StepVerifier.create(store.findById(INSTANCE_ID))
                    .assertNext(instance -> {
                        assertThat(instance.instanceId()).isEqualTo(INSTANCE_ID);
                        assertThat(instance.status()).isEqualTo(WorkflowStatus.RUNNING);
                        assertThat(instance.currentStepId()).isEqualTo("step-1");
                        assertThat(instance.stepExecutions()).hasSize(1);
                        assertThat(instance.stepExecutions().get(0).status())
                                .isEqualTo(StepStatus.COMPLETED);
                    })
                    .verifyComplete();
        }
    }

    // ========================================================================
    // exists Tests
    // ========================================================================

    @Nested
    @DisplayName("exists")
    class ExistsTests {

        @Test
        @DisplayName("should delegate to eventStore.aggregateExists")
        void exists_shouldDelegateToEventStore() {
            when(eventStore.aggregateExists(AGGREGATE_ID, "workflow"))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(store.exists(INSTANCE_ID))
                    .expectNext(true)
                    .verifyComplete();

            verify(eventStore).aggregateExists(AGGREGATE_ID, "workflow");
        }

        @Test
        @DisplayName("should return false when aggregate does not exist")
        void exists_shouldReturnFalseWhenNotFound() {
            when(eventStore.aggregateExists(AGGREGATE_ID, "workflow"))
                    .thenReturn(Mono.just(false));

            StepVerifier.create(store.exists(INSTANCE_ID))
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return false for invalid UUID format")
        void exists_shouldReturnFalseForInvalidUuid() {
            StepVerifier.create(store.exists("not-a-uuid"))
                    .expectNext(false)
                    .verifyComplete();

            verifyNoInteractions(eventStore);
        }
    }

    // ========================================================================
    // updateStatus Tests
    // ========================================================================

    @Nested
    @DisplayName("updateStatus")
    class UpdateStatusTests {

        @Test
        @DisplayName("should load aggregate, apply transition, and save")
        void updateStatus_shouldLoadModifySave() {
            // Load returns a RUNNING aggregate
            EventStream eventStream = createStartedEventStream(AGGREGATE_ID);
            when(eventStore.loadEventStream(AGGREGATE_ID, "workflow"))
                    .thenReturn(Mono.just(eventStream));

            // Save should be called with the cancel event
            EventStream resultStream = EventStream.builder()
                    .aggregateId(AGGREGATE_ID)
                    .aggregateType("workflow")
                    .currentVersion(2L)
                    .events(List.of())
                    .build();
            when(eventStore.appendEvents(eq(AGGREGATE_ID), eq("workflow"),
                    anyList(), eq(1L), eq(Map.of())))
                    .thenReturn(Mono.just(resultStream));

            StepVerifier.create(store.updateStatus(INSTANCE_ID, WorkflowStatus.RUNNING, WorkflowStatus.CANCELLED))
                    .expectNext(true)
                    .verifyComplete();

            verify(eventStore).loadEventStream(AGGREGATE_ID, "workflow");
            verify(eventStore).appendEvents(eq(AGGREGATE_ID), eq("workflow"),
                    eventsCaptor.capture(), eq(1L), eq(Map.of()));

            List<Event> capturedEvents = eventsCaptor.getValue();
            assertThat(capturedEvents).hasSize(1);
        }

        @Test
        @DisplayName("should return false when status does not match expected")
        void updateStatus_shouldReturnFalseOnMismatch() {
            EventStream eventStream = createStartedEventStream(AGGREGATE_ID);
            when(eventStore.loadEventStream(AGGREGATE_ID, "workflow"))
                    .thenReturn(Mono.just(eventStream));

            // Aggregate is RUNNING, but we expect PENDING
            StepVerifier.create(store.updateStatus(INSTANCE_ID, WorkflowStatus.PENDING, WorkflowStatus.CANCELLED))
                    .expectNext(false)
                    .verifyComplete();

            verify(eventStore).loadEventStream(AGGREGATE_ID, "workflow");
            verify(eventStore, never()).appendEvents(any(), anyString(), anyList(), anyLong(), any());
        }

        @Test
        @DisplayName("should return false when aggregate does not exist")
        void updateStatus_shouldReturnFalseWhenNotFound() {
            EventStream emptyStream = EventStream.empty(AGGREGATE_ID, "workflow");
            when(eventStore.loadEventStream(AGGREGATE_ID, "workflow"))
                    .thenReturn(Mono.just(emptyStream));

            StepVerifier.create(store.updateStatus(INSTANCE_ID, WorkflowStatus.RUNNING, WorkflowStatus.COMPLETED))
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return false for invalid UUID format")
        void updateStatus_shouldReturnFalseForInvalidUuid() {
            StepVerifier.create(store.updateStatus("not-a-uuid", WorkflowStatus.RUNNING, WorkflowStatus.COMPLETED))
                    .expectNext(false)
                    .verifyComplete();

            verifyNoInteractions(eventStore);
        }
    }

    // ========================================================================
    // isHealthy Tests
    // ========================================================================

    @Nested
    @DisplayName("isHealthy")
    class IsHealthyTests {

        @Test
        @DisplayName("should delegate to eventStore.isHealthy")
        void isHealthy_shouldDelegateToEventStore() {
            when(eventStore.isHealthy()).thenReturn(Mono.just(true));

            StepVerifier.create(store.isHealthy())
                    .expectNext(true)
                    .verifyComplete();

            verify(eventStore).isHealthy();
        }

        @Test
        @DisplayName("should return false when event store is unhealthy")
        void isHealthy_shouldReturnFalseWhenUnhealthy() {
            when(eventStore.isHealthy()).thenReturn(Mono.just(false));

            StepVerifier.create(store.isHealthy())
                    .expectNext(false)
                    .verifyComplete();
        }
    }

    // ========================================================================
    // Save (WorkflowInstance) Tests
    // ========================================================================

    @Nested
    @DisplayName("save (WorkflowInstance)")
    class SaveInstanceTests {

        @Test
        @DisplayName("should return instance as-is")
        void save_shouldReturnInstanceAsIs() {
            WorkflowInstance instance = new WorkflowInstance(
                    INSTANCE_ID, WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    WorkflowStatus.RUNNING, null, Map.of(), INPUT, null,
                    List.of(), null, null, CORRELATION_ID, TRIGGERED_BY,
                    Instant.now(), Instant.now(), null);

            StepVerifier.create(store.save(instance))
                    .assertNext(saved -> {
                        assertThat(saved.instanceId()).isEqualTo(INSTANCE_ID);
                        assertThat(saved.workflowId()).isEqualTo(WORKFLOW_ID);
                    })
                    .verifyComplete();

            verifyNoInteractions(eventStore);
        }

        @Test
        @DisplayName("save with TTL should return instance as-is")
        void save_withTtl_shouldReturnInstanceAsIs() {
            WorkflowInstance instance = new WorkflowInstance(
                    INSTANCE_ID, WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    WorkflowStatus.RUNNING, null, Map.of(), INPUT, null,
                    List.of(), null, null, CORRELATION_ID, TRIGGERED_BY,
                    Instant.now(), Instant.now(), null);

            StepVerifier.create(store.save(instance, Duration.ofHours(1)))
                    .assertNext(saved -> {
                        assertThat(saved.instanceId()).isEqualTo(INSTANCE_ID);
                    })
                    .verifyComplete();

            verifyNoInteractions(eventStore);
        }
    }

    // ========================================================================
    // Without Projection — returns empty/zero/false
    // ========================================================================

    @Nested
    @DisplayName("Without projection (null repository)")
    class WithoutProjectionTests {

        @Test
        @DisplayName("findByWorkflowId should return empty")
        void findByWorkflowId_shouldReturnEmpty() {
            StepVerifier.create(store.findByWorkflowId("some-workflow"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("findByStatus should return empty")
        void findByStatus_shouldReturnEmpty() {
            StepVerifier.create(store.findByStatus(WorkflowStatus.RUNNING))
                    .verifyComplete();
        }

        @Test
        @DisplayName("findByWorkflowIdAndStatus should return empty")
        void findByWorkflowIdAndStatus_shouldReturnEmpty() {
            StepVerifier.create(store.findByWorkflowIdAndStatus("some-workflow", WorkflowStatus.RUNNING))
                    .verifyComplete();
        }

        @Test
        @DisplayName("findByCorrelationId should return empty")
        void findByCorrelationId_shouldReturnEmpty() {
            StepVerifier.create(store.findByCorrelationId("some-correlation"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("findActiveInstances should return empty")
        void findActiveInstances_shouldReturnEmpty() {
            StepVerifier.create(store.findActiveInstances())
                    .verifyComplete();
        }

        @Test
        @DisplayName("findStaleInstances should return empty")
        void findStaleInstances_shouldReturnEmpty() {
            StepVerifier.create(store.findStaleInstances(Duration.ofHours(1)))
                    .verifyComplete();
        }

        @Test
        @DisplayName("delete should return false")
        void delete_shouldReturnFalse() {
            StepVerifier.create(store.delete("some-id"))
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("deleteByWorkflowId should return 0")
        void deleteByWorkflowId_shouldReturnZero() {
            StepVerifier.create(store.deleteByWorkflowId("some-workflow"))
                    .expectNext(0L)
                    .verifyComplete();
        }

        @Test
        @DisplayName("countByWorkflowId should return 0")
        void countByWorkflowId_shouldReturnZero() {
            StepVerifier.create(store.countByWorkflowId("some-workflow"))
                    .expectNext(0L)
                    .verifyComplete();
        }

        @Test
        @DisplayName("countByWorkflowIdAndStatus should return 0")
        void countByWorkflowIdAndStatus_shouldReturnZero() {
            StepVerifier.create(store.countByWorkflowIdAndStatus("some-workflow", WorkflowStatus.COMPLETED))
                    .expectNext(0L)
                    .verifyComplete();
        }
    }

    // ========================================================================
    // With Projection — delegates to repository + loads aggregates
    // ========================================================================

    @Nested
    @DisplayName("With projection (repository available)")
    class WithProjectionTests {

        @Mock
        private WorkflowProjectionRepository projectionRepository;

        private EventSourcedWorkflowStateStore storeWithProjection;

        @BeforeEach
        void setUpProjection() {
            storeWithProjection = new EventSourcedWorkflowStateStore(eventStore, projectionRepository);
        }

        @Test
        @DisplayName("findByWorkflowId should delegate to repository and load aggregates")
        void findByWorkflowId_shouldDelegateToRepository() {
            when(projectionRepository.findInstanceIdsByWorkflowId(WORKFLOW_ID))
                    .thenReturn(reactor.core.publisher.Flux.just(AGGREGATE_ID));
            EventStream eventStream = createStartedEventStream(AGGREGATE_ID);
            when(eventStore.loadEventStream(AGGREGATE_ID, "workflow"))
                    .thenReturn(Mono.just(eventStream));

            StepVerifier.create(storeWithProjection.findByWorkflowId(WORKFLOW_ID))
                    .assertNext(instance -> {
                        assertThat(instance.instanceId()).isEqualTo(INSTANCE_ID);
                        assertThat(instance.workflowId()).isEqualTo(WORKFLOW_ID);
                    })
                    .verifyComplete();

            verify(projectionRepository).findInstanceIdsByWorkflowId(WORKFLOW_ID);
            verify(eventStore).loadEventStream(AGGREGATE_ID, "workflow");
        }

        @Test
        @DisplayName("findByStatus should delegate to repository")
        void findByStatus_shouldDelegateToRepository() {
            when(projectionRepository.findInstanceIdsByStatus("RUNNING"))
                    .thenReturn(reactor.core.publisher.Flux.just(AGGREGATE_ID));
            EventStream eventStream = createStartedEventStream(AGGREGATE_ID);
            when(eventStore.loadEventStream(AGGREGATE_ID, "workflow"))
                    .thenReturn(Mono.just(eventStream));

            StepVerifier.create(storeWithProjection.findByStatus(WorkflowStatus.RUNNING))
                    .assertNext(instance -> {
                        assertThat(instance.status()).isEqualTo(WorkflowStatus.RUNNING);
                    })
                    .verifyComplete();

            verify(projectionRepository).findInstanceIdsByStatus("RUNNING");
        }

        @Test
        @DisplayName("countByWorkflowId should delegate to repository")
        void countByWorkflowId_shouldDelegateToRepository() {
            when(projectionRepository.countByWorkflowId(WORKFLOW_ID))
                    .thenReturn(Mono.just(5L));

            StepVerifier.create(storeWithProjection.countByWorkflowId(WORKFLOW_ID))
                    .expectNext(5L)
                    .verifyComplete();

            verify(projectionRepository).countByWorkflowId(WORKFLOW_ID);
            verifyNoInteractions(eventStore);
        }

        @Test
        @DisplayName("countByWorkflowIdAndStatus should delegate to repository")
        void countByWorkflowIdAndStatus_shouldDelegateToRepository() {
            when(projectionRepository.countByWorkflowIdAndStatus(WORKFLOW_ID, "COMPLETED"))
                    .thenReturn(Mono.just(3L));

            StepVerifier.create(storeWithProjection.countByWorkflowIdAndStatus(WORKFLOW_ID, WorkflowStatus.COMPLETED))
                    .expectNext(3L)
                    .verifyComplete();

            verify(projectionRepository).countByWorkflowIdAndStatus(WORKFLOW_ID, "COMPLETED");
        }

        @Test
        @DisplayName("delete should soft-delete via repository")
        void delete_shouldSoftDelete() {
            when(projectionRepository.softDelete(AGGREGATE_ID))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(storeWithProjection.delete(INSTANCE_ID))
                    .expectNext(true)
                    .verifyComplete();

            verify(projectionRepository).softDelete(AGGREGATE_ID);
        }

        @Test
        @DisplayName("deleteByWorkflowId should soft-delete via repository")
        void deleteByWorkflowId_shouldSoftDelete() {
            when(projectionRepository.softDeleteByWorkflowId(WORKFLOW_ID))
                    .thenReturn(Mono.just(3L));

            StepVerifier.create(storeWithProjection.deleteByWorkflowId(WORKFLOW_ID))
                    .expectNext(3L)
                    .verifyComplete();

            verify(projectionRepository).softDeleteByWorkflowId(WORKFLOW_ID);
        }

        @Test
        @DisplayName("delete with invalid UUID should return false")
        void delete_withInvalidUuid_shouldReturnFalse() {
            StepVerifier.create(storeWithProjection.delete("not-a-uuid"))
                    .expectNext(false)
                    .verifyComplete();

            verifyNoInteractions(projectionRepository);
        }
    }

    // ========================================================================
    // findByWorkflowAndInstanceId Tests
    // ========================================================================

    @Nested
    @DisplayName("findByWorkflowAndInstanceId")
    class FindByWorkflowAndInstanceIdTests {

        @Test
        @DisplayName("should delegate to findById")
        void shouldDelegateToFindById() {
            EventStream eventStream = createStartedEventStream(AGGREGATE_ID);
            when(eventStore.loadEventStream(AGGREGATE_ID, "workflow"))
                    .thenReturn(Mono.just(eventStream));

            StepVerifier.create(store.findByWorkflowAndInstanceId(WORKFLOW_ID, INSTANCE_ID))
                    .assertNext(instance -> {
                        assertThat(instance.instanceId()).isEqualTo(INSTANCE_ID);
                        assertThat(instance.workflowId()).isEqualTo(WORKFLOW_ID);
                    })
                    .verifyComplete();
        }
    }
}
