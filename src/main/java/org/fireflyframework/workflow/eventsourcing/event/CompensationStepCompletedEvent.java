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
 * Domain Event: A compensation step has completed.
 * <p>
 * This event is recorded when an individual compensation step finishes
 * execution during saga rollback. It captures whether the compensation
 * was successful and any error that may have occurred.
 */
@DomainEvent("workflow.compensation.step.completed")
@SuperBuilder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class CompensationStepCompletedEvent extends AbstractDomainEvent {

    /**
     * The identifier of the step whose compensation has completed.
     */
    private String stepId;

    /**
     * Whether the compensation step completed successfully.
     */
    private boolean success;

    /**
     * The error message if the compensation step failed, null otherwise.
     */
    private String errorMessage;
}
