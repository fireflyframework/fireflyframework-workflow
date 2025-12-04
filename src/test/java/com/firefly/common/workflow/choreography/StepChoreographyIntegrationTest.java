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
import com.firefly.common.cache.core.CacheAdapter;
import com.firefly.common.cache.core.CacheHealth;
import com.firefly.common.cache.core.CacheStats;
import com.firefly.common.cache.core.CacheType;
import com.firefly.common.eda.annotation.PublisherType;
import com.firefly.common.eda.event.EventEnvelope;
import com.firefly.common.eda.publisher.EventPublisher;
import com.firefly.common.eda.publisher.EventPublisherFactory;
import com.firefly.common.workflow.annotation.Workflow;
import com.firefly.common.workflow.annotation.WorkflowStep;
import com.firefly.common.workflow.aspect.WorkflowAspect;
import com.firefly.common.workflow.core.*;
import com.firefly.common.workflow.event.WorkflowEventListener;
import com.firefly.common.workflow.event.WorkflowEventPublisher;
import com.firefly.common.workflow.model.*;
import com.firefly.common.workflow.properties.WorkflowProperties;
import com.firefly.common.workflow.state.CacheStepStateStore;
import com.firefly.common.workflow.state.CacheWorkflowStateStore;
import com.firefly.common.workflow.state.StepStateStore;
import com.firefly.common.workflow.state.WorkflowStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Integration tests for step-level choreography using real implementations.
 * <p>
 * These tests use an in-memory cache adapter to verify the actual flow
 * of step and workflow state persistence without mocking.
 */
class StepChoreographyIntegrationTest {

    private InMemoryCacheAdapter cacheAdapter;
    private WorkflowProperties properties;
    private ObjectMapper objectMapper;
    private WorkflowRegistry registry;
    private WorkflowAspect workflowAspect;
    private WorkflowStateStore stateStore;
    private StepStateStore stepStateStore;
    private WorkflowEventPublisher eventPublisher;
    private WorkflowExecutor executor;
    private WorkflowEngine engine;
    private WorkflowEventListener eventListener;
    private ApplicationContext applicationContext;

    // Track published events for verification
    private List<EventEnvelope> publishedEvents = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        // Use real in-memory cache adapter
        cacheAdapter = new InMemoryCacheAdapter();
        properties = new WorkflowProperties();
        objectMapper = new ObjectMapper();
        registry = new WorkflowRegistry();
        workflowAspect = new WorkflowAspect(registry);
        
        // Real state stores using cache
        stateStore = new CacheWorkflowStateStore(cacheAdapter, properties);
        stepStateStore = new CacheStepStateStore(cacheAdapter, properties);
        
        // Event publisher that captures events
        EventPublisherFactory publisherFactory = mock(EventPublisherFactory.class);
        EventPublisher mockPublisher = mock(EventPublisher.class);
        when(publisherFactory.getPublisher(any(PublisherType.class))).thenReturn(mockPublisher);
        when(publisherFactory.getPublisher(any(PublisherType.class), any())).thenReturn(mockPublisher);
        when(mockPublisher.publish(any(), anyString(), any())).thenAnswer(inv -> {
            Object event = inv.getArgument(0);
            String destination = inv.getArgument(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> headers = inv.getArgument(2);
            // Extract eventType from headers if available
            String eventType = headers != null && headers.containsKey("eventType") 
                    ? headers.get("eventType").toString() 
                    : event.getClass().getSimpleName();
            EventEnvelope envelope = EventEnvelope.minimal(destination, eventType, event);
            publishedEvents.add(envelope);
            return Mono.empty();
        });
        when(mockPublisher.publish(any(), anyString())).thenAnswer(inv -> {
            Object event = inv.getArgument(0);
            String destination = inv.getArgument(1);
            EventEnvelope envelope = EventEnvelope.minimal(destination, event.getClass().getSimpleName(), event);
            publishedEvents.add(envelope);
            return Mono.empty();
        });
        when(mockPublisher.isAvailable()).thenReturn(true);
        when(mockPublisher.getPublisherType()).thenReturn(PublisherType.APPLICATION_EVENT);
        eventPublisher = new WorkflowEventPublisher(publisherFactory, properties);
        
        // Mock application context (only needed for programmatic handlers)
        applicationContext = mock(ApplicationContext.class);
        
        // Real executor and engine (null tracer and metrics for tests)
        executor = new WorkflowExecutor(
                stateStore, stepStateStore, eventPublisher, properties,
                applicationContext, objectMapper, workflowAspect, null, null, null);
        engine = new WorkflowEngine(
                registry, executor, stateStore, eventPublisher, properties, stepStateStore, null);
        
        // Real event listener
        eventListener = new WorkflowEventListener(engine, properties, stateStore, stepStateStore);
        
        publishedEvents.clear();
    }

    @Test
    @DisplayName("Should persist step states during workflow execution")
    void shouldPersistStepStatesDuringWorkflowExecution() {
        // Register workflow
        OrderWorkflow workflowBean = new OrderWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "orderWorkflow");

        // Start workflow
        Map<String, Object> input = Map.of("orderId", "ORD-001", "amount", 100.0);
        
        StepVerifier.create(engine.startWorkflow("order-workflow", input, "corr-001", "api"))
                .assertNext(instance -> {
                    assertThat(instance.workflowId()).isEqualTo("order-workflow");
                    assertThat(instance.status()).isEqualTo(WorkflowStatus.COMPLETED);
                })
                .verifyComplete();

