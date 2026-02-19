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

package org.fireflyframework.workflow.eventsourcing.aggregate;

import org.fireflyframework.workflow.model.StepStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WorkflowAggregate} step execution, signals,
 * timers, side effects, and search attributes.
 */
class WorkflowAggregateStepsTest {

    private static final UUID AGGREGATE_ID = UUID.randomUUID();
    private WorkflowAggregate aggregate;

    @BeforeEach
    void setUp() {
        aggregate = new WorkflowAggregate(AGGREGATE_ID);
        aggregate.start("test-workflow", "Test Workflow", "1.0.0",
                Map.of("key", "value"), "corr-1", "test", false);
    }

    // ========================================================================
    // Step Execution Tests
    // ========================================================================

    @Nested
    @DisplayName("Step Execution")
    class StepExecutionTests {

        @Test
        @DisplayName("startStep should create step state with RUNNING status")
        void startStepShouldCreateRunningState() {
            aggregate.startStep("step-1", "Validate Order", Map.of("orderId", "123"), 1);

            WorkflowAggregate.StepState state = aggregate.getStepStates().get("step-1");
            assertThat(state).isNotNull();
            assertThat(state.stepId()).isEqualTo("step-1");
            assertThat(state.stepName()).isEqualTo("Validate Order");
            assertThat(state.status()).isEqualTo(StepStatus.RUNNING);
            assertThat(state.attemptNumber()).isEqualTo(1);
            assertThat(state.input()).isEqualTo(Map.of("orderId", "123"));
            assertThat(state.startedAt()).isNotNull();
        }

        @Test
        @DisplayName("startStep should set currentStepId")
        void startStepShouldSetCurrentStepId() {
            aggregate.startStep("step-1", "Validate Order", Map.of(), 1);

            assertThat(aggregate.getCurrentStepId()).isEqualTo("step-1");
        }

        @Test
        @DisplayName("completeStep should update step state to COMPLETED")
        void completeStepShouldUpdateToCompleted() {
            aggregate.startStep("step-1", "Validate Order", Map.of(), 1);
            aggregate.completeStep("step-1", Map.of("valid", true), 150L);

            WorkflowAggregate.StepState state = aggregate.getStepStates().get("step-1");
            assertThat(state.status()).isEqualTo(StepStatus.COMPLETED);
            assertThat(state.output()).isEqualTo(Map.of("valid", true));
            assertThat(state.completedAt()).isNotNull();
        }

        @Test
        @DisplayName("completeStep should add to completedStepOrder")
        void completeStepShouldAddToCompletedOrder() {
            aggregate.startStep("step-1", "Step 1", Map.of(), 1);
            aggregate.completeStep("step-1", Map.of(), 100L);
            aggregate.startStep("step-2", "Step 2", Map.of(), 1);
            aggregate.completeStep("step-2", Map.of(), 200L);

            assertThat(aggregate.getCompletedStepOrder())
                    .containsExactly("step-1", "step-2");
        }

        @Test
        @DisplayName("completeStep should merge output map into context")
        void completeStepShouldMergeOutputIntoContext() {
            aggregate.startStep("step-1", "Validate", Map.of(), 1);
            aggregate.completeStep("step-1", Map.of("validated", true, "score", 95), 100L);

            assertThat(aggregate.getContext())
                    .containsEntry("validated", true)
                    .containsEntry("score", 95);
        }

        @Test
        @DisplayName("completeStep should store step output as stepId.output in context")
        void completeStepShouldStoreStepOutputInContext() {
            Map<String, Object> stepOutput = Map.of("result", "ok");
            aggregate.startStep("step-1", "Validate", Map.of(), 1);
            aggregate.completeStep("step-1", stepOutput, 100L);

            assertThat(aggregate.getContext())
                    .containsEntry("step-1.output", stepOutput);
        }

