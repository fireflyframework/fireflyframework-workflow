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
 * Domain Event: A workflow execution has been started.
 * <p>
 * This event is recorded when a new workflow instance begins execution.
 * It captures the initial configuration including the workflow definition,
 * input parameters, and execution context.
 */
@DomainEvent("workflow.started")
@SuperBuilder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowStartedEvent extends AbstractDomainEvent {

    /**
     * The unique identifier of the workflow definition.
     */
    private String workflowId;

    /**
     * The human-readable name of the workflow.
     */
    private String workflowName;

    /**
     * The version of the workflow definition being executed.
     */
    private String workflowVersion;

    /**
     * The input parameters provided to the workflow.
     */
    private Map<String, Object> input;

    /**
     * The correlation ID for distributed tracing across systems.
     */
    private String correlationId;

    /**
     * The entity or system that triggered the workflow execution.
     */
    private String triggeredBy;

    /**
     * Whether this is a dry-run execution (no side effects).
     */
    private boolean dryRun;
}
