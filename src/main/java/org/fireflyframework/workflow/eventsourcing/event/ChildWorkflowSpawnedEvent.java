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
 * Domain Event: A child workflow has been spawned from a parent workflow.
 * <p>
 * This event is recorded when a workflow step spawns a child workflow
 * instance. The parent workflow may wait for the child to complete or
 * continue executing in parallel.
 */
@DomainEvent("workflow.child.spawned")
@SuperBuilder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChildWorkflowSpawnedEvent extends AbstractDomainEvent {

    /**
     * The unique instance identifier of the spawned child workflow.
     */
    private String childInstanceId;

    /**
     * The workflow definition identifier of the child workflow.
     */
    private String childWorkflowId;

    /**
     * The input parameters provided to the child workflow.
     */
    private Map<String, Object> input;

    /**
     * The identifier of the parent step that spawned the child workflow.
     */
    private String parentStepId;
}
