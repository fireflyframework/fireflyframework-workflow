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
 * Domain Event: A workflow step has failed.
 * <p>
 * This event is recorded when an individual step within a workflow encounters
 * an error. It captures the error details, the current attempt number, and
 * whether the step can be retried.
 */
@JsonTypeName("workflow.step.failed")
@DomainEvent("workflow.step.failed")
@SuperBuilder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class StepFailedEvent extends AbstractDomainEvent {

    /**
     * The unique identifier of the step within the workflow.
     */
    private String stepId;

    /**
     * The error message describing the failure.
     */
    private String errorMessage;

    /**
     * The type or class name of the error that caused the failure.
     */
    private String errorType;

    /**
     * The attempt number when the failure occurred (1-based).
     */
    private int attemptNumber;

    /**
     * Whether the step failure is retryable.
     */
    private boolean retryable;
}
