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
 * Represents the complete state of a workflow instance with comprehensive tracking.
 * <p>
 * Provides a dashboard view of workflow progress including:
 * <ul>
 *   <li>Which steps have completed, failed, or been skipped</li>
 *   <li>Current step being executed</li>
 *   <li>Next step to execute</li>
 *   <li>What event the workflow is waiting for (if any)</li>
 *   <li>Full execution history with timestamps</li>
 * </ul>
 * <p>
 * Cache key pattern: {@code workflow:state:{workflowId}:{instanceId}}
 *
 * @param workflowId ID of the workflow definition
 * @param workflowName Human-readable name of the workflow
 * @param workflowVersion Version of the workflow definition
 * @param instanceId Unique instance identifier
 * @param correlationId Correlation ID for tracing
 * @param status Overall workflow status
 * @param currentStepId ID of the step currently executing (null if none)
 * @param nextStepId ID of the next step to execute (null if complete or failed)
 * @param waitingForEvent Event type the workflow is waiting for (if status is WAITING)
 * @param completedSteps List of step IDs that completed successfully
 * @param failedSteps List of step IDs that failed
 * @param skippedSteps List of step IDs that were skipped
 * @param pendingSteps List of step IDs yet to be executed
 * @param stepHistory Ordered history of step executions
 * @param input Original workflow input
 * @param output Final workflow output (when complete)
 * @param context Shared context data
 * @param errorMessage Error message if workflow failed
 * @param errorType Error type if workflow failed
 * @param triggeredBy How the workflow was triggered
 * @param totalSteps Total number of steps in the workflow
 * @param createdAt When the workflow instance was created
 * @param startedAt When workflow execution started
 * @param completedAt When workflow execution completed
 */
