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
 * Domain Event: A workflow step has been skipped.
 * <p>
 * This event is recorded when a step within a workflow is skipped,
 * typically because a conditional guard evaluated to false or the
 * step is not applicable for the current execution path.
 */
@JsonTypeName("workflow.step.skipped")
@DomainEvent("workflow.step.skipped")
@SuperBuilder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class StepSkippedEvent extends AbstractDomainEvent {

    /**
     * The unique identifier of the step that was skipped.
     */
    private String stepId;

    /**
     * The reason the step was skipped.
     */
    private String reason;
}
