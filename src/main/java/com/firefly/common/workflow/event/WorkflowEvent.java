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

package com.firefly.common.workflow.event;

import com.firefly.common.workflow.model.StepExecution;
import com.firefly.common.workflow.model.WorkflowInstance;
import com.firefly.common.workflow.model.WorkflowStatus;
import com.firefly.common.workflow.model.StepStatus;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

/**
 * Represents a workflow lifecycle event.
 * <p>
 * These events are published by the workflow engine to notify interested
 * parties about workflow and step state changes.
 *
 * @param eventType the type of event
 * @param workflowId the workflow definition ID
 * @param instanceId the workflow instance ID
 * @param correlationId correlation ID for tracing
 * @param workflowName the workflow name
 * @param workflowVersion the workflow version
 * @param status the current workflow status
 * @param stepId the step ID (for step events)
 * @param stepName the step name (for step events)
 * @param stepStatus the step status (for step events)
 * @param payload the event payload/output
 * @param error error information (for failure events)
 * @param context workflow context (if enabled)
 * @param timestamp when the event occurred
 * @param metadata additional metadata
 */
public record WorkflowEvent(
        WorkflowEventType eventType,
        String workflowId,
        String instanceId,
        String correlationId,
        String workflowName,
        String workflowVersion,
        WorkflowStatus status,
        String stepId,
        String stepName,
        StepStatus stepStatus,
        Object payload,
        ErrorInfo error,
        Map<String, Object> context,
        Instant timestamp,
        Map<String, Object> metadata
) implements Serializable {

    /**
     * Creates a workflow started event.
     */
    public static WorkflowEvent workflowStarted(WorkflowInstance instance) {
        return new WorkflowEvent(
                WorkflowEventType.WORKFLOW_STARTED,
                instance.workflowId(),
                instance.instanceId(),
                instance.correlationId(),
                instance.workflowName(),
                instance.workflowVersion(),
                instance.status(),
                null, null, null,
                instance.input(),
                null,
                instance.context(),
                Instant.now(),
                Map.of()
        );
    }

    /**
     * Creates a workflow completed event.
     */
    public static WorkflowEvent workflowCompleted(WorkflowInstance instance) {
        return new WorkflowEvent(
                WorkflowEventType.WORKFLOW_COMPLETED,
                instance.workflowId(),
                instance.instanceId(),
                instance.correlationId(),
                instance.workflowName(),
                instance.workflowVersion(),
                instance.status(),
                null, null, null,
                instance.output(),
                null,
                instance.context(),
                Instant.now(),
                Map.of("duration", instance.getDuration() != null ? instance.getDuration().toMillis() : 0)
        );
    }

    /**
     * Creates a workflow failed event.
     */
    public static WorkflowEvent workflowFailed(WorkflowInstance instance) {
        return new WorkflowEvent(
                WorkflowEventType.WORKFLOW_FAILED,
                instance.workflowId(),
                instance.instanceId(),
                instance.correlationId(),
                instance.workflowName(),
                instance.workflowVersion(),
                instance.status(),
                instance.currentStepId(),
                null, null,
                null,
                new ErrorInfo(instance.errorType(), instance.errorMessage()),
                instance.context(),
                Instant.now(),
                Map.of()
        );
    }

    /**
     * Creates a workflow cancelled event.
     */
    public static WorkflowEvent workflowCancelled(WorkflowInstance instance) {
        return new WorkflowEvent(
                WorkflowEventType.WORKFLOW_CANCELLED,
                instance.workflowId(),
                instance.instanceId(),
                instance.correlationId(),
                instance.workflowName(),
                instance.workflowVersion(),
                instance.status(),
                instance.currentStepId(),
                null, null,
                null, null,
                instance.context(),
                Instant.now(),
                Map.of()
        );
    }

    /**
     * Creates a step started event.
     */
    public static WorkflowEvent stepStarted(WorkflowInstance instance, StepExecution step) {
        return new WorkflowEvent(
                WorkflowEventType.STEP_STARTED,
                instance.workflowId(),
                instance.instanceId(),
                instance.correlationId(),
                instance.workflowName(),
                instance.workflowVersion(),
                instance.status(),
                step.stepId(),
                step.stepName(),
                step.status(),
                step.input(),
                null,
                instance.context(),
                Instant.now(),
                Map.of("attemptNumber", step.attemptNumber())
        );
    }

    /**
     * Creates a step completed event.
     */
    public static WorkflowEvent stepCompleted(WorkflowInstance instance, StepExecution step) {
        return new WorkflowEvent(
                WorkflowEventType.STEP_COMPLETED,
                instance.workflowId(),
                instance.instanceId(),
                instance.correlationId(),
                instance.workflowName(),
                instance.workflowVersion(),
                instance.status(),
                step.stepId(),
                step.stepName(),
                step.status(),
                step.output(),
                null,
                instance.context(),
                Instant.now(),
                Map.of(
                        "duration", step.getDuration() != null ? step.getDuration().toMillis() : 0,
                        "attemptNumber", step.attemptNumber()
                )
        );
    }

    /**
     * Creates a step failed event.
     */
    public static WorkflowEvent stepFailed(WorkflowInstance instance, StepExecution step) {
        return new WorkflowEvent(
                WorkflowEventType.STEP_FAILED,
                instance.workflowId(),
                instance.instanceId(),
                instance.correlationId(),
                instance.workflowName(),
                instance.workflowVersion(),
                instance.status(),
                step.stepId(),
                step.stepName(),
                step.status(),
                null,
                new ErrorInfo(step.errorType(), step.errorMessage()),
                instance.context(),
                Instant.now(),
                Map.of("attemptNumber", step.attemptNumber())
        );
    }

    /**
     * Creates a step retrying event.
     */
    public static WorkflowEvent stepRetrying(WorkflowInstance instance, StepExecution step) {
        return new WorkflowEvent(
                WorkflowEventType.STEP_RETRYING,
                instance.workflowId(),
                instance.instanceId(),
                instance.correlationId(),
                instance.workflowName(),
                instance.workflowVersion(),
                instance.status(),
                step.stepId(),
                step.stepName(),
                step.status(),
                null,
                new ErrorInfo(step.errorType(), step.errorMessage()),
                instance.context(),
                Instant.now(),
                Map.of("attemptNumber", step.attemptNumber())
        );
    }

    /**
     * Gets the event type as a string for EDA publishing.
     */
    public String getEventTypeString() {
        return "workflow." + eventType.name().toLowerCase().replace('_', '.');
    }

    /**
     * Types of workflow events.
     */
    public enum WorkflowEventType {
        WORKFLOW_STARTED,
        WORKFLOW_COMPLETED,
        WORKFLOW_FAILED,
        WORKFLOW_CANCELLED,
        WORKFLOW_TIMED_OUT,
        STEP_STARTED,
        STEP_COMPLETED,
        STEP_FAILED,
        STEP_SKIPPED,
        STEP_RETRYING,
        STEP_TIMED_OUT
    }

    /**
     * Error information.
     */
    public record ErrorInfo(String type, String message) implements Serializable {}
}
