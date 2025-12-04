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

package com.firefly.common.workflow.rest.dto;

import com.firefly.common.workflow.core.WorkflowTopology;
import com.firefly.common.workflow.model.StepExecution;
import com.firefly.common.workflow.model.WorkflowDefinition;
import com.firefly.common.workflow.model.WorkflowInstance;
import com.firefly.common.workflow.model.WorkflowStepDefinition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Response DTO for workflow topology visualization.
 * <p>
 * Provides the DAG (Directed Acyclic Graph) structure of a workflow
 * in a format compatible with frontend visualization libraries like
 * React Flow, Mermaid.js, or D3.js.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowTopologyResponse {

    /**
     * Workflow ID.
     */
    private String workflowId;

    /**
     * Workflow name.
     */
    private String workflowName;

    /**
     * Workflow version.
     */
    private String version;

    /**
     * Workflow description.
     */
    private String description;

    /**
     * List of nodes (steps) in the workflow.
     */
    private List<TopologyNode> nodes;

    /**
     * List of edges (dependencies) between steps.
     */
    private List<TopologyEdge> edges;

    /**
     * Metadata about the topology.
     */
    private TopologyMetadata metadata;

    /**
     * Creates a topology response from a workflow definition.
     *
     * @param definition the workflow definition
     * @return topology response
     */
    public static WorkflowTopologyResponse from(WorkflowDefinition definition) {
        return from(definition, null);
    }

    /**
     * Creates a topology response from a workflow definition and optional instance.
     * <p>
     * If an instance is provided, step statuses will be populated.
     *
     * @param definition the workflow definition
     * @param instance optional workflow instance for status info
     * @return topology response
     */
    public static WorkflowTopologyResponse from(WorkflowDefinition definition, WorkflowInstance instance) {
        WorkflowTopology topology = new WorkflowTopology(definition);
        
        // Build nodes from steps
        List<TopologyNode> nodes = definition.steps().stream()
                .map(step -> TopologyNode.from(step, instance))
                .collect(Collectors.toList());

        // Build edges from dependencies
        List<TopologyEdge> edges = new ArrayList<>();
        for (WorkflowStepDefinition step : definition.steps()) {
            if (step.hasDependencies()) {
                for (String depId : step.dependsOn()) {
                    edges.add(TopologyEdge.builder()
                            .id(depId + "->" + step.stepId())
                            .source(depId)
                            .target(step.stepId())
                            .build());
                }
            }
        }

        // If no explicit dependencies, create sequential edges based on order
        if (edges.isEmpty() && definition.steps().size() > 1) {
            List<WorkflowStepDefinition> sortedSteps = definition.steps().stream()
                    .sorted((a, b) -> Integer.compare(a.order(), b.order()))
                    .toList();
            
            for (int i = 0; i < sortedSteps.size() - 1; i++) {
                WorkflowStepDefinition current = sortedSteps.get(i);
                WorkflowStepDefinition next = sortedSteps.get(i + 1);
                edges.add(TopologyEdge.builder()
                        .id(current.stepId() + "->" + next.stepId())
                        .source(current.stepId())
                        .target(next.stepId())
                        .build());
            }
        }

        // Build metadata
        TopologyMetadata metadata = TopologyMetadata.builder()
                .totalSteps(definition.steps().size())
                .hasExplicitDependencies(definition.steps().stream()
                        .anyMatch(WorkflowStepDefinition::hasDependencies))
                .executionLayers(topology.buildExecutionLayers().size())
                .build();

        if (instance != null) {
            long completed = instance.stepExecutions().stream()
                    .filter(e -> e.status().isSuccessful())
                    .count();
            metadata.setCompletedSteps((int) completed);
            metadata.setInstanceId(instance.instanceId());
            metadata.setInstanceStatus(instance.status().name());
        }

        return WorkflowTopologyResponse.builder()
                .workflowId(definition.workflowId())
                .workflowName(definition.name())
                .version(definition.version())
                .description(definition.description())
                .nodes(nodes)
                .edges(edges)
                .metadata(metadata)
                .build();
    }

    /**
     * Represents a node (step) in the workflow topology.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopologyNode {
        /**
         * Unique step ID.
         */
        private String id;

        /**
         * Human-readable step name.
         */
        private String label;

        /**
         * Step description.
         */
        private String description;

        /**
         * Step type/category for visual styling.
         */
        private String type;

        /**
         * Current status of this step (if instance provided).
         */
        private String status;

        /**
         * Execution order.
         */
        private int order;

        /**
         * Whether this step runs asynchronously.
         */
        private boolean async;

        /**
         * Event type that triggers this step.
         */
        private String inputEventType;

        /**
         * Event type emitted on completion.
         */
        private String outputEventType;

        /**
         * Whether this is a root step (no dependencies).
         */
        private boolean isRoot;

        /**
         * Condition expression for conditional execution.
         */
        private String condition;

        /**
         * Additional data for visualization.
         */
        private Map<String, Object> data;

        /**
         * Creates a node from a step definition.
         */
        public static TopologyNode from(WorkflowStepDefinition step, WorkflowInstance instance) {
            String status = null;
            if (instance != null) {
                status = instance.getStepExecution(step.stepId())
                        .map(e -> e.status().name())
                        .orElse("PENDING");
            }

            String type = determineNodeType(step);

            return TopologyNode.builder()
                    .id(step.stepId())
                    .label(step.name())
                    .description(step.description())
                    .type(type)
                    .status(status)
                    .order(step.order())
                    .async(step.async())
                    .inputEventType(step.inputEventType())
                    .outputEventType(step.outputEventType())
                    .isRoot(step.isRootStep())
                    .condition(step.condition())
                    .build();
        }

        private static String determineNodeType(WorkflowStepDefinition step) {
            if (step.inputEventType() != null && !step.inputEventType().isEmpty()) {
                return "event-triggered";
            }
            if (step.async()) {
                return "async";
            }
            if (step.condition() != null && !step.condition().isEmpty()) {
                return "conditional";
            }
            return "default";
        }
    }

    /**
     * Represents an edge (dependency) between steps.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopologyEdge {
        /**
         * Unique edge ID.
         */
        private String id;

        /**
         * Source step ID (dependency).
         */
        private String source;

        /**
         * Target step ID (dependent step).
         */
        private String target;

        /**
         * Edge type for visual styling.
         */
        private String type;

        /**
         * Whether this edge is animated (e.g., for active paths).
         */
        private boolean animated;

        /**
         * Edge label.
         */
        private String label;
    }

    /**
     * Metadata about the workflow topology.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopologyMetadata {
        /**
         * Total number of steps.
         */
        private int totalSteps;

        /**
         * Number of completed steps (if instance provided).
         */
        private int completedSteps;

        /**
         * Whether the workflow has explicit step dependencies.
         */
        private boolean hasExplicitDependencies;

        /**
         * Number of execution layers (for parallel execution).
         */
        private int executionLayers;

        /**
         * Instance ID (if viewing instance topology).
         */
        private String instanceId;

        /**
         * Instance status (if viewing instance topology).
         */
        private String instanceStatus;
    }
}
