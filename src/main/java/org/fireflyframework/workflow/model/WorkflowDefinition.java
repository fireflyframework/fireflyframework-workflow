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

package org.fireflyframework.workflow.model;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;

/**
 * Defines a workflow with its steps and configuration.
 * <p>
 * A workflow definition is the blueprint for creating workflow instances.
 * It contains the ordered list of steps to execute and configuration
 * for triggering, timeouts, and retry behavior.
 *
 * @param workflowId Unique identifier for the workflow
 * @param name Human-readable name of the workflow
 * @param description Description of what the workflow does
 * @param version Version of the workflow definition
 * @param steps Ordered list of step definitions
 * @param triggerMode How the workflow can be triggered
 * @param triggerEventType Event type that can trigger this workflow (for ASYNC/BOTH modes)
 * @param timeout Maximum duration for the entire workflow
 * @param retryPolicy Default retry policy for steps
 * @param metadata Additional metadata for the workflow
 * @param workflowBean The bean instance containing the workflow logic (set by WorkflowAspect)
 * @param onStepCompleteMethod Method to call after each step completes
 * @param onWorkflowCompleteMethod Method to call after workflow completes
 * @param onWorkflowErrorMethod Method to call when workflow fails
 */
public record WorkflowDefinition(
        String workflowId,
        String name,
        String description,
        String version,
        List<WorkflowStepDefinition> steps,
        TriggerMode triggerMode,
        String triggerEventType,
        Duration timeout,
        RetryPolicy retryPolicy,
        Map<String, Object> metadata,
        Object workflowBean,
        Method onStepCompleteMethod,
        Method onWorkflowCompleteMethod,
        Method onWorkflowErrorMethod
) {

    public WorkflowDefinition {
        Objects.requireNonNull(workflowId, "workflowId cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        if (version == null) {
            version = "1.0.0";
        }
        if (steps == null) {
            steps = List.of();
        } else {
            steps = List.copyOf(steps);
        }
        if (triggerMode == null) {
            triggerMode = TriggerMode.BOTH;
        }
        if (timeout == null) {
            timeout = Duration.ofHours(1);
        }
        if (retryPolicy == null) {
            retryPolicy = RetryPolicy.DEFAULT;
        }
        if (metadata == null) {
            metadata = Map.of();
        } else {
            metadata = Map.copyOf(metadata);
        }
    }

    /**
     * Gets steps sorted by their order.
     *
     * @return ordered list of steps
     */
    public List<WorkflowStepDefinition> getOrderedSteps() {
        return steps.stream()
                .sorted(Comparator.comparingInt(WorkflowStepDefinition::order))
                .toList();
    }

    /**
     * Finds a step by its ID.
     *
     * @param stepId the step ID
     * @return optional containing the step if found
     */
    public Optional<WorkflowStepDefinition> findStep(String stepId) {
        return steps.stream()
                .filter(s -> s.stepId().equals(stepId))
                .findFirst();
    }

    /**
     * Gets a step by its ID.
     * <p>
     * Alias for {@link #findStep(String)} for convenience.
     *
     * @param stepId the step ID
     * @return optional containing the step if found
     */
    public Optional<WorkflowStepDefinition> getStep(String stepId) {
        return findStep(stepId);
    }

    /**
     * Gets the next step after the given step ID.
     *
     * @param currentStepId the current step ID
     * @return optional containing the next step if exists
     */
    public Optional<WorkflowStepDefinition> getNextStep(String currentStepId) {
        List<WorkflowStepDefinition> orderedSteps = getOrderedSteps();
        for (int i = 0; i < orderedSteps.size() - 1; i++) {
            if (orderedSteps.get(i).stepId().equals(currentStepId)) {
                return Optional.of(orderedSteps.get(i + 1));
            }
        }
        return Optional.empty();
    }

    /**
     * Gets the first step in the workflow.
     *
     * @return optional containing the first step if exists
     */
    public Optional<WorkflowStepDefinition> getFirstStep() {
        List<WorkflowStepDefinition> orderedSteps = getOrderedSteps();
        return orderedSteps.isEmpty() ? Optional.empty() : Optional.of(orderedSteps.get(0));
    }

    /**
     * Checks if this workflow supports sync triggering.
     *
     * @return true if can be triggered via REST API
     */
    public boolean supportsSyncTrigger() {
        return triggerMode == TriggerMode.SYNC || triggerMode == TriggerMode.BOTH;
    }

    /**
     * Checks if this workflow supports async triggering.
     *
     * @return true if can be triggered via events
     */
    public boolean supportsAsyncTrigger() {
        return triggerMode == TriggerMode.ASYNC || triggerMode == TriggerMode.BOTH;
    }

    /**
     * Builder for WorkflowDefinition.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String workflowId;
        private String name;
        private String description = "";
        private String version = "1.0.0";
        private List<WorkflowStepDefinition> steps = new ArrayList<>();
        private TriggerMode triggerMode = TriggerMode.BOTH;
        private String triggerEventType;
        private Duration timeout = Duration.ofHours(1);
        private RetryPolicy retryPolicy = RetryPolicy.DEFAULT;
        private Map<String, Object> metadata = new HashMap<>();
        private Object workflowBean;
        private Method onStepCompleteMethod;
        private Method onWorkflowCompleteMethod;
        private Method onWorkflowErrorMethod;

        public Builder workflowId(String workflowId) {
            this.workflowId = workflowId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder steps(List<WorkflowStepDefinition> steps) {
            this.steps = new ArrayList<>(steps);
            return this;
        }

        public Builder addStep(WorkflowStepDefinition step) {
            this.steps.add(step);
            return this;
        }

        public Builder triggerMode(TriggerMode triggerMode) {
            this.triggerMode = triggerMode;
            return this;
        }

        public Builder triggerEventType(String triggerEventType) {
            this.triggerEventType = triggerEventType;
            return this;
        }

        public Builder triggerEvent(String triggerEvent) {
            this.triggerEventType = triggerEvent;
            return this;
        }

        public Builder defaultTimeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder defaultRetryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = new HashMap<>(metadata);
            return this;
        }

        public Builder addMetadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder workflowBean(Object workflowBean) {
            this.workflowBean = workflowBean;
            return this;
        }

        public Builder onStepCompleteMethod(Method onStepCompleteMethod) {
            this.onStepCompleteMethod = onStepCompleteMethod;
            return this;
        }

        public Builder onWorkflowCompleteMethod(Method onWorkflowCompleteMethod) {
            this.onWorkflowCompleteMethod = onWorkflowCompleteMethod;
            return this;
        }

        public Builder onWorkflowErrorMethod(Method onWorkflowErrorMethod) {
            this.onWorkflowErrorMethod = onWorkflowErrorMethod;
            return this;
        }

        public WorkflowDefinition build() {
            return new WorkflowDefinition(
                    workflowId, name, description, version, steps,
                    triggerMode, triggerEventType, timeout, retryPolicy, metadata,
                    workflowBean, onStepCompleteMethod, onWorkflowCompleteMethod, onWorkflowErrorMethod
            );
        }
    }
}
