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

package org.fireflyframework.workflow.dlq;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Represents an entry in the Dead Letter Queue (DLQ).
 * <p>
 * Contains information about failed workflow or step executions
 * that can be retried or analyzed.
 *
 * @param id unique identifier for this DLQ entry
 * @param workflowId the workflow ID
 * @param instanceId the workflow instance ID
 * @param stepId the step ID (null for workflow-level failures)
 * @param stepName the step name
 * @param eventType the event type that triggered the workflow/step
 * @param payload the original input/payload
 * @param errorMessage the error message
 * @param errorType the error type/class name
 * @param stackTrace abbreviated stack trace
 * @param attemptCount number of retry attempts made
 * @param correlationId correlation ID for tracing
 * @param triggeredBy what triggered the original execution
 * @param createdAt when the entry was created
 * @param lastAttemptAt when the last retry was attempted
 * @param metadata additional metadata
 */
public record DeadLetterEntry(
        String id,
        String workflowId,
        String instanceId,
        String stepId,
        String stepName,
        String eventType,
        Map<String, Object> payload,
        String errorMessage,
        String errorType,
        String stackTrace,
        int attemptCount,
        String correlationId,
        String triggeredBy,
        Instant createdAt,
        Instant lastAttemptAt,
        Map<String, Object> metadata
) implements Serializable {

    /**
     * Creates a new DLQ entry for a failed step.
     */
    public static DeadLetterEntry forStep(
            String workflowId,
            String instanceId,
            String stepId,
            String stepName,
            Map<String, Object> payload,
            Throwable error,
            int attemptCount,
            String correlationId,
            String triggeredBy) {
        
        return new DeadLetterEntry(
                UUID.randomUUID().toString(),
                workflowId,
                instanceId,
                stepId,
                stepName,
                null,
                payload,
                error.getMessage(),
                error.getClass().getName(),
                getAbbreviatedStackTrace(error),
                attemptCount,
                correlationId,
                triggeredBy,
                Instant.now(),
                Instant.now(),
                Map.of()
        );
    }

    /**
     * Creates a new DLQ entry for a failed workflow.
     */
    public static DeadLetterEntry forWorkflow(
            String workflowId,
            String instanceId,
            Map<String, Object> payload,
            Throwable error,
            String correlationId,
            String triggeredBy) {
        
        return new DeadLetterEntry(
                UUID.randomUUID().toString(),
                workflowId,
                instanceId,
                null,
                null,
                null,
                payload,
                error.getMessage(),
                error.getClass().getName(),
                getAbbreviatedStackTrace(error),
                1,
                correlationId,
                triggeredBy,
                Instant.now(),
                Instant.now(),
                Map.of()
        );
    }

    /**
     * Creates an updated entry after a retry attempt.
     */
    public DeadLetterEntry withRetryAttempt() {
        return new DeadLetterEntry(
                id,
                workflowId,
                instanceId,
                stepId,
                stepName,
                eventType,
                payload,
                errorMessage,
                errorType,
                stackTrace,
                attemptCount + 1,
                correlationId,
                triggeredBy,
                createdAt,
                Instant.now(),
                metadata
        );
    }

    /**
     * Creates an updated entry with new error information.
     */
    public DeadLetterEntry withError(Throwable error) {
        return new DeadLetterEntry(
                id,
                workflowId,
                instanceId,
                stepId,
                stepName,
                eventType,
                payload,
                error.getMessage(),
                error.getClass().getName(),
                getAbbreviatedStackTrace(error),
                attemptCount + 1,
                correlationId,
                triggeredBy,
                createdAt,
                Instant.now(),
                metadata
        );
    }

    /**
     * Gets an abbreviated stack trace (first 10 lines).
     */
    private static String getAbbreviatedStackTrace(Throwable error) {
        if (error == null) {
            return null;
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append(error.getClass().getName()).append(": ").append(error.getMessage()).append("\n");
        
        StackTraceElement[] elements = error.getStackTrace();
        int count = Math.min(elements.length, 10);
        for (int i = 0; i < count; i++) {
            sb.append("\tat ").append(elements[i]).append("\n");
        }
        
        if (elements.length > 10) {
            sb.append("\t... ").append(elements.length - 10).append(" more\n");
        }
        
        return sb.toString();
    }
}
