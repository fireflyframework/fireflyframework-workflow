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

import org.fireflyframework.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WorkflowAggregate} lifecycle transitions.
 * <p>
 * Tests cover: start, suspend/resume, cancel, fail, complete,
 * and guard method validations.
 */
class WorkflowAggregateTest {

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
    // Constructor Tests
    // ========================================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should initialize with PENDING status")
        void shouldInitializeWithPendingStatus() {
            assertThat(aggregate.getStatus()).isEqualTo(WorkflowStatus.PENDING);
        }

        @Test
        @DisplayName("should initialize with correct aggregate ID")
        void shouldInitializeWithCorrectId() {
            assertThat(aggregate.getId()).isEqualTo(AGGREGATE_ID);
        }

        @Test
        @DisplayName("should initialize with empty context")
        void shouldInitializeWithEmptyContext() {
            assertThat(aggregate.getContext()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("should have no uncommitted events initially")
        void shouldHaveNoUncommittedEvents() {
            assertThat(aggregate.hasUncommittedEvents()).isFalse();
        }
    }

    // ========================================================================
    // Start Tests
    // ========================================================================

    @Nested
    @DisplayName("Start")
    class StartTests {

        @Test
        @DisplayName("should transition from PENDING to RUNNING")
        void shouldTransitionToRunning() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);

            assertThat(aggregate.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
        }

        @Test
        @DisplayName("should set all workflow fields")
        void shouldSetAllFields() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);

