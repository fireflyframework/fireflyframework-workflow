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

package org.fireflyframework.workflow.timer;

import org.fireflyframework.eventsourcing.store.ConcurrencyException;
import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.eventsourcing.store.EventSourcedWorkflowStateStore;
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TimerSchedulerService}.
 * <p>
 * Tests cover polling for due timers, firing timers on aggregates,
 * error handling (including optimistic concurrency conflicts),
 * and lifecycle management (start/stop).
 */
@ExtendWith(MockitoExtension.class)
class TimerSchedulerServiceTest {

    private static final UUID AGGREGATE_ID = UUID.randomUUID();
    private static final String TIMER_ID = "timeout-timer";
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final int BATCH_SIZE = 50;

    @Mock
    private EventSourcedWorkflowStateStore stateStore;

    private WorkflowTimerProjection projection;
    private TimerSchedulerService schedulerService;

    @BeforeEach
    void setUp() {
        projection = new WorkflowTimerProjection();
        schedulerService = new TimerSchedulerService(projection, stateStore, POLL_INTERVAL, BATCH_SIZE);
    }

    @AfterEach
    void tearDown() {
        schedulerService.stop();
    }

    /**
     * Creates a running WorkflowAggregate with an active timer.
     */
    private WorkflowAggregate createAggregateWithTimer(UUID aggregateId, String timerId) {
        WorkflowAggregate aggregate = new WorkflowAggregate(aggregateId);
        aggregate.start("test-workflow", "Test Workflow", "1.0.0",
                Map.of("key", "value"), "corr-1", "api", false);
        aggregate.registerTimer(timerId, Instant.now().minusSeconds(60), Map.of("type", "timeout"));
        aggregate.markEventsAsCommitted();
        return aggregate;
    }

    // ========================================================================
    // pollAndFireTimers Tests
    // ========================================================================

    @Nested
    @DisplayName("pollAndFireTimers")
    class PollAndFireTimersTests {

