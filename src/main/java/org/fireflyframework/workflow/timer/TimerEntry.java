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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Represents an active timer entry in the read model.
 * <p>
 * Timer entries are maintained by the {@link WorkflowTimerProjection} and
 * represent timers that have been registered via {@code TimerRegisteredEvent}
 * but have not yet fired. The {@link TimerSchedulerService} polls for due
 * entries and fires them by loading the aggregate.
 *
 * @param instanceId the workflow instance aggregate ID
 * @param timerId    the timer identifier within the workflow instance
 * @param fireAt     the instant at which the timer should fire
 * @param data       additional data associated with the timer
 */
public record TimerEntry(
        UUID instanceId,
        String timerId,
        Instant fireAt,
        Map<String, Object> data
) {
}
