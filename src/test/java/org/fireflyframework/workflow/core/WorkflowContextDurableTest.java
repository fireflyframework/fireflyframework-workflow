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
import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.model.WorkflowDefinition;
import org.fireflyframework.workflow.model.WorkflowInstance;
import org.fireflyframework.workflow.model.WorkflowStepDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the durable execution methods added to WorkflowContext:
 * sideEffect, heartbeat, and startChildWorkflow.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowContextDurableTest {

    @Mock
    private WorkflowAggregate aggregate;

    private WorkflowDefinition definition;
    private WorkflowInstance instance;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        definition = WorkflowDefinition.builder()
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
        instance = WorkflowInstance.create(definition, Map.of("key", "value"), "corr-1", "test");
    }

    // ========================================================================
    // sideEffect tests
    // ========================================================================

    @Nested
    @DisplayName("sideEffect")
    class SideEffectTests {

        @Test
        @DisplayName("first call should execute supplier and record on aggregate")
        void sideEffect_firstCall_shouldExecuteAndRecord() {
            // Arrange: aggregate has no stored value
            when(aggregate.getSideEffect("randomId")).thenReturn(Optional.empty());

            WorkflowContext context = new WorkflowContext(
                    definition, instance, "step-1", objectMapper, false, aggregate);

            Supplier<String> supplier = () -> "generated-value";

            // Act
            String result = context.sideEffect("randomId", supplier);

            // Assert
            assertThat(result).isEqualTo("generated-value");
            verify(aggregate).getSideEffect("randomId");
            verify(aggregate).recordSideEffect("randomId", "generated-value");
        }

        @Test
        @DisplayName("second call should replay stored value without calling supplier")
        void sideEffect_secondCall_shouldReplay() {
            // Arrange: aggregate already has a stored value
            when(aggregate.getSideEffect("randomId")).thenReturn(Optional.of("stored-value"));

            WorkflowContext context = new WorkflowContext(
                    definition, instance, "step-1", objectMapper, false, aggregate);

            @SuppressWarnings("unchecked")
            Supplier<String> supplier = mock(Supplier.class);

            // Act
            String result = context.sideEffect("randomId", supplier);

            // Assert
            assertThat(result).isEqualTo("stored-value");
            verify(aggregate).getSideEffect("randomId");
            verify(aggregate, never()).recordSideEffect(anyString(), any());
            verify(supplier, never()).get();
        }

        @Test
        @DisplayName("without aggregate should execute supplier directly")
        void sideEffect_withoutAggregate_shouldExecuteDirectly() {
            // Arrange: no aggregate (cache-only mode)
            WorkflowContext context = new WorkflowContext(
                    definition, instance, "step-1", objectMapper, false);

            Supplier<String> supplier = () -> "direct-value";

            // Act
            String result = context.sideEffect("randomId", supplier);

            // Assert
            assertThat(result).isEqualTo("direct-value");
        }
    }

    // ========================================================================
    // heartbeat tests
    // ========================================================================

    @Nested
    @DisplayName("heartbeat")
    class HeartbeatTests {

        @Test
        @DisplayName("with aggregate should delegate to aggregate")
        void heartbeat_withAggregate_shouldRecord() {
            WorkflowContext context = new WorkflowContext(
                    definition, instance, "step-1", objectMapper, false, aggregate);

            Map<String, Object> details = Map.of("progress", 50, "message", "halfway");

            // Act
            context.heartbeat(details);

            // Assert
            verify(aggregate).heartbeat("step-1", details);
        }

        @Test
        @DisplayName("without aggregate should be no-op")
        void heartbeat_withoutAggregate_shouldBeNoOp() {
            WorkflowContext context = new WorkflowContext(
                    definition, instance, "step-1", objectMapper, false);

            // Act — should not throw
            context.heartbeat(Map.of("progress", 75));

            // Assert — no exception, no aggregate interaction
            verifyNoInteractions(aggregate);
        }
    }

    // ========================================================================
    // startChildWorkflow tests
    // ========================================================================

    @Nested
    @DisplayName("startChildWorkflow")
    class StartChildWorkflowTests {

        @Test
        @DisplayName("with aggregate should spawn child workflow")
        void startChildWorkflow_withAggregate_shouldSpawn() {
            WorkflowContext context = new WorkflowContext(
                    definition, instance, "step-1", objectMapper, false, aggregate);

            Map<String, Object> childInput = Map.of("orderId", "ORD-123");

            // Act
            StepVerifier.create(context.startChildWorkflow("child-workflow-def", childInput))
                    .verifyComplete();

            // Assert
            verify(aggregate).spawnChildWorkflow(
                    anyString(),
                    eq("child-workflow-def"),
                    eq(childInput),
                    eq("step-1"));
        }

        @Test
        @DisplayName("without aggregate should return error")
        void startChildWorkflow_withoutAggregate_shouldError() {
            WorkflowContext context = new WorkflowContext(
                    definition, instance, "step-1", objectMapper, false);

            // Act & Assert
            StepVerifier.create(context.startChildWorkflow("child-workflow-def", Map.of()))
                    .expectError(UnsupportedOperationException.class)
                    .verify();
        }
    }
}
