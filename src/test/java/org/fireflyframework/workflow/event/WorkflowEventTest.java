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

package org.fireflyframework.workflow.event;

import org.fireflyframework.workflow.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for WorkflowEvent.
 */
class WorkflowEventTest {

    @Test
    void shouldCreateWorkflowStartedEvent() {
        WorkflowInstance instance = createTestInstance(WorkflowStatus.RUNNING);
        
        WorkflowEvent event = WorkflowEvent.workflowStarted(instance);
        
        assertThat(event.eventType()).isEqualTo(WorkflowEvent.WorkflowEventType.WORKFLOW_STARTED);
        assertThat(event.workflowId()).isEqualTo("test-workflow");
        assertThat(event.instanceId()).isEqualTo("instance-1");
        assertThat(event.correlationId()).isEqualTo("corr-1");
        assertThat(event.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(event.timestamp()).isNotNull();
    }

    @Test
    void shouldCreateWorkflowCompletedEvent() {
        WorkflowInstance instance = createTestInstance(WorkflowStatus.COMPLETED)
                .complete(Map.of("result", "success"));
        
        WorkflowEvent event = WorkflowEvent.workflowCompleted(instance);
        
        assertThat(event.eventType()).isEqualTo(WorkflowEvent.WorkflowEventType.WORKFLOW_COMPLETED);
        assertThat(event.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(event.payload()).isEqualTo(Map.of("result", "success"));
    }

    @Test
    void shouldCreateWorkflowFailedEvent() {
        WorkflowInstance instance = createTestInstance(WorkflowStatus.FAILED)
                .fail("Something went wrong", "RuntimeException");
        
        WorkflowEvent event = WorkflowEvent.workflowFailed(instance);
        
        assertThat(event.eventType()).isEqualTo(WorkflowEvent.WorkflowEventType.WORKFLOW_FAILED);
        assertThat(event.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(event.error()).isNotNull();
        assertThat(event.error().message()).isEqualTo("Something went wrong");
        assertThat(event.error().type()).isEqualTo("RuntimeException");
    }

    @Test
    void shouldCreateWorkflowCancelledEvent() {
        WorkflowInstance instance = createTestInstance(WorkflowStatus.RUNNING).cancel();
        
        WorkflowEvent event = WorkflowEvent.workflowCancelled(instance);
        
        assertThat(event.eventType()).isEqualTo(WorkflowEvent.WorkflowEventType.WORKFLOW_CANCELLED);
        assertThat(event.status()).isEqualTo(WorkflowStatus.CANCELLED);
    }

    @Test
    void shouldCreateStepStartedEvent() {
        WorkflowInstance instance = createTestInstance(WorkflowStatus.RUNNING);
        StepExecution step = StepExecution.create("step-1", "Test Step", Map.of("input", "value")).start();
        
        WorkflowEvent event = WorkflowEvent.stepStarted(instance, step);
        
        assertThat(event.eventType()).isEqualTo(WorkflowEvent.WorkflowEventType.STEP_STARTED);
        assertThat(event.stepId()).isEqualTo("step-1");
        assertThat(event.stepName()).isEqualTo("Test Step");
        assertThat(event.stepStatus()).isEqualTo(StepStatus.RUNNING);
        assertThat(event.payload()).isEqualTo(Map.of("input", "value"));
        assertThat(event.metadata()).containsEntry("attemptNumber", 1);
    }

    @Test
    void shouldCreateStepCompletedEvent() {
        WorkflowInstance instance = createTestInstance(WorkflowStatus.RUNNING);
        StepExecution step = StepExecution.create("step-1", "Test Step", null)
                .start()
                .complete(Map.of("output", "result"));
        
        WorkflowEvent event = WorkflowEvent.stepCompleted(instance, step);
        
        assertThat(event.eventType()).isEqualTo(WorkflowEvent.WorkflowEventType.STEP_COMPLETED);
        assertThat(event.stepStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(event.payload()).isEqualTo(Map.of("output", "result"));
    }

    @Test
    void shouldCreateStepFailedEvent() {
        WorkflowInstance instance = createTestInstance(WorkflowStatus.RUNNING);
        StepExecution step = StepExecution.create("step-1", "Test Step", null)
                .start()
                .fail("Step failed", "StepException");
        
        WorkflowEvent event = WorkflowEvent.stepFailed(instance, step);
        
        assertThat(event.eventType()).isEqualTo(WorkflowEvent.WorkflowEventType.STEP_FAILED);
        assertThat(event.stepStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(event.error()).isNotNull();
        assertThat(event.error().message()).isEqualTo("Step failed");
    }

    @Test
    void shouldCreateStepRetryingEvent() {
        WorkflowInstance instance = createTestInstance(WorkflowStatus.RUNNING);
        StepExecution step = StepExecution.create("step-1", "Test Step", null)
                .start()
                .fail("Temporary error", "TransientException")
                .retry();
        
        WorkflowEvent event = WorkflowEvent.stepRetrying(instance, step);
        
        assertThat(event.eventType()).isEqualTo(WorkflowEvent.WorkflowEventType.STEP_RETRYING);
        assertThat(event.stepStatus()).isEqualTo(StepStatus.RETRYING);
        assertThat(event.metadata()).containsEntry("attemptNumber", 2);
    }

    @Test
    void shouldFormatEventTypeString() {
        WorkflowInstance instance = createTestInstance(WorkflowStatus.RUNNING);
        
        WorkflowEvent startedEvent = WorkflowEvent.workflowStarted(instance);
        assertThat(startedEvent.getEventTypeString()).isEqualTo("workflow.workflow.started");
        
        StepExecution step = StepExecution.create("step-1", "Test", null).start();
        WorkflowEvent stepEvent = WorkflowEvent.stepStarted(instance, step);
        assertThat(stepEvent.getEventTypeString()).isEqualTo("workflow.step.started");
    }

    private WorkflowInstance createTestInstance(WorkflowStatus status) {
        return new WorkflowInstance(
                "instance-1", "test-workflow", "Test Workflow", "1.0.0",
                status, "step-1",
                Map.of(), Map.of("input", "value"), null, List.of(),
                null, null, "corr-1", "api",
                null, null, null
        );
    }
}
