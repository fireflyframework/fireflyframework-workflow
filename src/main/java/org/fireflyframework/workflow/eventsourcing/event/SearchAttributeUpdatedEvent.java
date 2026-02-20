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
 * Domain Event: A search attribute has been updated on the workflow.
 * <p>
 * This event is recorded when a workflow updates a custom search attribute,
 * which enables querying and filtering workflow instances by business-specific
 * criteria beyond the standard workflow metadata.
 */
@JsonTypeName("workflow.search-attribute.updated")
@DomainEvent("workflow.search-attribute.updated")
@SuperBuilder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class SearchAttributeUpdatedEvent extends AbstractDomainEvent {

    /**
     * The key of the search attribute being updated.
     */
    private String key;

    /**
     * The new value for the search attribute.
     */
    private Object value;
}
