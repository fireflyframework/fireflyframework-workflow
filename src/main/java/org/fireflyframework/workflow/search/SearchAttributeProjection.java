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

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory read model (projection) that tracks workflow search attributes.
 * <p>
 * This projection maintains a mapping from workflow instance IDs to their
 * search attributes, enabling fast lookup of workflow instances by
 * business-specific criteria. It is the primary data source for the
 * {@link WorkflowSearchService} when querying workflows by attributes.
 * <p>
 * Search attributes are key-value pairs that workflows can set during
 * execution to make themselves discoverable by external systems. For
 * example, a workflow might set {@code orderId=12345} so it can be
 * found later by order number.
 * <p>
 * The projection supports concurrent access from multiple threads via
 * a {@link ConcurrentHashMap}.
 * <p>
 * <b>Event handlers:</b>
 * <ul>
 *   <li>{@link #onSearchAttributeUpdated} -- upserts a single attribute</li>
 *   <li>{@link #onWorkflowRemoved} -- removes all attributes for an instance</li>
 * </ul>
 *
 * @see WorkflowSearchService
 */
@Slf4j
public class SearchAttributeProjection {

    /**
     * Search attributes keyed by instance ID, each containing a map of attribute key-value pairs.
     */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, Object>> instanceAttributes = new ConcurrentHashMap<>();

    /**
     * Handles a search attribute update by upserting the attribute value.
     * <p>
     * If the instance does not yet have any attributes, a new attribute map
     * is created. If the attribute key already exists, its value is replaced.
     *
     * @param instanceId the workflow instance aggregate ID
     * @param key        the search attribute key
     * @param value      the search attribute value
     */
    public void onSearchAttributeUpdated(UUID instanceId, String key, Object value) {
        instanceAttributes
                .computeIfAbsent(instanceId, id -> new ConcurrentHashMap<>())
                .put(key, value);

        log.debug("Search attribute updated in projection: instanceId={}, key={}, value={}",
                instanceId, key, value);
    }

    /**
     * Handles a workflow removal by removing all attributes for the instance.
     * <p>
     * This is used for cleanup when a workflow instance is no longer needed
     * in the projection (e.g., after completion or archival).
     *
     * @param instanceId the workflow instance aggregate ID
     */
    public void onWorkflowRemoved(UUID instanceId) {
        ConcurrentHashMap<String, Object> removed = instanceAttributes.remove(instanceId);

        if (removed != null) {
            log.debug("Workflow removed from search projection: instanceId={}, attributeCount={}",
                    instanceId, removed.size());
        } else {
            log.debug("Workflow not found in search projection (already removed): instanceId={}",
                    instanceId);
        }
    }

    /**
     * Finds all workflow instance IDs that have a specific attribute value.
     *
     * @param key   the search attribute key to match
     * @param value the search attribute value to match
     * @return a list of matching instance IDs (may be empty)
     */
    public List<UUID> findByAttribute(String key, Object value) {
        return instanceAttributes.entrySet().stream()
                .filter(entry -> value.equals(entry.getValue().get(key)))
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Finds all workflow instance IDs that match ALL given criteria.
     * <p>
     * This performs an AND query: an instance must have matching values
     * for every key-value pair in the criteria map to be included in
     * the results.
     * <p>
     * If the criteria map is empty, all tracked instances are returned.
     *
     * @param criteria a map of attribute key-value pairs that must all match
     * @return a list of matching instance IDs (may be empty)
     */
    public List<UUID> findByAttributes(Map<String, Object> criteria) {
        return instanceAttributes.entrySet().stream()
                .filter(entry -> {
                    Map<String, Object> attrs = entry.getValue();
                    return criteria.entrySet().stream()
                            .allMatch(criterion -> criterion.getValue().equals(attrs.get(criterion.getKey())));
                })
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Returns all search attributes for a specific workflow instance.
     *
     * @param instanceId the workflow instance aggregate ID
     * @return a map of attribute key-value pairs (empty map if instance not found)
     */
    public Map<String, Object> getAttributesForInstance(UUID instanceId) {
        ConcurrentHashMap<String, Object> attrs = instanceAttributes.get(instanceId);
        return attrs != null ? Map.copyOf(attrs) : Map.of();
    }

    /**
     * Returns the total number of workflow instances tracked by this projection.
     *
     * @return the count of tracked instances
     */
    public int getInstanceCount() {
        return instanceAttributes.size();
    }
}
