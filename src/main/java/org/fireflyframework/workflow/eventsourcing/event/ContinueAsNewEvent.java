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
 * Domain Event: A workflow is continuing as a new execution.
 * <p>
 * This event is recorded when a workflow completes its current run and
 * immediately starts a new execution with fresh event history. This is
 * used to prevent unbounded event history growth in long-running workflows.
 */
@DomainEvent("workflow.continue-as-new")
@SuperBuilder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ContinueAsNewEvent extends AbstractDomainEvent {

    /**
     * The input parameters for the new workflow execution.
     */
    private Map<String, Object> newInput;

    /**
     * The output produced by the completed run.
     */
    private Object completedRunOutput;

    /**
     * The run identifier of the previous execution.
     */
    private String previousRunId;
}
