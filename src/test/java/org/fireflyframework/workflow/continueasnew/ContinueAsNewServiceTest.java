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

package org.fireflyframework.workflow.continueasnew;

import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.eventsourcing.store.EventSourcedWorkflowStateStore;
import org.fireflyframework.workflow.exception.WorkflowNotFoundException;
import org.fireflyframework.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ContinueAsNewService}.
 * <p>
 * Tests cover the continue-as-new lifecycle: completing the current aggregate,
 * creating a new one, migrating signals and timers, and error handling.
 */
@ExtendWith(MockitoExtension.class)
class ContinueAsNewServiceTest {

    private static final UUID AGGREGATE_ID = UUID.randomUUID();
    private static final String INSTANCE_ID = AGGREGATE_ID.toString();
    private static final String WORKFLOW_ID = "order-processing";
    private static final String WORKFLOW_NAME = "Order Processing";
    private static final String WORKFLOW_VERSION = "1.0.0";
    private static final String CORRELATION_ID = "corr-123";
    private static final String TRIGGERED_BY = "scheduler";
    private static final Map<String, Object> ORIGINAL_INPUT = Map.of("orderId", "ORD-001");
    private static final Map<String, Object> NEW_INPUT = Map.of("orderId", "ORD-001", "iteration", 2);

    @Mock
    private EventSourcedWorkflowStateStore stateStore;

    private ContinueAsNewService continueAsNewService;

    @BeforeEach
    void setUp() {
        continueAsNewService = new ContinueAsNewService(stateStore);
    }