        // Verify step states were persisted by getting the instance ID from cache
        StepVerifier.create(cacheAdapter.<String>keys()
                        .flatMap(keys -> {
                            String instanceId = keys.stream()
                                    .filter(k -> k.startsWith("workflow:step:order-workflow:"))
                                    .map(k -> k.split(":")[3])
                                    .findFirst()
                                    .orElse(null);
                            if (instanceId != null) {
                                return stepStateStore.getStepStates("order-workflow", instanceId).collectList();
                            }
                            return Mono.just(List.<StepState>of());
                        }))
                .assertNext(stepStates -> {
                    assertThat(stepStates).isNotEmpty();
                    // All steps should be completed
                    assertThat(stepStates).allMatch(s -> s.status() == StepStatus.COMPLETED);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should persist and retrieve workflow state with step history")
    void shouldPersistWorkflowStateWithStepHistory() {
        // Register workflow
        OrderWorkflow workflowBean = new OrderWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "orderWorkflow");

        // Start workflow and capture instance ID
        String[] instanceId = new String[1];
        
        StepVerifier.create(engine.startWorkflow("order-workflow", Map.of("orderId", "ORD-002"), null, "api"))
                .assertNext(instance -> {
                    instanceId[0] = instance.instanceId();
                    assertThat(instance.status()).isEqualTo(WorkflowStatus.COMPLETED);
                })
                .verifyComplete();

        // Retrieve workflow state
        StepVerifier.create(engine.getWorkflowState("order-workflow", instanceId[0]))
                .assertNext(state -> {
                    assertThat(state.workflowId()).isEqualTo("order-workflow");
                    assertThat(state.instanceId()).isEqualTo(instanceId[0]);
                    assertThat(state.status()).isEqualTo(WorkflowStatus.COMPLETED);
                    assertThat(state.completedSteps()).contains("validate", "process", "notify");
                    assertThat(state.stepHistory()).hasSizeGreaterThanOrEqualTo(3);
                    assertThat(state.getProgress()).isEqualTo(100);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should trigger individual step and persist state")
    void shouldTriggerIndividualStepAndPersistState() {
        // Register workflow
        OrderWorkflow workflowBean = new OrderWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "orderWorkflow");

        WorkflowDefinition definition = registry.get("order-workflow").get();
        
        // Create initial instance manually
        WorkflowInstance instance = WorkflowInstance.create(
                definition, Map.of("orderId", "ORD-003"), "corr-003", "api");
        
        // Save initial instance
        StepVerifier.create(stateStore.save(instance.start("validate")))
                .assertNext(saved -> assertThat(saved.instanceId()).isEqualTo(instance.instanceId()))
                .verifyComplete();

        // Trigger specific step via engine
        StepVerifier.create(engine.triggerStep(
                        "order-workflow",
                        instance.instanceId(),
                        "validate",
                        Map.of("extra", "input"),
                        "event:order.created"))
                .assertNext(result -> {
                    assertThat(result.workflowId()).isEqualTo("order-workflow");
                })
                .verifyComplete();

        // Verify step state was persisted with correct triggeredBy
        StepVerifier.create(stepStateStore.getStepState("order-workflow", instance.instanceId(), "validate"))
                .assertNext(stepState -> {
                    assertThat(stepState.stepId()).isEqualTo("validate");
                    assertThat(stepState.status()).isEqualTo(StepStatus.COMPLETED);
                    assertThat(stepState.triggeredBy()).contains("event:order.created");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should track step execution with timestamps and duration")
    void shouldTrackStepExecutionWithTimestamps() {
        // Register workflow
        OrderWorkflow workflowBean = new OrderWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "orderWorkflow");

        String[] instanceId = new String[1];
        
        StepVerifier.create(engine.startWorkflow("order-workflow", Map.of("orderId", "ORD-004"), null, "api"))
                .assertNext(instance -> {
                    instanceId[0] = instance.instanceId();
                })
                .verifyComplete();

        // Verify step states have timestamps
        StepVerifier.create(stepStateStore.getStepStates("order-workflow", instanceId[0]).collectList())
                .assertNext(stepStates -> {
                    assertThat(stepStates).allSatisfy(state -> {
                        assertThat(state.createdAt()).isNotNull();
                        assertThat(state.startedAt()).isNotNull();
                        assertThat(state.completedAt()).isNotNull();
                        // Steps can execute so fast that duration is 0, so just check it exists
                        assertThat(state.getDuration()).isGreaterThanOrEqualTo(Duration.ZERO);
                    });
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should find steps by input event type for choreography")
    void shouldFindStepsByInputEventType() {
        // Register workflow with input event types
        EventDrivenWorkflow workflowBean = new EventDrivenWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "eventDrivenWorkflow");

        // Find steps that listen for specific events
        var matches = engine.findStepsByInputEvent("order.validated");
        
        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).step().stepId()).isEqualTo("processPayment");
        assertThat(matches.get(0).step().inputEventType()).isEqualTo("order.validated");
    }

    @Test
    @DisplayName("Should publish step output events for choreography")
    void shouldPublishStepOutputEvents() {
        // Register workflow
        EventDrivenWorkflow workflowBean = new EventDrivenWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "eventDrivenWorkflow");

        String[] instanceId = new String[1];
        
        StepVerifier.create(engine.startWorkflow("event-driven-workflow", 
                        Map.of("orderId", "ORD-005"), null, "api"))
                .assertNext(instance -> {
                    instanceId[0] = instance.instanceId();
                })
                .verifyComplete();

        // Verify output events were published
        assertThat(publishedEvents).anySatisfy(event -> 
                assertThat(event.eventType()).isEqualTo("order.validated"));
    }

    @Test
    @DisplayName("Should handle event-driven step triggering via listener")
    void shouldHandleEventDrivenStepTriggering() {
        // Register workflow with input event types
        EventDrivenWorkflow workflowBean = new EventDrivenWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "eventDrivenWorkflow");

        // Simulate incoming event
        EventEnvelope event = EventEnvelope.forPublishing(
                "workflow",
                "order.created",
                Map.of("orderId", "ORD-EVENT-001", "customerId", "CUST-001"),
                "txn-001",
                Map.of(),
                EventEnvelope.EventMetadata.empty(),
                Instant.now(),
                "test",
                "default"
        );

        // Process event through listener
        StepVerifier.create(eventListener.handleEvent(event))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should maintain workflow state consistency across step executions")
    void shouldMaintainWorkflowStateConsistency() {
        // Register workflow
        OrderWorkflow workflowBean = new OrderWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "orderWorkflow");

        String[] instanceId = new String[1];
        
        StepVerifier.create(engine.startWorkflow("order-workflow", 
                        Map.of("orderId", "ORD-006"), "corr-006", "api"))
                .assertNext(instance -> {
                    instanceId[0] = instance.instanceId();
                })
                .verifyComplete();

        // Verify workflow state is consistent
        StepVerifier.create(engine.getWorkflowState("order-workflow", instanceId[0]))
                .assertNext(state -> {
                    // All steps completed + pending should equal total
                    int tracked = state.completedSteps().size() + 
                                  state.failedSteps().size() + 
                                  state.skippedSteps().size() + 
                                  state.pendingSteps().size();
                    assertThat(tracked).isLessThanOrEqualTo(state.totalSteps());
                    
                    // Step history should have entries for completed steps
                    assertThat(state.stepHistory().size()).isGreaterThanOrEqualTo(state.completedSteps().size());
                    
                    // Each history entry should have valid data
                    state.stepHistory().forEach(history -> {
                        assertThat(history.stepId()).isNotNull();
                        assertThat(history.status()).isNotNull();
                        assertThat(history.triggeredBy()).isNotNull();
                    });
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should execute single step independently with executeSingleStep")
    void shouldExecuteSingleStepIndependently() {
        // Register workflow
        OrderWorkflow workflowBean = new OrderWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "orderWorkflow");

        WorkflowDefinition definition = registry.get("order-workflow").get();
        WorkflowInstance instance = WorkflowInstance.create(
                definition, Map.of("orderId", "ORD-007"), null, "api");

        // Execute just the validate step
        StepVerifier.create(executor.executeSingleStep(
                        definition,
                        instance.start("validate"),
                        "validate",
                        "manual-trigger",
                        Map.of("extra", "data")))
                .assertNext(result -> {
                    // Should have executed only the validate step
                    assertThat(result.getStepExecution("validate")).isPresent();
                    assertThat(result.getStepExecution("validate").get().status())
                            .isEqualTo(StepStatus.COMPLETED);
                })
                .verifyComplete();

        // Verify step state was persisted
        StepVerifier.create(stepStateStore.getStepState("order-workflow", instance.instanceId(), "validate"))
                .assertNext(state -> {
                    assertThat(state.triggeredBy()).isEqualTo("manual-trigger");
                    assertThat(state.status()).isEqualTo(StepStatus.COMPLETED);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should demonstrate complete state lifecycle with detailed logging")
    void shouldDemonstrateCompleteStateLifecycle() {
        // Register workflow
        OrderWorkflow workflowBean = new OrderWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "orderWorkflow");

        System.out.println("\n========== STATE LIFECYCLE TEST ==========\n");

        // 1. Start workflow and capture instance
        String[] instanceId = new String[1];
        Map<String, Object> input = Map.of("orderId", "STATE-TEST-001", "amount", 250.0);

        System.out.println(">>> STEP 1: Starting workflow with input: " + input);

        StepVerifier.create(engine.startWorkflow("order-workflow", input, "state-test-corr", "api"))
                .assertNext(instance -> {
                    instanceId[0] = instance.instanceId();
                    System.out.println("    Workflow started:");
                    System.out.println("      - Instance ID: " + instance.instanceId());
                    System.out.println("      - Workflow ID: " + instance.workflowId());
                    System.out.println("      - Status: " + instance.status());
                    System.out.println("      - Correlation ID: " + instance.correlationId());
                    System.out.println("      - Started At: " + instance.startedAt());
                    System.out.println("      - Completed At: " + instance.completedAt());

                    assertThat(instance.status()).isEqualTo(WorkflowStatus.COMPLETED);
                })
                .verifyComplete();

        // 2. Get workflow instance status
        System.out.println("\n>>> STEP 2: Getting workflow instance status");

        StepVerifier.create(engine.getStatus("order-workflow", instanceId[0]))
                .assertNext(instance -> {
                    System.out.println("    Workflow Instance Status:");
                    System.out.println("      - Status: " + instance.status());
                    System.out.println("      - Is Terminal: " + instance.status().isTerminal());
                    System.out.println("      - Is Active: " + instance.status().isActive());
                    System.out.println("      - Input: " + instance.input());
                    System.out.println("      - Output: " + instance.output());

                    assertThat(instance.status()).isEqualTo(WorkflowStatus.COMPLETED);
                    assertThat(instance.status().isTerminal()).isTrue();
                })
                .verifyComplete();

        // 3. Get comprehensive workflow state
        System.out.println("\n>>> STEP 3: Getting comprehensive workflow state");

        StepVerifier.create(engine.getWorkflowState("order-workflow", instanceId[0]))
                .assertNext(state -> {
                    System.out.println("    Workflow State:");
                    System.out.println("      - Workflow ID: " + state.workflowId());
                    System.out.println("      - Workflow Name: " + state.workflowName());
                    System.out.println("      - Instance ID: " + state.instanceId());
                    System.out.println("      - Correlation ID: " + state.correlationId());
                    System.out.println("      - Status: " + state.status());
                    System.out.println("      - Triggered By: " + state.triggeredBy());
                    System.out.println("      - Total Steps: " + state.totalSteps());
                    System.out.println("      - Progress: " + state.getProgress() + "%");
                    System.out.println("      - Completed Steps: " + state.completedSteps());
                    System.out.println("      - Failed Steps: " + state.failedSteps());
                    System.out.println("      - Skipped Steps: " + state.skippedSteps());
                    System.out.println("      - Pending Steps: " + state.pendingSteps());
                    System.out.println("      - Current Step ID: " + state.currentStepId());
                    System.out.println("      - Next Step ID: " + state.nextStepId());
                    System.out.println("      - Created At: " + state.createdAt());
                    System.out.println("      - Started At: " + state.startedAt());
                    System.out.println("      - Completed At: " + state.completedAt());
                    System.out.println("      - Duration: " + state.getDuration());
                    System.out.println("      - Input: " + state.input());
                    System.out.println("      - Context: " + state.context());

                    assertThat(state.status()).isEqualTo(WorkflowStatus.COMPLETED);
                    assertThat(state.completedSteps()).containsExactlyInAnyOrder("validate", "process", "notify");
                    assertThat(state.getProgress()).isEqualTo(100);
                })
                .verifyComplete();

        // 4. Get step history
        System.out.println("\n>>> STEP 4: Getting step execution history");

        StepVerifier.create(engine.getWorkflowState("order-workflow", instanceId[0]))
                .assertNext(state -> {
                    System.out.println("    Step History (" + state.stepHistory().size() + " entries):");
                    for (int i = 0; i < state.stepHistory().size(); i++) {
                        StepHistory history = state.stepHistory().get(i);
                        System.out.println("      [" + (i + 1) + "] Step: " + history.stepId());
                        System.out.println("          - Status: " + history.status());
                        System.out.println("          - Triggered By: " + history.triggeredBy());
                        System.out.println("          - Started At: " + history.startedAt());
                        System.out.println("          - Completed At: " + history.completedAt());
                        System.out.println("          - Duration: " + history.durationMs() + "ms");
                        System.out.println("          - Attempt: " + history.attemptNumber());
                    }

                    assertThat(state.stepHistory()).hasSizeGreaterThanOrEqualTo(3);
                })
                .verifyComplete();

        // 5. Get individual step states
        System.out.println("\n>>> STEP 5: Getting individual step states");

        StepVerifier.create(stepStateStore.getStepStates("order-workflow", instanceId[0]).collectList())
                .assertNext(stepStates -> {
                    System.out.println("    Step States (" + stepStates.size() + " steps):");
                    for (StepState stepState : stepStates) {
                        System.out.println("      Step: " + stepState.stepId() + " (" + stepState.stepName() + ")");
                        System.out.println("        - Status: " + stepState.status());
                        System.out.println("        - Is Terminal: " + stepState.status().isTerminal());
                        System.out.println("        - Triggered By: " + stepState.triggeredBy());
                        System.out.println("        - Attempt: " + stepState.attemptNumber() + "/" + stepState.maxRetries());
                        System.out.println("        - Input: " + stepState.input());
                        System.out.println("        - Output: " + stepState.output());
                        System.out.println("        - Created At: " + stepState.createdAt());
                        System.out.println("        - Started At: " + stepState.startedAt());
                        System.out.println("        - Completed At: " + stepState.completedAt());
                        System.out.println("        - Duration: " + stepState.getDuration());
                    }

                    assertThat(stepStates).hasSize(3);
                    assertThat(stepStates).allMatch(s -> s.status() == StepStatus.COMPLETED);
                })
                .verifyComplete();

        // 6. Query by status
        System.out.println("\n>>> STEP 6: Querying workflows by status");

        StepVerifier.create(stepStateStore.findWorkflowStatesByStatus(WorkflowStatus.COMPLETED).collectList())
                .assertNext(states -> {
                    System.out.println("    Found " + states.size() + " COMPLETED workflow(s)");
                    for (WorkflowState state : states) {
                        System.out.println("      - " + state.workflowId() + " / " + state.instanceId());
                    }

                    assertThat(states).anyMatch(s -> s.instanceId().equals(instanceId[0]));
                })
                .verifyComplete();

        // 7. Query steps by status
        System.out.println("\n>>> STEP 7: Querying steps by status");

        StepVerifier.create(stepStateStore.findStepsByStatus("order-workflow", instanceId[0], StepStatus.COMPLETED).collectList())
                .assertNext(steps -> {
                    System.out.println("    Found " + steps.size() + " COMPLETED step(s) for this instance:");
                    for (StepState step : steps) {
                        System.out.println("      - " + step.stepId() + " (completed at " + step.completedAt() + ")");
                    }

                    assertThat(steps).hasSize(3);
                })
                .verifyComplete();

        System.out.println("\n========== STATE LIFECYCLE TEST COMPLETE ==========\n");
    }

    @Test
    @DisplayName("Should track failed step state correctly")
    void shouldTrackFailedStepStateCorrectly() {
        // Register a workflow that will fail
        FailingWorkflow workflowBean = new FailingWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "failingWorkflow");

        System.out.println("\n========== FAILED STEP STATE TEST ==========\n");

        String[] instanceId = new String[1];

        System.out.println(">>> Starting workflow that will fail...");

        StepVerifier.create(engine.startWorkflow("failing-workflow", Map.of("orderId", "FAIL-001"), null, "api"))
                .assertNext(instance -> {
                    instanceId[0] = instance.instanceId();
                    System.out.println("    Workflow completed with status: " + instance.status());
                    System.out.println("    Error: " + instance.errorMessage());

                    assertThat(instance.status()).isEqualTo(WorkflowStatus.FAILED);
                })
                .verifyComplete();

        // Check workflow state
        System.out.println("\n>>> Checking workflow state after failure...");

        StepVerifier.create(engine.getWorkflowState("failing-workflow", instanceId[0]))
                .assertNext(state -> {
                    System.out.println("    Workflow State:");
                    System.out.println("      - Status: " + state.status());
                    System.out.println("      - Completed Steps: " + state.completedSteps());
                    System.out.println("      - Failed Steps: " + state.failedSteps());
                    System.out.println("      - Error Message: " + state.errorMessage());
                    System.out.println("      - Error Type: " + state.errorType());

                    assertThat(state.status()).isEqualTo(WorkflowStatus.FAILED);
                    assertThat(state.completedSteps()).contains("step1");
                    assertThat(state.failedSteps()).contains("failingStep");
                })
                .verifyComplete();

        // Check failed step state
        System.out.println("\n>>> Checking failed step state...");

        StepVerifier.create(stepStateStore.getStepState("failing-workflow", instanceId[0], "failingStep"))
                .assertNext(stepState -> {
                    System.out.println("    Failed Step State:");
                    System.out.println("      - Step ID: " + stepState.stepId());
                    System.out.println("      - Status: " + stepState.status());
                    System.out.println("      - Error Message: " + stepState.errorMessage());
                    System.out.println("      - Error Type: " + stepState.errorType());
                    System.out.println("      - Attempt: " + stepState.attemptNumber() + "/" + stepState.maxRetries());

                    assertThat(stepState.status()).isEqualTo(StepStatus.FAILED);
                    assertThat(stepState.errorMessage()).contains("Simulated failure");
                })
                .verifyComplete();

        System.out.println("\n========== FAILED STEP STATE TEST COMPLETE ==========\n");
    }

    @Test
    @DisplayName("Should track skipped step state correctly")
    void shouldTrackSkippedStepStateCorrectly() {
        // Register a workflow with conditional steps
        ConditionalWorkflow workflowBean = new ConditionalWorkflow();
        workflowAspect.postProcessAfterInitialization(workflowBean, "conditionalWorkflow");

        System.out.println("\n========== SKIPPED STEP STATE TEST ==========\n");

        String[] instanceId = new String[1];

        // Start with skipStep=true to skip step2
        System.out.println(">>> Starting workflow with skipStep=true...");

        StepVerifier.create(engine.startWorkflow("conditional-workflow",
                        Map.of("skipStep", true), null, "api"))
                .assertNext(instance -> {
                    instanceId[0] = instance.instanceId();
                    System.out.println("    Workflow completed with status: " + instance.status());
                })
                .verifyComplete();

        // Check workflow state
        System.out.println("\n>>> Checking workflow state with skipped step...");

        StepVerifier.create(engine.getWorkflowState("conditional-workflow", instanceId[0]))
                .assertNext(state -> {
                    System.out.println("    Workflow State:");
                    System.out.println("      - Status: " + state.status());
                    System.out.println("      - Completed Steps: " + state.completedSteps());
                    System.out.println("      - Skipped Steps: " + state.skippedSteps());
                    System.out.println("      - Progress: " + state.getProgress() + "%");

                    assertThat(state.status()).isEqualTo(WorkflowStatus.COMPLETED);
                    assertThat(state.completedSteps()).contains("step1", "step3");
                    assertThat(state.skippedSteps()).contains("step2");
                })
                .verifyComplete();

        System.out.println("\n========== SKIPPED STEP STATE TEST COMPLETE ==========\n");
    }

    // ==================== Sample Workflow Classes ====================

    @Slf4j
    @Workflow(id = "failing-workflow", name = "Failing Workflow")
    static class FailingWorkflow {

        @WorkflowStep(id = "step1", name = "Step 1", order = 1)
        public Mono<Map<String, Object>> step1(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [failing-workflow] Step 'step1': Executing successfully");
            return Mono.just(Map.of("step1", "done"));
        }

        @WorkflowStep(id = "failingStep", name = "Failing Step", order = 2, maxRetries = 1)
        public Mono<Map<String, Object>> failingStep(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [failing-workflow] Step 'failingStep': About to fail!");
            return Mono.error(new RuntimeException("Simulated failure for testing"));
        }
    }

    @Slf4j
    @Workflow(id = "conditional-workflow", name = "Conditional Workflow")
    static class ConditionalWorkflow {

        @WorkflowStep(id = "step1", name = "Step 1", order = 1)
        public Mono<String> step1(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [conditional-workflow] Step 'step1': Executing");
            return Mono.just("step1-done");
        }

        @WorkflowStep(id = "step2", name = "Step 2", order = 2, condition = "#input['skipStep'] != true")
        public Mono<String> step2(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [conditional-workflow] Step 'step2': Executing (not skipped)");
            return Mono.just("step2-done");
        }

        @WorkflowStep(id = "step3", name = "Step 3", order = 3)
        public Mono<String> step3(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [conditional-workflow] Step 'step3': Executing");
            return Mono.just("step3-done");
        }
    }

    @Slf4j
    @Workflow(
            id = "order-workflow",
            name = "Order Workflow",
            description = "Processes customer orders with step-level choreography"
    )
    static class OrderWorkflow {

        @WorkflowStep(id = "validate", name = "Validate Order", order = 1)
        public Mono<Map<String, Object>> validate(WorkflowContext ctx) {
            String orderId = ctx.getInput("orderId", String.class);
            Double amount = ctx.getInput("amount", Double.class);
            log.info("TEST_WORKFLOW [order-workflow] Step 'validate': Validating order orderId={}, amount={}", orderId, amount);
            Map<String, Object> result = Map.of("valid", true, "orderId", orderId != null ? orderId : "unknown");
            log.info("TEST_WORKFLOW [order-workflow] Step 'validate': Validation passed, result={}", result);
            return Mono.just(result);
        }

        @WorkflowStep(id = "process", name = "Process Order", order = 2)
        public Mono<Map<String, Object>> process(WorkflowContext ctx) {
            String orderId = ctx.get("orderId", String.class);
            log.info("TEST_WORKFLOW [order-workflow] Step 'process': Processing order orderId={}", orderId);
            ctx.set("processed", true);
            Map<String, Object> result = Map.of("status", "processed");
            log.info("TEST_WORKFLOW [order-workflow] Step 'process': Order processed successfully, result={}", result);
            return Mono.just(result);
        }

        @WorkflowStep(id = "notify", name = "Send Notification", order = 3)
        public Mono<Map<String, Object>> notify(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [order-workflow] Step 'notify': Sending notification for completed order");
            Map<String, Object> result = Map.of("notified", true);
            log.info("TEST_WORKFLOW [order-workflow] Step 'notify': Notification sent, result={}", result);
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
                id = "validateOrder",
                name = "Validate Order",
                order = 1,
                outputEventType = "order.validated"
        )
        public Mono<Map<String, Object>> validateOrder(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [event-driven-workflow] Step 'validateOrder': Validating order (triggered by 'order.created')");
            Map<String, Object> result = Map.of("valid", true);
            log.info("TEST_WORKFLOW [event-driven-workflow] Step 'validateOrder': Emitting 'order.validated' event, result={}", result);
            return Mono.just(result);
        }

        @WorkflowStep(
                id = "processPayment",
                name = "Process Payment",
                order = 2,
                inputEventType = "order.validated",
                outputEventType = "payment.processed"
        )
        public Mono<Map<String, Object>> processPayment(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [event-driven-workflow] Step 'processPayment': Processing payment (triggered by 'order.validated')");
            Map<String, Object> result = Map.of("paid", true);
            log.info("TEST_WORKFLOW [event-driven-workflow] Step 'processPayment': Emitting 'payment.processed' event, result={}", result);
            return Mono.just(result);
        }

        @WorkflowStep(
                id = "shipOrder",
                name = "Ship Order",
                order = 3,
                inputEventType = "payment.processed"
        )
        public Mono<Map<String, Object>> shipOrder(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [event-driven-workflow] Step 'shipOrder': Shipping order (triggered by 'payment.processed')");
            Map<String, Object> result = Map.of("shipped", true);
            log.info("TEST_WORKFLOW [event-driven-workflow] Step 'shipOrder': Order shipped, result={}", result);
            return Mono.just(result);
        }
    }

    // ==================== In-Memory Cache Adapter ====================

    /**
     * Simple in-memory cache adapter for testing.
     */
    static class InMemoryCacheAdapter implements CacheAdapter {

        private final Map<String, Object> cache = new ConcurrentHashMap<>();
        private final Map<String, Long> expirations = new ConcurrentHashMap<>();

        @Override
        public <K, V> Mono<Optional<V>> get(K key) {
            checkExpiration(key.toString());
            @SuppressWarnings("unchecked")
            V value = (V) cache.get(key.toString());
            return Mono.just(Optional.ofNullable(value));
        }

        @Override
        public <K, V> Mono<Optional<V>> get(K key, Class<V> valueType) {
            return get(key);
        }

        @Override
        public <K, V> Mono<Void> put(K key, V value) {
            cache.put(key.toString(), value);
            return Mono.empty();
        }

        @Override
        public <K, V> Mono<Void> put(K key, V value, Duration ttl) {
            cache.put(key.toString(), value);
            expirations.put(key.toString(), System.currentTimeMillis() + ttl.toMillis());
            return Mono.empty();
        }

        @Override
        public <K, V> Mono<Boolean> putIfAbsent(K key, V value) {
            if (!cache.containsKey(key.toString())) {
                cache.put(key.toString(), value);
                return Mono.just(true);
            }
            return Mono.just(false);
        }

        @Override
        public <K, V> Mono<Boolean> putIfAbsent(K key, V value, Duration ttl) {
            if (!cache.containsKey(key.toString())) {
                cache.put(key.toString(), value);
                expirations.put(key.toString(), System.currentTimeMillis() + ttl.toMillis());
                return Mono.just(true);
            }
            return Mono.just(false);
        }

        @Override
        public <K> Mono<Boolean> evict(K key) {
            Object removed = cache.remove(key.toString());
            expirations.remove(key.toString());
            return Mono.just(removed != null);
        }

        @Override
        public Mono<Void> clear() {
            cache.clear();
            expirations.clear();
            return Mono.empty();
        }

        @Override
        public <K> Mono<Boolean> exists(K key) {
            checkExpiration(key.toString());
            return Mono.just(cache.containsKey(key.toString()));
        }

        @Override
        public <K> Mono<Set<K>> keys() {
            // Clean expired keys first
            expirations.forEach((key, expiry) -> {
                if (System.currentTimeMillis() > expiry) {
                    cache.remove(key);
                }
            });
            @SuppressWarnings("unchecked")
            Set<K> keySet = (Set<K>) new HashSet<>(cache.keySet());
            return Mono.just(keySet);
        }

        @Override
        public Mono<Long> size() {
            return Mono.just((long) cache.size());
        }

        @Override
        public Mono<CacheStats> getStats() {
            return Mono.just(CacheStats.empty(CacheType.CAFFEINE, getCacheName()));
        }

        @Override
        public CacheType getCacheType() {
            return CacheType.CAFFEINE;
        }

        @Override
        public String getCacheName() {
            return "in-memory-test";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Mono<CacheHealth> getHealth() {
            return Mono.just(CacheHealth.healthy(getCacheType(), getCacheName(), 0L));
        }

        @Override
        public void close() {
            cache.clear();
            expirations.clear();
        }

        private void checkExpiration(String key) {
            Long expiry = expirations.get(key);
            if (expiry != null && System.currentTimeMillis() > expiry) {
                cache.remove(key);
                expirations.remove(key);
            }
        }
    }

    // ==================== Resilience4j Integration Tests ====================

    @Test
    @DisplayName("Should apply circuit breaker to step execution")
    void shouldApplyCircuitBreakerToStepExecution() {
        // Configure resilience with circuit breaker enabled
        WorkflowProperties resilienceProperties = new WorkflowProperties();
        resilienceProperties.getResilience().setEnabled(true);
        resilienceProperties.getResilience().getCircuitBreaker().setEnabled(true);
        resilienceProperties.getResilience().getCircuitBreaker().setMinimumNumberOfCalls(2);
        resilienceProperties.getResilience().getCircuitBreaker().setFailureRateThreshold(50);
        resilienceProperties.getResilience().getCircuitBreaker().setSlidingWindowSize(4);

        // Create resilience service
        com.firefly.common.workflow.resilience.WorkflowResilience resilience =
                new com.firefly.common.workflow.resilience.WorkflowResilience(resilienceProperties, null);

        // Create executor with resilience
        WorkflowExecutor resilienceExecutor = new WorkflowExecutor(
                stateStore, stepStateStore, eventPublisher, resilienceProperties,
                applicationContext, objectMapper, workflowAspect, null, null, resilience);
        WorkflowEngine resilienceEngine = new WorkflowEngine(
                registry, resilienceExecutor, stateStore, eventPublisher, resilienceProperties, stepStateStore, null);

        // Register a workflow that fails
        ResilienceFailingWorkflow failingWorkflow = new ResilienceFailingWorkflow();
        workflowAspect.postProcessAfterInitialization(failingWorkflow, "resilienceFailingWorkflow");

        // Execute workflow multiple times to trigger circuit breaker
        for (int i = 0; i < 3; i++) {
            StepVerifier.create(resilienceEngine.startWorkflow("resilience-failing-workflow", Map.of("attempt", i), null, "test"))
                    .assertNext(instance -> {
                        assertThat(instance.status()).isEqualTo(WorkflowStatus.FAILED);
                    })
                    .verifyComplete();
        }

        // Verify circuit breaker state
        io.github.resilience4j.circuitbreaker.CircuitBreaker.State cbState =
                resilience.getCircuitBreakerState("resilience-failing-workflow", "failStep");
        assertThat(cbState).isNotNull();

        // Get circuit breaker metrics
        var metrics = resilience.getCircuitBreakerMetrics("resilience-failing-workflow", "failStep");
        assertThat(metrics).isNotNull();
        assertThat(metrics.failedCalls()).isGreaterThan(0);

        System.out.println("\n========== CIRCUIT BREAKER TEST COMPLETE ==========");
        System.out.println("Circuit Breaker State: " + cbState);
        System.out.println("Failed Calls: " + metrics.failedCalls());
        System.out.println("Failure Rate: " + metrics.failureRate() + "%");
        System.out.println("===================================================\n");
    }

    @Test
    @DisplayName("Should apply time limiter to step execution")
    void shouldApplyTimeLimiterToStepExecution() {
        // Configure resilience with time limiter enabled
        WorkflowProperties resilienceProperties = new WorkflowProperties();
        resilienceProperties.getResilience().setEnabled(true);
        resilienceProperties.getResilience().getTimeLimiter().setEnabled(true);
        resilienceProperties.getResilience().getTimeLimiter().setTimeoutDuration(Duration.ofMillis(100));
        resilienceProperties.getResilience().getCircuitBreaker().setEnabled(false);
        resilienceProperties.getResilience().getRateLimiter().setEnabled(false);
        resilienceProperties.getResilience().getBulkhead().setEnabled(false);

        // Create resilience service
        com.firefly.common.workflow.resilience.WorkflowResilience resilience =
                new com.firefly.common.workflow.resilience.WorkflowResilience(resilienceProperties, null);

        // Create executor with resilience
        WorkflowExecutor resilienceExecutor = new WorkflowExecutor(
                stateStore, stepStateStore, eventPublisher, resilienceProperties,
                applicationContext, objectMapper, workflowAspect, null, null, resilience);
        WorkflowEngine resilienceEngine = new WorkflowEngine(
                registry, resilienceExecutor, stateStore, eventPublisher, resilienceProperties, stepStateStore, null);

        // Register a slow workflow
        SlowWorkflow slowWorkflow = new SlowWorkflow();
        workflowAspect.postProcessAfterInitialization(slowWorkflow, "slowWorkflow");

        // Execute workflow - should timeout
        StepVerifier.create(resilienceEngine.startWorkflow("slow-workflow", Map.of(), null, "test"))
                .assertNext(instance -> {
                    assertThat(instance.status()).isEqualTo(WorkflowStatus.FAILED);
                })
                .verifyComplete();

        System.out.println("\n========== TIME LIMITER TEST COMPLETE ==========");
        System.out.println("Slow workflow timed out as expected");
        System.out.println("================================================\n");
    }

    @Test
    @DisplayName("Should track resilience metrics correctly")
    void shouldTrackResilienceMetricsCorrectly() {
        // Configure resilience with all features enabled
        WorkflowProperties resilienceProperties = new WorkflowProperties();
        resilienceProperties.getResilience().setEnabled(true);
        resilienceProperties.getResilience().getCircuitBreaker().setEnabled(true);
        resilienceProperties.getResilience().getCircuitBreaker().setMinimumNumberOfCalls(1);
        resilienceProperties.getResilience().getRateLimiter().setEnabled(false);
        resilienceProperties.getResilience().getBulkhead().setEnabled(false);
        resilienceProperties.getResilience().getTimeLimiter().setEnabled(false);

        // Create resilience service
        com.firefly.common.workflow.resilience.WorkflowResilience resilience =
                new com.firefly.common.workflow.resilience.WorkflowResilience(resilienceProperties, null);

        // Create executor with resilience
        WorkflowExecutor resilienceExecutor = new WorkflowExecutor(
                stateStore, stepStateStore, eventPublisher, resilienceProperties,
                applicationContext, objectMapper, workflowAspect, null, null, resilience);
        WorkflowEngine resilienceEngine = new WorkflowEngine(
                registry, resilienceExecutor, stateStore, eventPublisher, resilienceProperties, stepStateStore, null);

        // Register a successful workflow
        OrderWorkflow orderWorkflow = new OrderWorkflow();
        workflowAspect.postProcessAfterInitialization(orderWorkflow, "orderWorkflow");

        // Execute workflow successfully
        StepVerifier.create(resilienceEngine.startWorkflow("order-workflow", Map.of("orderId", "ORD-100"), null, "test"))
                .assertNext(instance -> {
                    assertThat(instance.status()).isEqualTo(WorkflowStatus.COMPLETED);
                })
                .verifyComplete();

        // Verify circuit breaker metrics for each step
        var validateMetrics = resilience.getCircuitBreakerMetrics("order-workflow", "validate");
        var processMetrics = resilience.getCircuitBreakerMetrics("order-workflow", "process");
        var notifyMetrics = resilience.getCircuitBreakerMetrics("order-workflow", "notify");

        assertThat(validateMetrics).isNotNull();
        assertThat(validateMetrics.successfulCalls()).isEqualTo(1);
        assertThat(validateMetrics.failedCalls()).isEqualTo(0);

        assertThat(processMetrics).isNotNull();
        assertThat(processMetrics.successfulCalls()).isEqualTo(1);

        assertThat(notifyMetrics).isNotNull();
        assertThat(notifyMetrics.successfulCalls()).isEqualTo(1);

        // Get all circuit breaker states
        var allStates = resilience.getAllCircuitBreakerStates();
        assertThat(allStates).isNotEmpty();

        System.out.println("\n========== RESILIENCE METRICS TEST COMPLETE ==========");
        System.out.println("Circuit Breaker States:");
        allStates.forEach((name, state) -> System.out.println("  - " + name + ": " + state));
        System.out.println("Validate Step: " + validateMetrics.successfulCalls() + " successful, " + validateMetrics.failedCalls() + " failed");
        System.out.println("Process Step: " + processMetrics.successfulCalls() + " successful, " + processMetrics.failedCalls() + " failed");
        System.out.println("Notify Step: " + notifyMetrics.successfulCalls() + " successful, " + notifyMetrics.failedCalls() + " failed");
        System.out.println("======================================================\n");
    }

    @Test
    @DisplayName("Should reset circuit breaker on demand")
    void shouldResetCircuitBreakerOnDemand() {
        // Configure resilience with circuit breaker
        WorkflowProperties resilienceProperties = new WorkflowProperties();
        resilienceProperties.getResilience().setEnabled(true);
        resilienceProperties.getResilience().getCircuitBreaker().setEnabled(true);
        resilienceProperties.getResilience().getCircuitBreaker().setMinimumNumberOfCalls(2);
        resilienceProperties.getResilience().getCircuitBreaker().setFailureRateThreshold(50);
        resilienceProperties.getResilience().getCircuitBreaker().setSlidingWindowSize(4);
        resilienceProperties.getResilience().getTimeLimiter().setEnabled(false);

        // Create resilience service
        com.firefly.common.workflow.resilience.WorkflowResilience resilience =
                new com.firefly.common.workflow.resilience.WorkflowResilience(resilienceProperties, null);

        // Create executor with resilience
        WorkflowExecutor resilienceExecutor = new WorkflowExecutor(
                stateStore, stepStateStore, eventPublisher, resilienceProperties,
                applicationContext, objectMapper, workflowAspect, null, null, resilience);
        WorkflowEngine resilienceEngine = new WorkflowEngine(
                registry, resilienceExecutor, stateStore, eventPublisher, resilienceProperties, stepStateStore, null);

        // Register a failing workflow (use the existing FailingWorkflow which has failingStep)
        FailingWorkflow failingWorkflow = new FailingWorkflow();
        workflowAspect.postProcessAfterInitialization(failingWorkflow, "failingWorkflow");

        // Execute workflow to record failures
        for (int i = 0; i < 3; i++) {
            StepVerifier.create(resilienceEngine.startWorkflow("failing-workflow", Map.of("attempt", i), null, "test"))
                    .assertNext(instance -> assertThat(instance.status()).isEqualTo(WorkflowStatus.FAILED))
                    .verifyComplete();
        }

        // Get metrics before reset (use failingStep which is the step that fails)
        var metricsBefore = resilience.getCircuitBreakerMetrics("failing-workflow", "failingStep");
        assertThat(metricsBefore).isNotNull();
        int failedCallsBefore = metricsBefore.failedCalls();

        // Reset circuit breaker
        resilience.resetCircuitBreaker("failing-workflow", "failingStep");

        // Get metrics after reset
        var metricsAfter = resilience.getCircuitBreakerMetrics("failing-workflow", "failingStep");
        assertThat(metricsAfter).isNotNull();
        assertThat(metricsAfter.state()).isEqualTo(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED);
        assertThat(metricsAfter.failedCalls()).isEqualTo(0);

        System.out.println("\n========== CIRCUIT BREAKER RESET TEST COMPLETE ==========");
        System.out.println("Failed calls before reset: " + failedCallsBefore);
        System.out.println("Failed calls after reset: " + metricsAfter.failedCalls());
        System.out.println("Circuit breaker state after reset: " + metricsAfter.state());
        System.out.println("=========================================================\n");
    }

    @Test
    @DisplayName("Should verify resilience is disabled when configured")
    void shouldVerifyResilienceIsDisabledWhenConfigured() {
        // Configure resilience as disabled
        WorkflowProperties resilienceProperties = new WorkflowProperties();
        resilienceProperties.getResilience().setEnabled(false);

        // Create resilience service
        com.firefly.common.workflow.resilience.WorkflowResilience resilience =
                new com.firefly.common.workflow.resilience.WorkflowResilience(resilienceProperties, null);

        // Verify all features are disabled
        assertThat(resilience.isEnabled()).isFalse();
        assertThat(resilience.isCircuitBreakerEnabled()).isFalse();
        assertThat(resilience.isRateLimiterEnabled()).isFalse();
        assertThat(resilience.isBulkheadEnabled()).isFalse();
        assertThat(resilience.isTimeLimiterEnabled()).isFalse();

        System.out.println("\n========== RESILIENCE DISABLED TEST COMPLETE ==========");
        System.out.println("Resilience enabled: " + resilience.isEnabled());
        System.out.println("Circuit breaker enabled: " + resilience.isCircuitBreakerEnabled());
        System.out.println("Rate limiter enabled: " + resilience.isRateLimiterEnabled());
        System.out.println("Bulkhead enabled: " + resilience.isBulkheadEnabled());
        System.out.println("Time limiter enabled: " + resilience.isTimeLimiterEnabled());
        System.out.println("=======================================================\n");
    }

    // ==================== Test Workflows for Resilience Tests ====================

    @Slf4j
    @Workflow(id = "resilience-failing-workflow", name = "Resilience Failing Workflow")
    static class ResilienceFailingWorkflow {

        @WorkflowStep(id = "failStep", name = "Fail Step", order = 1)
        public Mono<Map<String, Object>> failStep(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [resilience-failing-workflow] Step 'failStep': About to fail intentionally");
            return Mono.error(new RuntimeException("Intentional failure for circuit breaker test"));
        }
    }

    @Slf4j
    @Workflow(id = "slow-workflow", name = "Slow Workflow")
    static class SlowWorkflow {

        @WorkflowStep(id = "slowStep", name = "Slow Step", order = 1)
        public Mono<Map<String, Object>> slowStep(WorkflowContext ctx) {
            log.info("TEST_WORKFLOW [slow-workflow] Step 'slowStep': Starting slow operation (500ms delay)");
            return Mono.delay(Duration.ofMillis(500))
                    .then(Mono.fromSupplier(() -> {
                        log.info("TEST_WORKFLOW [slow-workflow] Step 'slowStep': Completed after delay");
                        return Map.<String, Object>of("result", "completed");
                    }));
        }
    }
}
