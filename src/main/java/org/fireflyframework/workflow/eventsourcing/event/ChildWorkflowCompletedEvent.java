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
 * Domain Event: A child workflow has completed execution.
 * <p>
 * This event is recorded in the parent workflow when a previously spawned
 * child workflow finishes. It captures the child's output and whether
 * it completed successfully or with an error.
 */
@DomainEvent("workflow.child.completed")
@SuperBuilder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChildWorkflowCompletedEvent extends AbstractDomainEvent {

    /**
     * The unique instance identifier of the child workflow that completed.
     */
    private String childInstanceId;

    /**
     * The output produced by the child workflow.
     */
    private Object output;

    /**
     * Whether the child workflow completed successfully.
     */
    private boolean success;
}