    /**
     * Creates a running WorkflowAggregate (started and committed).
     */
    private WorkflowAggregate createRunningAggregate() {
        WorkflowAggregate aggregate = new WorkflowAggregate(AGGREGATE_ID);
        aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                ORIGINAL_INPUT, CORRELATION_ID, TRIGGERED_BY, false);
        aggregate.markEventsAsCommitted();
        return aggregate;
    }

    // ========================================================================
    // continueAsNew — basic lifecycle
    // ========================================================================

    @Nested
    @DisplayName("continueAsNew — basic lifecycle")
    class BasicLifecycleTests {

        @Test
        @DisplayName("should complete current aggregate and create new one")
        void continueAsNew_shouldCompleteCurrentAndCreateNew() {
            WorkflowAggregate aggregate = createRunningAggregate();

            when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(continueAsNewService.continueAsNew(AGGREGATE_ID, NEW_INPUT))
                    .assertNext(result -> {
                        assertThat(result.previousInstanceId()).isEqualTo(INSTANCE_ID);
                        assertThat(result.newInstanceId()).isNotNull();
                        assertThat(result.newInstanceId()).isNotEqualTo(INSTANCE_ID);
                        assertThat(result.workflowId()).isEqualTo(WORKFLOW_ID);
                        assertThat(result.migratedTimers()).isZero();
                        assertThat(result.migratedSignals()).isZero();
                    })
                    .verifyComplete();

            // Current aggregate should be completed (terminal)
            assertThat(aggregate.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);

            // Save should be called twice: once for old aggregate, once for new
            ArgumentCaptor<WorkflowAggregate> captor = ArgumentCaptor.forClass(WorkflowAggregate.class);
            verify(stateStore, times(2)).saveAggregate(captor.capture());

            // First save is the old aggregate (now completed)
            WorkflowAggregate savedOld = captor.getAllValues().get(0);
            assertThat(savedOld.getId()).isEqualTo(AGGREGATE_ID);
            assertThat(savedOld.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);

            // Second save is the new aggregate (running with same workflow identity)
            WorkflowAggregate savedNew = captor.getAllValues().get(1);
            assertThat(savedNew.getId()).isNotEqualTo(AGGREGATE_ID);
            assertThat(savedNew.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
            assertThat(savedNew.getWorkflowId()).isEqualTo(WORKFLOW_ID);
            assertThat(savedNew.getWorkflowName()).isEqualTo(WORKFLOW_NAME);
            assertThat(savedNew.getWorkflowVersion()).isEqualTo(WORKFLOW_VERSION);
            assertThat(savedNew.getCorrelationId()).isEqualTo(CORRELATION_ID);
        }

        @Test
        @DisplayName("should pass new input to the new aggregate")
        void continueAsNew_shouldPassNewInputToNewAggregate() {
            WorkflowAggregate aggregate = createRunningAggregate();

            when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(continueAsNewService.continueAsNew(AGGREGATE_ID, NEW_INPUT))
                    .assertNext(result -> assertThat(result.newInstanceId()).isNotNull())
                    .verifyComplete();

            ArgumentCaptor<WorkflowAggregate> captor = ArgumentCaptor.forClass(WorkflowAggregate.class);
            verify(stateStore, times(2)).saveAggregate(captor.capture());

            // New aggregate should have the new input
            WorkflowAggregate savedNew = captor.getAllValues().get(1);
            assertThat(savedNew.getInput()).isEqualTo(NEW_INPUT);
        }
    }

    // ========================================================================
    // continueAsNew — signal migration
    // ========================================================================

    @Nested
    @DisplayName("continueAsNew — signal migration")
    class SignalMigrationTests {

        @Test
        @DisplayName("should migrate pending signals to new aggregate")
        void continueAsNew_shouldMigratePendingSignals() {
            WorkflowAggregate aggregate = createRunningAggregate();
            // Add pending signals
            aggregate.receiveSignal("approval", Map.of("approved", true));
            aggregate.receiveSignal("payment", Map.of("amount", 100));
            aggregate.markEventsAsCommitted();

            when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(continueAsNewService.continueAsNew(AGGREGATE_ID, NEW_INPUT))
                    .assertNext(result -> {
                        assertThat(result.migratedSignals()).isEqualTo(2);
                    })
                    .verifyComplete();

            ArgumentCaptor<WorkflowAggregate> captor = ArgumentCaptor.forClass(WorkflowAggregate.class);
            verify(stateStore, times(2)).saveAggregate(captor.capture());

            // New aggregate should have the migrated signals
            WorkflowAggregate savedNew = captor.getAllValues().get(1);
            assertThat(savedNew.getPendingSignals()).containsKey("approval");
            assertThat(savedNew.getPendingSignals()).containsKey("payment");
            assertThat(savedNew.getPendingSignals().get("approval").payload())
                    .isEqualTo(Map.of("approved", true));
            assertThat(savedNew.getPendingSignals().get("payment").payload())
                    .isEqualTo(Map.of("amount", 100));
        }
    }

    // ========================================================================
    // continueAsNew — timer migration
    // ========================================================================

    @Nested
    @DisplayName("continueAsNew — timer migration")
    class TimerMigrationTests {

        @Test
        @DisplayName("should migrate active timers to new aggregate")
        void continueAsNew_shouldMigrateActiveTimers() {
            WorkflowAggregate aggregate = createRunningAggregate();
            // Add active timers
            Instant fireAt1 = Instant.now().plusSeconds(3600);
            Instant fireAt2 = Instant.now().plusSeconds(7200);
            aggregate.registerTimer("daily-check", fireAt1, Map.of("type", "daily"));
            aggregate.registerTimer("timeout", fireAt2, Map.of("type", "timeout"));
            aggregate.markEventsAsCommitted();

            when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(continueAsNewService.continueAsNew(AGGREGATE_ID, NEW_INPUT))
                    .assertNext(result -> {
                        assertThat(result.migratedTimers()).isEqualTo(2);
                    })
                    .verifyComplete();

            ArgumentCaptor<WorkflowAggregate> captor = ArgumentCaptor.forClass(WorkflowAggregate.class);
            verify(stateStore, times(2)).saveAggregate(captor.capture());

            // New aggregate should have the migrated timers
            WorkflowAggregate savedNew = captor.getAllValues().get(1);
            assertThat(savedNew.getActiveTimers()).containsKey("daily-check");
            assertThat(savedNew.getActiveTimers()).containsKey("timeout");
            assertThat(savedNew.getActiveTimers().get("daily-check").fireAt()).isEqualTo(fireAt1);
            assertThat(savedNew.getActiveTimers().get("daily-check").data())
                    .isEqualTo(Map.of("type", "daily"));
            assertThat(savedNew.getActiveTimers().get("timeout").fireAt()).isEqualTo(fireAt2);
            assertThat(savedNew.getActiveTimers().get("timeout").data())
                    .isEqualTo(Map.of("type", "timeout"));
        }
    }

    // ========================================================================
    // continueAsNew — no signals or timers
    // ========================================================================

    @Nested
    @DisplayName("continueAsNew — no signals or timers")
    class NoSignalsOrTimersTests {

        @Test
        @DisplayName("should work with no signals or timers to migrate")
        void continueAsNew_withNoSignalsOrTimers_shouldStillWork() {
            WorkflowAggregate aggregate = createRunningAggregate();

            when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(continueAsNewService.continueAsNew(AGGREGATE_ID, NEW_INPUT))
                    .assertNext(result -> {
                        assertThat(result.migratedTimers()).isZero();
                        assertThat(result.migratedSignals()).isZero();
                        assertThat(result.previousInstanceId()).isEqualTo(INSTANCE_ID);
                        assertThat(result.newInstanceId()).isNotNull();
                    })
                    .verifyComplete();

            verify(stateStore, times(2)).saveAggregate(any(WorkflowAggregate.class));
        }
    }

    // ========================================================================
    // continueAsNew — error cases
    // ========================================================================

    @Nested
    @DisplayName("continueAsNew — error cases")
    class ErrorCaseTests {

        @Test
        @DisplayName("should error when instance does not exist")
        void continueAsNew_withNonExistentInstance_shouldError() {
            when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.empty());

            StepVerifier.create(continueAsNewService.continueAsNew(AGGREGATE_ID, NEW_INPUT))
                    .expectError(WorkflowNotFoundException.class)
                    .verify();

            verify(stateStore).loadAggregate(AGGREGATE_ID);
            verify(stateStore, never()).saveAggregate(any());
        }

        @Test
        @DisplayName("should error when instance is already in terminal state")
        void continueAsNew_withTerminalInstance_shouldError() {
            WorkflowAggregate aggregate = createRunningAggregate();
            // Complete the aggregate so it's in a terminal state
            aggregate.complete(Map.of("result", "done"));
            aggregate.markEventsAsCommitted();

            when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));

            StepVerifier.create(continueAsNewService.continueAsNew(AGGREGATE_ID, NEW_INPUT))
                    .expectError(IllegalStateException.class)
                    .verify();

            verify(stateStore).loadAggregate(AGGREGATE_ID);
            verify(stateStore, never()).saveAggregate(any());
        }
    }
}
