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

package org.fireflyframework.workflow.compensation;

import org.fireflyframework.workflow.core.StepHandler;
import org.fireflyframework.workflow.core.WorkflowContext;
import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.eventsourcing.store.EventSourcedWorkflowStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CompensationOrchestrator}.
 * <p>
 * Tests cover all compensation policies (SKIP, STRICT_SEQUENTIAL, BEST_EFFORT)
 * and edge cases such as missing handlers, empty step lists, and instance not found.
 */
@ExtendWith(MockitoExtension.class)
class CompensationOrchestratorTest {

    private static final UUID INSTANCE_ID = UUID.randomUUID();
    private static final String FAILED_STEP_ID = "step-3-charge";

    @Mock
    private EventSourcedWorkflowStateStore stateStore;

    @Mock
    private StepHandler<?> stepHandlerA;

    @Mock
    private StepHandler<?> stepHandlerB;

    @Mock
    private StepHandler<?> stepHandlerC;

    private Map<String, StepHandler<?>> stepHandlers;
    private CompensationOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        stepHandlers = new LinkedHashMap<>();
        stepHandlers.put("step-1-validate", stepHandlerA);
        stepHandlers.put("step-2-reserve", stepHandlerB);
        stepHandlers.put("step-3-charge", stepHandlerC);

