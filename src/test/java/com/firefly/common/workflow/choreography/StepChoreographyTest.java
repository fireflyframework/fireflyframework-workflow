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

package com.firefly.common.workflow.choreography;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firefly.common.workflow.annotation.Workflow;
import com.firefly.common.workflow.annotation.WorkflowStep;
import com.firefly.common.workflow.aspect.WorkflowAspect;
import com.firefly.common.workflow.core.*;
import com.firefly.common.workflow.event.WorkflowEventPublisher;
import com.firefly.common.workflow.model.*;
import com.firefly.common.workflow.properties.WorkflowProperties;
import com.firefly.common.workflow.state.CacheStepStateStore;
import com.firefly.common.workflow.state.StepStateStore;
import com.firefly.common.workflow.state.WorkflowStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for step-level choreography functionality.
 * <p>
 * These tests validate:
 * - Individual step triggering via API
 * - Step state persistence
 * - Workflow state tracking
 * - Event-driven step execution
 */
@ExtendWith(MockitoExtension.class)
class StepChoreographyTest {

    @Mock
    private WorkflowStateStore stateStore;

    @Mock
    private StepStateStore stepStateStore;

    @Mock
    private WorkflowEventPublisher eventPublisher;

    @Mock
    private ApplicationContext applicationContext;

    private WorkflowProperties properties;
    private ObjectMapper objectMapper;
    private WorkflowRegistry registry;
    private WorkflowAspect workflowAspect;
    private WorkflowExecutor executor;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        properties = new WorkflowProperties();
        objectMapper = new ObjectMapper();
        registry = new WorkflowRegistry();
        workflowAspect = new WorkflowAspect(registry);
        executor = new WorkflowExecutor(
                stateStore, stepStateStore, eventPublisher, properties,
                applicationContext, objectMapper, workflowAspect, null, null, null);
        engine = new WorkflowEngine(registry, executor, stateStore, eventPublisher, properties, stepStateStore, null);

