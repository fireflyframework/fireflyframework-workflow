/*
 * Copyright 2025 Firefly Software Solutions Inc
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

package com.firefly.common.workflow.core;

import com.firefly.common.workflow.model.WorkflowDefinition;
import com.firefly.common.workflow.model.WorkflowStepDefinition;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages the topology (dependency graph) of workflow steps.
 * <p>
 * This class builds and validates the directed acyclic graph (DAG) of step
 * dependencies, and computes execution layers using Kahn's algorithm for
 * topological sorting.
 * <p>
 * <b>Execution Layers:</b>
 * Steps are organized into layers where:
 * <ul>
 *   <li>Layer 0 contains root steps (no dependencies)</li>
 *   <li>Each subsequent layer contains steps whose dependencies are all in previous layers</li>
 *   <li>Steps within the same layer can execute in parallel</li>
 * </ul>
 * <p>
 * <b>Backward Compatibility:</b>
 * If no steps have explicit dependencies, falls back to order-based execution.
 */
@Slf4j
public class WorkflowTopology {

    private final WorkflowDefinition workflow;
    private final Map<String, WorkflowStepDefinition> stepMap;
    private final Map<String, Set<String>> dependencyGraph;
    private final Map<String, Set<String>> reverseDependencyGraph;
    private List<List<WorkflowStepDefinition>> executionLayers;
    private boolean validated = false;

    /**
     * Creates a new topology for the given workflow.
     *
     * @param workflow the workflow definition
     */
    public WorkflowTopology(WorkflowDefinition workflow) {
        this.workflow = Objects.requireNonNull(workflow, "workflow cannot be null");
        this.stepMap = new HashMap<>();
        this.dependencyGraph = new HashMap<>();
        this.reverseDependencyGraph = new HashMap<>();

        buildGraph();
    }

    private void buildGraph() {
        // Build step map and initialize graphs
        for (WorkflowStepDefinition step : workflow.steps()) {
            stepMap.put(step.stepId(), step);
            dependencyGraph.put(step.stepId(), new HashSet<>());
            reverseDependencyGraph.put(step.stepId(), new HashSet<>());
        }

        // Populate dependency edges
        for (WorkflowStepDefinition step : workflow.steps()) {
            if (step.hasDependencies()) {
                for (String depId : step.dependsOn()) {
                    dependencyGraph.get(step.stepId()).add(depId);
                    if (reverseDependencyGraph.containsKey(depId)) {
                        reverseDependencyGraph.get(depId).add(step.stepId());
                    }
                }
            }
        }
    }

    /**
     * Validates the workflow topology.
     *
     * @throws WorkflowValidationException if validation fails
     */
    public void validate() throws WorkflowValidationException {
        if (validated) {
            return;
        }

        validateDependenciesExist();
        validateNoCycles();
        validated = true;
    }

    private void validateDependenciesExist() throws WorkflowValidationException {
        for (WorkflowStepDefinition step : workflow.steps()) {
            for (String depId : step.dependsOn()) {
                if (!stepMap.containsKey(depId)) {
                    throw new WorkflowValidationException(
                            String.format("Step '%s' depends on non-existent step '%s' in workflow '%s'",
                                    step.stepId(), depId, workflow.workflowId()));
                }
            }
        }
    }

    private void validateNoCycles() throws WorkflowValidationException {
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();

        for (String stepId : stepMap.keySet()) {
            if (hasCycle(stepId, visited, recursionStack, new ArrayList<>())) {
                throw new WorkflowValidationException(
                        String.format("Circular dependency detected in workflow '%s'", workflow.workflowId()));
            }
        }
    }

    private boolean hasCycle(String stepId, Set<String> visited, Set<String> stack, List<String> path) {
        if (stack.contains(stepId)) {
            path.add(stepId);
            log.error("Circular dependency detected: {}", String.join(" -> ", path));
            return true;
        }
        if (visited.contains(stepId)) {
            return false;
        }

        visited.add(stepId);
        stack.add(stepId);
        path.add(stepId);

        for (String depId : dependencyGraph.getOrDefault(stepId, Collections.emptySet())) {
            if (hasCycle(depId, visited, stack, new ArrayList<>(path))) {
                return true;
            }
        }

        stack.remove(stepId);
        return false;
    }