public record WorkflowState(
        String workflowId,
        String workflowName,
        String workflowVersion,
        String instanceId,
        String correlationId,
        WorkflowStatus status,
        String currentStepId,
        String nextStepId,
        String waitingForEvent,
        List<String> completedSteps,
        List<String> failedSteps,
        List<String> skippedSteps,
        List<String> pendingSteps,
        List<StepHistory> stepHistory,
        Map<String, Object> input,
        Object output,
        Map<String, Object> context,
        String errorMessage,
        String errorType,
        String triggeredBy,
        int totalSteps,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) implements Serializable {

    public WorkflowState {
        Objects.requireNonNull(workflowId, "workflowId cannot be null");
        Objects.requireNonNull(instanceId, "instanceId cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        if (completedSteps == null) completedSteps = List.of();
        if (failedSteps == null) failedSteps = List.of();
        if (skippedSteps == null) skippedSteps = List.of();
        if (pendingSteps == null) pendingSteps = List.of();
        if (stepHistory == null) stepHistory = List.of();
        if (input == null) input = Map.of();
        if (context == null) context = Map.of();
    }

    /**
     * Creates a new workflow state from a definition.
     */
    public static WorkflowState create(
            WorkflowDefinition definition,
            String instanceId,
            Map<String, Object> input,
            String correlationId,
            String triggeredBy) {
        
        List<String> pending = definition.getOrderedSteps().stream()
                .map(WorkflowStepDefinition::stepId)
                .toList();
        
        String firstStepId = definition.getFirstStep()
                .map(WorkflowStepDefinition::stepId)
                .orElse(null);
        
        return new WorkflowState(
                definition.workflowId(),
                definition.name(),
                definition.version(),
                instanceId,
                correlationId,
                WorkflowStatus.PENDING,
                null,
                firstStepId,
                null,
                List.of(),
                List.of(),
                List.of(),
                pending,
                List.of(),
                input != null ? Map.copyOf(input) : Map.of(),
                null,
                Map.of(),
                null,
                null,
                triggeredBy,
                definition.steps().size(),
                Instant.now(),
                null,
                null
        );
    }

    /**
     * Creates a copy with RUNNING status and sets the current step.
     */
    public WorkflowState start(String firstStepId) {
        return new WorkflowState(
                workflowId, workflowName, workflowVersion, instanceId, correlationId,
                WorkflowStatus.RUNNING,
                firstStepId,
                getNextStepAfter(firstStepId),
                null,
                completedSteps, failedSteps, skippedSteps, pendingSteps,
                stepHistory,
                input, output, context,
                null, null,
                triggeredBy, totalSteps,
                createdAt,
                Instant.now(),
                null
        );
    }

    /**
     * Records a step as started.
     */
    public WorkflowState stepStarted(String stepId) {
        return new WorkflowState(
                workflowId, workflowName, workflowVersion, instanceId, correlationId,
                WorkflowStatus.RUNNING,
                stepId,
                getNextStepAfter(stepId),
                null,
                completedSteps, failedSteps, skippedSteps,
                removeFromPending(stepId),
                stepHistory,
                input, output, context,
                null, null,
                triggeredBy, totalSteps,
                createdAt, startedAt, null
        );
    }

    /**
     * Records a step as completed.
     */
    public WorkflowState stepCompleted(String stepId, StepHistory history) {
        List<String> newCompleted = new ArrayList<>(completedSteps);
        newCompleted.add(stepId);
        
        List<StepHistory> newHistory = new ArrayList<>(stepHistory);
        newHistory.add(history);
        
        String nextStep = getNextStepAfter(stepId);
        
        return new WorkflowState(
                workflowId, workflowName, workflowVersion, instanceId, correlationId,
                WorkflowStatus.RUNNING,
                null,
                nextStep,
                null,
                List.copyOf(newCompleted), failedSteps, skippedSteps,
                removeFromPending(stepId),
                List.copyOf(newHistory),
                input, output, context,
                null, null,
                triggeredBy, totalSteps,
                createdAt, startedAt, null
        );
    }

    /**
     * Records a step as failed.
     */
    public WorkflowState stepFailed(String stepId, StepHistory history) {
        List<String> newFailed = new ArrayList<>(failedSteps);
        newFailed.add(stepId);
        
        List<StepHistory> newHistory = new ArrayList<>(stepHistory);
        newHistory.add(history);
        
        return new WorkflowState(
                workflowId, workflowName, workflowVersion, instanceId, correlationId,
                WorkflowStatus.FAILED,
                stepId,
                null,
                null,
                completedSteps, List.copyOf(newFailed), skippedSteps,
                removeFromPending(stepId),
                List.copyOf(newHistory),
                input, output, context,
                history.errorMessage(),
                null,
                triggeredBy, totalSteps,
                createdAt, startedAt, Instant.now()
        );
    }

    /**
     * Records a step as skipped.
     */
    public WorkflowState stepSkipped(String stepId, StepHistory history) {
        List<String> newSkipped = new ArrayList<>(skippedSteps);
        newSkipped.add(stepId);
        
        List<StepHistory> newHistory = new ArrayList<>(stepHistory);
        newHistory.add(history);
        
        String nextStep = getNextStepAfter(stepId);
        
        return new WorkflowState(
                workflowId, workflowName, workflowVersion, instanceId, correlationId,
                WorkflowStatus.RUNNING,
                null,
                nextStep,
                null,
                completedSteps, failedSteps, List.copyOf(newSkipped),
                removeFromPending(stepId),
                List.copyOf(newHistory),
                input, output, context,
                null, null,
                triggeredBy, totalSteps,
                createdAt, startedAt, null
        );
    }

    /**
     * Sets the workflow to waiting for an event.
     */
    public WorkflowState waitingFor(String stepId, String eventType) {
        return new WorkflowState(
                workflowId, workflowName, workflowVersion, instanceId, correlationId,
                WorkflowStatus.WAITING,
                stepId,
                nextStepId,
                eventType,
                completedSteps, failedSteps, skippedSteps, pendingSteps,
                stepHistory,
                input, output, context,
                null, null,
                triggeredBy, totalSteps,
                createdAt, startedAt, null
        );
    }

    /**
     * Completes the workflow.
     */
    public WorkflowState complete(Object result) {
        return new WorkflowState(
                workflowId, workflowName, workflowVersion, instanceId, correlationId,
                WorkflowStatus.COMPLETED,
                null,
                null,
                null,
                completedSteps, failedSteps, skippedSteps, List.of(),
                stepHistory,
                input, result, context,
                null, null,
                triggeredBy, totalSteps,
                createdAt, startedAt, Instant.now()
        );
    }

    /**
     * Fails the workflow.
     */
    public WorkflowState fail(String errorMessage, String errorType) {
        return new WorkflowState(
                workflowId, workflowName, workflowVersion, instanceId, correlationId,
                WorkflowStatus.FAILED,
                currentStepId,
                null,
                null,
                completedSteps, failedSteps, skippedSteps, pendingSteps,
                stepHistory,
                input, output, context,
                errorMessage, errorType,
                triggeredBy, totalSteps,
                createdAt, startedAt, Instant.now()
        );
    }

    /**
     * Cancels the workflow.
     */
    public WorkflowState cancel() {
        return new WorkflowState(
                workflowId, workflowName, workflowVersion, instanceId, correlationId,
                WorkflowStatus.CANCELLED,
                currentStepId,
                null,
                null,
                completedSteps, failedSteps, skippedSteps, pendingSteps,
                stepHistory,
                input, output, context,
                "Workflow cancelled", null,
                triggeredBy, totalSteps,
                createdAt, startedAt, Instant.now()
        );
    }

    /**
     * Updates the context.
     */
    public WorkflowState withContext(Map<String, Object> newContext) {
        Map<String, Object> merged = new HashMap<>(context);
        merged.putAll(newContext);
        return new WorkflowState(
                workflowId, workflowName, workflowVersion, instanceId, correlationId,
                status, currentStepId, nextStepId, waitingForEvent,
                completedSteps, failedSteps, skippedSteps, pendingSteps,
                stepHistory,
                input, output, Map.copyOf(merged),
                errorMessage, errorType,
                triggeredBy, totalSteps,
                createdAt, startedAt, completedAt
        );
    }

    /**
     * Calculates progress as a percentage.
     */
    public int getProgress() {
        if (totalSteps == 0) {
            return status.isTerminal() ? 100 : 0;
        }
        int completed = completedSteps.size() + skippedSteps.size();
        return (int) ((completed * 100) / totalSteps);
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
     * Checks if all steps are complete.
     */
    public boolean isAllStepsComplete() {
        return pendingSteps.isEmpty() && failedSteps.isEmpty();
    }

    /**
     * Gets the next step ID after the given step.
     */
    private String getNextStepAfter(String stepId) {
        if (pendingSteps.isEmpty()) {
            return null;
        }
        int idx = pendingSteps.indexOf(stepId);
        if (idx >= 0 && idx < pendingSteps.size() - 1) {
            return pendingSteps.get(idx + 1);
        }
        // If step not in pending, return first pending
        return pendingSteps.isEmpty() ? null : pendingSteps.get(0);
    }

    /**
     * Removes a step from the pending list.
     */
    private List<String> removeFromPending(String stepId) {
        return pendingSteps.stream()
                .filter(id -> !id.equals(stepId))
                .toList();
    }

    /**
     * Builder for WorkflowState.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String workflowId;
        private String workflowName;
        private String workflowVersion = "1.0.0";
        private String instanceId;
        private String correlationId;
        private WorkflowStatus status = WorkflowStatus.PENDING;
        private String currentStepId;
        private String nextStepId;
        private String waitingForEvent;
        private List<String> completedSteps = new ArrayList<>();
        private List<String> failedSteps = new ArrayList<>();
        private List<String> skippedSteps = new ArrayList<>();
        private List<String> pendingSteps = new ArrayList<>();
        private List<StepHistory> stepHistory = new ArrayList<>();
        private Map<String, Object> input = new HashMap<>();
        private Object output;
        private Map<String, Object> context = new HashMap<>();
        private String errorMessage;
        private String errorType;
        private String triggeredBy;
        private int totalSteps;
        private Instant createdAt = Instant.now();
        private Instant startedAt;
        private Instant completedAt;

        public Builder workflowId(String workflowId) {
            this.workflowId = workflowId;
            return this;
        }

        public Builder workflowName(String workflowName) {
            this.workflowName = workflowName;
            return this;
        }

        public Builder workflowVersion(String workflowVersion) {
            this.workflowVersion = workflowVersion;
            return this;
        }

        public Builder instanceId(String instanceId) {
            this.instanceId = instanceId;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder status(WorkflowStatus status) {
            this.status = status;
            return this;
        }

        public Builder currentStepId(String currentStepId) {
            this.currentStepId = currentStepId;
            return this;
        }

        public Builder nextStepId(String nextStepId) {
            this.nextStepId = nextStepId;
            return this;
        }

        public Builder waitingForEvent(String waitingForEvent) {
            this.waitingForEvent = waitingForEvent;
            return this;
        }

        public Builder completedSteps(List<String> completedSteps) {
            this.completedSteps = new ArrayList<>(completedSteps);
            return this;
        }

        public Builder failedSteps(List<String> failedSteps) {
            this.failedSteps = new ArrayList<>(failedSteps);
            return this;
        }

        public Builder skippedSteps(List<String> skippedSteps) {
            this.skippedSteps = new ArrayList<>(skippedSteps);
            return this;
        }

        public Builder pendingSteps(List<String> pendingSteps) {
            this.pendingSteps = new ArrayList<>(pendingSteps);
            return this;
        }

        public Builder stepHistory(List<StepHistory> stepHistory) {
            this.stepHistory = new ArrayList<>(stepHistory);
            return this;
        }

        public Builder input(Map<String, Object> input) {
            this.input = new HashMap<>(input);
            return this;
        }

        public Builder output(Object output) {
            this.output = output;
            return this;
        }

        public Builder context(Map<String, Object> context) {
            this.context = new HashMap<>(context);
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

        public Builder triggeredBy(String triggeredBy) {
            this.triggeredBy = triggeredBy;
            return this;
        }

        public Builder totalSteps(int totalSteps) {
            this.totalSteps = totalSteps;
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

        public WorkflowState build() {
            return new WorkflowState(
                    workflowId, workflowName, workflowVersion, instanceId, correlationId,
                    status, currentStepId, nextStepId, waitingForEvent,
                    List.copyOf(completedSteps), List.copyOf(failedSteps), 
                    List.copyOf(skippedSteps), List.copyOf(pendingSteps),
                    List.copyOf(stepHistory),
                    Map.copyOf(input), output, Map.copyOf(context),
                    errorMessage, errorType,
                    triggeredBy, totalSteps,
                    createdAt, startedAt, completedAt
            );
        }
    }
}
