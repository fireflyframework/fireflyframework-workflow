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

/**
 * Domain Event: A durable timer has fired.
 * <p>
 * This event is recorded when a previously registered timer reaches its
 * fire time. During replay, this event ensures deterministic timer behavior.
 */
@JsonTypeName("workflow.timer.fired")
@DomainEvent("workflow.timer.fired")
@SuperBuilder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TimerFiredEvent extends AbstractDomainEvent {

    /**
     * The unique identifier of the timer that fired.
     */
    private String timerId;
}
