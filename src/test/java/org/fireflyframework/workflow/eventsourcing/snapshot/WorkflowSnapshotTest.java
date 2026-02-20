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

package org.fireflyframework.workflow.eventsourcing.snapshot;

import org.fireflyframework.eventsourcing.snapshot.Snapshot;
import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.model.StepStatus;
import org.fireflyframework.workflow.model.WorkflowStatus;
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
 * Unit tests for {@link WorkflowSnapshot}.
 * <p>
 * Verifies snapshot capture, restoration, Snapshot interface implementation,
 * and round-trip preservation of signals, timers, and side effects.
 */
class WorkflowSnapshotTest {

    private static final UUID AGGREGATE_ID = UUID.randomUUID();
    private static final String WORKFLOW_ID = "order-processing";
    private static final String WORKFLOW_NAME = "Order Processing";
    private static final String WORKFLOW_VERSION = "1.0.0";
    private static final Map<String, Object> INPUT = Map.of("orderId", "ORD-123");
    private static final String CORRELATION_ID = "corr-456";
    private static final String TRIGGERED_BY = "api-gateway";

    private WorkflowAggregate aggregate;

    @BeforeEach
    void setUp() {
        aggregate = new WorkflowAggregate(AGGREGATE_ID);
    }

    // ========================================================================
    // Capture Tests
    // ========================================================================

    @Nested
    @DisplayName("Capture aggregate state")
    class CaptureTests {

        @Test
        @DisplayName("should capture aggregate state after start and steps")
        void shouldCaptureAggregateState() {
            // Given: a started workflow with steps
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);
            aggregate.startStep("step-1", "Validate Order", Map.of("orderId", "ORD-123"), 1);
            aggregate.completeStep("step-1", Map.of("valid", true), 150L);
            aggregate.startStep("step-2", "Process Payment", Map.of("amount", 99.99), 1);

            // When: we create a snapshot
            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);

            // Then: all fields should match the aggregate state
            assertThat(snapshot.getAggregateId()).isEqualTo(AGGREGATE_ID);
            assertThat(snapshot.getVersion()).isEqualTo(aggregate.getCurrentVersion());
            assertThat(snapshot.getWorkflowId()).isEqualTo(WORKFLOW_ID);
            assertThat(snapshot.getWorkflowName()).isEqualTo(WORKFLOW_NAME);
            assertThat(snapshot.getWorkflowVersion()).isEqualTo(WORKFLOW_VERSION);
            assertThat(snapshot.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
            assertThat(snapshot.getCurrentStepId()).isEqualTo("step-2");
            assertThat(snapshot.getCorrelationId()).isEqualTo(CORRELATION_ID);
            assertThat(snapshot.getTriggeredBy()).isEqualTo(TRIGGERED_BY);
            assertThat(snapshot.isDryRun()).isFalse();
            assertThat(snapshot.getStartedAt()).isNotNull();
            assertThat(snapshot.getCompletedAt()).isNull();

            // Verify context contains step outputs
            assertThat(snapshot.getContext()).containsEntry("valid", true);
            assertThat(snapshot.getContext()).containsKey("step-1.output");

            // Verify input
            assertThat(snapshot.getInput()).containsEntry("orderId", "ORD-123");

            // Verify step states
            assertThat(snapshot.getStepStatesSnapshot()).hasSize(2);
            WorkflowSnapshot.StepStateData step1 = snapshot.getStepStatesSnapshot().get("step-1");
            assertThat(step1.stepId()).isEqualTo("step-1");
            assertThat(step1.stepName()).isEqualTo("Validate Order");
            assertThat(step1.status()).isEqualTo(StepStatus.COMPLETED);

            WorkflowSnapshot.StepStateData step2 = snapshot.getStepStatesSnapshot().get("step-2");
            assertThat(step2.stepId()).isEqualTo("step-2");
            assertThat(step2.status()).isEqualTo(StepStatus.RUNNING);

            // Verify completed step order
            assertThat(snapshot.getCompletedStepOrder()).containsExactly("step-1");
        }

