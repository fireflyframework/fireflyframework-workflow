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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fireflyframework.workflow.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for WorkflowContext.
 */
class WorkflowContextTest {

    private WorkflowContext context;
    private WorkflowDefinition definition;
    private WorkflowInstance instance;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        definition = createTestDefinition();
        instance = createTestInstance();
        context = new WorkflowContext(definition, instance, "step-1", objectMapper);
    }

    @Test
    void shouldGetInstanceId() {
        assertThat(context.getInstanceId()).isEqualTo(instance.instanceId());
    }

    @Test
    void shouldGetWorkflowId() {
        assertThat(context.getWorkflowId()).isEqualTo("test-workflow");
    }

    @Test
    void shouldGetCorrelationId() {
        assertThat(context.getCorrelationId()).isEqualTo("corr-123");
    }

    @Test
    void shouldGetInputValue() {
        Optional<Object> value = context.getInput("orderId");
        
        assertThat(value).isPresent();
        assertThat(value.get()).isEqualTo("ORD-001");
    }

    @Test
    void shouldGetTypedInputValue() {
        String orderId = context.getInput("orderId", String.class);
        
        assertThat(orderId).isEqualTo("ORD-001");
    }

    @Test
    void shouldReturnNullForMissingInput() {
        String missing = context.getInput("missing", String.class);
        
        assertThat(missing).isNull();
    }

    @Test
    void shouldGetAllInputs() {
        Map<String, Object> inputs = context.getAllInputs();
        
        assertThat(inputs).containsEntry("orderId", "ORD-001");
        assertThat(inputs).containsEntry("customerId", "CUST-001");
    }

    @Test
    void shouldSetAndGetContextValue() {
        context.set("myKey", "myValue");
        
        Optional<Object> value = context.get("myKey");
        assertThat(value).isPresent();
        assertThat(value.get()).isEqualTo("myValue");
    }

    @Test
    void shouldGetTypedContextValue() {
        context.set("count", 42);
        
        Integer count = context.get("count", Integer.class);
        assertThat(count).isEqualTo(42);
    }

    @Test
    void shouldGetOrDefaultValue() {
        String value = context.getOrDefault("missing", "default");
        
        assertThat(value).isEqualTo("default");
    }

    @Test
    void shouldCheckIfKeyExists() {
        context.set("existing", "value");
        
        assertThat(context.has("existing")).isTrue();
        assertThat(context.has("missing")).isFalse();
    }

    @Test
    void shouldRemoveValue() {
        context.set("toRemove", "value");
        context.remove("toRemove");
        
        assertThat(context.has("toRemove")).isFalse();
    }

    @Test
    void shouldMergeData() {
        context.merge(Map.of("key1", "val1", "key2", "val2"));
        
        assertThat(context.get("key1")).isPresent();
        assertThat(context.get("key2")).isPresent();
    }

    @Test
    void shouldGetStepOutput() {
        StepExecution completed = StepExecution.create("step-1", "Step 1", null)
                .start()
                .complete(Map.of("result", "success"));
        
        WorkflowInstance withStep = instance.withStepExecution(completed);
        WorkflowContext contextWithStep = new WorkflowContext(definition, withStep, "step-2", objectMapper);
        
        Optional<Object> output = contextWithStep.getStepOutput("step-1");
        assertThat(output).isPresent();
        assertThat(output.get()).isEqualTo(Map.of("result", "success"));
    }

    @Test
    void shouldGetTypedStepOutput() {
        StepExecution completed = StepExecution.create("step-1", "Step 1", null)
                .start()
                .complete(Map.of("value", 100));
        
        WorkflowInstance withStep = instance.withStepExecution(completed);
        WorkflowContext contextWithStep = new WorkflowContext(definition, withStep, "step-2", objectMapper);
        
        @SuppressWarnings("unchecked")
        Map<String, Integer> output = contextWithStep.getStepOutput("step-1", Map.class);
        assertThat(output).containsEntry("value", 100);
    }

    @Test
    void shouldReturnEmptyForMissingStepOutput() {
        Optional<Object> output = context.getStepOutput("missing-step");
        assertThat(output).isEmpty();
    }

    @Test
    void shouldGetAllData() {
        context.set("key1", "value1");
        context.set("key2", "value2");
        
        Map<String, Object> allData = context.getAllData();
        
        assertThat(allData).containsKey("key1");
        assertThat(allData).containsKey("key2");
    }

    @Test
    void shouldCreateContextForNextStep() {
        context.set("sharedKey", "sharedValue");
        
        WorkflowInstance updated = instance.withCurrentStep("step-2");
        WorkflowContext nextContext = context.forNextStep("step-2", updated);
        
        assertThat(nextContext.getCurrentStepId()).isEqualTo("step-2");
        assertThat(nextContext.get("sharedKey")).isPresent();
    }

    @Test
    void shouldConvertTypedValues() {
        context.set("nested", Map.of("name", "test", "value", 123));
        
        TestData data = context.get("nested", TestData.class);
        
        assertThat(data).isNotNull();
        assertThat(data.name()).isEqualTo("test");
        assertThat(data.value()).isEqualTo(123);
    }

    @Test
    void shouldThrowOnInvalidConversion() {
        context.set("invalidData", "not a number");
        
        assertThatThrownBy(() -> context.get("invalidData", Integer.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot convert");
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
                                .build(),
                        WorkflowStepDefinition.builder()
                                .stepId("step-2")
                                .name("Step 2")
                                .order(2)
                                .build()
                ))
                .build();
    }

    private WorkflowInstance createTestInstance() {
        Map<String, Object> input = Map.of(
                "orderId", "ORD-001",
                "customerId", "CUST-001"
        );
        return WorkflowInstance.create(definition, input, "corr-123", "test");
    }

    record TestData(String name, int value) {}
}
