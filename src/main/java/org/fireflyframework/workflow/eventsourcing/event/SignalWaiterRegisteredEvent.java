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
 * Domain Event: A step has registered to wait for an external signal.
 * <p>
 * This event is recorded when a workflow step declares that it is waiting
 * for a signal with a specific name. When the signal is delivered, the
 * waiting step can be resumed.
 */
@JsonTypeName("workflow.signal.waiter.registered")
@DomainEvent("workflow.signal.waiter.registered")
@SuperBuilder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SignalWaiterRegisteredEvent extends AbstractDomainEvent {

    /**
     * The name of the signal this step is waiting for.
     */
    private String signalName;

    /**
     * The step that is waiting for the signal.
     */
    private String waitingStepId;
}
