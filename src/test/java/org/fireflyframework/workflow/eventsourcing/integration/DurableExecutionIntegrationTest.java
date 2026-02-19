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

package org.fireflyframework.workflow.eventsourcing.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fireflyframework.eventsourcing.domain.Event;
import org.fireflyframework.eventsourcing.domain.EventStream;
import org.fireflyframework.eventsourcing.domain.StoredEventEnvelope;
import org.fireflyframework.eventsourcing.store.EventStore;
import org.fireflyframework.workflow.child.ChildWorkflowService;
import org.fireflyframework.workflow.compensation.CompensationOrchestrator;
import org.fireflyframework.workflow.compensation.CompensationPolicy;
import org.fireflyframework.workflow.continueasnew.ContinueAsNewService;
import org.fireflyframework.workflow.core.StepHandler;
import org.fireflyframework.workflow.core.WorkflowContext;
import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.eventsourcing.event.*;
import org.fireflyframework.workflow.eventsourcing.snapshot.WorkflowSnapshot;
import org.fireflyframework.workflow.eventsourcing.store.EventSourcedWorkflowStateStore;
import org.fireflyframework.workflow.model.WorkflowDefinition;
import org.fireflyframework.workflow.model.WorkflowInstance;
import org.fireflyframework.workflow.model.WorkflowStatus;
import org.fireflyframework.workflow.search.SearchAttributeProjection;
import org.fireflyframework.workflow.search.WorkflowSearchService;
import org.fireflyframework.workflow.signal.SignalService;
import org.fireflyframework.workflow.timer.TimerSchedulerService;
import org.fireflyframework.workflow.timer.WorkflowTimerProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Integration tests for the durable execution engine.
 * <p>
 * These tests exercise the full durable execution flow across multiple components
 * working together, using an in-memory mock EventStore. They verify that the
 * aggregate, state store, and services cooperate correctly to implement
 * the complete durable execution lifecycle.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Durable Execution Integration Tests")
class DurableExecutionIntegrationTest {

    private static final String WORKFLOW_ID = "order-processing";
    private static final String WORKFLOW_NAME = "Order Processing";
    private static final String WORKFLOW_VERSION = "1.0.0";
    private static final Map<String, Object> INPUT = Map.of("orderId", "ORD-123");
    private static final String CORRELATION_ID = "corr-456";
    private static final String TRIGGERED_BY = "api-gateway";

    @Mock
    private EventStore eventStore;

    private EventSourcedWorkflowStateStore stateStore;

    /**
     * In-memory event storage for integration testing.
     * Keyed by aggregate ID, each value is a list of StoredEventEnvelope.
     */
    private final Map<UUID, List<StoredEventEnvelope>> storedEvents = new HashMap<>();
    private final AtomicLong globalSequence = new AtomicLong(0);