        // Default mock behavior
        lenient().when(stateStore.save(any(WorkflowInstance.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(stepStateStore.saveStepState(any(StepState.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(stepStateStore.saveWorkflowState(any(WorkflowState.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(stepStateStore.getStepState(anyString(), anyString(), anyString()))
                .thenReturn(Mono.empty());
        lenient().when(stepStateStore.getWorkflowState(anyString(), anyString()))
                .thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishWorkflowStarted(any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishWorkflowCompleted(any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishStepStarted(any(), any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishStepCompleted(any(), any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishCustomEvent(anyString(), any(), any(), any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Should trigger individual step via engine")
    void shouldTriggerIndividualStep() {
        // Register workflow
        ChoreographyWorkflow workflowBean = new ChoreographyWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "choreographyWorkflow");

        WorkflowDefinition definition = registry.get("choreography-workflow").get();
        WorkflowInstance instance = WorkflowInstance.create(definition, Map.of("orderId", "123"), "corr-1", "api");
        
        when(stateStore.findByWorkflowAndInstanceId("choreography-workflow", instance.instanceId()))
                .thenReturn(Mono.just(instance.start("validateOrder")));

        StepVerifier.create(engine.triggerStep(
                        "choreography-workflow",
                        instance.instanceId(),
                        "validateOrder",
                        Map.of("extra", "data"),
                        "api"))
                .assertNext(result -> {
                    assertThat(result.workflowId()).isEqualTo("choreography-workflow");
                })
                .verifyComplete();

        // Verify step state was persisted
        verify(stepStateStore, atLeastOnce()).saveStepState(any(StepState.class));
    }

    @Test
    @DisplayName("Should persist step state during execution")
    void shouldPersistStepState() {
        // Register workflow
        ChoreographyWorkflow workflowBean = new ChoreographyWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "choreographyWorkflow");

        WorkflowDefinition definition = registry.get("choreography-workflow").get();
        Map<String, Object> input = Map.of("orderId", "ORD-789");
        WorkflowInstance instance = WorkflowInstance.create(definition, input, null, "test");

        StepVerifier.create(executor.executeWorkflow(definition, instance.start("validateOrder")))
                .assertNext(result -> {
                    assertThat(result.status()).isIn(WorkflowStatus.COMPLETED, WorkflowStatus.RUNNING);
                })
                .verifyComplete();

        // Verify step states were saved
        verify(stepStateStore, atLeastOnce()).saveStepState(any(StepState.class));
        // Verify workflow state was saved
        verify(stepStateStore, atLeastOnce()).saveWorkflowState(any(WorkflowState.class));
    }

    @Test
    @DisplayName("Should track workflow state with step history")
    void shouldTrackWorkflowStateWithHistory() {
        // Register workflow
        ChoreographyWorkflow workflowBean = new ChoreographyWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "choreographyWorkflow");

        WorkflowDefinition definition = registry.get("choreography-workflow").get();
        Map<String, Object> input = Map.of("orderId", "ORD-456");
        WorkflowInstance instance = WorkflowInstance.create(definition, input, "corr-1", "test");

        // Mock workflow state retrieval
        WorkflowState initialState = WorkflowState.create(definition, instance.instanceId(), input, "corr-1", "test");
        when(stepStateStore.getWorkflowState("choreography-workflow", instance.instanceId()))
                .thenReturn(Mono.just(initialState));

        StepVerifier.create(executor.executeWorkflow(definition, instance.start("validateOrder")))
                .assertNext(result -> {
                    assertThat(result.status()).isIn(WorkflowStatus.COMPLETED, WorkflowStatus.RUNNING);
                })
                .verifyComplete();

        // Verify workflow state was updated
        verify(stepStateStore, atLeastOnce()).saveWorkflowState(argThat(state -> 
                state.workflowId().equals("choreography-workflow")));
    }

    @Test
    @DisplayName("Should find steps by input event type")
    void shouldFindStepsByInputEventType() {
        // Register workflow with input event types
        EventDrivenWorkflow workflowBean = new EventDrivenWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "eventDrivenWorkflow");

        var matches = engine.findStepsByInputEvent("order.created");
        
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).step().stepId()).isEqualTo("processOrder");
        assertThat(matches.get(0).workflow().workflowId()).isEqualTo("event-driven-workflow");
    }

    @Test
    @DisplayName("Should support step execution with triggeredBy tracking")
    void shouldTrackTriggeredBy() {
        // Register workflow
        ChoreographyWorkflow workflowBean = new ChoreographyWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "choreographyWorkflow");

        WorkflowDefinition definition = registry.get("choreography-workflow").get();
        WorkflowInstance instance = WorkflowInstance.create(definition, Map.of("orderId", "123"), null, "test");
        
        // Execute single step with specific triggeredBy
        StepVerifier.create(executor.executeSingleStep(
                        definition,
                        instance.start("validateOrder"),
                        "validateOrder",
                        "event:order.submitted",
                        Map.of("extra", "data")))
                .assertNext(result -> {
                    assertThat(result.workflowId()).isEqualTo("choreography-workflow");
                })
                .verifyComplete();

        // Verify step state captured triggeredBy
        verify(stepStateStore, atLeastOnce()).saveStepState(argThat(state ->
                state.triggeredBy() != null && state.triggeredBy().contains("event:order.submitted")));
    }

    // ==================== Sample Workflow Classes ====================

    @Slf4j
    @Workflow(
            id = "choreography-workflow",
            name = "Choreography Workflow",
            description = "Workflow demonstrating step-level choreography"
    )
    static class ChoreographyWorkflow {

        @WorkflowStep(
                id = "validateOrder",
                name = "Validate Order",
                order = 1,
                outputEventType = "order.validated"
        )
        public Mono<Map<String, Object>> validateOrder(WorkflowContext ctx) {
            String orderId = ctx.getInput("orderId", String.class);
            log.info("TEST_WORKFLOW [choreography-workflow] Step 'validateOrder': Validating order orderId={}", orderId);
            Map<String, Object> result = Map.of("valid", true, "orderId", orderId != null ? orderId : "unknown");
            log.info("TEST_WORKFLOW [choreography-workflow] Step 'validateOrder': Emitting 'order.validated' event, result={}", result);
            return Mono.just(result);
        }

        @WorkflowStep(
                id = "processPayment",
                name = "Process Payment",
                order = 2,
                outputEventType = "payment.processed"
        )
        public Mono<Map<String, Object>> processPayment(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [choreography-workflow] Step 'processPayment': Processing payment");
            Map<String, Object> result = Map.of("paid", true);
            log.info("TEST_WORKFLOW [choreography-workflow] Step 'processPayment': Emitting 'payment.processed' event, result={}", result);
            return Mono.just(result);
        }
    }

    @Slf4j
    @Workflow(
            id = "event-driven-workflow",
            name = "Event Driven Workflow",
            triggerMode = TriggerMode.ASYNC,
            triggerEventType = "order.created"
    )
    static class EventDrivenWorkflow {

        @WorkflowStep(
                id = "processOrder",
                name = "Process Order",
                order = 1,
                inputEventType = "order.created",
                outputEventType = "order.processed"
        )
        public Mono<Map<String, Object>> processOrder(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [event-driven-workflow] Step 'processOrder': Processing order (triggered by 'order.created')");
            Map<String, Object> result = Map.of("processed", true);
            log.info("TEST_WORKFLOW [event-driven-workflow] Step 'processOrder': Emitting 'order.processed' event, result={}", result);
            return Mono.just(result);
        }

        @WorkflowStep(
                id = "shipOrder",
                name = "Ship Order",
                order = 2,
                inputEventType = "order.processed",
                outputEventType = "order.shipped"
        )
        public Mono<Map<String, Object>> shipOrder(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [event-driven-workflow] Step 'shipOrder': Shipping order (triggered by 'order.processed')");
            Map<String, Object> result = Map.of("shipped", true);
            log.info("TEST_WORKFLOW [event-driven-workflow] Step 'shipOrder': Emitting 'order.shipped' event, result={}", result);
            return Mono.just(result);
        }
    }
}