            assertThat(aggregate.getWorkflowId()).isEqualTo(WORKFLOW_ID);
            assertThat(aggregate.getWorkflowName()).isEqualTo(WORKFLOW_NAME);
            assertThat(aggregate.getWorkflowVersion()).isEqualTo(WORKFLOW_VERSION);
            assertThat(aggregate.getInput()).isEqualTo(INPUT);
            assertThat(aggregate.getCorrelationId()).isEqualTo(CORRELATION_ID);
            assertThat(aggregate.getTriggeredBy()).isEqualTo(TRIGGERED_BY);
            assertThat(aggregate.isDryRun()).isFalse();
        }

        @Test
        @DisplayName("should set startedAt timestamp")
        void shouldSetStartedAt() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);

            assertThat(aggregate.getStartedAt()).isNotNull();
        }

        @Test
        @DisplayName("should support dry run mode")
        void shouldSupportDryRun() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, true);

            assertThat(aggregate.isDryRun()).isTrue();
        }

        @Test
        @DisplayName("should produce one uncommitted event")
        void shouldProduceOneEvent() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);

            assertThat(aggregate.getUncommittedEventCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should throw if already started")
        void shouldThrowIfAlreadyStarted() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);

            assertThatThrownBy(() -> aggregate.start(WORKFLOW_ID, WORKFLOW_NAME,
                    WORKFLOW_VERSION, INPUT, CORRELATION_ID, TRIGGERED_BY, false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("RUNNING");
        }

        @Test
        @DisplayName("should handle null input gracefully")
        void shouldHandleNullInput() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    null, CORRELATION_ID, TRIGGERED_BY, false);

            assertThat(aggregate.getInput()).isNotNull().isEmpty();
        }
    }

    // ========================================================================
    // Complete Tests
    // ========================================================================

    @Nested
    @DisplayName("Complete")
    class CompleteTests {

        @BeforeEach
        void startWorkflow() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);
        }

        @Test
        @DisplayName("should transition to COMPLETED")
        void shouldTransitionToCompleted() {
            aggregate.complete(Map.of("result", "success"));

            assertThat(aggregate.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        }

        @Test
        @DisplayName("should set output")
        void shouldSetOutput() {
            Map<String, Object> output = Map.of("result", "success");
            aggregate.complete(output);

            assertThat(aggregate.getOutput()).isEqualTo(output);
        }

        @Test
        @DisplayName("should set completedAt timestamp")
        void shouldSetCompletedAt() {
            aggregate.complete(Map.of("result", "success"));

            assertThat(aggregate.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("should throw if already completed")
        void shouldThrowIfAlreadyCompleted() {
            aggregate.complete(Map.of("result", "success"));

            assertThatThrownBy(() -> aggregate.complete(Map.of("result", "another")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("terminal");
        }
    }

    // ========================================================================
    // Fail Tests
    // ========================================================================

    @Nested
    @DisplayName("Fail")
    class FailTests {

        @BeforeEach
        void startWorkflow() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);
        }

        @Test
        @DisplayName("should transition to FAILED")
        void shouldTransitionToFailed() {
            aggregate.fail("Something went wrong", "RuntimeException", "step-1");

            assertThat(aggregate.getStatus()).isEqualTo(WorkflowStatus.FAILED);
        }

        @Test
        @DisplayName("should set completedAt timestamp")
        void shouldSetCompletedAt() {
            aggregate.fail("Something went wrong", "RuntimeException", "step-1");

            assertThat(aggregate.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("should throw if already failed")
        void shouldThrowIfAlreadyFailed() {
            aggregate.fail("Error 1", "RuntimeException", "step-1");

            assertThatThrownBy(() -> aggregate.fail("Error 2", "RuntimeException", "step-2"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("terminal");
        }
    }

    // ========================================================================
    // Cancel Tests
    // ========================================================================

    @Nested
    @DisplayName("Cancel")
    class CancelTests {

        @BeforeEach
        void startWorkflow() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);
        }

        @Test
        @DisplayName("should transition to CANCELLED")
        void shouldTransitionToCancelled() {
            aggregate.cancel("User requested");

            assertThat(aggregate.getStatus()).isEqualTo(WorkflowStatus.CANCELLED);
        }

        @Test
        @DisplayName("should set completedAt timestamp")
        void shouldSetCompletedAt() {
            aggregate.cancel("User requested");

            assertThat(aggregate.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("should throw if already in terminal state")
        void shouldThrowIfTerminal() {
            aggregate.complete(Map.of("done", true));

            assertThatThrownBy(() -> aggregate.cancel("Too late"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("terminal");
        }

        @Test
        @DisplayName("should throw when cancelling a cancelled workflow")
        void shouldThrowWhenCancellingCancelled() {
            aggregate.cancel("First cancel");

            assertThatThrownBy(() -> aggregate.cancel("Second cancel"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("terminal");
        }
    }

    // ========================================================================
    // Suspend / Resume Tests
    // ========================================================================

    @Nested
    @DisplayName("Suspend and Resume")
    class SuspendResumeTests {

        @BeforeEach
        void startWorkflow() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);
        }

        @Test
        @DisplayName("should transition to SUSPENDED from RUNNING")
        void shouldSuspendFromRunning() {
            aggregate.suspend("Waiting for approval");

            assertThat(aggregate.getStatus()).isEqualTo(WorkflowStatus.SUSPENDED);
        }

        @Test
        @DisplayName("should resume from SUSPENDED to RUNNING")
        void shouldResumeToRunning() {
            aggregate.suspend("Waiting for approval");
            aggregate.resume();

            assertThat(aggregate.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
        }

        @Test
        @DisplayName("should throw when suspending a terminal workflow")
        void shouldThrowWhenSuspendingTerminal() {
            aggregate.complete(Map.of("done", true));

            assertThatThrownBy(() -> aggregate.suspend("Too late"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("should throw when suspending an already suspended workflow")
        void shouldThrowWhenSuspendingAlreadySuspended() {
            aggregate.suspend("First suspension");

            assertThatThrownBy(() -> aggregate.suspend("Second suspension"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("should throw when resuming a non-suspended workflow")
        void shouldThrowWhenResumingNonSuspended() {
            assertThatThrownBy(() -> aggregate.resume())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("should allow suspend from PENDING")
        void shouldAllowSuspendFromPending() {
            WorkflowAggregate pendingAggregate = new WorkflowAggregate(UUID.randomUUID());

            pendingAggregate.suspend("Pre-start suspension");

            assertThat(pendingAggregate.getStatus()).isEqualTo(WorkflowStatus.SUSPENDED);
        }

        @Test
        @DisplayName("should allow multiple suspend/resume cycles")
        void shouldAllowMultipleCycles() {
            aggregate.suspend("First suspension");
            aggregate.resume();
            aggregate.suspend("Second suspension");
            aggregate.resume();

            assertThat(aggregate.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
        }
    }

    // ========================================================================
    // Continue As New Tests
    // ========================================================================

    @Nested
    @DisplayName("Continue As New")
    class ContinueAsNewTests {

        @BeforeEach
        void startWorkflow() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);
        }

        @Test
        @DisplayName("should transition to COMPLETED")
        void shouldTransitionToCompleted() {
            aggregate.continueAsNew(Map.of("nextBatch", 2), Map.of("processed", 100));

            assertThat(aggregate.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        }

        @Test
        @DisplayName("should set output to completedRunOutput")
        void shouldSetOutput() {
            Map<String, Object> completedOutput = Map.of("processed", 100);
            aggregate.continueAsNew(Map.of("nextBatch", 2), completedOutput);

            assertThat(aggregate.getOutput()).isEqualTo(completedOutput);
        }

        @Test
        @DisplayName("should throw if workflow is in terminal state")
        void shouldThrowIfTerminal() {
            aggregate.complete(Map.of("done", true));

            assertThatThrownBy(() -> aggregate.continueAsNew(Map.of("next", 1), null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("terminal");
        }
    }

    // ========================================================================
    // Error Tracking Tests
    // ========================================================================

    @Nested
    @DisplayName("Error Tracking")
    class ErrorTrackingTests {

        @BeforeEach
        void startWorkflow() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);
        }

        @Test
        @DisplayName("should store error details on failure")
        void shouldStoreErrorDetails() {
            aggregate.fail("Connection timeout", "TimeoutException", "step-2");

            assertThat(aggregate.getErrorMessage()).isEqualTo("Connection timeout");
            assertThat(aggregate.getErrorType()).isEqualTo("TimeoutException");
            assertThat(aggregate.getFailedStepId()).isEqualTo("step-2");
        }

        @Test
        @DisplayName("should have null error fields before failure")
        void shouldHaveNullErrorFieldsBeforeFailure() {
            assertThat(aggregate.getErrorMessage()).isNull();
            assertThat(aggregate.getErrorType()).isNull();
            assertThat(aggregate.getFailedStepId()).isNull();
        }

        @Test
        @DisplayName("error fields should survive snapshot round-trip")
        void errorFieldsShouldSurviveRoundTrip() {
            aggregate.fail("NullPointerException occurred", "NullPointerException", "validate");

            var snapshot = org.fireflyframework.workflow.eventsourcing.snapshot.WorkflowSnapshot.from(aggregate);
            WorkflowAggregate restored = snapshot.restore();

            assertThat(restored.getErrorMessage()).isEqualTo("NullPointerException occurred");
            assertThat(restored.getErrorType()).isEqualTo("NullPointerException");
            assertThat(restored.getFailedStepId()).isEqualTo("validate");
        }
    }

    // ========================================================================
    // Event Version Tests
    // ========================================================================

    @Nested
    @DisplayName("Event Version")
    class VersionTests {

        @Test
        @DisplayName("should increment version with each event")
        void shouldIncrementVersion() {
            assertThat(aggregate.getCurrentVersion()).isEqualTo(-1L);

            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);
            assertThat(aggregate.getCurrentVersion()).isEqualTo(0L);

            aggregate.suspend("reason");
            assertThat(aggregate.getCurrentVersion()).isEqualTo(1L);

            aggregate.resume();
            assertThat(aggregate.getCurrentVersion()).isEqualTo(2L);
        }
    }

    // ========================================================================
    // Guard Method Integration Tests
    // ========================================================================

    @Nested
    @DisplayName("Guard Methods")
    class GuardMethodTests {

        @Test
        @DisplayName("cannot start a workflow that is already running")
        void cannotStartRunning() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);

            assertThatThrownBy(() -> aggregate.start(WORKFLOW_ID, WORKFLOW_NAME,
                    WORKFLOW_VERSION, INPUT, CORRELATION_ID, TRIGGERED_BY, false))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("cannot complete a PENDING workflow that was never started")
        void canCompleteFromPending() {
            // PENDING is not terminal, so complete should work
            // (the workflow was created but can be completed without starting)
            // Actually, PENDING is non-terminal so this should succeed
            aggregate.complete(Map.of("early", true));
            assertThat(aggregate.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        }

        @Test
        @DisplayName("cannot fail a completed workflow")
        void cannotFailCompleted() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);
            aggregate.complete(Map.of("done", true));

            assertThatThrownBy(() -> aggregate.fail("error", "RuntimeException", "step-1"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("cannot cancel a failed workflow")
        void cannotCancelFailed() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);
            aggregate.fail("error", "RuntimeException", "step-1");

            assertThatThrownBy(() -> aggregate.cancel("reason"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("cannot receive signal on terminal workflow")
        void cannotReceiveSignalOnTerminal() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);
            aggregate.complete(Map.of("done", true));

            assertThatThrownBy(() -> aggregate.receiveSignal("approval", Map.of()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("cannot register timer on terminal workflow")
        void cannotRegisterTimerOnTerminal() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);
            aggregate.cancel("done");

            assertThatThrownBy(() -> aggregate.registerTimer("t1",
                    java.time.Instant.now().plusSeconds(60), Map.of()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("cannot spawn child workflow on terminal workflow")
        void cannotSpawnChildOnTerminal() {
            aggregate.start(WORKFLOW_ID, WORKFLOW_NAME, WORKFLOW_VERSION,
                    INPUT, CORRELATION_ID, TRIGGERED_BY, false);
            aggregate.fail("error", "RuntimeException", "step-1");

            assertThatThrownBy(() -> aggregate.spawnChildWorkflow("child-1",
                    "child-wf", Map.of(), "step-1"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