        @Test
        @DisplayName("completeStep with non-map output should not merge into context but store as stepId.output")
        void completeStepWithNonMapOutputShouldNotMerge() {
            aggregate.startStep("step-1", "Calculate", Map.of(), 1);
            aggregate.completeStep("step-1", "simple-result", 100L);

            assertThat(aggregate.getContext())
                    .containsEntry("step-1.output", "simple-result")
                    .doesNotContainKey("simple-result");
        }

        @Test
        @DisplayName("failStep should update step state to FAILED")
        void failStepShouldUpdateToFailed() {
            aggregate.startStep("step-1", "Process Order", Map.of(), 1);
            aggregate.failStep("step-1", "Connection timeout", "TimeoutException", 1, true);

            WorkflowAggregate.StepState state = aggregate.getStepStates().get("step-1");
            assertThat(state.status()).isEqualTo(StepStatus.FAILED);
            assertThat(state.errorOrReason()).isEqualTo("Connection timeout");
            assertThat(state.completedAt()).isNotNull();
        }

        @Test
        @DisplayName("skipStep should create step state with SKIPPED status")
        void skipStepShouldCreateSkippedState() {
            aggregate.skipStep("step-optional", "Condition not met");

            WorkflowAggregate.StepState state = aggregate.getStepStates().get("step-optional");
            assertThat(state).isNotNull();
            assertThat(state.status()).isEqualTo(StepStatus.SKIPPED);
            assertThat(state.errorOrReason()).isEqualTo("Condition not met");
        }

        @Test
        @DisplayName("retryStep should update step state to RETRYING with new attempt number")
        void retryStepShouldUpdateToRetrying() {
            aggregate.startStep("step-1", "Process", Map.of(), 1);
            aggregate.failStep("step-1", "Error", "Exception", 1, true);
            aggregate.retryStep("step-1", 2, 1000L);

            WorkflowAggregate.StepState state = aggregate.getStepStates().get("step-1");
            assertThat(state.status()).isEqualTo(StepStatus.RETRYING);
            assertThat(state.attemptNumber()).isEqualTo(2);
        }
    }

    // ========================================================================
    // Signal Tests
    // ========================================================================

    @Nested
    @DisplayName("Signal Handling")
    class SignalTests {

        @Test
        @DisplayName("receiveSignal should buffer signal in pendingSignals")
        void receiveSignalShouldBufferSignal() {
            aggregate.receiveSignal("approval", Map.of("approved", true, "by", "manager"));

            assertThat(aggregate.getPendingSignals()).containsKey("approval");
            WorkflowAggregate.SignalData signal = aggregate.getPendingSignals().get("approval");
            assertThat(signal.signalName()).isEqualTo("approval");
            assertThat(signal.payload()).containsEntry("approved", true);
            assertThat(signal.receivedAt()).isNotNull();
        }

        @Test
        @DisplayName("receiveSignal should overwrite previous signal with same name")
        void receiveSignalShouldOverwritePrevious() {
            aggregate.receiveSignal("approval", Map.of("attempt", 1));
            aggregate.receiveSignal("approval", Map.of("attempt", 2));

            assertThat(aggregate.getPendingSignals()).hasSize(1);
            assertThat(aggregate.getPendingSignals().get("approval").payload())
                    .containsEntry("attempt", 2);
        }

        @Test
        @DisplayName("should support multiple different signals")
        void shouldSupportMultipleSignals() {
            aggregate.receiveSignal("approval", Map.of("approved", true));
            aggregate.receiveSignal("payment", Map.of("amount", 100));

            assertThat(aggregate.getPendingSignals()).hasSize(2);
            assertThat(aggregate.getPendingSignals()).containsKey("approval");
            assertThat(aggregate.getPendingSignals()).containsKey("payment");
        }

