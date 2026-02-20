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

package org.fireflyframework.workflow.signal;

import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.eventsourcing.store.EventSourcedWorkflowStateStore;
import org.fireflyframework.workflow.exception.WorkflowNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SignalService}.
 * <p>
 * Tests cover signal delivery to aggregates, result verification,
 * error handling for non-existent instances, and signal consumption.
 */
@ExtendWith(MockitoExtension.class)
class SignalServiceTest {

    private static final UUID AGGREGATE_ID = UUID.randomUUID();
    private static final String INSTANCE_ID = AGGREGATE_ID.toString();
    private static final String SIGNAL_NAME = "approval-received";
    private static final Map<String, Object> SIGNAL_PAYLOAD = Map.of("approved", true, "approver", "admin");

    @Mock
    private EventSourcedWorkflowStateStore stateStore;

    private SignalService signalService;

    @BeforeEach
    void setUp() {
        signalService = new SignalService(stateStore);
    }

    /**
     * Creates a running WorkflowAggregate (started and committed).
     */
    private WorkflowAggregate createRunningAggregate() {
        WorkflowAggregate aggregate = new WorkflowAggregate(AGGREGATE_ID);
        aggregate.start("test-workflow", "Test Workflow", "1.0.0",
                Map.of("key", "value"), "corr-1", "api", false);
        aggregate.markEventsAsCommitted();
        return aggregate;
    }

    /**
     * Creates a running aggregate that already has a pending signal.
     */
    private WorkflowAggregate createAggregateWithPendingSignal() {
        WorkflowAggregate aggregate = createRunningAggregate();
        aggregate.receiveSignal(SIGNAL_NAME, SIGNAL_PAYLOAD);
        aggregate.markEventsAsCommitted();
        return aggregate;
    }

    // ========================================================================
    // sendSignal Tests
    // ========================================================================

    @Nested
    @DisplayName("sendSignal")
    class SendSignalTests {

        @Test
        @DisplayName("should deliver signal to aggregate and save")
        void sendSignal_shouldDeliverToAggregate() {
            WorkflowAggregate aggregate = createRunningAggregate();

            when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(signalService.sendSignal(INSTANCE_ID, SIGNAL_NAME, SIGNAL_PAYLOAD))
                    .assertNext(result -> {
                        assertThat(result.instanceId()).isEqualTo(INSTANCE_ID);
                        assertThat(result.signalName()).isEqualTo(SIGNAL_NAME);
                        assertThat(result.delivered()).isTrue();
                    })
                    .verifyComplete();

            verify(stateStore).loadAggregate(AGGREGATE_ID);
            verify(stateStore).saveAggregate(any(WorkflowAggregate.class));

            // Verify the signal was applied to the aggregate
            assertThat(aggregate.getPendingSignals()).containsKey(SIGNAL_NAME);
            assertThat(aggregate.getPendingSignals().get(SIGNAL_NAME).payload())
                    .isEqualTo(SIGNAL_PAYLOAD);
        }

        @Test
        @DisplayName("should return delivered result with correct fields")
        void sendSignal_shouldReturnDeliveredResult() {
            WorkflowAggregate aggregate = createRunningAggregate();

            when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(signalService.sendSignal(INSTANCE_ID, SIGNAL_NAME, SIGNAL_PAYLOAD))
                    .assertNext(result -> {
                        assertThat(result.delivered()).isTrue();
                        assertThat(result.instanceId()).isEqualTo(INSTANCE_ID);
                        assertThat(result.signalName()).isEqualTo(SIGNAL_NAME);
                        // No step was waiting for this signal
                        assertThat(result.stepResumed()).isFalse();
                        assertThat(result.resumedStepId()).isNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should error when instance does not exist")
        void sendSignal_withNonExistentInstance_shouldError() {
            when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.empty());

            StepVerifier.create(signalService.sendSignal(INSTANCE_ID, SIGNAL_NAME, SIGNAL_PAYLOAD))
                    .expectError(WorkflowNotFoundException.class)
                    .verify();

            verify(stateStore).loadAggregate(AGGREGATE_ID);
            verify(stateStore, never()).saveAggregate(any());
        }

        @Test
        @DisplayName("should error when instance ID has invalid format")
        void sendSignal_withInvalidInstanceId_shouldError() {
            StepVerifier.create(signalService.sendSignal("not-a-uuid", SIGNAL_NAME, SIGNAL_PAYLOAD))
                    .expectError(WorkflowNotFoundException.class)
                    .verify();

            verifyNoInteractions(stateStore);
        }

        @Test
        @DisplayName("should detect step resumed when signal waiter exists")
        void sendSignal_withWaitingStep_shouldIndicateStepResumed() {
            WorkflowAggregate aggregate = createRunningAggregate();
            // Register a signal waiter by adding an entry to the signalWaiters map
            aggregate.getSignalWaiters().put(SIGNAL_NAME, "waiting-step-1");

            when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(signalService.sendSignal(INSTANCE_ID, SIGNAL_NAME, SIGNAL_PAYLOAD))
                    .assertNext(result -> {
                        assertThat(result.delivered()).isTrue();
                        assertThat(result.stepResumed()).isTrue();
                        assertThat(result.resumedStepId()).isEqualTo("waiting-step-1");
                    })
                    .verifyComplete();
        }
    }

    // ========================================================================
    // consumeSignal Tests
    // ========================================================================

    @Nested
    @DisplayName("consumeSignal")
    class ConsumeSignalTests {

        @Test
        @DisplayName("should return buffered payload and persist consumption event")
        void consumeSignal_shouldReturnBufferedPayload() {
            WorkflowAggregate aggregate = createAggregateWithPendingSignal();

            when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(signalService.consumeSignal(INSTANCE_ID, SIGNAL_NAME))
                    .assertNext(payload -> {
                        assertThat(payload).containsEntry("approved", true);
                        assertThat(payload).containsEntry("approver", "admin");
                    })
                    .verifyComplete();

            verify(stateStore).loadAggregate(AGGREGATE_ID);
            verify(stateStore).saveAggregate(any(WorkflowAggregate.class));

            // Verify the signal was removed from pending
            assertThat(aggregate.getPendingSignals()).doesNotContainKey(SIGNAL_NAME);
        }

        @Test
        @DisplayName("should return empty when no signal is pending")
        void consumeSignal_withNoSignal_shouldReturnEmpty() {
            WorkflowAggregate aggregate = createRunningAggregate();
            // No pending signals

            when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.just(aggregate));

            StepVerifier.create(signalService.consumeSignal(INSTANCE_ID, SIGNAL_NAME))
                    .verifyComplete();

            verify(stateStore).loadAggregate(AGGREGATE_ID);
        }

        @Test
        @DisplayName("should return empty when instance does not exist")
        void consumeSignal_withNonExistentInstance_shouldReturnEmpty() {
            when(stateStore.loadAggregate(AGGREGATE_ID)).thenReturn(Mono.empty());

            StepVerifier.create(signalService.consumeSignal(INSTANCE_ID, SIGNAL_NAME))
                    .verifyComplete();

            verify(stateStore).loadAggregate(AGGREGATE_ID);
        }

        @Test
        @DisplayName("should return empty when instance ID has invalid format")
        void consumeSignal_withInvalidInstanceId_shouldReturnEmpty() {
            StepVerifier.create(signalService.consumeSignal("not-a-uuid", SIGNAL_NAME))
                    .verifyComplete();

            verifyNoInteractions(stateStore);
        }
    }
}