    @BeforeEach
    void setUp() {
        storedEvents.clear();
        globalSequence.set(0);

        // Wire up the mock EventStore to use in-memory storage
        when(eventStore.loadEventStream(any(UUID.class), eq("workflow")))
                .thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(0);
                    List<StoredEventEnvelope> events = storedEvents.getOrDefault(id, List.of());
                    if (events.isEmpty()) {
                        return Mono.just(EventStream.empty(id, "workflow"));
                    }
                    long maxVersion = events.stream()
                            .mapToLong(StoredEventEnvelope::getAggregateVersion)
                            .max().orElse(0L);
                    return Mono.just(EventStream.builder()
                            .aggregateId(id)
                            .aggregateType("workflow")
                            .currentVersion(maxVersion)
                            .events(List.copyOf(events))
                            .build());
                });

        when(eventStore.appendEvents(any(UUID.class), eq("workflow"), anyList(), anyLong(), anyMap()))
                .thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(0);
                    List<Event> newEvents = invocation.getArgument(2);
                    long expectedVersion = invocation.getArgument(3);

                    List<StoredEventEnvelope> existing = storedEvents.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>());
                    long currentVersion = existing.isEmpty() ? 0L :
                            existing.stream().mapToLong(StoredEventEnvelope::getAggregateVersion).max().orElse(0L);

                    List<StoredEventEnvelope> envelopes = new ArrayList<>();
                    long version = currentVersion;
                    for (Event event : newEvents) {
                        version++;
                        envelopes.add(StoredEventEnvelope.builder()
                                .eventId(UUID.randomUUID())
                                .event(event)
                                .aggregateId(id)
                                .aggregateType("workflow")
                                .aggregateVersion(version)
                                .globalSequence(globalSequence.incrementAndGet())
                                .eventType(event.getEventType())
                                .createdAt(Instant.now())
                                .build());
                    }
                    existing.addAll(envelopes);

                    return Mono.just(EventStream.builder()
                            .aggregateId(id)
                            .aggregateType("workflow")
                            .currentVersion(version)
                            .events(List.copyOf(existing))
                            .build());
                });

        lenient().when(eventStore.aggregateExists(any(UUID.class), eq("workflow")))
                .thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(0);
                    boolean exists = storedEvents.containsKey(id) && !storedEvents.get(id).isEmpty();
                    return Mono.just(exists);
                });

        lenient().when(eventStore.isHealthy())
                .thenReturn(Mono.just(true));

        stateStore = new EventSourcedWorkflowStateStore(eventStore);
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Creates a new aggregate, starts it, and saves it to the in-memory event store.
     */
    private UUID createAndStartWorkflow() {
        UUID aggregateId = UUID.randomUUID();
        WorkflowAggregate aggregate = new WorkflowAggregate(aggregateId);
        aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                INPUT, CORRELATION_ID, TRIGGERED_BY, false);
        stateStore.saveAggregate(aggregate).block();
        return aggregateId;
    }

    /**
     * Collects all events of a specific type from the in-memory store for a given aggregate.
     */
    private <T extends Event> List<T> getEventsOfType(UUID aggregateId, Class<T> eventType) {
        return storedEvents.getOrDefault(aggregateId, List.of()).stream()
                .map(StoredEventEnvelope::getEvent)
                .filter(eventType::isInstance)
                .map(eventType::cast)
                .toList();
    }

    // ========================================================================
    // Test 1: Full Workflow Lifecycle
    // ========================================================================

    @Nested
    @DisplayName("Full Workflow Lifecycle")
    class FullWorkflowLifecycleTests {

        @Test
        @DisplayName("should emit correct events through start, steps, and completion")
        void fullWorkflowLifecycle() {
            // Start workflow
            UUID aggregateId = createAndStartWorkflow();

            // Execute step 1
            WorkflowAggregate aggregate = stateStore.loadAggregate(aggregateId).block();
            assertThat(aggregate).isNotNull();
            aggregate.startStep("validate-order", "Validate Order", Map.of("orderId", "ORD-123"), 1);
            aggregate.completeStep("validate-order", Map.of("valid", true), 100L);
            stateStore.saveAggregate(aggregate).block();

            // Execute step 2
            aggregate = stateStore.loadAggregate(aggregateId).block();
            assertThat(aggregate).isNotNull();
            aggregate.startStep("charge-payment", "Charge Payment", Map.of("amount", 99.99), 1);
            aggregate.completeStep("charge-payment", Map.of("transactionId", "TXN-789"), 200L);
            stateStore.saveAggregate(aggregate).block();

            // Complete workflow
            aggregate = stateStore.loadAggregate(aggregateId).block();
            assertThat(aggregate).isNotNull();
            aggregate.complete(Map.of("orderId", "ORD-123", "status", "processed"));
            stateStore.saveAggregate(aggregate).block();

            // Verify events
            assertThat(getEventsOfType(aggregateId, WorkflowStartedEvent.class)).hasSize(1);
            assertThat(getEventsOfType(aggregateId, StepStartedEvent.class)).hasSize(2);
            assertThat(getEventsOfType(aggregateId, StepCompletedEvent.class)).hasSize(2);
            assertThat(getEventsOfType(aggregateId, WorkflowCompletedEvent.class)).hasSize(1);

            // Verify final aggregate state
            aggregate = stateStore.loadAggregate(aggregateId).block();
            assertThat(aggregate).isNotNull();
            assertThat(aggregate.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(aggregate.getOutput()).isNotNull();
            assertThat(aggregate.getCompletedStepOrder()).containsExactly("validate-order", "charge-payment");
            assertThat(aggregate.getStepStates()).hasSize(2);

            // Verify WorkflowInstance conversion
            WorkflowInstance instance = stateStore.toWorkflowInstance(aggregate);
            assertThat(instance.status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(instance.workflowId()).isEqualTo(WORKFLOW_ID);
            assertThat(instance.stepExecutions()).hasSize(2);
        }
    }

    // ========================================================================
    // Test 2: State Reconstruction from Events
    // ========================================================================

    @Nested
    @DisplayName("State Reconstruction from Events")
    class StateReconstructionTests {

        @Test
        @DisplayName("should reconstruct identical state by replaying events")
        void stateReconstructionFromEvents() {
            // Create an aggregate with rich state
            UUID aggregateId = createAndStartWorkflow();

            WorkflowAggregate aggregate = stateStore.loadAggregate(aggregateId).block();
            assertThat(aggregate).isNotNull();
            aggregate.startStep("step-1", "Step One", Map.of("k", "v"), 1);
            aggregate.completeStep("step-1", Map.of("result", "done"), 50L);
            aggregate.receiveSignal("approval", Map.of("approved", true));
            aggregate.registerTimer("reminder-1", Instant.now().plusSeconds(3600), Map.of("type", "reminder"));
            aggregate.recordSideEffect("random-id", "abc-123");
            aggregate.upsertSearchAttribute("region", "us-east-1");
            aggregate.heartbeat("step-1", Map.of("progress", 100));
            stateStore.saveAggregate(aggregate).block();

            // Load from the event store (state reconstructed from events)
            WorkflowAggregate reconstructed = stateStore.loadAggregate(aggregateId).block();
            assertThat(reconstructed).isNotNull();

            // Verify all state matches
            assertThat(reconstructed.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
            assertThat(reconstructed.getWorkflowId()).isEqualTo(WORKFLOW_ID);
            assertThat(reconstructed.getWorkflowName()).isEqualTo(WORKFLOW_NAME);
            assertThat(reconstructed.getWorkflowVersion()).isEqualTo(WORKFLOW_VERSION);
            assertThat(reconstructed.getCorrelationId()).isEqualTo(CORRELATION_ID);
            assertThat(reconstructed.getTriggeredBy()).isEqualTo(TRIGGERED_BY);
            assertThat(reconstructed.getCompletedStepOrder()).containsExactly("step-1");
            assertThat(reconstructed.getPendingSignals()).containsKey("approval");
            assertThat(reconstructed.getActiveTimers()).containsKey("reminder-1");
            assertThat(reconstructed.getSideEffects()).containsEntry("random-id", "abc-123");
            assertThat(reconstructed.getSearchAttributes()).containsEntry("region", "us-east-1");
            assertThat(reconstructed.getLastHeartbeats()).containsKey("step-1");
        }
    }

    // ========================================================================
    // Test 3: Signal Delivery to Workflow
    // ========================================================================

    @Nested
    @DisplayName("Signal Delivery to Workflow")
    class SignalDeliveryTests {

        @Test
        @DisplayName("should deliver signal and buffer it in aggregate via SignalService")
        void signalDeliveryToWorkflow() {
            UUID aggregateId = createAndStartWorkflow();
            SignalService signalService = new SignalService(stateStore);

            // Send signal
            StepVerifier.create(signalService.sendSignal(
                            aggregateId.toString(), "payment-received", Map.of("amount", 99.99)))
                    .assertNext(result -> {
                        assertThat(result.delivered()).isTrue();
                        assertThat(result.signalName()).isEqualTo("payment-received");
                        assertThat(result.instanceId()).isEqualTo(aggregateId.toString());
                    })
                    .verifyComplete();

            // Verify SignalReceivedEvent was emitted
            assertThat(getEventsOfType(aggregateId, SignalReceivedEvent.class)).hasSize(1);

            // Verify signal is buffered in the aggregate
            WorkflowAggregate aggregate = stateStore.loadAggregate(aggregateId).block();
            assertThat(aggregate).isNotNull();
            assertThat(aggregate.getPendingSignals()).containsKey("payment-received");
            assertThat(aggregate.getPendingSignals().get("payment-received").payload())
                    .containsEntry("amount", 99.99);

            // Verify consumeSignal returns the payload
            StepVerifier.create(signalService.consumeSignal(aggregateId.toString(), "payment-received"))
                    .assertNext(payload -> {
                        assertThat(payload).containsEntry("amount", 99.99);
                    })
                    .verifyComplete();
        }
    }

    // ========================================================================
    // Test 4: Timer Registration and Firing
    // ========================================================================

    @Nested
    @DisplayName("Timer Registration and Firing")
    class TimerTests {

        @Test
        @DisplayName("should register timer and fire it via TimerSchedulerService")
        void timerRegistrationAndFiring() {
            UUID aggregateId = createAndStartWorkflow();

            // Register a timer on the aggregate
            WorkflowAggregate aggregate = stateStore.loadAggregate(aggregateId).block();
            assertThat(aggregate).isNotNull();
            Instant fireAt = Instant.now().minusSeconds(10); // Already due
            aggregate.registerTimer("timeout-1", fireAt, Map.of("reason", "timeout"));
            stateStore.saveAggregate(aggregate).block();

            // Verify TimerRegisteredEvent
            assertThat(getEventsOfType(aggregateId, TimerRegisteredEvent.class)).hasSize(1);

            // Add timer to projection
            WorkflowTimerProjection timerProjection = new WorkflowTimerProjection();
            timerProjection.onTimerRegistered(aggregateId, "timeout-1", fireAt, Map.of("reason", "timeout"));

            // Create TimerSchedulerService and poll
            TimerSchedulerService timerService = new TimerSchedulerService(
                    timerProjection, stateStore, Duration.ofSeconds(1), 50);

            StepVerifier.create(timerService.pollAndFireTimers())
                    .verifyComplete();

            // Verify TimerFiredEvent was emitted
            assertThat(getEventsOfType(aggregateId, TimerFiredEvent.class)).hasSize(1);

            // Verify timer was removed from aggregate and projection
            aggregate = stateStore.loadAggregate(aggregateId).block();
            assertThat(aggregate).isNotNull();
            assertThat(aggregate.getActiveTimers()).doesNotContainKey("timeout-1");
            assertThat(timerProjection.getActiveTimerCount()).isZero();
        }
    }

    // ========================================================================
    // Test 5: Child Workflow Spawn and Complete
    // ========================================================================

    @Nested
    @DisplayName("Child Workflow Spawn and Complete")
    class ChildWorkflowTests {

        @Test
        @DisplayName("should spawn child and complete with parent notification")
        void childWorkflowSpawnAndComplete() {
            UUID parentId = createAndStartWorkflow();
            ChildWorkflowService childService = new ChildWorkflowService(stateStore);

            // Spawn child workflow
            StepVerifier.create(childService.spawnChildWorkflow(
                            parentId, "step-1", "child-workflow", Map.of("childInput", "data")))
                    .assertNext(result -> {
                        assertThat(result.spawned()).isTrue();
                        assertThat(result.parentInstanceId()).isEqualTo(parentId.toString());
                        assertThat(result.childWorkflowId()).isEqualTo("child-workflow");
                        assertThat(result.childInstanceId()).isNotNull();
                    })
                    .verifyComplete();

            // Verify ChildWorkflowSpawnedEvent on parent
            assertThat(getEventsOfType(parentId, ChildWorkflowSpawnedEvent.class)).hasSize(1);

            // Verify parent has child workflow reference
            WorkflowAggregate parent = stateStore.loadAggregate(parentId).block();
            assertThat(parent).isNotNull();
            assertThat(parent.getChildWorkflows()).hasSize(1);

            String childInstanceId = parent.getChildWorkflows().keySet().iterator().next();
            UUID childUuid = UUID.fromString(childInstanceId);

            // Verify child aggregate was created and started
            WorkflowAggregate child = stateStore.loadAggregate(childUuid).block();
            assertThat(child).isNotNull();
            assertThat(child.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
            assertThat(child.getWorkflowId()).isEqualTo("child-workflow");

            // Complete child workflow
            StepVerifier.create(childService.completeChildWorkflow(
                            childUuid, Map.of("childResult", "success"), true))
                    .verifyComplete();

            // Verify ChildWorkflowCompletedEvent on parent
            assertThat(getEventsOfType(parentId, ChildWorkflowCompletedEvent.class)).hasSize(1);

            // Verify parent shows child as completed
            parent = stateStore.loadAggregate(parentId).block();
            assertThat(parent).isNotNull();
            WorkflowAggregate.ChildWorkflowRef childRef = parent.getChildWorkflows().get(childInstanceId);
            assertThat(childRef.completed()).isTrue();
            assertThat(childRef.success()).isTrue();
        }
    }

    // ========================================================================
    // Test 6: Compensation on Failure
    // ========================================================================

    @Nested
    @DisplayName("Compensation on Failure")
    class CompensationTests {

        @Test
        @DisplayName("should compensate completed steps in reverse order")
        void compensationOnFailure() {
            UUID aggregateId = createAndStartWorkflow();

            // Execute two steps to completion
            WorkflowAggregate aggregate = stateStore.loadAggregate(aggregateId).block();
            assertThat(aggregate).isNotNull();
            aggregate.startStep("step-1", "Validate", Map.of(), 1);
            aggregate.completeStep("step-1", Map.of("valid", true), 50L);
            aggregate.startStep("step-2", "Reserve", Map.of(), 1);
            aggregate.completeStep("step-2", Map.of("reserved", true), 100L);
            stateStore.saveAggregate(aggregate).block();

            // Create step handlers that succeed compensation
            Map<String, StepHandler<?>> stepHandlers = new HashMap<>();
            stepHandlers.put("step-1", new NoOpStepHandler());
            stepHandlers.put("step-2", new NoOpStepHandler());

            CompensationOrchestrator orchestrator = new CompensationOrchestrator(stateStore, stepHandlers);

            // Trigger compensation
            StepVerifier.create(orchestrator.compensate(aggregateId, "step-3", CompensationPolicy.STRICT_SEQUENTIAL))
                    .assertNext(result -> {
                        assertThat(result.allSuccessful()).isTrue();
                        assertThat(result.policy()).isEqualTo(CompensationPolicy.STRICT_SEQUENTIAL);
                        assertThat(result.failedStepId()).isEqualTo("step-3");
                        assertThat(result.compensatedSteps()).hasSize(2);
                        // Verify reverse order: step-2 first, then step-1
                        assertThat(result.compensatedSteps().get(0).stepId()).isEqualTo("step-2");
                        assertThat(result.compensatedSteps().get(1).stepId()).isEqualTo("step-1");
                    })
                    .verifyComplete();

            // Verify CompensationStartedEvent and CompensationStepCompletedEvent
            assertThat(getEventsOfType(aggregateId, CompensationStartedEvent.class)).hasSize(1);
            assertThat(getEventsOfType(aggregateId, CompensationStepCompletedEvent.class)).hasSize(2);
        }
    }

    // ========================================================================
    // Test 7: Continue-as-New Creates New Aggregate
    // ========================================================================

    @Nested
    @DisplayName("Continue-as-New Creates New Aggregate")
    class ContinueAsNewTests {

        @Test
        @DisplayName("should complete old aggregate and create new one with migrated state")
        void continueAsNewCreatesNewAggregate() {
            UUID oldId = createAndStartWorkflow();

            // Add signals and timers to the old aggregate
            WorkflowAggregate aggregate = stateStore.loadAggregate(oldId).block();
            assertThat(aggregate).isNotNull();
            aggregate.receiveSignal("pending-signal", Map.of("data", "value"));
            Instant timerFireAt = Instant.now().plusSeconds(3600);
            aggregate.registerTimer("pending-timer", timerFireAt, Map.of("type", "reminder"));
            aggregate.startStep("step-1", "Step One", Map.of(), 1);
            aggregate.completeStep("step-1", Map.of("result", "done"), 50L);
            stateStore.saveAggregate(aggregate).block();

            ContinueAsNewService continueAsNewService = new ContinueAsNewService(stateStore);

            // Continue as new
            StepVerifier.create(continueAsNewService.continueAsNew(oldId, Map.of("newKey", "newValue")))
                    .assertNext(result -> {
                        assertThat(result.previousInstanceId()).isEqualTo(oldId.toString());
                        assertThat(result.newInstanceId()).isNotNull();
                        assertThat(result.workflowId()).isEqualTo(WORKFLOW_ID);
                        assertThat(result.migratedSignals()).isEqualTo(1);
                        assertThat(result.migratedTimers()).isEqualTo(1);
                    })
                    .verifyComplete();

            // Verify old aggregate is COMPLETED with ContinueAsNewEvent
            aggregate = stateStore.loadAggregate(oldId).block();
            assertThat(aggregate).isNotNull();
            assertThat(aggregate.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(getEventsOfType(oldId, ContinueAsNewEvent.class)).hasSize(1);

            // Find the new aggregate ID from stored events
            UUID newId = storedEvents.keySet().stream()
                    .filter(id -> !id.equals(oldId))
                    .findFirst()
                    .orElseThrow();

            // Verify new aggregate is RUNNING with migrated signals and timers
            WorkflowAggregate newAggregate = stateStore.loadAggregate(newId).block();
            assertThat(newAggregate).isNotNull();
            assertThat(newAggregate.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
            assertThat(newAggregate.getWorkflowId()).isEqualTo(WORKFLOW_ID);
            assertThat(newAggregate.getPendingSignals()).containsKey("pending-signal");
            assertThat(newAggregate.getActiveTimers()).containsKey("pending-timer");
            assertThat(newAggregate.getInput()).containsEntry("newKey", "newValue");
        }
    }

    // ========================================================================
    // Test 8: Search Attributes Queryable
    // ========================================================================

    @Nested
    @DisplayName("Search Attributes Queryable")
    class SearchAttributeTests {

        @Test
        @DisplayName("should update and query search attributes via projection")
        void searchAttributesQueryable() {
            UUID aggregateId = createAndStartWorkflow();

            SearchAttributeProjection projection = new SearchAttributeProjection();
            WorkflowSearchService searchService = new WorkflowSearchService(projection, stateStore);

            // Update search attributes
            StepVerifier.create(searchService.updateSearchAttribute(aggregateId, "region", "us-east-1"))
                    .verifyComplete();
            StepVerifier.create(searchService.updateSearchAttribute(aggregateId, "priority", "high"))
                    .verifyComplete();

            // Verify SearchAttributeUpdatedEvent was emitted
            assertThat(getEventsOfType(aggregateId, SearchAttributeUpdatedEvent.class)).hasSize(2);

            // Search by attribute
            StepVerifier.create(searchService.searchByAttribute("region", "us-east-1").collectList())
                    .assertNext(results -> {
                        assertThat(results).hasSize(1);
                        assertThat(results.get(0).instanceId()).isEqualTo(aggregateId.toString());
                    })
                    .verifyComplete();

            // Search by multiple attributes
            StepVerifier.create(searchService.searchByAttributes(
                            Map.of("region", "us-east-1", "priority", "high")).collectList())
                    .assertNext(results -> {
                        assertThat(results).hasSize(1);
                    })
                    .verifyComplete();

            // Get search attributes from aggregate
            StepVerifier.create(searchService.getSearchAttributes(aggregateId))
                    .assertNext(attrs -> {
                        assertThat(attrs).containsEntry("region", "us-east-1");
                        assertThat(attrs).containsEntry("priority", "high");
                    })
                    .verifyComplete();
        }
    }

    // ========================================================================
    // Test 9: Side Effect Deterministic Replay
    // ========================================================================

    @Nested
    @DisplayName("Side Effect Deterministic Replay")
    class SideEffectTests {

        @Test
        @DisplayName("should record side effect on first call and replay on second call")
        void sideEffectDeterministicReplay() {
            UUID aggregateId = createAndStartWorkflow();

            WorkflowAggregate aggregate = stateStore.loadAggregate(aggregateId).block();
            assertThat(aggregate).isNotNull();

            WorkflowInstance instance = stateStore.toWorkflowInstance(aggregate);
            WorkflowDefinition definition = WorkflowDefinition.builder()
                    .workflowId(WORKFLOW_ID)
                    .name(WORKFLOW_NAME)
                    .version(WORKFLOW_VERSION)
                    .build();

            // Create context with aggregate
            WorkflowContext context = new WorkflowContext(
                    definition, instance, "step-1", new ObjectMapper(), false, aggregate);

            // First call: supplier executes and records
            String value1 = context.sideEffect("unique-id-gen", () -> "generated-value-42");
            assertThat(value1).isEqualTo("generated-value-42");

            // Verify SideEffectRecordedEvent was applied to aggregate
            assertThat(aggregate.getSideEffects()).containsEntry("unique-id-gen", "generated-value-42");

            // Save the aggregate
            stateStore.saveAggregate(aggregate).block();

            // Load a fresh aggregate (simulating replay)
            WorkflowAggregate replayedAggregate = stateStore.loadAggregate(aggregateId).block();
            assertThat(replayedAggregate).isNotNull();

            WorkflowInstance replayedInstance = stateStore.toWorkflowInstance(replayedAggregate);
            WorkflowContext replayContext = new WorkflowContext(
                    definition, replayedInstance, "step-1", new ObjectMapper(), false, replayedAggregate);

            // Second call: should return stored value without calling supplier
            String value2 = replayContext.sideEffect("unique-id-gen", () -> "DIFFERENT-VALUE");
            assertThat(value2).isEqualTo("generated-value-42");
        }
    }

    // ========================================================================
    // Test 10: Snapshot Restores State
    // ========================================================================

    @Nested
    @DisplayName("Snapshot Restores State")
    class SnapshotTests {

        @Test
        @DisplayName("should create snapshot and restore all state to new aggregate")
        void snapshotRestoresState() {
            UUID aggregateId = createAndStartWorkflow();

            // Build up rich state on the aggregate
            WorkflowAggregate aggregate = stateStore.loadAggregate(aggregateId).block();
            assertThat(aggregate).isNotNull();

            // Add steps
            aggregate.startStep("step-1", "Validate", Map.of("orderId", "ORD-123"), 1);
            aggregate.completeStep("step-1", Map.of("valid", true), 100L);
            aggregate.startStep("step-2", "Process", Map.of("amount", 99.99), 1);
            aggregate.completeStep("step-2", Map.of("processed", true), 200L);

            // Add signals, timers, child workflows
            aggregate.receiveSignal("approval", Map.of("approved", true));
            Instant timerFireAt = Instant.now().plusSeconds(3600);
            aggregate.registerTimer("reminder-timer", timerFireAt, Map.of("type", "reminder"));
            aggregate.spawnChildWorkflow("child-001", "child-wf", Map.of(), "step-2");

            // Add side effects, search attributes, heartbeats
            aggregate.recordSideEffect("random-id", "abc-123");
            aggregate.upsertSearchAttribute("region", "us-east-1");
            aggregate.upsertSearchAttribute("priority", "high");
            aggregate.heartbeat("step-2", Map.of("progress", 75));

            // Create snapshot
            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);

            // Restore to new aggregate
            WorkflowAggregate restored = snapshot.restore();

            // Verify all state matches
            assertThat(restored.getId()).isEqualTo(aggregateId);
            assertThat(restored.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
            assertThat(restored.getWorkflowId()).isEqualTo(WORKFLOW_ID);
            assertThat(restored.getWorkflowName()).isEqualTo(WORKFLOW_NAME);
            assertThat(restored.getWorkflowVersion()).isEqualTo(WORKFLOW_VERSION);
            assertThat(restored.getCorrelationId()).isEqualTo(CORRELATION_ID);
            assertThat(restored.getTriggeredBy()).isEqualTo(TRIGGERED_BY);
            assertThat(restored.isDryRun()).isFalse();

            // Verify step states
            assertThat(restored.getStepStates()).hasSize(2);
            assertThat(restored.getCompletedStepOrder()).containsExactly("step-1", "step-2");

            // Verify signals
            assertThat(restored.getPendingSignals()).containsKey("approval");
            assertThat(restored.getPendingSignals().get("approval").payload())
                    .containsEntry("approved", true);

            // Verify timers
            assertThat(restored.getActiveTimers()).containsKey("reminder-timer");
            assertThat(restored.getActiveTimers().get("reminder-timer").fireAt()).isEqualTo(timerFireAt);

            // Verify child workflows
            assertThat(restored.getChildWorkflows()).containsKey("child-001");
            assertThat(restored.getChildWorkflows().get("child-001").childWorkflowId()).isEqualTo("child-wf");

            // Verify side effects
            assertThat(restored.getSideEffects()).containsEntry("random-id", "abc-123");

            // Verify search attributes
            assertThat(restored.getSearchAttributes()).containsEntry("region", "us-east-1");
            assertThat(restored.getSearchAttributes()).containsEntry("priority", "high");

            // Verify heartbeats
            assertThat(restored.getLastHeartbeats()).containsKey("step-2");
            assertThat(restored.getLastHeartbeats().get("step-2")).containsEntry("progress", 75);

            // Verify version
            assertThat(restored.getCurrentVersion()).isEqualTo(aggregate.getCurrentVersion());
        }
    }

    // ========================================================================
    // Inner Helper Classes
    // ========================================================================

    /**
     * A no-op StepHandler that successfully completes compensation.
     */
    private static class NoOpStepHandler implements StepHandler<Map<String, Object>> {

        @Override
        public Mono<Map<String, Object>> execute(WorkflowContext context) {
            return Mono.just(Map.of());
        }

        @Override
        public Mono<Void> compensate(WorkflowContext context) {
            return Mono.empty();
        }
    }
}
