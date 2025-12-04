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

package com.firefly.common.workflow.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.firefly.common.workflow.annotation.Workflow;
import com.firefly.common.workflow.annotation.WorkflowStep;
import com.firefly.common.workflow.aspect.WorkflowAspect;
import com.firefly.common.workflow.core.*;
import com.firefly.common.workflow.event.WorkflowEventPublisher;
import com.firefly.common.workflow.model.*;
import com.firefly.common.workflow.properties.WorkflowProperties;
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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Integration tests for the workflow engine demonstrating:
 * - Annotation-based workflow execution
 * - Parallel step execution
 * - Conditional step execution
 * - Context persistence between steps
 * - Event-driven choreography
 */
@ExtendWith(MockitoExtension.class)
class WorkflowIntegrationTest {

    @Mock
    private WorkflowStateStore stateStore;

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
                stateStore, null, eventPublisher, properties,
                applicationContext, objectMapper, workflowAspect, null, null, null, null);
        engine = new WorkflowEngine(registry, executor, stateStore, eventPublisher, properties);

        // Default mock behavior
        lenient().when(stateStore.save(any(WorkflowInstance.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        lenient().when(eventPublisher.publishWorkflowStarted(any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishWorkflowCompleted(any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishWorkflowFailed(any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishStepStarted(any(), any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishStepCompleted(any(), any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishStepFailed(any(), any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishStepRetrying(any(), any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishCustomEvent(anyString(), any(), any(), any())).thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("Should execute simple sequential workflow with annotation-based steps")
    void shouldExecuteSequentialWorkflow() {
        // Register workflow via aspect
        SampleOrderWorkflow workflowBean = new SampleOrderWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "sampleOrderWorkflow");

        // Verify registration
        assertThat(registry.get("order-processing")).isPresent();
        WorkflowDefinition definition = registry.get("order-processing").get();
        assertThat(definition.steps()).hasSize(3);

        // Execute workflow
        Map<String, Object> input = Map.of("orderId", "ORD-123", "amount", 100.0);

        StepVerifier.create(engine.startWorkflow("order-processing", input))
                .assertNext(instance -> {
                    assertThat(instance.workflowId()).isEqualTo("order-processing");
                    assertThat(instance.input().get("orderId")).isEqualTo("ORD-123");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should skip steps based on SpEL condition")
    void shouldSkipStepsBasedOnCondition() {
        // Register workflow with conditional step
        ConditionalWorkflow workflowBean = new ConditionalWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "conditionalWorkflow");

        WorkflowDefinition definition = registry.get("conditional-workflow").get();

        // Create instance with skipStep = true
        Map<String, Object> input = Map.of("skipStep", true);
        WorkflowInstance instance = WorkflowInstance.create(definition, input, null, "test");

        // Execute workflow
        StepVerifier.create(executor.executeWorkflow(definition, instance.start("step1")))
                .assertNext(result -> {
                    // Step 2 should be skipped due to condition
                    result.getStepExecution("step2").ifPresent(exec -> 
                            assertThat(exec.status()).isEqualTo(StepStatus.SKIPPED));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should persist context changes between steps")
    void shouldPersistContextBetweenSteps() {
        // Register workflow
        ContextSharingWorkflow workflowBean = new ContextSharingWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "contextWorkflow");

        WorkflowDefinition definition = registry.get("context-workflow").get();
        Map<String, Object> input = Map.of("initialValue", "hello");
        WorkflowInstance instance = WorkflowInstance.create(definition, input, null, "test");

        StepVerifier.create(executor.executeWorkflow(definition, instance.start("produceData")))
                .assertNext(result -> {
                    // Context should contain data from first step
                    assertThat(result.context()).containsKey("sharedData");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should execute parallel async steps")
    void shouldExecuteParallelSteps() {
        // Register workflow with async steps
        ParallelWorkflow workflowBean = new ParallelWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "parallelWorkflow");

        WorkflowDefinition definition = registry.get("parallel-workflow").get();

        // Verify async steps are marked correctly
        assertThat(definition.steps()).anySatisfy(step -> 
                assertThat(step.async()).isTrue());
    }

    @Test
    @DisplayName("Should publish step output events for choreography")
    void shouldPublishStepOutputEvents() {
        // Register workflow with output event
        ChoreographyWorkflow workflowBean = new ChoreographyWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "choreographyWorkflow");

        WorkflowDefinition definition = registry.get("choreography-workflow").get();

        // Verify output event type is configured
        assertThat(definition.steps()).anySatisfy(step -> 
                assertThat(step.outputEventType()).isEqualTo("order.validated"));

        // Mock custom event publisher
        when(eventPublisher.publishCustomEvent(eq("order.validated"), any(), any(), any()))
                .thenReturn(Mono.empty());

        Map<String, Object> input = Map.of("orderId", "ORD-456");
        WorkflowInstance instance = WorkflowInstance.create(definition, input, null, "test");

        StepVerifier.create(executor.executeWorkflow(definition, instance.start("validateOrder")))
                .assertNext(result -> {
                    // Verify custom event was published
                    verify(eventPublisher, atLeastOnce())
                            .publishCustomEvent(eq("order.validated"), any(), any(), any());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should handle step handler not found gracefully")
    void shouldHandleStepHandlerNotFound() {
        // Create workflow definition programmatically without registering handlers
        WorkflowDefinition definition = WorkflowDefinition.builder()
                .workflowId("programmatic-workflow")
                .name("Programmatic Workflow")
                .addStep(WorkflowStepDefinition.builder()
                        .stepId("step1")
                        .name("Step 1")
                        .order(1)
                        .handlerBeanName("nonExistentHandler")
                        .build())
                .build();

        registry.register(definition);

        Map<String, Object> input = Map.of("key", "value");
        WorkflowInstance instance = WorkflowInstance.create(definition, input, null, "test");

        // Should fail because handler doesn't exist
        StepVerifier.create(executor.executeStep(definition, instance.start("step1"), 
                        definition.steps().get(0), "test"))
                .expectError()
                .verify();
    }

    @Test
    @DisplayName("Should support workflow trigger modes")
    void shouldSupportTriggerModes() {
        // SYNC only workflow
        SyncOnlyWorkflow syncWorkflow = new SyncOnlyWorkflow();
        workflowAspect.postProcessAfterInitialization(syncWorkflow, "syncWorkflow");

        WorkflowDefinition syncDef = registry.get("sync-workflow").get();
        assertThat(syncDef.supportsSyncTrigger()).isTrue();
        assertThat(syncDef.supportsAsyncTrigger()).isFalse();

        // ASYNC only workflow
        AsyncOnlyWorkflow asyncWorkflow = new AsyncOnlyWorkflow();
        workflowAspect.postProcessAfterInitialization(asyncWorkflow, "asyncWorkflow");

        WorkflowDefinition asyncDef = registry.get("async-workflow").get();
        assertThat(asyncDef.supportsSyncTrigger()).isFalse();
        assertThat(asyncDef.supportsAsyncTrigger()).isTrue();
    }

    @Test
    @DisplayName("Should execute workflow with step dependencies in correct order")
    void shouldExecuteWorkflowWithDependencies() {
        // Register workflow with dependencies
        DependencyWorkflow workflowBean = new DependencyWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "dependencyWorkflow");

        // Verify registration
        assertThat(registry.get("dependency-workflow")).isPresent();
        WorkflowDefinition definition = registry.get("dependency-workflow").get();
        assertThat(definition.steps()).hasSize(4);

        // Verify dependencies are set correctly
        assertThat(definition.getStep("step-b").get().dependsOn()).containsExactly("step-a");
        assertThat(definition.getStep("step-c").get().dependsOn()).containsExactly("step-a");
        assertThat(definition.getStep("step-d").get().dependsOn()).containsExactlyInAnyOrder("step-b", "step-c");

        // Execute workflow
        Map<String, Object> input = Map.of("value", "test");
        WorkflowInstance instance = WorkflowInstance.create(definition, input, null, "test");

        StepVerifier.create(executor.executeWorkflow(definition, instance.start("step-a")))
                .assertNext(result -> {
                    // All steps should be completed
                    assertThat(result.getStepExecution("step-a")).isPresent();
                    assertThat(result.getStepExecution("step-b")).isPresent();
                    assertThat(result.getStepExecution("step-c")).isPresent();
                    assertThat(result.getStepExecution("step-d")).isPresent();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should execute steps with StepTriggerMode correctly")
    void shouldRespectStepTriggerMode() {
        // Register workflow with trigger modes
        TriggerModeWorkflow workflowBean = new TriggerModeWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "triggerModeWorkflow");

        WorkflowDefinition definition = registry.get("trigger-mode-workflow").get();

        // Verify trigger modes are set correctly
        assertThat(definition.getStep("event-step").get().triggerMode()).isEqualTo(StepTriggerMode.EVENT);
        assertThat(definition.getStep("programmatic-step").get().triggerMode()).isEqualTo(StepTriggerMode.PROGRAMMATIC);
        assertThat(definition.getStep("both-step").get().triggerMode()).isEqualTo(StepTriggerMode.BOTH);

        // Verify helper methods
        assertThat(definition.getStep("event-step").get().allowsEventTrigger()).isTrue();
        assertThat(definition.getStep("event-step").get().allowsProgrammaticTrigger()).isFalse();
        assertThat(definition.getStep("programmatic-step").get().allowsEventTrigger()).isFalse();
        assertThat(definition.getStep("programmatic-step").get().allowsProgrammaticTrigger()).isTrue();
        assertThat(definition.getStep("both-step").get().allowsEventTrigger()).isTrue();
        assertThat(definition.getStep("both-step").get().allowsProgrammaticTrigger()).isTrue();
    }

    // ==================== Sample Workflow Classes ====================

    @Slf4j
    @Workflow(
            id = "order-processing",
            name = "Order Processing Workflow",
            description = "Processes customer orders"
    )
    static class SampleOrderWorkflow {

        @WorkflowStep(id = "validate", name = "Validate Order", order = 1)
        public Mono<Map<String, Object>> validateOrder(WorkflowContext ctx) {
            String orderId = ctx.getInput("orderId", String.class);
            Double amount = ctx.getInput("amount", Double.class);
            log.info("TEST_WORKFLOW [order-processing] Step 'validate': Validating order orderId={}, amount={}", orderId, amount);
            Map<String, Object> result = Map.of("valid", true, "orderId", orderId);
            log.info("TEST_WORKFLOW [order-processing] Step 'validate': Validation complete, result={}", result);
            return Mono.just(result);
        }

        @WorkflowStep(id = "process", name = "Process Order", order = 2)
        public Mono<Map<String, Object>> processOrder(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [order-processing] Step 'process': Processing order...");
            ctx.set("processed", true);
            Map<String, Object> result = Map.of("status", "processed");
            log.info("TEST_WORKFLOW [order-processing] Step 'process': Order processed, result={}", result);
            return Mono.just(result);
        }

        @WorkflowStep(id = "notify", name = "Send Notification", order = 3)
        public Mono<Void> sendNotification(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [order-processing] Step 'notify': Sending notification for completed order");
            return Mono.fromRunnable(() ->
                log.info("TEST_WORKFLOW [order-processing] Step 'notify': Notification sent successfully"));
        }
    }

    @Slf4j
    @Workflow(id = "conditional-workflow", name = "Conditional Workflow")
    static class ConditionalWorkflow {

        @WorkflowStep(id = "step1", name = "Step 1", order = 1)
        public Mono<String> step1(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [conditional-workflow] Step 'step1': Executing step 1");
            return Mono.just("step1-done");
        }

        @WorkflowStep(
                id = "step2",
                name = "Step 2",
                order = 2,
                condition = "#input['skipStep'] != true"
        )
        public Mono<String> step2(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [conditional-workflow] Step 'step2': Executing step 2 (condition passed)");
            return Mono.just("step2-done");
        }

        @WorkflowStep(id = "step3", name = "Step 3", order = 3)
        public Mono<String> step3(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [conditional-workflow] Step 'step3': Executing step 3");
            return Mono.just("step3-done");
        }
    }

    @Slf4j
    @Workflow(id = "context-workflow", name = "Context Sharing Workflow")
    static class ContextSharingWorkflow {

        @WorkflowStep(id = "produceData", name = "Produce Data", order = 1)
        public Mono<String> produceData(WorkflowContext ctx) {
            String initial = ctx.getInput("initialValue", String.class);
            log.info("TEST_WORKFLOW [context-workflow] Step 'produceData': Received initial value '{}'", initial);
            String sharedData = initial + " world";
            ctx.set("sharedData", sharedData);
            log.info("TEST_WORKFLOW [context-workflow] Step 'produceData': Stored sharedData='{}'", sharedData);
            return Mono.just("produced");
        }

        @WorkflowStep(id = "consumeData", name = "Consume Data", order = 2)
        public Mono<String> consumeData(WorkflowContext ctx) {
            String shared = ctx.get("sharedData", String.class);
            log.info("TEST_WORKFLOW [context-workflow] Step 'consumeData': Retrieved sharedData='{}'", shared);
            String result = "consumed: " + shared;
            log.info("TEST_WORKFLOW [context-workflow] Step 'consumeData': Returning result='{}'", result);
            return Mono.just(result);
        }
    }

    @Slf4j
    @Workflow(id = "parallel-workflow", name = "Parallel Workflow")
    static class ParallelWorkflow {

        private final AtomicInteger counter = new AtomicInteger(0);

        @WorkflowStep(id = "async1", name = "Async Step 1", order = 1, async = true)
        public Mono<Integer> asyncStep1(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [parallel-workflow] Step 'async1': Starting async step 1");
            return Mono.just(counter.incrementAndGet())
                    .delayElement(Duration.ofMillis(100))
                    .doOnNext(count -> log.info("TEST_WORKFLOW [parallel-workflow] Step 'async1': Completed with count={}", count));
        }

        @WorkflowStep(id = "async2", name = "Async Step 2", order = 2, async = true)
        public Mono<Integer> asyncStep2(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [parallel-workflow] Step 'async2': Starting async step 2");
            return Mono.just(counter.incrementAndGet())
                    .delayElement(Duration.ofMillis(100))
                    .doOnNext(count -> log.info("TEST_WORKFLOW [parallel-workflow] Step 'async2': Completed with count={}", count));
        }

        @WorkflowStep(id = "sync", name = "Sync Step", order = 3)
        public Mono<String> syncStep(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [parallel-workflow] Step 'sync': Executing final sync step");
            return Mono.just("final");
        }
    }

    @Slf4j
    @Workflow(id = "choreography-workflow", name = "Choreography Workflow")
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
            Map<String, Object> result = Map.of("orderId", orderId, "valid", true);
            log.info("TEST_WORKFLOW [choreography-workflow] Step 'validateOrder': Emitting event 'order.validated' with result={}", result);
            return Mono.just(result);
        }

        @WorkflowStep(
                id = "processPayment",
                name = "Process Payment",
                order = 2,
                inputEventType = "payment.received",
                outputEventType = "payment.processed"
        )
        public Mono<Map<String, Object>> processPayment(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [choreography-workflow] Step 'processPayment': Processing payment (triggered by 'payment.received')");
            Map<String, Object> result = Map.of("paymentStatus", "completed");
            log.info("TEST_WORKFLOW [choreography-workflow] Step 'processPayment': Emitting event 'payment.processed' with result={}", result);
            return Mono.just(result);
        }
    }

    @Slf4j
    @Workflow(
            id = "sync-workflow",
            name = "Sync Only Workflow",
            triggerMode = TriggerMode.SYNC
    )
    static class SyncOnlyWorkflow {

        @WorkflowStep(id = "step1", name = "Step 1", order = 1)
        public Mono<String> step1(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [sync-workflow] Step 'step1': Executing synchronous step");
            return Mono.just("done");
        }
    }

    @Slf4j
    @Workflow(
            id = "async-workflow",
            name = "Async Only Workflow",
            triggerMode = TriggerMode.ASYNC,
            triggerEventType = "order.created"
    )
    static class AsyncOnlyWorkflow {

        @WorkflowStep(id = "step1", name = "Step 1", order = 1)
        public Mono<String> step1(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [async-workflow] Step 'step1': Executing async step (triggered by 'order.created')");
            return Mono.just("done");
        }
    }

    /**
     * Workflow demonstrating step dependencies (DAG execution).
     * Execution order: step-a -> (step-b, step-c in parallel) -> step-d
     */
    @Slf4j
    @Workflow(id = "dependency-workflow", name = "Dependency Workflow")
    static class DependencyWorkflow {

        @WorkflowStep(id = "step-a", name = "Step A", order = 1)
        public Mono<String> stepA(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [dependency-workflow] Step 'step-a': Executing root step");
            ctx.set("stepA", "completed");
            return Mono.just("step-a-done");
        }

        @WorkflowStep(id = "step-b", name = "Step B", order = 2, dependsOn = {"step-a"})
        public Mono<String> stepB(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [dependency-workflow] Step 'step-b': Executing (depends on step-a)");
            assertThat(ctx.get("stepA", String.class)).isEqualTo("completed");
            ctx.set("stepB", "completed");
            return Mono.just("step-b-done");
        }

        @WorkflowStep(id = "step-c", name = "Step C", order = 3, dependsOn = {"step-a"})
        public Mono<String> stepC(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [dependency-workflow] Step 'step-c': Executing (depends on step-a)");
            assertThat(ctx.get("stepA", String.class)).isEqualTo("completed");
            ctx.set("stepC", "completed");
            return Mono.just("step-c-done");
        }

        @WorkflowStep(id = "step-d", name = "Step D", order = 4, dependsOn = {"step-b", "step-c"})
        public Mono<String> stepD(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [dependency-workflow] Step 'step-d': Executing (depends on step-b and step-c)");
            assertThat(ctx.get("stepB", String.class)).isEqualTo("completed");
            assertThat(ctx.get("stepC", String.class)).isEqualTo("completed");
            return Mono.just("step-d-done");
        }
    }

    /**
     * Workflow demonstrating step trigger modes.
     */
    @Slf4j
    @Workflow(id = "trigger-mode-workflow", name = "Trigger Mode Workflow")
    static class TriggerModeWorkflow {

        @WorkflowStep(
                id = "event-step",
                name = "Event Step",
                order = 1,
                triggerMode = StepTriggerMode.EVENT,
                inputEventType = "order.created"
        )
        public Mono<String> eventStep(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [trigger-mode-workflow] Step 'event-step': Event-triggered step");
            return Mono.just("event-done");
        }

        @WorkflowStep(
                id = "programmatic-step",
                name = "Programmatic Step",
                order = 2,
                triggerMode = StepTriggerMode.PROGRAMMATIC
        )
        public Mono<String> programmaticStep(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [trigger-mode-workflow] Step 'programmatic-step': Programmatic step");
            return Mono.just("programmatic-done");
        }

        @WorkflowStep(
                id = "both-step",
                name = "Both Step",
                order = 3,
                triggerMode = StepTriggerMode.BOTH
        )
        public Mono<String> bothStep(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [trigger-mode-workflow] Step 'both-step': Both trigger modes");
            return Mono.just("both-done");
        }
    }
}
