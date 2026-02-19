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
 * Domain Event: Compensation (saga rollback) has been started.
 * <p>
 * This event is recorded when a workflow begins executing compensation
 * logic to undo the effects of previously completed steps, typically
 * in response to a failure in a later step (saga pattern).
 */
@DomainEvent("workflow.compensation.started")
@SuperBuilder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CompensationStartedEvent extends AbstractDomainEvent {

    /**
     * The identifier of the step whose failure triggered compensation.
     */
    private String failedStepId;

    /**
     * The compensation policy being applied (e.g., "REVERSE_ORDER", "PARALLEL").
     */
    private String compensationPolicy;
}