    /**
     * Builds execution layers using Kahn's algorithm (topological sort).
     * <p>
     * Steps are organized into layers where each layer contains steps
     * whose dependencies are all satisfied by previous layers.
     *
     * @return list of execution layers, each containing steps that can run in parallel
     */
    public List<List<WorkflowStepDefinition>> buildExecutionLayers() {
        if (executionLayers != null) {
            return executionLayers;
        }

        validate();

        // Check if any step has dependencies
        boolean hasDependencies = workflow.steps().stream()
                .anyMatch(WorkflowStepDefinition::hasDependencies);

        if (!hasDependencies) {
            // Fall back to order-based execution
            executionLayers = buildOrderBasedLayers();
            log.debug("Using order-based execution for workflow '{}' ({} layers)",
                    workflow.workflowId(), executionLayers.size());
            return executionLayers;
        }

        // Use Kahn's algorithm for dependency-based execution
        executionLayers = buildDependencyBasedLayers();
        log.debug("Using dependency-based execution for workflow '{}' ({} layers)",
                workflow.workflowId(), executionLayers.size());
        return executionLayers;
    }

    private List<List<WorkflowStepDefinition>> buildOrderBasedLayers() {
        // Group steps by order, each order becomes a layer
        Map<Integer, List<WorkflowStepDefinition>> orderGroups = workflow.steps().stream()
                .collect(Collectors.groupingBy(WorkflowStepDefinition::order));

        return orderGroups.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
    }

    private List<List<WorkflowStepDefinition>> buildDependencyBasedLayers() {
        List<List<WorkflowStepDefinition>> layers = new ArrayList<>();
        Map<String, Integer> inDegree = new HashMap<>();

        // Calculate in-degree for each step
        for (String stepId : stepMap.keySet()) {
            inDegree.put(stepId, dependencyGraph.get(stepId).size());
        }

        Set<String> processed = new HashSet<>();

        while (processed.size() < stepMap.size()) {
            // Find all steps with in-degree 0 (no unprocessed dependencies)
            List<WorkflowStepDefinition> currentLayer = new ArrayList<>();

            for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
                if (entry.getValue() == 0 && !processed.contains(entry.getKey())) {
                    currentLayer.add(stepMap.get(entry.getKey()));
                }
            }

            if (currentLayer.isEmpty()) {
                // This shouldn't happen if validation passed
                throw new IllegalStateException("Unable to build execution layers - possible cycle");
            }

            // Sort layer by order for deterministic execution within layer
            currentLayer.sort(Comparator.comparingInt(WorkflowStepDefinition::order));
            layers.add(currentLayer);

            // Mark as processed and update in-degrees
            for (WorkflowStepDefinition step : currentLayer) {
                processed.add(step.stepId());
                for (String dependentId : reverseDependencyGraph.getOrDefault(step.stepId(), Collections.emptySet())) {
                    inDegree.put(dependentId, inDegree.get(dependentId) - 1);
                }
            }
        }

        return layers;
    }

    /**
     * Gets the steps that depend on the given step.
     *
     * @param stepId the step ID
     * @return set of dependent step IDs
     */
    public Set<String> getDependents(String stepId) {
        return Collections.unmodifiableSet(
                reverseDependencyGraph.getOrDefault(stepId, Collections.emptySet()));
    }

    /**
     * Gets the dependencies of the given step.
     *
     * @param stepId the step ID
     * @return set of dependency step IDs
     */
    public Set<String> getDependencies(String stepId) {
        return Collections.unmodifiableSet(
                dependencyGraph.getOrDefault(stepId, Collections.emptySet()));
    }

    /**
     * Gets root steps (steps with no dependencies).
     *
     * @return list of root steps
     */
    public List<WorkflowStepDefinition> getRootSteps() {
        return workflow.steps().stream()
                .filter(WorkflowStepDefinition::isRootStep)
                .sorted(Comparator.comparingInt(WorkflowStepDefinition::order))
                .collect(Collectors.toList());
    }

    /**
     * Checks if all dependencies of a step are satisfied.
     *
     * @param stepId the step ID
     * @param completedSteps set of completed step IDs
     * @return true if all dependencies are satisfied
     */
    public boolean areDependenciesSatisfied(String stepId, Set<String> completedSteps) {
        Set<String> deps = dependencyGraph.getOrDefault(stepId, Collections.emptySet());
        return completedSteps.containsAll(deps);
    }

    /**
     * Gets the workflow definition.
     *
     * @return the workflow definition
     */
    public WorkflowDefinition getWorkflow() {
        return workflow;
    }

    /**
     * Gets a step by ID.
     *
     * @param stepId the step ID
     * @return optional containing the step if found
     */
    public Optional<WorkflowStepDefinition> getStep(String stepId) {
        return Optional.ofNullable(stepMap.get(stepId));
    }
}