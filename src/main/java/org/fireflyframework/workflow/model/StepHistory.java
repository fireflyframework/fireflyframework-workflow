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

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Represents a historical record of a step execution within a workflow.
 * <p>
 * Used to track the execution history of steps in the WorkflowState,
 * providing visibility into which steps have run, their outcomes, and timing.
 *
 * @param stepId Unique identifier of the step
 * @param stepName Human-readable name of the step
 * @param status Final status of the step execution
 * @param triggeredBy How the step was triggered (e.g., "api", "event:order.created", "workflow")
 * @param startedAt When the step execution started
 * @param completedAt When the step execution completed
 * @param durationMs Duration of execution in milliseconds
 * @param errorMessage Error message if the step failed
 * @param attemptNumber Which retry attempt this was (1 = first attempt)
 */
public record StepHistory(
        String stepId,
        String stepName,
        StepStatus status,
        String triggeredBy,
        Instant startedAt,
        Instant completedAt,
        long durationMs,
        String errorMessage,
        int attemptNumber
) implements Serializable {

    public StepHistory {
        Objects.requireNonNull(stepId, "stepId cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
    }

    /**
     * Creates a StepHistory from a StepState.
     */
    public static StepHistory from(StepState state) {
        long duration = 0;
        if (state.startedAt() != null && state.completedAt() != null) {
            duration = java.time.Duration.between(state.startedAt(), state.completedAt()).toMillis();
        }
        
        return new StepHistory(
                state.stepId(),
                state.stepName(),
                state.status(),
                state.triggeredBy(),
                state.startedAt(),
                state.completedAt(),
                duration,
                state.errorMessage(),
                state.attemptNumber()
        );
    }

    /**
     * Creates a StepHistory from a StepExecution (for backward compatibility).
     */
    public static StepHistory from(StepExecution execution, String triggeredBy) {
        long duration = 0;
        if (execution.startedAt() != null && execution.completedAt() != null) {
            duration = java.time.Duration.between(execution.startedAt(), execution.completedAt()).toMillis();
        }
        
        return new StepHistory(
                execution.stepId(),
                execution.stepName(),
                execution.status(),
                triggeredBy,
                execution.startedAt(),
                execution.completedAt(),
                duration,
                execution.errorMessage(),
                execution.attemptNumber()
        );
    }

    /**
     * Builder for StepHistory.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String stepId;
        private String stepName;
        private StepStatus status;
        private String triggeredBy;
        private Instant startedAt;
        private Instant completedAt;
        private long durationMs;
        private String errorMessage;
        private int attemptNumber = 1;

        public Builder stepId(String stepId) {
            this.stepId = stepId;
            return this;
        }

        public Builder stepName(String stepName) {
            this.stepName = stepName;
            return this;
        }

        public Builder status(StepStatus status) {
            this.status = status;
            return this;
        }

        public Builder triggeredBy(String triggeredBy) {
            this.triggeredBy = triggeredBy;
            return this;
        }

        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder completedAt(Instant completedAt) {
            this.completedAt = completedAt;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder attemptNumber(int attemptNumber) {
            this.attemptNumber = attemptNumber;
            return this;
        }

        public StepHistory build() {
            return new StepHistory(
                    stepId, stepName, status, triggeredBy,
                    startedAt, completedAt, durationMs, errorMessage, attemptNumber
            );
        }
    }
}
