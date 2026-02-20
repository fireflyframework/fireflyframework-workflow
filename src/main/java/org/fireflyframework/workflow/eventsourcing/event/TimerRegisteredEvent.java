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

package org.fireflyframework.workflow.eventsourcing.event;

import com.fasterxml.jackson.annotation.JsonTypeName;
import org.fireflyframework.eventsourcing.annotation.DomainEvent;
import org.fireflyframework.eventsourcing.domain.AbstractDomainEvent;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.Map;

/**
 * Domain Event: A durable timer has been registered for the workflow.
 * <p>
 * This event is recorded when a workflow registers a timer that will fire
 * at a specified time. Durable timers survive process restarts and are
 * guaranteed to fire even after workflow replay.
 */
@JsonTypeName("workflow.timer.registered")
@DomainEvent("workflow.timer.registered")
@SuperBuilder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TimerRegisteredEvent extends AbstractDomainEvent {

    /**
     * The unique identifier of the timer.
     */
    private String timerId;

    /**
     * The instant at which the timer should fire.
     */
    private Instant fireAt;

    /**
     * Additional data associated with the timer.
     */
    private Map<String, Object> data;
}
