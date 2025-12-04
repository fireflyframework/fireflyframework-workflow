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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for WorkflowInstance.
 */
class WorkflowInstanceTest {

    @Test
    void shouldCreateInstanceFromDefinition() {
        WorkflowDefinition definition = createTestDefinition();
        Map<String, Object> input = Map.of("orderId", "123");
        
        WorkflowInstance instance = WorkflowInstance.create(definition, input, "corr-1", "api");
        
        assertThat(instance.instanceId()).isNotNull();
        assertThat(instance.workflowId()).isEqualTo("test-workflow");
        assertThat(instance.workflowName()).isEqualTo("Test Workflow");
        assertThat(instance.status()).isEqualTo(WorkflowStatus.PENDING);
        assertThat(instance.input()).containsEntry("orderId", "123");
        assertThat(instance.correlationId()).isEqualTo("corr-1");
        assertThat(instance.triggeredBy()).isEqualTo("api");
        assertThat(instance.createdAt()).isNotNull();
        assertThat(instance.startedAt()).isNull();
        assertThat(instance.completedAt()).isNull();
    }

    @Test
    void shouldTransitionToRunning() {
        WorkflowInstance instance = createTestInstance();
        
        WorkflowInstance started = instance.start("step-1");
        
        assertThat(started.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(started.currentStepId()).isEqualTo("step-1");
        assertThat(started.startedAt()).isNotNull();
        assertThat(started.completedAt()).isNull();
    }

    @Test
    void shouldTransitionToCompleted() {
        WorkflowInstance instance = createTestInstance().start("step-1");
        Object result = Map.of("result", "success");
        
        WorkflowInstance completed = instance.complete(result);
        
        assertThat(completed.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(completed.output()).isEqualTo(result);
        assertThat(completed.completedAt()).isNotNull();
    }

    @Test
    void shouldTransitionToFailed() {
        WorkflowInstance instance = createTestInstance().start("step-1");
        RuntimeException error = new RuntimeException("Test error");
        
        WorkflowInstance failed = instance.fail(error);
        
        assertThat(failed.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(failed.errorMessage()).isEqualTo("Test error");
        assertThat(failed.errorType()).isEqualTo("java.lang.RuntimeException");
        assertThat(failed.completedAt()).isNotNull();
    }

    @Test
    void shouldTransitionToCancelled() {
        WorkflowInstance instance = createTestInstance().start("step-1");
        
        WorkflowInstance cancelled = instance.cancel();
        
        assertThat(cancelled.status()).isEqualTo(WorkflowStatus.CANCELLED);
        assertThat(cancelled.errorMessage()).isEqualTo("Workflow cancelled");
        assertThat(cancelled.completedAt()).isNotNull();
    }

    @Test
    void shouldTransitionToTimedOut() {
        WorkflowInstance instance = createTestInstance().start("step-1");
        
        WorkflowInstance timedOut = instance.timeout();
        
        assertThat(timedOut.status()).isEqualTo(WorkflowStatus.TIMED_OUT);
        assertThat(timedOut.errorMessage()).isEqualTo("Workflow timed out");
        assertThat(timedOut.errorType()).isEqualTo("TimeoutException");
    }

    @Test
    void shouldUpdateCurrentStep() {
        WorkflowInstance instance = createTestInstance().start("step-1");
        
        WorkflowInstance updated = instance.withCurrentStep("step-2");
        
        assertThat(updated.currentStepId()).isEqualTo("step-2");
        assertThat(updated.status()).isEqualTo(WorkflowStatus.RUNNING);
    }

    @Test
    void shouldAddStepExecution() {
        WorkflowInstance instance = createTestInstance().start("step-1");
        StepExecution stepExec = StepExecution.create("step-1", "Step 1", null);
        
        WorkflowInstance updated = instance.withStepExecution(stepExec);
        
        assertThat(updated.stepExecutions()).hasSize(1);
        assertThat(updated.getStepExecution("step-1")).isPresent();
    }

    @Test
    void shouldReplaceExistingStepExecution() {
        WorkflowInstance instance = createTestInstance().start("step-1");
        StepExecution step1 = StepExecution.create("step-1", "Step 1", null);
        StepExecution step1Updated = step1.start().complete("result");
        
        WorkflowInstance withStep = instance.withStepExecution(step1);
        WorkflowInstance updated = withStep.withStepExecution(step1Updated);
        
        assertThat(updated.stepExecutions()).hasSize(1);
        assertThat(updated.getStepExecution("step-1").get().status())
                .isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void shouldUpdateContext() {
        WorkflowInstance instance = createTestInstance();
        
        WorkflowInstance updated = instance.withContext("key1", "value1");
        
        assertThat(updated.context()).containsEntry("key1", "value1");
    }

    @Test
    void shouldMergeContext() {
        WorkflowInstance instance = createTestInstance()
                .withContext("existing", "value");
        
        WorkflowInstance updated = instance.withContext(Map.of("new1", "val1", "new2", "val2"));
        
        assertThat(updated.context())
                .containsEntry("existing", "value")
                .containsEntry("new1", "val1")
                .containsEntry("new2", "val2");
    }

    @Test
    void shouldGetLatestStepExecution() {
        WorkflowInstance instance = createTestInstance().start("step-1");
        StepExecution step1 = StepExecution.create("step-1", "Step 1", null).start().complete("r1");
        StepExecution step2 = StepExecution.create("step-2", "Step 2", null).start();
        
        WorkflowInstance updated = instance
                .withStepExecution(step1)
                .withStepExecution(step2);
        
        assertThat(updated.getLatestStepExecution()).isPresent();
        assertThat(updated.getLatestStepExecution().get().stepId()).isEqualTo("step-2");
    }

    @Test
    void shouldCalculateProgress() {
        WorkflowInstance instance = createTestInstance().start("step-1");
        StepExecution step1 = StepExecution.create("step-1", "Step 1", null).start().complete("r1");
        StepExecution step2 = StepExecution.create("step-2", "Step 2", null).start().complete("r2");
        
        WorkflowInstance updated = instance
                .withStepExecution(step1)
                .withStepExecution(step2);
        
        assertThat(updated.getProgress(4)).isEqualTo(50); // 2 of 4 steps
    }

    @Test
    void shouldCalculateDuration() throws InterruptedException {
        WorkflowInstance instance = createTestInstance().start("step-1");
        Thread.sleep(10); // Small delay
        
        assertThat(instance.getDuration()).isNotNull();
        assertThat(instance.getDuration().toMillis()).isGreaterThanOrEqualTo(10);
    }

    private WorkflowDefinition createTestDefinition() {
        return WorkflowDefinition.builder()
                .workflowId("test-workflow")
                .name("Test Workflow")
                .steps(List.of(
                        WorkflowStepDefinition.builder()
                                .stepId("step-1")
                                .name("Step 1")
                                .order(1)
                                .build()
                ))
                .build();
    }

    private WorkflowInstance createTestInstance() {
        return WorkflowInstance.create(
                createTestDefinition(),
                Map.of("input", "value"),
                "correlation-123",
                "test"
        );
    }
}
