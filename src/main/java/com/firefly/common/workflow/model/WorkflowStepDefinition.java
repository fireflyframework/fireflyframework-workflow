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

package com.firefly.common.workflow.model;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Defines a step within a workflow.
 * <p>
 * A step represents a single unit of work within a workflow. Steps can be executed
 * based on explicit dependencies (via {@link #dependsOn()}) or by order for backward
 * compatibility. Steps can also be triggered via events or programmatically.
 *
 * @param stepId Unique identifier for the step within the workflow
 * @param name Human-readable name of the step
 * @param description Description of what the step does
 * @param dependsOn List of step IDs that must complete before this step executes
 * @param order Execution order (lower numbers execute first) - for backward compatibility
 * @param triggerMode How this step can be triggered (EVENT, PROGRAMMATIC, or BOTH)
 * @param handlerBeanName Spring bean name of the step handler
 * @param inputEventType Event type that can trigger this step (optional)
 * @param outputEventType Event type emitted on step completion (optional)
 * @param timeout Maximum duration for step execution
 * @param retryPolicy Retry configuration for this step
 * @param condition SpEL expression for conditional execution
 * @param async Whether the step should execute asynchronously
 * @param compensatable Whether this step supports compensation on failure
 */
public record WorkflowStepDefinition(
        String stepId,
        String name,
        String description,
        List<String> dependsOn,
        int order,
        StepTriggerMode triggerMode,
        String handlerBeanName,
        String inputEventType,
        String outputEventType,
        Duration timeout,
        RetryPolicy retryPolicy,
        String condition,
        boolean async,
        boolean compensatable
) {

    public WorkflowStepDefinition {
        Objects.requireNonNull(stepId, "stepId cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        if (dependsOn == null) {
            dependsOn = Collections.emptyList();
        } else {
            dependsOn = Collections.unmodifiableList(dependsOn);
        }
        if (triggerMode == null) {
            triggerMode = StepTriggerMode.BOTH;
        }
        if (timeout == null) {
            timeout = Duration.ofMinutes(5);
        }
        if (retryPolicy == null) {
            retryPolicy = RetryPolicy.DEFAULT;
        }
    }

    /**
     * Checks if this step has explicit dependencies.
     *
     * @return true if this step depends on other steps
     */
    public boolean hasDependencies() {
        return dependsOn != null && !dependsOn.isEmpty();
    }

    /**
     * Checks if this step is a root step (no dependencies).
     *
     * @return true if this step has no dependencies
     */
    public boolean isRootStep() {
        return !hasDependencies();
    }

    /**
     * Checks if this step can be triggered by events.
     *
     * @return true if event triggering is allowed
     */
    public boolean allowsEventTrigger() {
        return triggerMode != null && triggerMode.allowsEventTrigger();
    }

    /**
     * Checks if this step can be invoked programmatically.
     *
     * @return true if programmatic invocation is allowed
     */
    public boolean allowsProgrammaticTrigger() {
        return triggerMode != null && triggerMode.allowsProgrammaticTrigger();
    }

    /**
     * Builder for WorkflowStepDefinition.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String stepId;
        private String name;
        private String description = "";
        private List<String> dependsOn = Collections.emptyList();
        private int order = 0;
        private StepTriggerMode triggerMode = StepTriggerMode.BOTH;
        private String handlerBeanName;
        private String inputEventType;
        private String outputEventType;
        private Duration timeout = Duration.ofMinutes(5);
        private RetryPolicy retryPolicy = RetryPolicy.DEFAULT;
        private String condition = "";
        private boolean async = false;
        private boolean compensatable = false;

        public Builder stepId(String stepId) {
            this.stepId = stepId;
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

        public Builder dependsOn(List<String> dependsOn) {
            this.dependsOn = dependsOn != null ? List.copyOf(dependsOn) : Collections.emptyList();
            return this;
        }

        public Builder dependsOn(String... dependsOn) {
            this.dependsOn = dependsOn != null ? List.of(dependsOn) : Collections.emptyList();
            return this;
        }

        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public Builder triggerMode(StepTriggerMode triggerMode) {
            this.triggerMode = triggerMode;
            return this;
        }

        public Builder handlerBeanName(String handlerBeanName) {
            this.handlerBeanName = handlerBeanName;
            return this;
        }

        public Builder inputEventType(String inputEventType) {
            this.inputEventType = inputEventType;
            return this;
        }

        public Builder outputEventType(String outputEventType) {
            this.outputEventType = outputEventType;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = retryPolicy;
            return this;
        }

        public Builder condition(String condition) {
            this.condition = condition;
            return this;
        }

        public Builder async(boolean async) {
            this.async = async;
            return this;
        }

        public Builder compensatable(boolean compensatable) {
            this.compensatable = compensatable;
            return this;
        }

        public WorkflowStepDefinition build() {
            return new WorkflowStepDefinition(
                    stepId, name, description, dependsOn, order, triggerMode, handlerBeanName,
                    inputEventType, outputEventType, timeout, retryPolicy, condition, async, compensatable
            );
        }
    }
}
