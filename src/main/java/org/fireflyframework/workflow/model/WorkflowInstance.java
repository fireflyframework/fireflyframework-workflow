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
import java.util.*;

/**
 * Represents a running instance of a workflow.
 * <p>
 * Contains the complete runtime state of a workflow execution including
 * its current status, step executions, context data, and timing information.
 */
public record WorkflowInstance(
        String instanceId,
        String workflowId,
        String workflowName,
        String workflowVersion,
        WorkflowStatus status,
        String currentStepId,
        Map<String, Object> context,
        Map<String, Object> input,
        Object output,
        List<StepExecution> stepExecutions,
        String errorMessage,
        String errorType,
        String correlationId,
        String triggeredBy,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) implements Serializable {

    public WorkflowInstance {
        Objects.requireNonNull(instanceId, "instanceId cannot be null");
        Objects.requireNonNull(workflowId, "workflowId cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        if (context == null) {
            context = Map.of();
        }
        if (input == null) {
            input = Map.of();
        }
        if (stepExecutions == null) {
            stepExecutions = List.of();
        }
    }

    /**
     * Creates a new workflow instance.
     *
     * @param workflowDef the workflow definition
     * @param input the initial input data
     * @param correlationId optional correlation ID
     * @param triggeredBy identifier of the trigger source
     * @return new workflow instance
     */
    public static WorkflowInstance create(
            WorkflowDefinition workflowDef,
            Map<String, Object> input,
            String correlationId,
            String triggeredBy) {
        return new WorkflowInstance(
                UUID.randomUUID().toString(),
                workflowDef.workflowId(),
                workflowDef.name(),
                workflowDef.version(),
                WorkflowStatus.PENDING,
                null,
                new HashMap<>(input != null ? input : Map.of()),
                input != null ? Map.copyOf(input) : Map.of(),
                null,
                new ArrayList<>(),
                null,
                null,
                correlationId != null ? correlationId : UUID.randomUUID().toString(),
                triggeredBy,
                Instant.now(),
                null,
                null
        );
    }

    /**
     * Creates a copy with running status.
     *
     * @param firstStepId the first step ID
     * @return updated instance
     */
    public WorkflowInstance start(String firstStepId) {
        return new WorkflowInstance(
                instanceId, workflowId, workflowName, workflowVersion,
                WorkflowStatus.RUNNING, firstStepId,
                context, input, output, stepExecutions,
                null, null, correlationId, triggeredBy,
                createdAt, Instant.now(), null
        );
    }

    /**
     * Creates a copy with completed status.
     *
     * @param result the workflow output
     * @return updated instance
     */
    public WorkflowInstance complete(Object result) {
        return new WorkflowInstance(
                instanceId, workflowId, workflowName, workflowVersion,
                WorkflowStatus.COMPLETED, currentStepId,
                context, input, result, stepExecutions,
                null, null, correlationId, triggeredBy,
                createdAt, startedAt, Instant.now()
        );
    }

    /**
     * Creates a copy with failed status.
     *
     * @param error the error that caused the failure
     * @return updated instance
     */
    public WorkflowInstance fail(Throwable error) {
        return new WorkflowInstance(
                instanceId, workflowId, workflowName, workflowVersion,
                WorkflowStatus.FAILED, currentStepId,
                context, input, output, stepExecutions,
                error.getMessage(), error.getClass().getName(),
                correlationId, triggeredBy,
                createdAt, startedAt, Instant.now()
        );
    }

    /**
     * Creates a copy with failed status.
     *
     * @param errorMessage the error message
     * @param errorType the error type
     * @return updated instance
     */
    public WorkflowInstance fail(String errorMessage, String errorType) {
        return new WorkflowInstance(
                instanceId, workflowId, workflowName, workflowVersion,
                WorkflowStatus.FAILED, currentStepId,
                context, input, output, stepExecutions,
                errorMessage, errorType, correlationId, triggeredBy,
                createdAt, startedAt, Instant.now()
        );
    }

    /**
     * Creates a copy with cancelled status.
     *
     * @return updated instance
     */
    public WorkflowInstance cancel() {
        return new WorkflowInstance(
                instanceId, workflowId, workflowName, workflowVersion,
                WorkflowStatus.CANCELLED, currentStepId,
                context, input, output, stepExecutions,
                "Workflow cancelled", null, correlationId, triggeredBy,
                createdAt, startedAt, Instant.now()
        );
    }

    /**
     * Creates a copy with timed out status.
     *
     * @return updated instance
     */
    public WorkflowInstance timeout() {
        return new WorkflowInstance(
                instanceId, workflowId, workflowName, workflowVersion,
                WorkflowStatus.TIMED_OUT, currentStepId,
                context, input, output, stepExecutions,
                "Workflow timed out", "TimeoutException",
                correlationId, triggeredBy,
                createdAt, startedAt, Instant.now()
        );
    }

    /**
     * Creates a copy with waiting status.
     *
     * @return updated instance
     */
    public WorkflowInstance waiting() {
        return new WorkflowInstance(
                instanceId, workflowId, workflowName, workflowVersion,
                WorkflowStatus.WAITING, currentStepId,
                context, input, output, stepExecutions,
                errorMessage, errorType, correlationId, triggeredBy,
                createdAt, startedAt, null
        );
    }

    /**
     * Creates a copy with suspended status.
     * Preserves the current step so execution can resume from the same point.
     *
     * @param reason the reason for suspension
     * @return updated instance
     */
    public WorkflowInstance suspend(String reason) {
        Map<String, Object> newContext = new HashMap<>(context);
        newContext.put("_suspendedAt", Instant.now().toString());
        newContext.put("_suspendReason", reason != null ? reason : "Manual suspension");
        newContext.put("_statusBeforeSuspend", status.name());
        return new WorkflowInstance(
                instanceId, workflowId, workflowName, workflowVersion,
                WorkflowStatus.SUSPENDED, currentStepId,
                newContext, input, output, stepExecutions,
                null, null, correlationId, triggeredBy,
                createdAt, startedAt, null
        );
    }

    /**
     * Creates a copy that resumes from suspended status.
     * Returns to RUNNING status to continue execution.
     *
     * @return updated instance
     */
    public WorkflowInstance resume() {
        Map<String, Object> newContext = new HashMap<>(context);
        newContext.put("_resumedAt", Instant.now().toString());
        newContext.remove("_suspendedAt");
        newContext.remove("_suspendReason");
        newContext.remove("_statusBeforeSuspend");
        return new WorkflowInstance(
                instanceId, workflowId, workflowName, workflowVersion,
                WorkflowStatus.RUNNING, currentStepId,
                newContext, input, output, stepExecutions,
                null, null, correlationId, triggeredBy,
                createdAt, startedAt, null
        );
    }

    /**
     * Creates a copy with updated current step.
     *
     * @param stepId the new current step ID
     * @return updated instance
     */
    public WorkflowInstance withCurrentStep(String stepId) {
        return new WorkflowInstance(
                instanceId, workflowId, workflowName, workflowVersion,
                status, stepId, context, input, output, stepExecutions,
                errorMessage, errorType, correlationId, triggeredBy,
                createdAt, startedAt, completedAt
        );
    }

    /**
     * Creates a copy with added step execution.
     *
     * @param stepExecution the step execution to add
     * @return updated instance
     */
    public WorkflowInstance withStepExecution(StepExecution stepExecution) {
        List<StepExecution> newExecutions = new ArrayList<>(stepExecutions);
        // Replace existing execution for the same step if present
        newExecutions.removeIf(e -> e.stepId().equals(stepExecution.stepId()));
        newExecutions.add(stepExecution);
        return new WorkflowInstance(
                instanceId, workflowId, workflowName, workflowVersion,
                status, currentStepId, context, input, output, newExecutions,
                errorMessage, errorType, correlationId, triggeredBy,
                createdAt, startedAt, completedAt
        );
    }

    /**
     * Creates a copy with updated context.
     *
     * @param key the context key
     * @param value the context value
     * @return updated instance
     */
    public WorkflowInstance withContext(String key, Object value) {
        Map<String, Object> newContext = new HashMap<>(context);
        newContext.put(key, value);
        return new WorkflowInstance(
                instanceId, workflowId, workflowName, workflowVersion,
                status, currentStepId, newContext, input, output, stepExecutions,
                errorMessage, errorType, correlationId, triggeredBy,
                createdAt, startedAt, completedAt
        );
    }

    /**
     * Creates a copy with merged context.
     *
     * @param additionalContext context to merge
     * @return updated instance
     */
    public WorkflowInstance withContext(Map<String, Object> additionalContext) {
        Map<String, Object> newContext = new HashMap<>(context);
        newContext.putAll(additionalContext);
        return new WorkflowInstance(
                instanceId, workflowId, workflowName, workflowVersion,
                status, currentStepId, newContext, input, output, stepExecutions,
                errorMessage, errorType, correlationId, triggeredBy,
                createdAt, startedAt, completedAt
        );
    }

    /**
     * Gets the execution for a specific step.
     *
     * @param stepId the step ID
     * @return optional containing the execution if found
     */
    public Optional<StepExecution> getStepExecution(String stepId) {
        return stepExecutions.stream()
                .filter(e -> e.stepId().equals(stepId))
                .findFirst();
    }

    /**
     * Gets the most recent step execution.
     *
     * @return optional containing the latest execution
     */
    public Optional<StepExecution> getLatestStepExecution() {
        return stepExecutions.stream()
                .max(Comparator.comparing(e -> e.startedAt() != null ? e.startedAt() : Instant.MIN));
    }

    /**
     * Gets the duration of the workflow execution.
     *
     * @return duration or null if not started
     */
    public Duration getDuration() {
        if (startedAt == null) {
            return null;
        }
        Instant end = completedAt != null ? completedAt : Instant.now();
        return Duration.between(startedAt, end);
    }

    /**
     * Calculates the progress percentage.
     *
     * @param totalSteps total number of steps in workflow
     * @return progress from 0 to 100
     */
    public int getProgress(int totalSteps) {
        if (totalSteps == 0) {
            return status.isTerminal() ? 100 : 0;
        }
        long completedSteps = stepExecutions.stream()
                .filter(e -> e.status().isSuccessful())
                .count();
        return (int) ((completedSteps * 100) / totalSteps);
    }
}
