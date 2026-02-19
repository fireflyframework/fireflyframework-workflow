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

package org.fireflyframework.workflow.query;

import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.eventsourcing.store.EventSourcedWorkflowStateStore;
import org.fireflyframework.workflow.exception.WorkflowNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WorkflowQueryService}.
 * <p>
 * Tests cover all built-in queries, error handling for unknown queries,
 * and missing instances.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowQueryServiceTest {

    private static final UUID AGGREGATE_ID = UUID.randomUUID();
    private static final String INSTANCE_ID = AGGREGATE_ID.toString();

    @Mock
    private EventSourcedWorkflowStateStore stateStore;

    private WorkflowQueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new WorkflowQueryService(stateStore);
    }

    /**
     * Creates a running WorkflowAggregate with some state populated.
     */
    private WorkflowAggregate createRunningAggregate() {
        WorkflowAggregate aggregate = new WorkflowAggregate(AGGREGATE_ID);
        aggregate.start("test-workflow", "Test Workflow", "1.0.0",
                Map.of("orderId", "123"), "corr-1", "api", false);
        aggregate.markEventsAsCommitted();
        return aggregate;
    }

    /**
     * Creates a running aggregate with steps, signals, timers, and child workflows.
     */
    private WorkflowAggregate createAggregateWithFullState() {
        WorkflowAggregate aggregate = createRunningAggregate();

        // Add steps
        aggregate.startStep("step-1", "Validate Order", Map.of("input", "data"), 1);
        aggregate.completeStep("step-1", Map.of("validated", true), 100L);
        aggregate.startStep("step-2", "Process Payment", Map.of(), 1);
        aggregate.markEventsAsCommitted();

        // Add signals
        aggregate.receiveSignal("approval-signal", Map.of("approved", true));
        aggregate.markEventsAsCommitted();

        // Add timers
        aggregate.registerTimer("timeout-timer", Instant.now().plusSeconds(300), Map.of("timeout", true));
        aggregate.markEventsAsCommitted();

        // Add search attributes
        aggregate.upsertSearchAttribute("customerId", "cust-456");
        aggregate.upsertSearchAttribute("priority", "high");
        aggregate.markEventsAsCommitted();

        // Add child workflows
        aggregate.spawnChildWorkflow("child-instance-1", "payment-workflow",
                Map.of("amount", 100), "step-2");
        aggregate.markEventsAsCommitted();

        return aggregate;
    }

    // ========================================================================
    // Built-in Query Tests
    // ========================================================================

    @Test
    @DisplayName("getStatus should return current status")
    void getStatus_shouldReturnCurrentStatus() {
        WorkflowAggregate aggregate = createRunningAggregate();
        when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));

        StepVerifier.create(queryService.executeQuery(INSTANCE_ID, "getStatus"))
                .assertNext(result -> assertThat(result).isEqualTo("RUNNING"))
                .verifyComplete();
    }

    @Test
    @DisplayName("getCurrentStep should return step ID")
    void getCurrentStep_shouldReturnStepId() {
        WorkflowAggregate aggregate = createRunningAggregate();
        aggregate.startStep("step-1", "Validate", Map.of(), 1);
        aggregate.markEventsAsCommitted();

        when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));

        StepVerifier.create(queryService.executeQuery(INSTANCE_ID, "getCurrentStep"))
                .assertNext(result -> assertThat(result).isEqualTo("step-1"))
                .verifyComplete();
    }

    @Test
    @DisplayName("getStepHistory should return all steps")
    @SuppressWarnings("unchecked")
    void getStepHistory_shouldReturnAllSteps() {
        WorkflowAggregate aggregate = createAggregateWithFullState();
        when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));

        StepVerifier.create(queryService.executeQuery(INSTANCE_ID, "getStepHistory"))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(Map.class);
                    Map<String, Map<String, Object>> history = (Map<String, Map<String, Object>>) result;
                    assertThat(history).containsKeys("step-1", "step-2");

                    // step-1 should be COMPLETED
                    Map<String, Object> step1 = history.get("step-1");
                    assertThat(step1.get("status")).isEqualTo("COMPLETED");

                    // step-2 should be RUNNING
                    Map<String, Object> step2 = history.get("step-2");
                    assertThat(step2.get("status")).isEqualTo("RUNNING");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getContext should return context data")
    @SuppressWarnings("unchecked")
    void getContext_shouldReturnContext() {
        WorkflowAggregate aggregate = createAggregateWithFullState();
        when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));

        StepVerifier.create(queryService.executeQuery(INSTANCE_ID, "getContext"))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(Map.class);
                    Map<String, Object> context = (Map<String, Object>) result;
                    // Context should contain merged step outputs
                    assertThat(context).containsKey("validated");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getSearchAttributes should return attributes")
    @SuppressWarnings("unchecked")
    void getSearchAttributes_shouldReturnAttributes() {
        WorkflowAggregate aggregate = createAggregateWithFullState();
        when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));

        StepVerifier.create(queryService.executeQuery(INSTANCE_ID, "getSearchAttributes"))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(Map.class);
                    Map<String, Object> attrs = (Map<String, Object>) result;
                    assertThat(attrs).containsEntry("customerId", "cust-456");
                    assertThat(attrs).containsEntry("priority", "high");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getInput should return workflow input")
    @SuppressWarnings("unchecked")
    void getInput_shouldReturnInput() {
        WorkflowAggregate aggregate = createRunningAggregate();
        when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));

        StepVerifier.create(queryService.executeQuery(INSTANCE_ID, "getInput"))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(Map.class);
                    Map<String, Object> input = (Map<String, Object>) result;
                    assertThat(input).containsEntry("orderId", "123");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getOutput should return workflow output when completed")
    void getOutput_shouldReturnOutput() {
        WorkflowAggregate aggregate = createRunningAggregate();
        aggregate.complete(Map.of("result", "success"));
        aggregate.markEventsAsCommitted();

        when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));

        StepVerifier.create(queryService.executeQuery(INSTANCE_ID, "getOutput"))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(Map.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> output = (Map<String, Object>) result;
                    assertThat(output).containsEntry("result", "success");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getPendingSignals should return signal names")
    @SuppressWarnings("unchecked")
    void getPendingSignals_shouldReturnSignalNames() {
        WorkflowAggregate aggregate = createAggregateWithFullState();
        when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));

        StepVerifier.create(queryService.executeQuery(INSTANCE_ID, "getPendingSignals"))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(Set.class);
                    Set<String> signalNames = (Set<String>) result;
                    assertThat(signalNames).contains("approval-signal");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getActiveTimers should return timer IDs")
    @SuppressWarnings("unchecked")
    void getActiveTimers_shouldReturnTimerIds() {
        WorkflowAggregate aggregate = createAggregateWithFullState();
        when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));

        StepVerifier.create(queryService.executeQuery(INSTANCE_ID, "getActiveTimers"))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(Set.class);
                    Set<String> timerIds = (Set<String>) result;
                    assertThat(timerIds).contains("timeout-timer");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("getChildWorkflows should return child workflow summary")
    @SuppressWarnings("unchecked")
    void getChildWorkflows_shouldReturnChildWorkflowSummary() {
        WorkflowAggregate aggregate = createAggregateWithFullState();
        when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));

        StepVerifier.create(queryService.executeQuery(INSTANCE_ID, "getChildWorkflows"))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(Map.class);
                    Map<String, Map<String, Object>> children = (Map<String, Map<String, Object>>) result;
                    assertThat(children).containsKey("child-instance-1");

                    Map<String, Object> child = children.get("child-instance-1");
                    assertThat(child.get("childWorkflowId")).isEqualTo("payment-workflow");
                    assertThat(child.get("parentStepId")).isEqualTo("step-2");
                    assertThat(child.get("completed")).isEqualTo(false);
                })
                .verifyComplete();
    }

    // ========================================================================
    // Error Handling Tests
    // ========================================================================

    @Test
    @DisplayName("unknown query should throw IllegalArgumentException")
    void unknownQuery_shouldThrowException() {
        WorkflowAggregate aggregate = createRunningAggregate();
        when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));

        StepVerifier.create(queryService.executeQuery(INSTANCE_ID, "unknownQuery"))
                .expectErrorMatches(e ->
                        e instanceof IllegalArgumentException
                                && e.getMessage().contains("Unknown query: unknownQuery"))
                .verify();
    }

    @Test
    @DisplayName("non-existent instance should error with WorkflowNotFoundException")
    void nonExistentInstance_shouldError() {
        when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.empty());

        StepVerifier.create(queryService.executeQuery(INSTANCE_ID, "getStatus"))
                .expectError(WorkflowNotFoundException.class)
                .verify();
    }

    @Test
    @DisplayName("invalid instance ID format should error with WorkflowNotFoundException")
    void invalidInstanceId_shouldError() {
        StepVerifier.create(queryService.executeQuery("not-a-uuid", "getStatus"))
                .expectError(WorkflowNotFoundException.class)
                .verify();
    }
}
