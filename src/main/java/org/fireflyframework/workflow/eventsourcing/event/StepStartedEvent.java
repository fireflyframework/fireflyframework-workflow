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

import java.util.Map;

/**
 * Domain Event: A workflow step has started execution.
 * <p>
 * This event is recorded when an individual step within a workflow begins
 * execution. It captures the step identity, input, and the current attempt
 * number for retry tracking.
 */
@DomainEvent("workflow.step.started")
@SuperBuilder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class StepStartedEvent extends AbstractDomainEvent {

    /**
     * The unique identifier of the step within the workflow.
     */
    private String stepId;

    /**
     * The human-readable name of the step.
     */
    private String stepName;

    /**
     * The input parameters provided to the step.
     */
    private Map<String, Object> input;

    /**
     * The current attempt number (1-based) for retry tracking.
     */
    private int attemptNumber;
}
