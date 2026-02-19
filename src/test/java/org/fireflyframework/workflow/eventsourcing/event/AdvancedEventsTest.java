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

package org.fireflyframework.workflow.eventsourcing.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for advanced domain events (12-22).
 */
class AdvancedEventsTest {

    private static final UUID AGGREGATE_ID = UUID.randomUUID();

    // --- SignalReceivedEvent ---

    @Test
    void signalReceivedEvent_shouldBuildWithAllFields() {
        Map<String, Object> payload = Map.of("approved", true, "approver", "manager-1");

        SignalReceivedEvent event = SignalReceivedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .signalName("approval")
                .payload(payload)
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getSignalName()).isEqualTo("approval");
        assertThat(event.getPayload()).isEqualTo(payload);
    }

    @Test
    void signalReceivedEvent_shouldReturnCorrectEventType() {
        SignalReceivedEvent event = SignalReceivedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.signal.received");
    }

    // --- TimerRegisteredEvent ---

    @Test
    void timerRegisteredEvent_shouldBuildWithAllFields() {
        Instant fireAt = Instant.parse("2026-03-01T10:00:00Z");
        Map<String, Object> data = Map.of("reminderType", "expiry");

        TimerRegisteredEvent event = TimerRegisteredEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .timerId("timer-001")
                .fireAt(fireAt)
                .data(data)
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getTimerId()).isEqualTo("timer-001");
        assertThat(event.getFireAt()).isEqualTo(fireAt);
        assertThat(event.getData()).isEqualTo(data);
    }

    @Test
    void timerRegisteredEvent_shouldReturnCorrectEventType() {
        TimerRegisteredEvent event = TimerRegisteredEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.timer.registered");
    }

    // --- TimerFiredEvent ---

    @Test
    void timerFiredEvent_shouldBuildWithAllFields() {
        TimerFiredEvent event = TimerFiredEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .timerId("timer-001")
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getTimerId()).isEqualTo("timer-001");
    }

    @Test
    void timerFiredEvent_shouldReturnCorrectEventType() {
        TimerFiredEvent event = TimerFiredEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.timer.fired");
    }

    // --- ChildWorkflowSpawnedEvent ---

    @Test
    void childWorkflowSpawnedEvent_shouldBuildWithAllFields() {
        Map<String, Object> input = Map.of("itemId", "ITEM-100");

        ChildWorkflowSpawnedEvent event = ChildWorkflowSpawnedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .childInstanceId("child-inst-001")
                .childWorkflowId("process-item")
                .input(input)
                .parentStepId("batch-step")
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getChildInstanceId()).isEqualTo("child-inst-001");
        assertThat(event.getChildWorkflowId()).isEqualTo("process-item");
        assertThat(event.getInput()).isEqualTo(input);
        assertThat(event.getParentStepId()).isEqualTo("batch-step");
    }

    @Test
    void childWorkflowSpawnedEvent_shouldReturnCorrectEventType() {
        ChildWorkflowSpawnedEvent event = ChildWorkflowSpawnedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.child.spawned");
    }

    // --- ChildWorkflowCompletedEvent ---

    @Test
    void childWorkflowCompletedEvent_shouldBuildWithAllFields() {
        ChildWorkflowCompletedEvent event = ChildWorkflowCompletedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .childInstanceId("child-inst-001")
                .output(Map.of("processed", true))
                .success(true)
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getChildInstanceId()).isEqualTo("child-inst-001");
        assertThat(event.getOutput()).isEqualTo(Map.of("processed", true));
        assertThat(event.isSuccess()).isTrue();
    }

    @Test
    void childWorkflowCompletedEvent_shouldReturnCorrectEventType() {
        ChildWorkflowCompletedEvent event = ChildWorkflowCompletedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.child.completed");
    }

    @Test
    void childWorkflowCompletedEvent_shouldSupportFailedChild() {
        ChildWorkflowCompletedEvent event = ChildWorkflowCompletedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .childInstanceId("child-inst-002")
                .success(false)
                .build();

        assertThat(event.isSuccess()).isFalse();
    }

    // --- SideEffectRecordedEvent ---

    @Test
    void sideEffectRecordedEvent_shouldBuildWithAllFields() {
        SideEffectRecordedEvent event = SideEffectRecordedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .sideEffectId("random-uuid-1")
                .value("generated-value-abc")
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getSideEffectId()).isEqualTo("random-uuid-1");
        assertThat(event.getValue()).isEqualTo("generated-value-abc");
    }

    @Test
    void sideEffectRecordedEvent_shouldReturnCorrectEventType() {
        SideEffectRecordedEvent event = SideEffectRecordedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.side-effect.recorded");
    }

    // --- HeartbeatRecordedEvent ---

    @Test
    void heartbeatRecordedEvent_shouldBuildWithAllFields() {
        Map<String, Object> details = Map.of("progress", 75, "itemsProcessed", 150);

        HeartbeatRecordedEvent event = HeartbeatRecordedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .stepId("long-running-import")
                .details(details)
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getStepId()).isEqualTo("long-running-import");
        assertThat(event.getDetails()).isEqualTo(details);
    }

    @Test
    void heartbeatRecordedEvent_shouldReturnCorrectEventType() {
        HeartbeatRecordedEvent event = HeartbeatRecordedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.heartbeat.recorded");
    }

    // --- ContinueAsNewEvent ---

    @Test
    void continueAsNewEvent_shouldBuildWithAllFields() {
        Map<String, Object> newInput = Map.of("cursor", "page-5");

        ContinueAsNewEvent event = ContinueAsNewEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .newInput(newInput)
                .completedRunOutput(Map.of("pagesProcessed", 4))
                .previousRunId("run-001")
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getNewInput()).isEqualTo(newInput);
        assertThat(event.getCompletedRunOutput()).isEqualTo(Map.of("pagesProcessed", 4));
        assertThat(event.getPreviousRunId()).isEqualTo("run-001");
    }

    @Test
    void continueAsNewEvent_shouldReturnCorrectEventType() {
        ContinueAsNewEvent event = ContinueAsNewEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.continue-as-new");
    }

    // --- CompensationStartedEvent ---

    @Test
    void compensationStartedEvent_shouldBuildWithAllFields() {
        CompensationStartedEvent event = CompensationStartedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .failedStepId("charge-payment")
                .compensationPolicy("REVERSE_ORDER")
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getFailedStepId()).isEqualTo("charge-payment");
        assertThat(event.getCompensationPolicy()).isEqualTo("REVERSE_ORDER");
    }

    @Test
    void compensationStartedEvent_shouldReturnCorrectEventType() {
        CompensationStartedEvent event = CompensationStartedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.compensation.started");
    }

    // --- CompensationStepCompletedEvent ---

    @Test
    void compensationStepCompletedEvent_shouldBuildWithAllFields() {
        CompensationStepCompletedEvent event = CompensationStepCompletedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .stepId("refund-payment")
                .success(true)
                .errorMessage(null)
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getStepId()).isEqualTo("refund-payment");
        assertThat(event.isSuccess()).isTrue();
        assertThat(event.getErrorMessage()).isNull();
    }

    @Test
    void compensationStepCompletedEvent_shouldReturnCorrectEventType() {
        CompensationStepCompletedEvent event = CompensationStepCompletedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.compensation.step.completed");
    }

    @Test
    void compensationStepCompletedEvent_shouldSupportFailedCompensation() {
        CompensationStepCompletedEvent event = CompensationStepCompletedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .stepId("restore-inventory")
                .success(false)
                .errorMessage("Inventory service unavailable")
                .build();

        assertThat(event.isSuccess()).isFalse();
        assertThat(event.getErrorMessage()).isEqualTo("Inventory service unavailable");
    }

    // --- SearchAttributeUpdatedEvent ---

    @Test
    void searchAttributeUpdatedEvent_shouldBuildWithAllFields() {
        SearchAttributeUpdatedEvent event = SearchAttributeUpdatedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .key("customerId")
                .value("CUST-456")
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getKey()).isEqualTo("customerId");
        assertThat(event.getValue()).isEqualTo("CUST-456");
    }

    @Test
    void searchAttributeUpdatedEvent_shouldReturnCorrectEventType() {
        SearchAttributeUpdatedEvent event = SearchAttributeUpdatedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.search-attribute.updated");
    }

    // --- Common behavior ---

    @Test
    void allAdvancedEvents_shouldHaveDefaultEventTimestamp() {
        SignalReceivedEvent event = SignalReceivedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventTimestamp()).isNotNull();
    }

    @Test
    void allAdvancedEvents_shouldSupportMetadata() {
        TimerRegisteredEvent event = TimerRegisteredEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .addMetadata("source", "scheduler")
                .build();

        assertThat(event.getMetadata()).containsEntry("source", "scheduler");
    }
}
