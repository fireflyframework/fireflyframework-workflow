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

package org.fireflyframework.workflow.core;

import org.fireflyframework.workflow.model.TriggerMode;
import org.fireflyframework.workflow.model.WorkflowDefinition;
import org.fireflyframework.workflow.model.WorkflowStepDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for WorkflowRegistry.
 */
class WorkflowRegistryTest {

    private WorkflowRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new WorkflowRegistry();
    }

    @Test
    void shouldRegisterWorkflow() {
        WorkflowDefinition workflow = createTestWorkflow("test-workflow");
        
        registry.register(workflow);
        
        assertThat(registry.contains("test-workflow")).isTrue();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void shouldGetRegisteredWorkflow() {
        WorkflowDefinition workflow = createTestWorkflow("test-workflow");
        registry.register(workflow);
        
        Optional<WorkflowDefinition> result = registry.get("test-workflow");
        
        assertThat(result).isPresent();
        assertThat(result.get().workflowId()).isEqualTo("test-workflow");
    }

    @Test
    void shouldReturnEmptyForUnknownWorkflow() {
        Optional<WorkflowDefinition> result = registry.get("unknown");
        
        assertThat(result).isEmpty();
    }

    @Test
    void shouldUnregisterWorkflow() {
        WorkflowDefinition workflow = createTestWorkflow("test-workflow");
        registry.register(workflow);
        
        boolean removed = registry.unregister("test-workflow");
        
        assertThat(removed).isTrue();
        assertThat(registry.contains("test-workflow")).isFalse();
    }

    @Test
    void shouldReturnFalseWhenUnregisteringUnknownWorkflow() {
        boolean removed = registry.unregister("unknown");
        
        assertThat(removed).isFalse();
    }

    @Test
    void shouldGetAllWorkflows() {
        registry.register(createTestWorkflow("workflow-1"));
        registry.register(createTestWorkflow("workflow-2"));
        registry.register(createTestWorkflow("workflow-3"));
        
        assertThat(registry.getAll()).hasSize(3);
        assertThat(registry.getWorkflowIds()).containsExactlyInAnyOrder(
                "workflow-1", "workflow-2", "workflow-3");
    }

    @Test
    void shouldFindWorkflowsByTriggerEvent() {
        WorkflowDefinition workflow1 = WorkflowDefinition.builder()
                .workflowId("order-workflow")
                .name("Order Workflow")
                .triggerMode(TriggerMode.ASYNC)
                .triggerEventType("order.created")
                .steps(List.of(createTestStep("step-1")))
                .build();
        
        WorkflowDefinition workflow2 = WorkflowDefinition.builder()
                .workflowId("payment-workflow")
                .name("Payment Workflow")
                .triggerMode(TriggerMode.BOTH)
                .triggerEventType("payment.*")
                .steps(List.of(createTestStep("step-1")))
                .build();
        
        registry.register(workflow1);
        registry.register(workflow2);
        
        List<WorkflowDefinition> orderMatches = registry.findByTriggerEvent("order.created");
        assertThat(orderMatches).hasSize(1);
        assertThat(orderMatches.get(0).workflowId()).isEqualTo("order-workflow");
        
        List<WorkflowDefinition> paymentMatches = registry.findByTriggerEvent("payment.processed");
        assertThat(paymentMatches).hasSize(1);
        assertThat(paymentMatches.get(0).workflowId()).isEqualTo("payment-workflow");
    }

    @Test
    void shouldFindSyncTriggerableWorkflows() {
        WorkflowDefinition syncOnly = WorkflowDefinition.builder()
                .workflowId("sync-workflow")
                .name("Sync Workflow")
                .triggerMode(TriggerMode.SYNC)
                .steps(List.of(createTestStep("step-1")))
                .build();
        
        WorkflowDefinition asyncOnly = WorkflowDefinition.builder()
                .workflowId("async-workflow")
                .name("Async Workflow")
                .triggerMode(TriggerMode.ASYNC)
                .triggerEventType("event.type")
                .steps(List.of(createTestStep("step-1")))
                .build();
        
        registry.register(syncOnly);
        registry.register(asyncOnly);
        
        List<WorkflowDefinition> syncWorkflows = registry.findSyncTriggerable();
        
        assertThat(syncWorkflows).hasSize(1);
        assertThat(syncWorkflows.get(0).workflowId()).isEqualTo("sync-workflow");
    }

    @Test
    void shouldClearAllWorkflows() {
        registry.register(createTestWorkflow("workflow-1"));
        registry.register(createTestWorkflow("workflow-2"));
        
        registry.clear();
        
        assertThat(registry.size()).isZero();
        assertThat(registry.getAll()).isEmpty();
    }

    @Test
    void shouldOverwriteExistingWorkflow() {
        WorkflowDefinition original = WorkflowDefinition.builder()
                .workflowId("test-workflow")
                .name("Original")
                .steps(List.of(createTestStep("step-1")))
                .build();

        WorkflowDefinition updated = WorkflowDefinition.builder()
                .workflowId("test-workflow")
                .name("Updated")
                .steps(List.of(createTestStep("step-1")))
                .build();

        registry.register(original);
        registry.register(updated);

        Optional<WorkflowDefinition> result = registry.get("test-workflow");
        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("Updated");
    }

    @Test
    void shouldRegisterWorkflowWithValidDependencies() {
        WorkflowStepDefinition step1 = WorkflowStepDefinition.builder()
                .stepId("step-1")
                .name("Step 1")
                .order(1)
                .build();

        WorkflowStepDefinition step2 = WorkflowStepDefinition.builder()
                .stepId("step-2")
                .name("Step 2")
                .order(2)
                .dependsOn(List.of("step-1"))
                .build();

        WorkflowDefinition workflow = WorkflowDefinition.builder()
                .workflowId("dependency-workflow")
                .name("Dependency Workflow")
                .steps(List.of(step1, step2))
                .build();

        registry.register(workflow);

        assertThat(registry.contains("dependency-workflow")).isTrue();
    }

    @Test
    void shouldRejectWorkflowWithMissingDependency() {
        WorkflowStepDefinition step = WorkflowStepDefinition.builder()
                .stepId("step-1")
                .name("Step 1")
                .order(1)
                .dependsOn(List.of("non-existent-step"))
                .build();

        WorkflowDefinition workflow = WorkflowDefinition.builder()
                .workflowId("invalid-workflow")
                .name("Invalid Workflow")
                .steps(List.of(step))
                .build();

        org.junit.jupiter.api.Assertions.assertThrows(
                WorkflowValidationException.class,
                () -> registry.register(workflow)
        );
    }

    @Test
    void shouldRejectWorkflowWithCyclicDependency() {
        WorkflowStepDefinition step1 = WorkflowStepDefinition.builder()
                .stepId("step-1")
                .name("Step 1")
                .order(1)
                .dependsOn(List.of("step-2"))
                .build();

        WorkflowStepDefinition step2 = WorkflowStepDefinition.builder()
                .stepId("step-2")
                .name("Step 2")
                .order(2)
                .dependsOn(List.of("step-1"))
                .build();

        WorkflowDefinition workflow = WorkflowDefinition.builder()
                .workflowId("cyclic-workflow")
                .name("Cyclic Workflow")
                .steps(List.of(step1, step2))
                .build();

        org.junit.jupiter.api.Assertions.assertThrows(
                WorkflowValidationException.class,
                () -> registry.register(workflow)
        );
    }

    private WorkflowDefinition createTestWorkflow(String id) {
        return WorkflowDefinition.builder()
                .workflowId(id)
                .name("Test Workflow " + id)
                .triggerMode(TriggerMode.BOTH)
                .timeout(Duration.ofMinutes(5))
                .steps(List.of(createTestStep("step-1")))
                .build();
    }

    private WorkflowStepDefinition createTestStep(String id) {
        return WorkflowStepDefinition.builder()
                .stepId(id)
                .name("Test Step " + id)
                .order(1)
                .build();
    }
}
