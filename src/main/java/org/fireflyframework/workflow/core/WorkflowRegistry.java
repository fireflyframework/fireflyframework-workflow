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

package org.fireflyframework.workflow.core;

import org.fireflyframework.workflow.model.WorkflowDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Registry for workflow definitions.
 * <p>
 * The WorkflowRegistry manages all registered workflow definitions and
 * provides lookup functionality by ID and trigger event type.
 */
@Slf4j
public class WorkflowRegistry {

    // Versioned storage: workflowId -> (version -> definition)
    private final Map<String, Map<String, WorkflowDefinition>> versionedWorkflows = new ConcurrentHashMap<>();
    // Latest version index: workflowId -> definition (backwards compatible)
    private final Map<String, WorkflowDefinition> workflows = new ConcurrentHashMap<>();
    private final Map<String, Pattern> triggerPatterns = new ConcurrentHashMap<>();

    /**
     * Registers a workflow definition.
     * <p>
     * This method validates the workflow topology (dependencies) before registration.
     * If validation fails, a {@link WorkflowValidationException} is thrown.
     *
     * @param workflow the workflow definition
     * @throws WorkflowValidationException if the workflow has invalid dependencies
     */
    public void register(WorkflowDefinition workflow) {
        String id = workflow.workflowId();
        String version = workflow.version();

        // Validate topology before registration
        validateTopology(workflow);

        if (workflows.containsKey(id)) {
            log.info("Registering new version of workflow: id={}, version={}", id, version);
        }

        // Store in versioned map
        versionedWorkflows.computeIfAbsent(id, k -> new ConcurrentHashMap<>()).put(version, workflow);

        // Update latest version index
        workflows.put(id, workflow);

        // Register trigger pattern if present
        if (workflow.triggerEventType() != null && !workflow.triggerEventType().isEmpty()) {
            triggerPatterns.put(id, compilePattern(workflow.triggerEventType()));
        }

        log.info("Registered workflow: id={}, name={}, version={}, triggerMode={}, steps={}",
                id, workflow.name(), version, workflow.triggerMode(),
                workflow.steps().size());
    }

    /**
     * Validates the workflow topology (step dependencies).
     *
     * @param workflow the workflow to validate
     * @throws WorkflowValidationException if validation fails
     */
    private void validateTopology(WorkflowDefinition workflow) {
        if (workflow.steps().isEmpty()) {
            return; // Empty workflow is valid
        }

        WorkflowTopology topology = new WorkflowTopology(workflow);
        topology.validate(); // Throws WorkflowValidationException if invalid

        log.debug("Workflow topology validated: id={}, steps={}",
                workflow.workflowId(), workflow.steps().size());
    }

    /**
     * Unregisters a workflow definition.
     *
     * @param workflowId the workflow ID
     * @return true if the workflow was removed
     */
    public boolean unregister(String workflowId) {
        WorkflowDefinition removed = workflows.remove(workflowId);
        triggerPatterns.remove(workflowId);
        // Keep versioned entries so running instances can still resolve their version
        // Only remove from the latest-version index

        if (removed != null) {
            log.info("Unregistered latest version of workflow: {} (version history retained)", workflowId);
            return true;
        }
        return false;
    }

    /**
     * Gets the latest workflow definition by ID.
     *
     * @param workflowId the workflow ID
     * @return optional containing the latest workflow version if found
     */
    public Optional<WorkflowDefinition> get(String workflowId) {
        return Optional.ofNullable(workflows.get(workflowId));
    }

    /**
     * Gets a specific version of a workflow definition.
     * <p>
     * This is important for running instances that must use the definition version
     * they were started with, not the latest registered version.
     *
     * @param workflowId the workflow ID
     * @param version the specific version string (e.g., "1.0.0")
     * @return optional containing the workflow for that version, or empty if not found
     */
    public Optional<WorkflowDefinition> get(String workflowId, String version) {
        if (version == null) {
            return get(workflowId);
        }
        Map<String, WorkflowDefinition> versions = versionedWorkflows.get(workflowId);
        if (versions == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(versions.get(version));
    }

    /**
     * Gets all registered workflow definitions.
     *
     * @return collection of all workflows
     */
    public Collection<WorkflowDefinition> getAll() {
        return Collections.unmodifiableCollection(workflows.values());
    }

    /**
     * Gets all workflow IDs.
     *
     * @return set of all workflow IDs
     */
    public Set<String> getWorkflowIds() {
        return Collections.unmodifiableSet(workflows.keySet());
    }

    /**
     * Checks if a workflow is registered.
     *
     * @param workflowId the workflow ID
     * @return true if registered
     */
    public boolean contains(String workflowId) {
        return workflows.containsKey(workflowId);
    }

    /**
     * Finds workflows that can be triggered by the given event type.
     *
     * @param eventType the event type
     * @return list of matching workflow definitions
     */
    public List<WorkflowDefinition> findByTriggerEvent(String eventType) {
        if (eventType == null || eventType.isEmpty()) {
            return List.of();
        }
        
        return workflows.entrySet().stream()
                .filter(entry -> {
                    WorkflowDefinition workflow = entry.getValue();
                    if (!workflow.supportsAsyncTrigger()) {
                        return false;
                    }
                    
                    Pattern pattern = triggerPatterns.get(entry.getKey());
                    if (pattern == null) {
                        return false;
                    }
                    
                    return pattern.matcher(eventType).matches();
                })
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
    }

    /**
     * Finds workflows that support sync triggering.
     *
     * @return list of sync-triggerable workflows
     */
    public List<WorkflowDefinition> findSyncTriggerable() {
        return workflows.values().stream()
                .filter(WorkflowDefinition::supportsSyncTrigger)
                .collect(Collectors.toList());
    }

    /**
     * Gets the count of registered workflows.
     *
     * @return the count
     */
    public int size() {
        return workflows.size();
    }

    /**
     * Clears all registered workflows.
     */
    public void clear() {
        workflows.clear();
        versionedWorkflows.clear();
        triggerPatterns.clear();
        log.info("Cleared all workflow registrations");
    }

    /**
     * Compiles a trigger pattern (with glob support) to a regex.
     */
    private Pattern compilePattern(String triggerPattern) {
        String regex = triggerPattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return Pattern.compile("^" + regex + "$");
    }
}