        @Test
        @DisplayName("should capture dry run flag")
        void shouldCaptureDryRunFlag() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, true);

            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);

            assertThat(snapshot.isDryRun()).isTrue();
        }

        @Test
        @DisplayName("should capture completed workflow state")
        void shouldCaptureCompletedState() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);
            aggregate.complete(Map.of("result", "success"));

            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);

            assertThat(snapshot.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(snapshot.getOutput()).isEqualTo(Map.of("result", "success"));
            assertThat(snapshot.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("should throw on null aggregate")
        void shouldThrowOnNullAggregate() {
            assertThatThrownBy(() -> WorkflowSnapshot.from(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null");
        }

        @Test
        @DisplayName("should create snapshot timestamp")
        void shouldCreateSnapshotTimestamp() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);

            Instant before = Instant.now();
            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);
            Instant after = Instant.now();

            assertThat(snapshot.getCreatedAt()).isBetween(before, after);
        }
    }

    // ========================================================================
    // Restore Tests
    // ========================================================================

    @Nested
    @DisplayName("Restore aggregate state")
    class RestoreTests {

        @Test
        @DisplayName("should restore aggregate state from snapshot")
        void shouldRestoreAggregateState() {
            // Given: a workflow with various state
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);
            aggregate.startStep("step-1", "Validate Order", Map.of("orderId", "ORD-123"), 1);
            aggregate.completeStep("step-1", Map.of("valid", true), 150L);
            aggregate.startStep("step-2", "Process Payment", Map.of("amount", 99.99), 1);

            // When: snapshot and restore
            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);
            WorkflowAggregate restored = snapshot.restore();

            // Then: restored aggregate matches original
            assertThat(restored.getId()).isEqualTo(aggregate.getId());
            assertThat(restored.getCurrentVersion()).isEqualTo(aggregate.getCurrentVersion());
            assertThat(restored.getWorkflowId()).isEqualTo(aggregate.getWorkflowId());
            assertThat(restored.getWorkflowName()).isEqualTo(aggregate.getWorkflowName());
            assertThat(restored.getWorkflowVersion()).isEqualTo(aggregate.getWorkflowVersion());
            assertThat(restored.getStatus()).isEqualTo(aggregate.getStatus());
            assertThat(restored.getCurrentStepId()).isEqualTo(aggregate.getCurrentStepId());
            assertThat(restored.getCorrelationId()).isEqualTo(aggregate.getCorrelationId());
            assertThat(restored.getTriggeredBy()).isEqualTo(aggregate.getTriggeredBy());
            assertThat(restored.isDryRun()).isEqualTo(aggregate.isDryRun());
            assertThat(restored.getStartedAt()).isEqualTo(aggregate.getStartedAt());
            assertThat(restored.getCompletedAt()).isEqualTo(aggregate.getCompletedAt());

            // Verify context
            assertThat(restored.getContext()).containsEntry("valid", true);
            assertThat(restored.getContext()).containsKey("step-1.output");

            // Verify input
            assertThat(restored.getInput()).isEqualTo(aggregate.getInput());

            // Verify step states
            assertThat(restored.getStepStates()).hasSize(2);
            assertThat(restored.getStepStates().get("step-1").status())
                    .isEqualTo(StepStatus.COMPLETED);
            assertThat(restored.getStepStates().get("step-2").status())
                    .isEqualTo(StepStatus.RUNNING);

            // Verify completed step order
            assertThat(restored.getCompletedStepOrder()).containsExactly("step-1");
        }

        @Test
        @DisplayName("should restore with no uncommitted events")
        void shouldRestoreWithNoUncommittedEvents() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);

            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);
            WorkflowAggregate restored = snapshot.restore();

            assertThat(restored.hasUncommittedEvents()).isFalse();
        }

        @Test
        @DisplayName("should restore completed workflow output")
        void shouldRestoreCompletedOutput() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);
            Map<String, Object> outputData = Map.of("result", "success", "count", 42);
            aggregate.complete(outputData);

            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);
            WorkflowAggregate restored = snapshot.restore();

            assertThat(restored.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(restored.getOutput()).isEqualTo(outputData);
            assertThat(restored.getCompletedAt()).isNotNull();
        }
    }

    // ========================================================================
    // Snapshot Interface Tests
    // ========================================================================

    @Nested
    @DisplayName("Snapshot interface implementation")
    class SnapshotInterfaceTests {

        @Test
        @DisplayName("should implement Snapshot interface")
        void shouldImplementSnapshotInterface() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);

            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);

            // Verify it is a Snapshot
            assertThat(snapshot).isInstanceOf(Snapshot.class);
        }

        @Test
        @DisplayName("should return correct aggregate ID")
        void shouldReturnCorrectAggregateId() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);

            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);

            assertThat(snapshot.getAggregateId()).isEqualTo(AGGREGATE_ID);
        }

        @Test
        @DisplayName("should return snapshot type as 'workflow'")
        void shouldReturnSnapshotType() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);

            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);

            assertThat(snapshot.getSnapshotType()).isEqualTo("workflow");
        }

        @Test
        @DisplayName("should return correct version")
        void shouldReturnCorrectVersion() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);
            aggregate.startStep("step-1", "Step One", Map.of(), 1);
            aggregate.completeStep("step-1", "done", 100L);

            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);

            // start(1) + startStep(1) + completeStep(1) = 3 events, version = 3 - 1 = 2
            assertThat(snapshot.getVersion()).isEqualTo(2L);
        }

        @Test
        @DisplayName("should return non-null createdAt")
        void shouldReturnCreatedAt() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);

            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);

            assertThat(snapshot.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("should return default snapshot version of 1")
        void shouldReturnDefaultSnapshotVersion() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);

            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);

            assertThat(snapshot.getSnapshotVersion()).isEqualTo(1);
        }

        @Test
        @DisplayName("isForVersion should work correctly")
        void isForVersionShouldWork() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);

            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);

            assertThat(snapshot.isForVersion(0L)).isTrue();
            assertThat(snapshot.isForVersion(1L)).isFalse();
        }

        @Test
        @DisplayName("isNewerThan should work correctly")
        void isNewerThanShouldWork() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);

            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);

            assertThat(snapshot.isNewerThan(-1L)).isTrue();
            assertThat(snapshot.isNewerThan(0L)).isFalse();
        }
    }

    // ========================================================================
    // Round-Trip with Signals, Timers, Side Effects
    // ========================================================================

    @Nested
    @DisplayName("Round-trip with signals, timers, and side effects")
    class RoundTripTests {

        @Test
        @DisplayName("should preserve signals, timers, and side effects through round-trip")
        void roundTripShouldPreserveSignalsTimersAndSideEffects() {
            // Given: a workflow with signals, timers, side effects, and search attributes
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);

            // Add signals
            aggregate.receiveSignal("approval", Map.of("approved", true, "approver", "admin"));
            aggregate.receiveSignal("notification", Map.of("type", "email"));

            // Add timers
            Instant timerFireAt = Instant.now().plusSeconds(3600);
            aggregate.registerTimer("timeout-timer", timerFireAt, Map.of("reason", "deadline"));
            Instant reminderFireAt = Instant.now().plusSeconds(1800);
            aggregate.registerTimer("reminder-timer", reminderFireAt, Map.of("type", "reminder"));

            // Add side effects
            aggregate.recordSideEffect("random-id", "abc-123-def");
            aggregate.recordSideEffect("timestamp", 1700000000L);

            // Add search attributes
            aggregate.upsertSearchAttribute("customer", "ACME Corp");
            aggregate.upsertSearchAttribute("priority", "HIGH");

            // Add heartbeat
            aggregate.startStep("long-step", "Long Running Step", Map.of(), 1);
            aggregate.heartbeat("long-step", Map.of("progress", 50, "message", "halfway"));

            // Spawn a child workflow
            aggregate.spawnChildWorkflow("child-1", "sub-workflow",
                    Map.of("input", "data"), "long-step");

            // When: snapshot and restore
            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);
            WorkflowAggregate restored = snapshot.restore();

            // Then: signals are preserved
            assertThat(restored.getPendingSignals()).hasSize(2);
            assertThat(restored.getPendingSignals()).containsKey("approval");
            assertThat(restored.getPendingSignals().get("approval").payload())
                    .containsEntry("approved", true)
                    .containsEntry("approver", "admin");
            assertThat(restored.getPendingSignals()).containsKey("notification");
            assertThat(restored.getPendingSignals().get("notification").payload())
                    .containsEntry("type", "email");

            // Then: timers are preserved
            assertThat(restored.getActiveTimers()).hasSize(2);
            assertThat(restored.getActiveTimers()).containsKey("timeout-timer");
            assertThat(restored.getActiveTimers().get("timeout-timer").fireAt())
                    .isEqualTo(timerFireAt);
            assertThat(restored.getActiveTimers().get("timeout-timer").data())
                    .containsEntry("reason", "deadline");
            assertThat(restored.getActiveTimers()).containsKey("reminder-timer");

            // Then: side effects are preserved
            assertThat(restored.getSideEffect("random-id")).contains("abc-123-def");
            assertThat(restored.getSideEffect("timestamp")).contains(1700000000L);

            // Then: search attributes are preserved
            assertThat(restored.getSearchAttributes())
                    .containsEntry("customer", "ACME Corp")
                    .containsEntry("priority", "HIGH");

            // Then: heartbeats are preserved
            assertThat(restored.getLastHeartbeats()).containsKey("long-step");
            assertThat(restored.getLastHeartbeats().get("long-step"))
                    .containsEntry("progress", 50)
                    .containsEntry("message", "halfway");

            // Then: child workflows are preserved
            assertThat(restored.getChildWorkflows()).hasSize(1);
            assertThat(restored.getChildWorkflows()).containsKey("child-1");
            assertThat(restored.getChildWorkflows().get("child-1").childWorkflowId())
                    .isEqualTo("sub-workflow");
            assertThat(restored.getChildWorkflows().get("child-1").parentStepId())
                    .isEqualTo("long-step");
            assertThat(restored.getChildWorkflows().get("child-1").completed())
                    .isFalse();
        }

        @Test
        @DisplayName("should preserve step state details through round-trip")
        void roundTripShouldPreserveStepStateDetails() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);

            // Various step states
            aggregate.startStep("step-1", "Step One", Map.of("key", "val"), 1);
            aggregate.completeStep("step-1", Map.of("result", "ok"), 200L);

            aggregate.startStep("step-2", "Step Two", Map.of(), 1);
            aggregate.failStep("step-2", "Connection refused", "IOException", 1, true);

            aggregate.skipStep("step-3", "Condition not met");

            // Snapshot and restore
            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);
            WorkflowAggregate restored = snapshot.restore();

            // Verify step state details
            assertThat(restored.getStepStates()).hasSize(3);

            WorkflowAggregate.StepState s1 = restored.getStepStates().get("step-1");
            assertThat(s1.stepId()).isEqualTo("step-1");
            assertThat(s1.stepName()).isEqualTo("Step One");
            assertThat(s1.status()).isEqualTo(StepStatus.COMPLETED);
            assertThat(s1.output()).isEqualTo(Map.of("result", "ok"));
            assertThat(s1.startedAt()).isNotNull();
            assertThat(s1.completedAt()).isNotNull();

            WorkflowAggregate.StepState s2 = restored.getStepStates().get("step-2");
            assertThat(s2.status()).isEqualTo(StepStatus.FAILED);
            assertThat(s2.errorOrReason()).isEqualTo("Connection refused");

            WorkflowAggregate.StepState s3 = restored.getStepStates().get("step-3");
            assertThat(s3.status()).isEqualTo(StepStatus.SKIPPED);
            assertThat(s3.errorOrReason()).isEqualTo("Condition not met");
        }

        @Test
        @DisplayName("should preserve version through round-trip")
        void roundTripShouldPreserveVersion() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);
            aggregate.startStep("step-1", "Step One", Map.of(), 1);
            aggregate.completeStep("step-1", "done", 100L);

            long originalVersion = aggregate.getCurrentVersion();

            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);
            WorkflowAggregate restored = snapshot.restore();

            assertThat(restored.getCurrentVersion()).isEqualTo(originalVersion);
        }

        @Test
        @DisplayName("should handle empty collections gracefully")
        void roundTripShouldHandleEmptyCollections() {
            // A freshly started workflow with minimal state
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);

            WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);
            WorkflowAggregate restored = snapshot.restore();

            assertThat(restored.getStepStates()).isEmpty();
            assertThat(restored.getPendingSignals()).isEmpty();
            assertThat(restored.getActiveTimers()).isEmpty();
            assertThat(restored.getChildWorkflows()).isEmpty();
            assertThat(restored.getSideEffects()).isEmpty();
            assertThat(restored.getSearchAttributes()).isEmpty();
            assertThat(restored.getLastHeartbeats()).isEmpty();
            assertThat(restored.getCompletedStepOrder()).isEmpty();
        }
    }

    // ========================================================================
    // StepStateData Tests
    // ========================================================================

    @Nested
    @DisplayName("StepStateData conversion")
    class StepStateDataTests {

        @Test
        @DisplayName("should convert StepState to StepStateData and back")
        void shouldConvertRoundTrip() {
            WorkflowAggregate.StepState original = new WorkflowAggregate.StepState(
                    "step-1", "Validate Order", StepStatus.COMPLETED,
                    2, Map.of("key", "value"), Map.of("result", true),
                    null, Instant.now().minusSeconds(60), Instant.now());

            WorkflowSnapshot.StepStateData data = WorkflowSnapshot.StepStateData.from(original);
            WorkflowAggregate.StepState restored = data.toStepState();

            assertThat(restored.stepId()).isEqualTo(original.stepId());
            assertThat(restored.stepName()).isEqualTo(original.stepName());
            assertThat(restored.status()).isEqualTo(original.status());
            assertThat(restored.attemptNumber()).isEqualTo(original.attemptNumber());
            assertThat(restored.input()).isEqualTo(original.input());
            assertThat(restored.output()).isEqualTo(original.output());
            assertThat(restored.errorOrReason()).isEqualTo(original.errorOrReason());
            assertThat(restored.startedAt()).isEqualTo(original.startedAt());
            assertThat(restored.completedAt()).isEqualTo(original.completedAt());
        }

        @Test
        @DisplayName("should handle null input in StepState conversion")
        void shouldHandleNullInput() {
            WorkflowAggregate.StepState original = new WorkflowAggregate.StepState(
                    "step-1", "Step", StepStatus.RUNNING, 1,
                    null, null, null, Instant.now(), null);

            WorkflowSnapshot.StepStateData data = WorkflowSnapshot.StepStateData.from(original);
            WorkflowAggregate.StepState restored = data.toStepState();

            assertThat(restored.input()).isNull();
            assertThat(restored.output()).isNull();
        }
    }
}
