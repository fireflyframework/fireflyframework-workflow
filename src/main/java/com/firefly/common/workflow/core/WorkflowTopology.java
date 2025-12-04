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

