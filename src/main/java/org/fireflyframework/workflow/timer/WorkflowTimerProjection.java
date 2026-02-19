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

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory read model (projection) that tracks active workflow timers.
 * <p>
 * This projection subscribes to timer lifecycle events and maintains a
 * collection of all active (registered but not yet fired) timers across
 * all workflow instances. It is the primary data source for the
 * {@link TimerSchedulerService} when polling for due timers.
 * <p>
 * Timer entries are keyed by {@code instanceId:timerId} to ensure
 * uniqueness across instances. The projection supports concurrent access
 * from multiple threads via a {@link ConcurrentHashMap}.
 * <p>
 * <b>Event handlers:</b>
 * <ul>
 *   <li>{@link #onTimerRegistered} -- adds or replaces a timer entry</li>
 *   <li>{@link #onTimerFired} -- removes a timer entry</li>
 * </ul>
 *
 * @see TimerEntry
 * @see TimerSchedulerService
 */
@Slf4j
public class WorkflowTimerProjection {

    /**
     * Active timers keyed by {@code instanceId:timerId}.
     */
    private final ConcurrentHashMap<String, TimerEntry> activeTimers = new ConcurrentHashMap<>();

    /**
     * Handles a timer registration by adding or replacing the timer entry.
     * <p>
     * If a timer with the same instance ID and timer ID already exists,
     * it is replaced with the new entry (e.g., rescheduled timer).
     *
     * @param instanceId the workflow instance aggregate ID
     * @param timerId    the timer identifier
     * @param fireAt     the instant at which the timer should fire
     * @param data       additional data associated with the timer
     */
    public void onTimerRegistered(UUID instanceId, String timerId, Instant fireAt, Map<String, Object> data) {
        String key = buildKey(instanceId, timerId);
        TimerEntry entry = new TimerEntry(instanceId, timerId, fireAt, data);
        activeTimers.put(key, entry);

        log.debug("Timer registered in projection: instanceId={}, timerId={}, fireAt={}",
                instanceId, timerId, fireAt);
    }

    /**
     * Handles a timer firing by removing the timer entry from the projection.
     * <p>
     * If the timer does not exist in the projection (e.g., already removed
     * by another event), this is a no-op.
     *
     * @param instanceId the workflow instance aggregate ID
     * @param timerId    the timer identifier
     */
    public void onTimerFired(UUID instanceId, String timerId) {
        String key = buildKey(instanceId, timerId);
        TimerEntry removed = activeTimers.remove(key);

        if (removed != null) {
            log.debug("Timer removed from projection: instanceId={}, timerId={}", instanceId, timerId);
        } else {
            log.debug("Timer not found in projection (already removed): instanceId={}, timerId={}",
                    instanceId, timerId);
        }
    }

    /**
     * Finds all timers that are due (i.e., their fire time is at or before the given instant).
     * <p>
     * Results are sorted by {@code fireAt} in ascending order so that the oldest
     * due timers are processed first. The result set is limited to {@code batchSize}
     * entries to prevent unbounded processing in a single poll cycle.
     *
     * @param now       the current instant to compare against
     * @param batchSize the maximum number of due timers to return
     * @return a list of due timer entries, sorted by fire time ascending
     */
    public List<TimerEntry> findDueTimers(Instant now, int batchSize) {
        return activeTimers.values().stream()
                .filter(entry -> !entry.fireAt().isAfter(now))
                .sorted(Comparator.comparing(TimerEntry::fireAt))
                .limit(batchSize)
                .toList();
    }

    /**
     * Returns the total number of active timers across all workflow instances.
     *
     * @return the count of active timers
     */
    public int getActiveTimerCount() {
        return activeTimers.size();
    }

    /**
     * Returns all active timers for a specific workflow instance.
     *
     * @param instanceId the workflow instance aggregate ID
     * @return a list of timer entries for the given instance (may be empty)
     */
    public List<TimerEntry> getTimersForInstance(UUID instanceId) {
        return activeTimers.values().stream()
                .filter(entry -> entry.instanceId().equals(instanceId))
                .toList();
    }

    /**
     * Builds the composite key for the active timers map.
     *
     * @param instanceId the workflow instance aggregate ID
     * @param timerId    the timer identifier
     * @return the composite key in the form {@code instanceId:timerId}
     */
    private String buildKey(UUID instanceId, String timerId) {
        return instanceId + ":" + timerId;
    }
}