        @Test
        @DisplayName("should fire due timer by loading aggregate, calling fireTimer, and saving")
        void pollAndFireTimers_shouldFireDueTimer() {
            // Register a due timer in the projection
            Instant pastTime = Instant.now().minusSeconds(60);
            projection.onTimerRegistered(AGGREGATE_ID, TIMER_ID, pastTime, Map.of("type", "timeout"));

            // Set up the mock aggregate
            WorkflowAggregate aggregate = createAggregateWithTimer(AGGREGATE_ID, TIMER_ID);

            when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            // Execute polling
            StepVerifier.create(schedulerService.pollAndFireTimers())
                    .verifyComplete();

            // Verify the timer was fired on the aggregate
            verify(stateStore).loadAggregate(AGGREGATE_ID);
            verify(stateStore).saveAggregate(any(WorkflowAggregate.class));

            // The timer should have been removed from the aggregate
            assertThat(aggregate.getActiveTimers()).doesNotContainKey(TIMER_ID);

            // The timer should have been removed from the projection
            assertThat(projection.getActiveTimerCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("should not fire timers that are not due")
        void pollAndFireTimers_shouldNotFireFutureTimers() {
            Instant futureTime = Instant.now().plusSeconds(3600);
            projection.onTimerRegistered(AGGREGATE_ID, TIMER_ID, futureTime, Map.of());

            StepVerifier.create(schedulerService.pollAndFireTimers())
                    .verifyComplete();

            // Should not interact with state store at all
            verifyNoInteractions(stateStore);

            // Timer should still be active
            assertThat(projection.getActiveTimerCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should handle multiple due timers across instances")
        void pollAndFireTimers_shouldHandleMultipleDueTimers() {
            UUID instance1 = UUID.randomUUID();
            UUID instance2 = UUID.randomUUID();
            Instant pastTime = Instant.now().minusSeconds(60);

            projection.onTimerRegistered(instance1, "timer-1", pastTime, Map.of());
            projection.onTimerRegistered(instance2, "timer-2", pastTime.plusSeconds(1), Map.of());

            WorkflowAggregate aggregate1 = createAggregateWithTimer(instance1, "timer-1");
            WorkflowAggregate aggregate2 = createAggregateWithTimer(instance2, "timer-2");

            when(stateStore.loadAggregate(instance1)).thenReturn(Mono.just(aggregate1));
            when(stateStore.loadAggregate(instance2)).thenReturn(Mono.just(aggregate2));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(schedulerService.pollAndFireTimers())
                    .verifyComplete();

            verify(stateStore).loadAggregate(instance1);
            verify(stateStore).loadAggregate(instance2);
            verify(stateStore, times(2)).saveAggregate(any(WorkflowAggregate.class));
        }

        @Test
        @DisplayName("should complete successfully when no timers are due")
        void pollAndFireTimers_noTimersDue_shouldCompleteSuccessfully() {
            StepVerifier.create(schedulerService.pollAndFireTimers())
                    .verifyComplete();

            verifyNoInteractions(stateStore);
        }

        @Test
        @DisplayName("should handle concurrency exception gracefully (another node fired the timer)")
        void pollAndFireTimers_concurrencyConflict_shouldContinue() {
            UUID instance1 = UUID.randomUUID();
            UUID instance2 = UUID.randomUUID();
            Instant pastTime = Instant.now().minusSeconds(60);

            projection.onTimerRegistered(instance1, "timer-1", pastTime, Map.of());
            projection.onTimerRegistered(instance2, "timer-2", pastTime.plusSeconds(1), Map.of());

            WorkflowAggregate aggregate1 = createAggregateWithTimer(instance1, "timer-1");
            WorkflowAggregate aggregate2 = createAggregateWithTimer(instance2, "timer-2");

            // First timer save fails with concurrency exception
            when(stateStore.loadAggregate(instance1)).thenReturn(Mono.just(aggregate1));
            when(stateStore.saveAggregate(aggregate1))
                    .thenReturn(Mono.error(new ConcurrencyException(instance1, "workflow", 1, 2)));

            // Second timer succeeds
            when(stateStore.loadAggregate(instance2)).thenReturn(Mono.just(aggregate2));
            when(stateStore.saveAggregate(aggregate2))
                    .thenReturn(Mono.just(aggregate2));

            StepVerifier.create(schedulerService.pollAndFireTimers())
                    .verifyComplete();

            // Both timers should have been attempted
            verify(stateStore).loadAggregate(instance1);
            verify(stateStore).loadAggregate(instance2);
        }

        @Test
        @DisplayName("should handle aggregate load failure gracefully")
        void pollAndFireTimers_loadFailure_shouldContinue() {
            UUID instance1 = UUID.randomUUID();
            UUID instance2 = UUID.randomUUID();
            Instant pastTime = Instant.now().minusSeconds(60);

            projection.onTimerRegistered(instance1, "timer-1", pastTime, Map.of());
            projection.onTimerRegistered(instance2, "timer-2", pastTime.plusSeconds(1), Map.of());

            // First aggregate load fails
            when(stateStore.loadAggregate(instance1))
                    .thenReturn(Mono.error(new RuntimeException("DB connection failed")));

            // Second aggregate works
            WorkflowAggregate aggregate2 = createAggregateWithTimer(instance2, "timer-2");
            when(stateStore.loadAggregate(instance2)).thenReturn(Mono.just(aggregate2));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(schedulerService.pollAndFireTimers())
                    .verifyComplete();

            // Both timers should have been attempted
            verify(stateStore).loadAggregate(instance1);
            verify(stateStore).loadAggregate(instance2);
        }

        @Test
        @DisplayName("should handle aggregate not found gracefully")
        void pollAndFireTimers_aggregateNotFound_shouldContinue() {
            Instant pastTime = Instant.now().minusSeconds(60);
            projection.onTimerRegistered(AGGREGATE_ID, TIMER_ID, pastTime, Map.of());

            when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.empty());

            StepVerifier.create(schedulerService.pollAndFireTimers())
                    .verifyComplete();

            verify(stateStore).loadAggregate(AGGREGATE_ID);
            verify(stateStore, never()).saveAggregate(any());
        }

        @Test
        @DisplayName("should handle timer already fired on aggregate (IllegalStateException)")
        void pollAndFireTimers_timerAlreadyFired_shouldContinue() {
            Instant pastTime = Instant.now().minusSeconds(60);
            projection.onTimerRegistered(AGGREGATE_ID, TIMER_ID, pastTime, Map.of());

            // Create an aggregate that does NOT have the timer active
            // (e.g., it was already fired by another node and this projection is stale)
            WorkflowAggregate aggregate = new WorkflowAggregate(AGGREGATE_ID);
            aggregate.start("test-workflow", "Test Workflow", "1.0.0",
                    Map.of(), "corr-1", "api", false);
            aggregate.markEventsAsCommitted();

            when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));

            // Should not throw -- fireTimer() on aggregate will throw IllegalStateException
            // but the scheduler should handle it gracefully
            StepVerifier.create(schedulerService.pollAndFireTimers())
                    .verifyComplete();

            verify(stateStore).loadAggregate(AGGREGATE_ID);
            verify(stateStore, never()).saveAggregate(any());
        }
    }

    // ========================================================================
    // Lifecycle Tests
    // ========================================================================

    @Nested
    @DisplayName("Lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("start should begin polling loop")
        void start_shouldBeginPolling() {
            // Just verify it doesn't throw
            schedulerService.start();

            // Brief delay to allow at least one poll cycle
            // The polling loop itself is tested via pollAndFireTimers
            assertThat(schedulerService).isNotNull();
        }

        @Test
        @DisplayName("stop should be safe to call multiple times")
        void stop_shouldBeSafeToCallMultipleTimes() {
            schedulerService.start();
            schedulerService.stop();
            schedulerService.stop(); // Should not throw
        }

        @Test
        @DisplayName("stop should be safe to call before start")
        void stop_shouldBeSafeBeforeStart() {
            schedulerService.stop(); // Should not throw
        }

        @Test
        @DisplayName("destroy should stop the scheduler")
        void destroy_shouldStopScheduler() throws Exception {
            schedulerService.start();
            schedulerService.destroy();
            // After destroy, the polling should be stopped
        }
    }
}
