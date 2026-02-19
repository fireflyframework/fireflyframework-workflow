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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WorkflowTimerProjection}.
 * <p>
 * Tests cover timer registration, firing, due timer queries,
 * batch limiting, sorting, and per-instance queries.
 */
class WorkflowTimerProjectionTest {

    private WorkflowTimerProjection projection;

    @BeforeEach
    void setUp() {
        projection = new WorkflowTimerProjection();
    }

    // ========================================================================
    // onTimerRegistered Tests
    // ========================================================================

    @Nested
    @DisplayName("onTimerRegistered")
    class OnTimerRegisteredTests {

        @Test
        @DisplayName("should add timer to active timers")
        void onTimerRegistered_shouldAddTimer() {
            UUID instanceId = UUID.randomUUID();
            Instant fireAt = Instant.now().plusSeconds(60);
            Map<String, Object> data = Map.of("type", "timeout");

            projection.onTimerRegistered(instanceId, "timer-1", fireAt, data);

            assertThat(projection.getActiveTimerCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should add multiple timers for same instance")
        void onTimerRegistered_multipleTimersSameInstance() {
            UUID instanceId = UUID.randomUUID();
            Instant fireAt1 = Instant.now().plusSeconds(60);
            Instant fireAt2 = Instant.now().plusSeconds(120);

            projection.onTimerRegistered(instanceId, "timer-1", fireAt1, Map.of());
            projection.onTimerRegistered(instanceId, "timer-2", fireAt2, Map.of());

            assertThat(projection.getActiveTimerCount()).isEqualTo(2);
            assertThat(projection.getTimersForInstance(instanceId)).hasSize(2);
        }

        @Test
        @DisplayName("should add timers for different instances")
        void onTimerRegistered_differentInstances() {
            UUID instance1 = UUID.randomUUID();
            UUID instance2 = UUID.randomUUID();
            Instant fireAt = Instant.now().plusSeconds(60);

            projection.onTimerRegistered(instance1, "timer-1", fireAt, Map.of());
            projection.onTimerRegistered(instance2, "timer-1", fireAt, Map.of());

            assertThat(projection.getActiveTimerCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("should overwrite timer with same instance and timer ID")
        void onTimerRegistered_shouldOverwriteDuplicate() {
            UUID instanceId = UUID.randomUUID();
            Instant originalFireAt = Instant.now().plusSeconds(60);
            Instant updatedFireAt = Instant.now().plusSeconds(120);

            projection.onTimerRegistered(instanceId, "timer-1", originalFireAt, Map.of("v", 1));
            projection.onTimerRegistered(instanceId, "timer-1", updatedFireAt, Map.of("v", 2));

            assertThat(projection.getActiveTimerCount()).isEqualTo(1);

            List<TimerEntry> timers = projection.getTimersForInstance(instanceId);
            assertThat(timers).hasSize(1);
            assertThat(timers.get(0).fireAt()).isEqualTo(updatedFireAt);
            assertThat(timers.get(0).data()).containsEntry("v", 2);
        }

        @Test
        @DisplayName("should preserve timer data")
        void onTimerRegistered_shouldPreserveData() {
            UUID instanceId = UUID.randomUUID();
            Instant fireAt = Instant.now().plusSeconds(60);
            Map<String, Object> data = Map.of("timeout", true, "stepId", "step-1");

            projection.onTimerRegistered(instanceId, "timer-1", fireAt, data);

            List<TimerEntry> timers = projection.getTimersForInstance(instanceId);
            assertThat(timers).hasSize(1);
            assertThat(timers.get(0).instanceId()).isEqualTo(instanceId);
            assertThat(timers.get(0).timerId()).isEqualTo("timer-1");
            assertThat(timers.get(0).fireAt()).isEqualTo(fireAt);
            assertThat(timers.get(0).data()).containsEntry("timeout", true);
            assertThat(timers.get(0).data()).containsEntry("stepId", "step-1");
        }
    }

    // ========================================================================
    // onTimerFired Tests
    // ========================================================================

    @Nested
    @DisplayName("onTimerFired")
    class OnTimerFiredTests {

        @Test
        @DisplayName("should remove timer from active timers")
        void onTimerFired_shouldRemoveTimer() {
            UUID instanceId = UUID.randomUUID();
            Instant fireAt = Instant.now().plusSeconds(60);

            projection.onTimerRegistered(instanceId, "timer-1", fireAt, Map.of());
            assertThat(projection.getActiveTimerCount()).isEqualTo(1);

            projection.onTimerFired(instanceId, "timer-1");
            assertThat(projection.getActiveTimerCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("should not affect other timers when one is fired")
        void onTimerFired_shouldNotAffectOtherTimers() {
            UUID instanceId = UUID.randomUUID();
            Instant fireAt = Instant.now().plusSeconds(60);

            projection.onTimerRegistered(instanceId, "timer-1", fireAt, Map.of());
            projection.onTimerRegistered(instanceId, "timer-2", fireAt, Map.of());
            assertThat(projection.getActiveTimerCount()).isEqualTo(2);

            projection.onTimerFired(instanceId, "timer-1");
            assertThat(projection.getActiveTimerCount()).isEqualTo(1);

            List<TimerEntry> remaining = projection.getTimersForInstance(instanceId);
            assertThat(remaining).hasSize(1);
            assertThat(remaining.get(0).timerId()).isEqualTo("timer-2");
        }

        @Test
        @DisplayName("should be safe to fire non-existent timer")
        void onTimerFired_nonExistentTimer_shouldNotFail() {
            UUID instanceId = UUID.randomUUID();

            // Should not throw
            projection.onTimerFired(instanceId, "non-existent");
            assertThat(projection.getActiveTimerCount()).isEqualTo(0);
        }
    }

    // ========================================================================
    // findDueTimers Tests
    // ========================================================================

    @Nested
    @DisplayName("findDueTimers")
    class FindDueTimersTests {

        @Test
        @DisplayName("should return timers that are due")
        void findDueTimers_shouldReturnDueTimers() {
            UUID instanceId = UUID.randomUUID();
            Instant past = Instant.now().minusSeconds(60);
            Instant future = Instant.now().plusSeconds(60);

            projection.onTimerRegistered(instanceId, "due-timer", past, Map.of());
            projection.onTimerRegistered(instanceId, "future-timer", future, Map.of());

            List<TimerEntry> dueTimers = projection.findDueTimers(Instant.now(), 50);
            assertThat(dueTimers).hasSize(1);
            assertThat(dueTimers.get(0).timerId()).isEqualTo("due-timer");
        }

        @Test
        @DisplayName("should include timers with fireAt exactly equal to now")
        void findDueTimers_shouldIncludeExactTime() {
            UUID instanceId = UUID.randomUUID();
            Instant now = Instant.now();

            projection.onTimerRegistered(instanceId, "exact-timer", now, Map.of());

            List<TimerEntry> dueTimers = projection.findDueTimers(now, 50);
            assertThat(dueTimers).hasSize(1);
            assertThat(dueTimers.get(0).timerId()).isEqualTo("exact-timer");
        }

        @Test
        @DisplayName("should return empty list when no timers are due")
        void findDueTimers_noTimersDue_shouldReturnEmpty() {
            UUID instanceId = UUID.randomUUID();
            Instant future = Instant.now().plusSeconds(3600);

            projection.onTimerRegistered(instanceId, "future-timer", future, Map.of());

            List<TimerEntry> dueTimers = projection.findDueTimers(Instant.now(), 50);
            assertThat(dueTimers).isEmpty();
        }

        @Test
        @DisplayName("should return empty list when no timers exist")
        void findDueTimers_noTimers_shouldReturnEmpty() {
            List<TimerEntry> dueTimers = projection.findDueTimers(Instant.now(), 50);
            assertThat(dueTimers).isEmpty();
        }

        @Test
        @DisplayName("should respect batch size limit")
        void findDueTimers_shouldRespectBatchSize() {
            Instant past = Instant.now().minusSeconds(60);

            for (int i = 0; i < 10; i++) {
                UUID instanceId = UUID.randomUUID();
                projection.onTimerRegistered(instanceId, "timer-" + i, past.plusSeconds(i), Map.of());
            }

            List<TimerEntry> dueTimers = projection.findDueTimers(Instant.now(), 3);
            assertThat(dueTimers).hasSize(3);
        }

        @Test
        @DisplayName("should return timers sorted by fireAt ascending")
        void findDueTimers_shouldReturnSortedByFireAt() {
            Instant base = Instant.now().minusSeconds(300);
            UUID instance1 = UUID.randomUUID();
            UUID instance2 = UUID.randomUUID();
            UUID instance3 = UUID.randomUUID();

            // Register in reverse order
            projection.onTimerRegistered(instance3, "timer-c", base.plusSeconds(3), Map.of());
            projection.onTimerRegistered(instance1, "timer-a", base.plusSeconds(1), Map.of());
            projection.onTimerRegistered(instance2, "timer-b", base.plusSeconds(2), Map.of());

            List<TimerEntry> dueTimers = projection.findDueTimers(Instant.now(), 50);
            assertThat(dueTimers).hasSize(3);
            assertThat(dueTimers.get(0).timerId()).isEqualTo("timer-a");
            assertThat(dueTimers.get(1).timerId()).isEqualTo("timer-b");
            assertThat(dueTimers.get(2).timerId()).isEqualTo("timer-c");
        }

        @Test
        @DisplayName("should return due timers from multiple instances")
        void findDueTimers_multipleInstances() {
            Instant past = Instant.now().minusSeconds(60);
            UUID instance1 = UUID.randomUUID();
            UUID instance2 = UUID.randomUUID();

            projection.onTimerRegistered(instance1, "timer-1", past, Map.of());
            projection.onTimerRegistered(instance2, "timer-2", past, Map.of());

            List<TimerEntry> dueTimers = projection.findDueTimers(Instant.now(), 50);
            assertThat(dueTimers).hasSize(2);
        }
    }

    // ========================================================================
    // getActiveTimerCount Tests
    // ========================================================================

    @Nested
    @DisplayName("getActiveTimerCount")
    class GetActiveTimerCountTests {

        @Test
        @DisplayName("should return zero when no timers exist")
        void getActiveTimerCount_shouldReturnZeroInitially() {
            assertThat(projection.getActiveTimerCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("should return correct count after registrations and firings")
        void getActiveTimerCount_shouldTrackCorrectly() {
            UUID instanceId = UUID.randomUUID();
            Instant fireAt = Instant.now().plusSeconds(60);

            projection.onTimerRegistered(instanceId, "timer-1", fireAt, Map.of());
            projection.onTimerRegistered(instanceId, "timer-2", fireAt, Map.of());
            assertThat(projection.getActiveTimerCount()).isEqualTo(2);

            projection.onTimerFired(instanceId, "timer-1");
            assertThat(projection.getActiveTimerCount()).isEqualTo(1);
        }
    }

    // ========================================================================
    // getTimersForInstance Tests
    // ========================================================================

    @Nested
    @DisplayName("getTimersForInstance")
    class GetTimersForInstanceTests {

        @Test
        @DisplayName("should return empty list for unknown instance")
        void getTimersForInstance_unknownInstance_shouldReturnEmpty() {
            List<TimerEntry> timers = projection.getTimersForInstance(UUID.randomUUID());
            assertThat(timers).isEmpty();
        }

        @Test
        @DisplayName("should return only timers for the specified instance")
        void getTimersForInstance_shouldFilterByInstance() {
            UUID instance1 = UUID.randomUUID();
            UUID instance2 = UUID.randomUUID();
            Instant fireAt = Instant.now().plusSeconds(60);

            projection.onTimerRegistered(instance1, "timer-a", fireAt, Map.of());
            projection.onTimerRegistered(instance1, "timer-b", fireAt, Map.of());
            projection.onTimerRegistered(instance2, "timer-c", fireAt, Map.of());

            List<TimerEntry> instance1Timers = projection.getTimersForInstance(instance1);
            assertThat(instance1Timers).hasSize(2);
            assertThat(instance1Timers).allMatch(t -> t.instanceId().equals(instance1));

            List<TimerEntry> instance2Timers = projection.getTimersForInstance(instance2);
            assertThat(instance2Timers).hasSize(1);
            assertThat(instance2Timers.get(0).timerId()).isEqualTo("timer-c");
        }

        @Test
        @DisplayName("should return empty after all instance timers are fired")
        void getTimersForInstance_afterAllFired_shouldReturnEmpty() {
            UUID instanceId = UUID.randomUUID();
            Instant fireAt = Instant.now().plusSeconds(60);

            projection.onTimerRegistered(instanceId, "timer-1", fireAt, Map.of());
            projection.onTimerFired(instanceId, "timer-1");

            List<TimerEntry> timers = projection.getTimersForInstance(instanceId);
            assertThat(timers).isEmpty();
        }
    }
}
