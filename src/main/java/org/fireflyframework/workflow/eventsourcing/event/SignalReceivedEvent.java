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

import java.util.Map;

/**
 * Domain Event: An external signal has been received by the workflow.
 * <p>
 * This event is recorded when a workflow instance receives an external signal,
 * which can be used to communicate data or trigger state transitions from
 * outside the workflow execution.
 */
@JsonTypeName("workflow.signal.received")
@DomainEvent("workflow.signal.received")
@SuperBuilder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SignalReceivedEvent extends AbstractDomainEvent {

    /**
     * The name of the signal that was received.
     */
    private String signalName;

    /**
     * The payload data carried by the signal.
     */
    private Map<String, Object> payload;
}