        orchestrator = new CompensationOrchestrator(stateStore, stepHandlers);
    }

    /**
     * Creates a running WorkflowAggregate with the specified completed steps.
     */
    private WorkflowAggregate createAggregateWithCompletedSteps(UUID aggregateId, String... completedStepIds) {
        WorkflowAggregate aggregate = new WorkflowAggregate(aggregateId);
        aggregate.start("test-workflow", "Test Workflow", "1.0.0",
                Map.of("key", "value"), "corr-1", "api", false);

        for (String stepId : completedStepIds) {
            aggregate.startStep(stepId, stepId + "-name", Map.of(), 1);
            aggregate.completeStep(stepId, Map.of("result", "ok"), 100L);
        }

        aggregate.markEventsAsCommitted();
        return aggregate;
    }

    // ========================================================================
    // SKIP Policy Tests
    // ========================================================================

    @Nested
    @DisplayName("SKIP policy")
    class SkipPolicyTests {

        @Test
        @DisplayName("should return immediately with empty result")
        void skipPolicy_shouldReturnImmediately() {
            StepVerifier.create(orchestrator.compensate(INSTANCE_ID, FAILED_STEP_ID, CompensationPolicy.SKIP))
                    .assertNext(result -> {
                        assertThat(result.instanceId()).isEqualTo(INSTANCE_ID.toString());
                        assertThat(result.policy()).isEqualTo(CompensationPolicy.SKIP);
                        assertThat(result.failedStepId()).isEqualTo(FAILED_STEP_ID);
                        assertThat(result.compensatedSteps()).isEmpty();
                        assertThat(result.allSuccessful()).isTrue();
                        assertThat(result.errors()).isEmpty();
                    })
                    .verifyComplete();

            // Should never touch the state store
            verifyNoInteractions(stateStore);
        }
    }

    // ========================================================================
    // STRICT_SEQUENTIAL Policy Tests
    // ========================================================================

    @Nested
    @DisplayName("STRICT_SEQUENTIAL policy")
    class StrictSequentialPolicyTests {

        @Test
        @DisplayName("should compensate in reverse order")
        void strictSequential_shouldCompensateInReverseOrder() {
            WorkflowAggregate aggregate = createAggregateWithCompletedSteps(
                    INSTANCE_ID, "step-1-validate", "step-2-reserve");

            when(stateStore.loadAggregate(INSTANCE_ID)).thenReturn(Mono.just(aggregate));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
            when(stateStore.toWorkflowInstance(any(WorkflowAggregate.class)))
                    .thenCallRealMethod();

            // Both handlers compensate successfully
            when(stepHandlerA.compensate(any(WorkflowContext.class))).thenReturn(Mono.empty());
            when(stepHandlerB.compensate(any(WorkflowContext.class))).thenReturn(Mono.empty());

            StepVerifier.create(orchestrator.compensate(INSTANCE_ID, FAILED_STEP_ID, CompensationPolicy.STRICT_SEQUENTIAL))
                    .assertNext(result -> {
                        assertThat(result.instanceId()).isEqualTo(INSTANCE_ID.toString());
                        assertThat(result.policy()).isEqualTo(CompensationPolicy.STRICT_SEQUENTIAL);
                        assertThat(result.failedStepId()).isEqualTo(FAILED_STEP_ID);
                        assertThat(result.compensatedSteps()).hasSize(2);
                        assertThat(result.allSuccessful()).isTrue();
                        assertThat(result.errors()).isEmpty();

                        // Verify reverse order: step-2 first, then step-1
                        assertThat(result.compensatedSteps().get(0).stepId()).isEqualTo("step-2-reserve");
                        assertThat(result.compensatedSteps().get(1).stepId()).isEqualTo("step-1-validate");
                    })
                    .verifyComplete();

            verify(stateStore).loadAggregate(INSTANCE_ID);
            verify(stateStore).saveAggregate(any(WorkflowAggregate.class));
        }

        @Test
        @DisplayName("should stop on first compensation failure")
        void strictSequential_shouldStopOnFirstFailure() {
            WorkflowAggregate aggregate = createAggregateWithCompletedSteps(
                    INSTANCE_ID, "step-1-validate", "step-2-reserve");

            when(stateStore.loadAggregate(INSTANCE_ID)).thenReturn(Mono.just(aggregate));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
            when(stateStore.toWorkflowInstance(any(WorkflowAggregate.class)))
                    .thenCallRealMethod();

            // step-2-reserve compensation fails
            when(stepHandlerB.compensate(any(WorkflowContext.class)))
                    .thenReturn(Mono.error(new RuntimeException("Compensation failed for reserve")));

            StepVerifier.create(orchestrator.compensate(INSTANCE_ID, FAILED_STEP_ID, CompensationPolicy.STRICT_SEQUENTIAL))
                    .assertNext(result -> {
                        assertThat(result.allSuccessful()).isFalse();
                        // Only one step attempted (step-2-reserve), then stopped
                        assertThat(result.compensatedSteps()).hasSize(1);
                        assertThat(result.compensatedSteps().get(0).stepId()).isEqualTo("step-2-reserve");
                        assertThat(result.compensatedSteps().get(0).success()).isFalse();
                        assertThat(result.errors()).hasSize(1);
                        assertThat(result.errors().get(0)).contains("Compensation failed for reserve");
                    })
                    .verifyComplete();

            // step-1-validate compensate should never be called
            verify(stepHandlerA, never()).compensate(any(WorkflowContext.class));
        }
    }

    // ========================================================================
    // BEST_EFFORT Policy Tests
    // ========================================================================

    @Nested
    @DisplayName("BEST_EFFORT policy")
    class BestEffortPolicyTests {

        @Test
        @DisplayName("should compensate all and collect errors")
        void bestEffort_shouldCompensateAllAndCollectErrors() {
            WorkflowAggregate aggregate = createAggregateWithCompletedSteps(
                    INSTANCE_ID, "step-1-validate", "step-2-reserve");

            when(stateStore.loadAggregate(INSTANCE_ID)).thenReturn(Mono.just(aggregate));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
            when(stateStore.toWorkflowInstance(any(WorkflowAggregate.class)))
                    .thenCallRealMethod();

            // step-2-reserve fails, step-1-validate succeeds
            when(stepHandlerB.compensate(any(WorkflowContext.class)))
                    .thenReturn(Mono.error(new RuntimeException("Reserve compensation failed")));
            when(stepHandlerA.compensate(any(WorkflowContext.class))).thenReturn(Mono.empty());

            StepVerifier.create(orchestrator.compensate(INSTANCE_ID, FAILED_STEP_ID, CompensationPolicy.BEST_EFFORT))
                    .assertNext(result -> {
                        assertThat(result.allSuccessful()).isFalse();
                        // Both steps attempted
                        assertThat(result.compensatedSteps()).hasSize(2);
                        assertThat(result.errors()).hasSize(1);
                        assertThat(result.errors().get(0)).contains("Reserve compensation failed");

                        // step-2-reserve attempted first (reverse order), failed
                        assertThat(result.compensatedSteps().get(0).stepId()).isEqualTo("step-2-reserve");
                        assertThat(result.compensatedSteps().get(0).success()).isFalse();

                        // step-1-validate attempted second, succeeded
                        assertThat(result.compensatedSteps().get(1).stepId()).isEqualTo("step-1-validate");
                        assertThat(result.compensatedSteps().get(1).success()).isTrue();
                    })
                    .verifyComplete();

            // Both handlers should have been called
            verify(stepHandlerB).compensate(any(WorkflowContext.class));
            verify(stepHandlerA).compensate(any(WorkflowContext.class));
        }

        @Test
        @DisplayName("should return all successful when no errors")
        void bestEffort_noErrors_shouldReturnAllSuccessful() {
            WorkflowAggregate aggregate = createAggregateWithCompletedSteps(
                    INSTANCE_ID, "step-1-validate", "step-2-reserve");

            when(stateStore.loadAggregate(INSTANCE_ID)).thenReturn(Mono.just(aggregate));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
            when(stateStore.toWorkflowInstance(any(WorkflowAggregate.class)))
                    .thenCallRealMethod();

            when(stepHandlerA.compensate(any(WorkflowContext.class))).thenReturn(Mono.empty());
            when(stepHandlerB.compensate(any(WorkflowContext.class))).thenReturn(Mono.empty());

            StepVerifier.create(orchestrator.compensate(INSTANCE_ID, FAILED_STEP_ID, CompensationPolicy.BEST_EFFORT))
                    .assertNext(result -> {
                        assertThat(result.allSuccessful()).isTrue();
                        assertThat(result.compensatedSteps()).hasSize(2);
                        assertThat(result.errors()).isEmpty();
                    })
                    .verifyComplete();
        }
    }

    // ========================================================================
    // Edge Case Tests
    // ========================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("should handle no completed steps to compensate")
        void noCompletedSteps_shouldReturnEmptyResult() {
            // Aggregate with no completed steps
            WorkflowAggregate aggregate = new WorkflowAggregate(INSTANCE_ID);
            aggregate.start("test-workflow", "Test Workflow", "1.0.0",
                    Map.of("key", "value"), "corr-1", "api", false);
            aggregate.markEventsAsCommitted();

            when(stateStore.loadAggregate(INSTANCE_ID)).thenReturn(Mono.just(aggregate));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
            when(stateStore.toWorkflowInstance(any(WorkflowAggregate.class)))
                    .thenCallRealMethod();

            StepVerifier.create(orchestrator.compensate(INSTANCE_ID, FAILED_STEP_ID, CompensationPolicy.STRICT_SEQUENTIAL))
                    .assertNext(result -> {
                        assertThat(result.compensatedSteps()).isEmpty();
                        assertThat(result.allSuccessful()).isTrue();
                        assertThat(result.errors()).isEmpty();
                    })
                    .verifyComplete();

            verify(stateStore).loadAggregate(INSTANCE_ID);
            verify(stateStore).saveAggregate(any(WorkflowAggregate.class));
        }

        @Test
        @DisplayName("should skip step handler not found (not error)")
        void stepHandlerNotFound_shouldBeSkipped() {
            // Aggregate has a step that doesn't have a handler registered
            WorkflowAggregate aggregate = createAggregateWithCompletedSteps(
                    INSTANCE_ID, "step-1-validate", "unregistered-step");

            when(stateStore.loadAggregate(INSTANCE_ID)).thenReturn(Mono.just(aggregate));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
            when(stateStore.toWorkflowInstance(any(WorkflowAggregate.class)))
                    .thenCallRealMethod();

            when(stepHandlerA.compensate(any(WorkflowContext.class))).thenReturn(Mono.empty());

            StepVerifier.create(orchestrator.compensate(INSTANCE_ID, FAILED_STEP_ID, CompensationPolicy.STRICT_SEQUENTIAL))
                    .assertNext(result -> {
                        assertThat(result.allSuccessful()).isTrue();
                        // Both steps are recorded: unregistered-step is skipped (success), step-1-validate is compensated
                        assertThat(result.compensatedSteps()).hasSize(2);
                        assertThat(result.errors()).isEmpty();

                        // Reverse order: unregistered-step first, step-1-validate second
                        assertThat(result.compensatedSteps().get(0).stepId()).isEqualTo("unregistered-step");
                        assertThat(result.compensatedSteps().get(0).success()).isTrue();
                        assertThat(result.compensatedSteps().get(1).stepId()).isEqualTo("step-1-validate");
                        assertThat(result.compensatedSteps().get(1).success()).isTrue();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return error when instance not found")
        void instanceNotFound_shouldReturnError() {
            UUID unknownId = UUID.randomUUID();
            when(stateStore.loadAggregate(unknownId)).thenReturn(Mono.empty());

            StepVerifier.create(orchestrator.compensate(unknownId, FAILED_STEP_ID, CompensationPolicy.STRICT_SEQUENTIAL))
                    .assertNext(result -> {
                        assertThat(result.instanceId()).isEqualTo(unknownId.toString());
                        assertThat(result.allSuccessful()).isFalse();
                        assertThat(result.errors()).hasSize(1);
                        assertThat(result.errors().get(0)).contains("not found");
                        assertThat(result.compensatedSteps()).isEmpty();
                    })
                    .verifyComplete();

            verify(stateStore).loadAggregate(unknownId);
            verify(stateStore, never()).saveAggregate(any());
        }
    }
}
