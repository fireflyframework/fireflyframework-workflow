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

package org.fireflyframework.workflow.search;

import org.fireflyframework.workflow.eventsourcing.store.EventSourcedWorkflowStateStore;
import org.fireflyframework.workflow.model.WorkflowInstance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for querying workflow instances by their search attributes.
 * <p>
 * Combines the in-memory {@link SearchAttributeProjection} for fast lookups
 * with the {@link EventSourcedWorkflowStateStore} for loading full workflow
 * instances from the event store.
 * <p>
 * Search attributes allow workflows to be indexed by business-specific
 * criteria (e.g., order ID, customer ID, region) enabling efficient
 * discovery without scanning all workflow event streams.
 * <p>
 * <b>Read path:</b> The projection provides fast instance ID lookups;
 * matched instances are then loaded from the event store for full details.
 * <p>
 * <b>Write path:</b> Attribute updates are applied to both the aggregate
 * (for durability via events) and the projection (for immediate queryability).
 *
 * @see SearchAttributeProjection
 * @see EventSourcedWorkflowStateStore
 */
@Slf4j
@RequiredArgsConstructor
public class WorkflowSearchService {

    private final SearchAttributeProjection projection;
    private final EventSourcedWorkflowStateStore stateStore;

    /**
     * Searches for workflow instances matching a specific search attribute.
     * <p>
     * Queries the projection for matching instance IDs, then loads each
     * matching aggregate from the event store and converts it to a
     * {@link WorkflowInstance}. Instances that cannot be loaded (e.g.,
     * due to concurrent deletion) are silently skipped.
     *
     * @param key   the search attribute key to match
     * @param value the search attribute value to match
     * @return a Flux of matching workflow instances
     */
    public Flux<WorkflowInstance> searchByAttribute(String key, Object value) {
        log.info("Searching workflows by attribute: key={}, value={}", key, value);

        List<UUID> matchingIds = projection.findByAttribute(key, value);

        if (matchingIds.isEmpty()) {
            log.debug("No matching instances found for key={}, value={}", key, value);
            return Flux.empty();
        }

        log.debug("Found {} matching instance IDs for key={}, value={}",
                matchingIds.size(), key, value);

        return Flux.fromIterable(matchingIds)
                .flatMap(this::loadWorkflowInstance);
    }

    /**
     * Searches for workflow instances matching ALL given search attribute criteria.
     * <p>
     * Performs an AND query: only instances that match every key-value pair
     * in the criteria map are returned.
     *
     * @param criteria a map of attribute key-value pairs that must all match
     * @return a Flux of matching workflow instances
     */
    public Flux<WorkflowInstance> searchByAttributes(Map<String, Object> criteria) {
        log.info("Searching workflows by attributes: criteria={}", criteria);

        List<UUID> matchingIds = projection.findByAttributes(criteria);

        if (matchingIds.isEmpty()) {
            log.debug("No matching instances found for criteria={}", criteria);
            return Flux.empty();
        }

        log.debug("Found {} matching instance IDs for criteria={}", matchingIds.size(), criteria);

        return Flux.fromIterable(matchingIds)
                .flatMap(this::loadWorkflowInstance);
    }

    /**
     * Updates a search attribute on both the aggregate and the projection.
     * <p>
     * The update is applied to the aggregate first (which generates a
     * {@code SearchAttributeUpdatedEvent}) and then to the projection
     * for immediate queryability. If the aggregate save fails, the
     * projection is not updated.
     *
     * @param instanceId the workflow instance identifier
     * @param key        the search attribute key
     * @param value      the search attribute value
     * @return a Mono that completes when the update is persisted
     */
    public Mono<Void> updateSearchAttribute(UUID instanceId, String key, Object value) {
        log.info("Updating search attribute: instanceId={}, key={}, value={}", instanceId, key, value);

        return stateStore.loadAggregate(instanceId)
                .flatMap(aggregate -> {
                    aggregate.upsertSearchAttribute(key, value);
                    return stateStore.saveAggregate(aggregate);
                })
                .doOnSuccess(aggregate -> {
                    projection.onSearchAttributeUpdated(instanceId, key, value);
                    log.debug("Search attribute updated on aggregate and projection: instanceId={}, key={}",
                            instanceId, key);
                })
                .then();
    }

    /**
     * Gets the search attributes for a specific workflow instance from the aggregate.
     * <p>
     * Loads the aggregate from the event store to get the authoritative
     * attribute values. Returns an empty map if the aggregate is not found.
     *
     * @param instanceId the workflow instance identifier
     * @return a Mono emitting the search attribute map
     */
    public Mono<Map<String, Object>> getSearchAttributes(UUID instanceId) {
        log.debug("Getting search attributes for instance: {}", instanceId);

        return stateStore.loadAggregate(instanceId)
                .map(aggregate -> Map.copyOf(aggregate.getSearchAttributes()))
                .defaultIfEmpty(Map.of());
    }

    /**
     * Loads a workflow instance from the event store by aggregate ID.
     * <p>
     * Returns an empty Mono if the aggregate cannot be loaded, allowing
     * the caller to skip missing instances gracefully.
     *
     * @param instanceId the aggregate ID to load
     * @return a Mono emitting the workflow instance, or empty
     */
    private Mono<WorkflowInstance> loadWorkflowInstance(UUID instanceId) {
        return stateStore.loadAggregate(instanceId)
                .map(stateStore::toWorkflowInstance)
                .doOnError(error -> log.warn("Failed to load workflow instance: instanceId={}, error={}",
                        instanceId, error.getMessage()))
                .onErrorResume(error -> Mono.empty());
    }
}
