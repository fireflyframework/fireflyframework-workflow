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
import java.util.Objects;
import java.util.UUID;

/**
 * Represents a single execution of a workflow step.
 * <p>
 * Contains the runtime state of a step including its status, input/output data,
 * timing information, and retry state.
 */
public record StepExecution(
        String executionId,
        String stepId,
        String stepName,
        StepStatus status,
        Object input,
        Object output,
        String errorMessage,
        String errorType,
        int attemptNumber,
        Instant startedAt,
        Instant completedAt
) implements Serializable {

    public StepExecution {
        Objects.requireNonNull(executionId, "executionId cannot be null");
        Objects.requireNonNull(stepId, "stepId cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
    }

    /**
     * Creates a new pending step execution.
     *
     * @param stepId the step ID
     * @param stepName the step name
     * @param input the input data
     * @return new step execution
     */
    public static StepExecution create(String stepId, String stepName, Object input) {
        return create(stepId, stepName, input, 1);
    }

    /**
     * Creates a new pending step execution with a specific attempt number.
     *
     * @param stepId the step ID
     * @param stepName the step name
     * @param input the input data
     * @param attemptNumber the attempt number (1-based)
     * @return new step execution
     */
    public static StepExecution create(String stepId, String stepName, Object input, int attemptNumber) {
        return new StepExecution(
                UUID.randomUUID().toString(),
                stepId,
                stepName,
                StepStatus.PENDING,
                input,
                null,
                null,
                null,
                attemptNumber,
                null,
                null
        );
    }

    /**
     * Creates a copy with running status.
     *
     * @return updated step execution
     */
    public StepExecution start() {
        return new StepExecution(
                executionId, stepId, stepName, StepStatus.RUNNING,
                input, output, errorMessage, errorType, attemptNumber,
                Instant.now(), null
        );
    }

    /**
     * Creates a copy with completed status.
     *
     * @param result the step output
     * @return updated step execution
     */
    public StepExecution complete(Object result) {
        return new StepExecution(
                executionId, stepId, stepName, StepStatus.COMPLETED,
                input, result, null, null, attemptNumber,
                startedAt, Instant.now()
        );
    }

    /**
     * Creates a copy with failed status.
     *
     * @param error the error that caused the failure
     * @return updated step execution
     */
    public StepExecution fail(Throwable error) {
        return new StepExecution(
                executionId, stepId, stepName, StepStatus.FAILED,
                input, output, error.getMessage(), error.getClass().getName(), attemptNumber,
                startedAt, Instant.now()
        );
    }

    /**
     * Creates a copy with failed status.
     *
     * @param errorMessage the error message
     * @param errorType the error type
     * @return updated step execution
     */
    public StepExecution fail(String errorMessage, String errorType) {
        return new StepExecution(
                executionId, stepId, stepName, StepStatus.FAILED,
                input, output, errorMessage, errorType, attemptNumber,
                startedAt, Instant.now()
        );
    }

    /**
     * Creates a copy with retrying status for next attempt.
     *
     * @return updated step execution
     */
    public StepExecution retry() {
        return new StepExecution(
                executionId, stepId, stepName, StepStatus.RETRYING,
                input, null, null, null, attemptNumber + 1,
                null, null
        );
    }

    /**
     * Creates a copy with skipped status.
     *
     * @return updated step execution
     */
    public StepExecution skip() {
        return new StepExecution(
                executionId, stepId, stepName, StepStatus.SKIPPED,
                input, null, null, null, attemptNumber,
                startedAt != null ? startedAt : Instant.now(), Instant.now()
        );
    }

    /**
     * Creates a copy with timed out status.
     *
     * @return updated step execution
     */
    public StepExecution timeout() {
        return new StepExecution(
                executionId, stepId, stepName, StepStatus.TIMED_OUT,
                input, output, "Step execution timed out", "TimeoutException", attemptNumber,
                startedAt, Instant.now()
        );
    }

    /**
     * Creates a copy with waiting status.
     *
     * @return updated step execution
     */
    public StepExecution waiting() {
        return new StepExecution(
                executionId, stepId, stepName, StepStatus.WAITING,
                input, output, errorMessage, errorType, attemptNumber,
                startedAt, null
        );
    }

    /**
     * Gets the duration of the step execution.
     *
     * @return duration or null if not completed
     */
    public Duration getDuration() {
        if (startedAt == null) {
            return null;
        }
        Instant end = completedAt != null ? completedAt : Instant.now();
        return Duration.between(startedAt, end);
    }

    /**
     * Checks if the step has failed and can be retried.
     *
     * @param retryPolicy the retry policy to check against
     * @return true if can retry
     */
    public boolean canRetry(RetryPolicy retryPolicy) {
        return status == StepStatus.FAILED && retryPolicy.shouldRetry(attemptNumber);
    }
}