        @Test
        @DisplayName("should not allow signal on terminal workflow")
        void shouldNotAllowSignalOnTerminal() {
            aggregate.complete(Map.of("done", true));

            assertThatThrownBy(() -> aggregate.receiveSignal("late-signal", Map.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("terminal");
        }
    }

    // ========================================================================
    // Timer Tests
    // ========================================================================

    @Nested
    @DisplayName("Timer Management")
    class TimerTests {

        @Test
        @DisplayName("registerTimer should add timer to activeTimers")
        void registerTimerShouldAddToActive() {
            Instant fireAt = Instant.now().plusSeconds(60);
            aggregate.registerTimer("reminder-1", fireAt, Map.of("type", "reminder"));

            assertThat(aggregate.getActiveTimers()).containsKey("reminder-1");
            WorkflowAggregate.TimerData timer = aggregate.getActiveTimers().get("reminder-1");
            assertThat(timer.timerId()).isEqualTo("reminder-1");
            assertThat(timer.fireAt()).isEqualTo(fireAt);
            assertThat(timer.data()).containsEntry("type", "reminder");
        }

        @Test
        @DisplayName("fireTimer should remove timer from activeTimers")
        void fireTimerShouldRemoveFromActive() {
            Instant fireAt = Instant.now().plusSeconds(60);
            aggregate.registerTimer("timer-1", fireAt, Map.of());
            assertThat(aggregate.getActiveTimers()).containsKey("timer-1");

            aggregate.fireTimer("timer-1");

            assertThat(aggregate.getActiveTimers()).doesNotContainKey("timer-1");
        }

        @Test
        @DisplayName("fireTimer should throw if timer does not exist")
        void fireTimerShouldThrowIfNotExists() {
            assertThatThrownBy(() -> aggregate.fireTimer("nonexistent"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("nonexistent");
        }

        @Test
        @DisplayName("should support multiple active timers")
        void shouldSupportMultipleTimers() {
            aggregate.registerTimer("t1", Instant.now().plusSeconds(30), Map.of());
            aggregate.registerTimer("t2", Instant.now().plusSeconds(60), Map.of());

            assertThat(aggregate.getActiveTimers()).hasSize(2);
        }

        @Test
        @DisplayName("full timer lifecycle: register then fire")
        void fullTimerLifecycle() {
            Instant fireAt = Instant.now().plusSeconds(60);
            aggregate.registerTimer("lifecycle-timer", fireAt, Map.of("data", "test"));

            assertThat(aggregate.getActiveTimers()).hasSize(1);

            aggregate.fireTimer("lifecycle-timer");

            assertThat(aggregate.getActiveTimers()).isEmpty();
        }
    }

    // ========================================================================
    // Side Effect Tests
    // ========================================================================

    @Nested
    @DisplayName("Side Effects")
    class SideEffectTests {

        @Test
        @DisplayName("recordSideEffect should store value")
        void recordSideEffectShouldStore() {
            aggregate.recordSideEffect("random-uuid", "abc-123");

            assertThat(aggregate.getSideEffects()).containsEntry("random-uuid", "abc-123");
        }

        @Test
        @DisplayName("getSideEffect should return recorded value")
        void getSideEffectShouldReturnValue() {
            aggregate.recordSideEffect("api-response", Map.of("status", 200));

            assertThat(aggregate.getSideEffect("api-response"))
                    .isPresent()
                    .contains(Map.of("status", 200));
        }

        @Test
        @DisplayName("getSideEffect should return empty for unknown id")
        void getSideEffectShouldReturnEmptyForUnknown() {
            assertThat(aggregate.getSideEffect("nonexistent")).isEmpty();
        }

        @Test
        @DisplayName("should support multiple side effects")
        void shouldSupportMultipleSideEffects() {
            aggregate.recordSideEffect("se-1", "value-1");
            aggregate.recordSideEffect("se-2", 42);
            aggregate.recordSideEffect("se-3", Map.of("nested", true));

            assertThat(aggregate.getSideEffects()).hasSize(3);
            assertThat(aggregate.getSideEffect("se-1")).contains("value-1");
            assertThat(aggregate.getSideEffect("se-2")).contains(42);
        }
    }

    // ========================================================================
    // Search Attribute Tests
    // ========================================================================

    @Nested
    @DisplayName("Search Attributes")
    class SearchAttributeTests {

        @Test
        @DisplayName("upsertSearchAttribute should add new attribute")
        void upsertShouldAddNew() {
            aggregate.upsertSearchAttribute("customerId", "CUST-123");

            assertThat(aggregate.getSearchAttributes())
                    .containsEntry("customerId", "CUST-123");
        }

        @Test
        @DisplayName("upsertSearchAttribute should update existing attribute")
        void upsertShouldUpdateExisting() {
            aggregate.upsertSearchAttribute("status", "processing");
            aggregate.upsertSearchAttribute("status", "shipped");

            assertThat(aggregate.getSearchAttributes())
                    .containsEntry("status", "shipped");
        }

        @Test
        @DisplayName("should support multiple search attributes")
        void shouldSupportMultipleAttributes() {
            aggregate.upsertSearchAttribute("region", "US-WEST");
            aggregate.upsertSearchAttribute("priority", "HIGH");
            aggregate.upsertSearchAttribute("amount", 99.99);

            assertThat(aggregate.getSearchAttributes()).hasSize(3);
        }
    }

    // ========================================================================
    // Child Workflow Tests
    // ========================================================================

    @Nested
    @DisplayName("Child Workflows")
    class ChildWorkflowTests {

        @Test
        @DisplayName("spawnChildWorkflow should track child reference")
        void spawnShouldTrackChild() {
            aggregate.spawnChildWorkflow("child-1", "payment-workflow",
                    Map.of("amount", 100), "step-2");

            assertThat(aggregate.getChildWorkflows()).containsKey("child-1");
            WorkflowAggregate.ChildWorkflowRef ref = aggregate.getChildWorkflows().get("child-1");
            assertThat(ref.childInstanceId()).isEqualTo("child-1");
            assertThat(ref.childWorkflowId()).isEqualTo("payment-workflow");
            assertThat(ref.parentStepId()).isEqualTo("step-2");
            assertThat(ref.completed()).isFalse();
            assertThat(ref.output()).isNull();
        }

        @Test
        @DisplayName("completeChildWorkflow should mark child as completed")
        void completeChildShouldMarkCompleted() {
            aggregate.spawnChildWorkflow("child-1", "payment-workflow",
                    Map.of("amount", 100), "step-2");
            aggregate.completeChildWorkflow("child-1", Map.of("paid", true), true);

            WorkflowAggregate.ChildWorkflowRef ref = aggregate.getChildWorkflows().get("child-1");
            assertThat(ref.completed()).isTrue();
            assertThat(ref.output()).isEqualTo(Map.of("paid", true));
        }
    }

    // ========================================================================
    // Heartbeat Tests
    // ========================================================================

    @Nested
    @DisplayName("Heartbeats")
    class HeartbeatTests {

        @Test
        @DisplayName("heartbeat should store latest details for step")
        void heartbeatShouldStoreDetails() {
            aggregate.heartbeat("step-1", Map.of("progress", 50, "message", "Halfway done"));

            assertThat(aggregate.getLastHeartbeats()).containsKey("step-1");
            assertThat(aggregate.getLastHeartbeats().get("step-1"))
                    .containsEntry("progress", 50)
                    .containsEntry("message", "Halfway done");
        }

        @Test
        @DisplayName("heartbeat should overwrite previous details for same step")
        void heartbeatShouldOverwrite() {
            aggregate.heartbeat("step-1", Map.of("progress", 25));
            aggregate.heartbeat("step-1", Map.of("progress", 75));

            assertThat(aggregate.getLastHeartbeats().get("step-1"))
                    .containsEntry("progress", 75)
                    .hasSize(1);
        }
    }

    // ========================================================================
    // Compensation Tests
    // ========================================================================

    @Nested
    @DisplayName("Compensation")
    class CompensationTests {

        @Test
        @DisplayName("startCompensation should produce event without error")
        void startCompensationShouldSucceed() {
            int eventsBefore = aggregate.getUncommittedEventCount();
            aggregate.startCompensation("step-3", "REVERSE_ORDER");

            assertThat(aggregate.getUncommittedEventCount()).isEqualTo(eventsBefore + 1);
        }

        @Test
        @DisplayName("completeCompensationStep should produce event without error")
        void completeCompensationStepShouldSucceed() {
            int eventsBefore = aggregate.getUncommittedEventCount();
            aggregate.completeCompensationStep("step-2", true, null);

            assertThat(aggregate.getUncommittedEventCount()).isEqualTo(eventsBefore + 1);
        }
    }

    // ========================================================================
    // StepState Record Tests
    // ========================================================================

    @Nested
    @DisplayName("StepState Record")
    class StepStateRecordTests {

        @Test
        @DisplayName("complete should return new state with COMPLETED status")
        void completeShouldReturnCompleted() {
            WorkflowAggregate.StepState initial = new WorkflowAggregate.StepState(
                    "s1", "Step 1", StepStatus.RUNNING, 1, Map.of(), null, null,
                    Instant.now(), null);

            Instant completedAt = Instant.now();
            WorkflowAggregate.StepState completed = initial.complete("output", completedAt);

            assertThat(completed.status()).isEqualTo(StepStatus.COMPLETED);
            assertThat(completed.output()).isEqualTo("output");
            assertThat(completed.completedAt()).isEqualTo(completedAt);
            assertThat(completed.errorOrReason()).isNull();
        }

        @Test
        @DisplayName("fail should return new state with FAILED status")
        void failShouldReturnFailed() {
            WorkflowAggregate.StepState initial = new WorkflowAggregate.StepState(
                    "s1", "Step 1", StepStatus.RUNNING, 1, Map.of(), null, null,
                    Instant.now(), null);

            Instant failedAt = Instant.now();
            WorkflowAggregate.StepState failed = initial.fail("Error occurred", failedAt);

            assertThat(failed.status()).isEqualTo(StepStatus.FAILED);
            assertThat(failed.errorOrReason()).isEqualTo("Error occurred");
            assertThat(failed.completedAt()).isEqualTo(failedAt);
            assertThat(failed.output()).isNull();
        }

        @Test
        @DisplayName("retry should return new state with RETRYING status")
        void retryShouldReturnRetrying() {
            WorkflowAggregate.StepState initial = new WorkflowAggregate.StepState(
                    "s1", "Step 1", StepStatus.FAILED, 1, Map.of(), null,
                    "Error", Instant.now(), Instant.now());

            WorkflowAggregate.StepState retrying = initial.retry(2);

            assertThat(retrying.status()).isEqualTo(StepStatus.RETRYING);
            assertThat(retrying.attemptNumber()).isEqualTo(2);
            assertThat(retrying.errorOrReason()).isNull();
            assertThat(retrying.completedAt()).isNull();
        }
    }

    // ========================================================================
    // ChildWorkflowRef Record Tests
    // ========================================================================

    @Nested
    @DisplayName("ChildWorkflowRef Record")
    class ChildWorkflowRefRecordTests {

        @Test
        @DisplayName("complete should return new ref marked as completed")
        void completeShouldReturnCompleted() {
            WorkflowAggregate.ChildWorkflowRef ref = new WorkflowAggregate.ChildWorkflowRef(
                    "child-1", "payment-wf", "step-2", false, false, null);

            WorkflowAggregate.ChildWorkflowRef completed = ref.complete("result", true);

            assertThat(completed.completed()).isTrue();
            assertThat(completed.success()).isTrue();
            assertThat(completed.output()).isEqualTo("result");
            assertThat(completed.childInstanceId()).isEqualTo("child-1");
            assertThat(completed.childWorkflowId()).isEqualTo("payment-wf");
            assertThat(completed.parentStepId()).isEqualTo("step-2");
        }
    }
}
