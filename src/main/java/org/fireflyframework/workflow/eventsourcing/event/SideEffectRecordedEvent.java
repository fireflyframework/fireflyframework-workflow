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

import org.fireflyframework.eventsourcing.annotation.DomainEvent;
import org.fireflyframework.eventsourcing.domain.AbstractDomainEvent;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Domain Event: A side effect result has been recorded.
 * <p>
 * This event is recorded when a non-deterministic operation (side effect)
 * is executed for the first time. During replay, the recorded value is
 * returned instead of re-executing the operation, ensuring deterministic
 * workflow behavior.
 */
@DomainEvent("workflow.side-effect.recorded")
@SuperBuilder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SideEffectRecordedEvent extends AbstractDomainEvent {

    /**
     * The unique identifier of the side effect within the workflow.
     */
    private String sideEffectId;

    /**
     * The recorded value produced by the side effect.
     */
    private Object value;
}
