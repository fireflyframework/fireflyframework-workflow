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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Represents the persisted state of an individual workflow step.
 * <p>
 * Each step in a workflow instance has its own independent state entry in the cache,
 * allowing for step-level choreography and event-driven execution.
 * <p>
 * Cache key pattern: {@code workflow:step:{workflowId}:{instanceId}:{stepId}}
 *
 * @param stepId Unique identifier of the step within the workflow
 * @param stepName Human-readable name of the step
 * @param workflowId ID of the parent workflow
 * @param instanceId ID of the workflow instance this step belongs to
 * @param status Current execution status of the step
 * @param waitingForEvent Event type this step is waiting for (when status is WAITING)
 * @param triggeredBy How this step was triggered (e.g., "api", "event:order.created", "workflow")
 * @param input Input data provided to this step
 * @param output Output data produced by this step
 * @param errorMessage Error message if step failed
 * @param errorType Error type/class if step failed
 * @param attemptNumber Current retry attempt number (starts at 1)
 * @param maxRetries Maximum number of retry attempts allowed
 * @param correlationId Correlation ID for tracing
 * @param createdAt When the step state was created
 * @param startedAt When step execution started
 * @param completedAt When step execution completed
 */
public record StepState(
        String stepId,
        String stepName,
        String workflowId,
        String instanceId,
        StepStatus status,
        String waitingForEvent,
        String triggeredBy,
        Map<String, Object> input,
        Object output,
        String errorMessage,
        String errorType,
        int attemptNumber,
        int maxRetries,
        String correlationId,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) implements Serializable {

    public StepState {
        Objects.requireNonNull(stepId, "stepId cannot be null");
        Objects.requireNonNull(workflowId, "workflowId cannot be null");
        Objects.requireNonNull(instanceId, "instanceId cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        if (input == null) {
            input = Map.of();
        }
        if (attemptNumber < 1) {
            attemptNumber = 1;
        }
    }

    /**
     * Creates a new pending step state.
     */
    public static StepState create(
            String stepId,
            String stepName,
            String workflowId,
            String instanceId,
            String correlationId,
            int maxRetries) {
        return new StepState(
                stepId,
                stepName,
                workflowId,
                instanceId,
                StepStatus.PENDING,
                null,
                null,
                Map.of(),
                null,
                null,
                null,
                1,
                maxRetries,
                correlationId,
                Instant.now(),
                null,
                null
        );
    }

    /**
     * Creates a copy with RUNNING status.
     */
    public StepState start(String triggeredBy, Map<String, Object> stepInput) {
        return new StepState(
                stepId, stepName, workflowId, instanceId,
                StepStatus.RUNNING,
                null,
                triggeredBy,
                stepInput != null ? stepInput : input,
                null,
                null, null,
                attemptNumber, maxRetries,
                correlationId,
                createdAt,
                Instant.now(),
                null
        );
    }

    /**
     * Creates a copy with COMPLETED status.
     */
    public StepState complete(Object result) {
        return new StepState(
                stepId, stepName, workflowId, instanceId,
                StepStatus.COMPLETED,
                null,
                triggeredBy,
                input,
                result,
                null, null,
                attemptNumber, maxRetries,
                correlationId,
                createdAt,
                startedAt,
                Instant.now()
        );
    }

    /**
     * Creates a copy with FAILED status.
     */
    public StepState fail(Throwable error) {
        return new StepState(
                stepId, stepName, workflowId, instanceId,
                StepStatus.FAILED,
                null,
                triggeredBy,
                input,
                output,
                error.getMessage(),
                error.getClass().getName(),
                attemptNumber, maxRetries,
                correlationId,
                createdAt,
                startedAt,
                Instant.now()
        );
    }

    /**
     * Creates a copy with FAILED status.
     */
    public StepState fail(String errorMessage, String errorType) {
        return new StepState(
                stepId, stepName, workflowId, instanceId,
                StepStatus.FAILED,
                null,
                triggeredBy,
                input,
                output,
                errorMessage,
                errorType,
                attemptNumber, maxRetries,
                correlationId,
                createdAt,
                startedAt,
                Instant.now()
        );
    }

    /**
     * Creates a copy with WAITING status, waiting for specified event.
     */
    public StepState waitForEvent(String eventType) {
        return new StepState(
                stepId, stepName, workflowId, instanceId,
                StepStatus.WAITING,
                eventType,
                triggeredBy,
                input,
                output,
                null, null,
                attemptNumber, maxRetries,
                correlationId,
                createdAt,
                startedAt,
                null
        );
    }

    /**
     * Creates a copy with SKIPPED status.
     */
    public StepState skip(String reason) {
        return new StepState(
                stepId, stepName, workflowId, instanceId,
                StepStatus.SKIPPED,
                null,
                triggeredBy,
                input,
                null,
                reason, null,
                attemptNumber, maxRetries,
                correlationId,
                createdAt,
                startedAt,
                Instant.now()
        );
    }

    /**
     * Creates a copy with RETRYING status and incremented attempt number.
     */
    public StepState retry() {
        return new StepState(
                stepId, stepName, workflowId, instanceId,
                StepStatus.RETRYING,
                null,
                triggeredBy,
                input,
                null,
                null, null,
                attemptNumber + 1, maxRetries,
                correlationId,
                createdAt,
                null,
                null
        );
    }

    /**
     * Checks if the step can be retried.
     */
    public boolean canRetry() {
        return attemptNumber < maxRetries && 
               (status == StepStatus.FAILED || status == StepStatus.RETRYING);
    }

    /**
     * Gets the execution duration.
     */
    public Duration getDuration() {
        if (startedAt == null) {
            return Duration.ZERO;
        }
        Instant end = completedAt != null ? completedAt : Instant.now();
        return Duration.between(startedAt, end);
    }

    /**
     * Checks if the step is in a terminal state.
     */
    public boolean isTerminal() {
        return status.isTerminal();
    }

    /**
     * Checks if the step completed successfully.
     */
    public boolean isSuccessful() {
        return status.isSuccessful();
    }

    /**
     * Builder for StepState.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String stepId;
        private String stepName;
        private String workflowId;
        private String instanceId;
        private StepStatus status = StepStatus.PENDING;
        private String waitingForEvent;
        private String triggeredBy;
        private Map<String, Object> input = Map.of();
        private Object output;
        private String errorMessage;
        private String errorType;
        private int attemptNumber = 1;
        private int maxRetries = 3;
        private String correlationId;
        private Instant createdAt = Instant.now();
        private Instant startedAt;
        private Instant completedAt;

        public Builder stepId(String stepId) {
            this.stepId = stepId;
            return this;
        }

        public Builder stepName(String stepName) {
            this.stepName = stepName;
            return this;
        }

        public Builder workflowId(String workflowId) {
            this.workflowId = workflowId;
            return this;
        }

        public Builder instanceId(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        public Builder status(StepStatus status) {
            this.status = status;
            return this;
        }

        public Builder waitingForEvent(String waitingForEvent) {
            this.waitingForEvent = waitingForEvent;
            return this;
        }

        public Builder triggeredBy(String triggeredBy) {
            this.triggeredBy = triggeredBy;
            return this;
        }

        public Builder input(Map<String, Object> input) {
            this.input = input;
            return this;
        }

        public Builder output(Object output) {
            this.output = output;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder errorType(String errorType) {
            this.errorType = errorType;
            return this;
        }

        public Builder attemptNumber(int attemptNumber) {
            this.attemptNumber = attemptNumber;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
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

        public StepState build() {
            return new StepState(
                    stepId, stepName, workflowId, instanceId, status,
                    waitingForEvent, triggeredBy, input, output,
                    errorMessage, errorType, attemptNumber, maxRetries,
                    correlationId, createdAt, startedAt, completedAt
            );
        }
    }
}
