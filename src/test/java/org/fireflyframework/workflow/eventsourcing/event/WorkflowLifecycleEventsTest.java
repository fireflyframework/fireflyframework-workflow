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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for workflow lifecycle domain events (1-6).
 */
class WorkflowLifecycleEventsTest {

    private static final UUID AGGREGATE_ID = UUID.randomUUID();

    // --- WorkflowStartedEvent ---

    @Test
    void workflowStartedEvent_shouldBuildWithAllFields() {
        Map<String, Object> input = Map.of("key", "value");

        WorkflowStartedEvent event = WorkflowStartedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .workflowId("order-processing")
                .workflowName("Order Processing")
                .workflowVersion("1.0.0")
                .input(input)
                .correlationId("corr-123")
                .triggeredBy("api-gateway")
                .dryRun(false)
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getWorkflowId()).isEqualTo("order-processing");
        assertThat(event.getWorkflowName()).isEqualTo("Order Processing");
        assertThat(event.getWorkflowVersion()).isEqualTo("1.0.0");
        assertThat(event.getInput()).isEqualTo(input);
        assertThat(event.getCorrelationId()).isEqualTo("corr-123");
        assertThat(event.getTriggeredBy()).isEqualTo("api-gateway");
        assertThat(event.isDryRun()).isFalse();
    }

    @Test
    void workflowStartedEvent_shouldReturnCorrectEventType() {
        WorkflowStartedEvent event = WorkflowStartedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.started");
    }

    @Test
    void workflowStartedEvent_shouldSupportDryRun() {
        WorkflowStartedEvent event = WorkflowStartedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .dryRun(true)
                .build();

        assertThat(event.isDryRun()).isTrue();
    }

    // --- WorkflowCompletedEvent ---

    @Test
    void workflowCompletedEvent_shouldBuildWithAllFields() {
        WorkflowCompletedEvent event = WorkflowCompletedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .output(Map.of("result", "success"))
                .durationMs(5000L)
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getOutput()).isEqualTo(Map.of("result", "success"));
        assertThat(event.getDurationMs()).isEqualTo(5000L);
    }

    @Test
    void workflowCompletedEvent_shouldReturnCorrectEventType() {
        WorkflowCompletedEvent event = WorkflowCompletedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.completed");
    }

    // --- WorkflowFailedEvent ---

    @Test
    void workflowFailedEvent_shouldBuildWithAllFields() {
        WorkflowFailedEvent event = WorkflowFailedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .errorMessage("Null pointer exception")
                .errorType("NullPointerException")
                .failedStepId("step-3")
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getErrorMessage()).isEqualTo("Null pointer exception");
        assertThat(event.getErrorType()).isEqualTo("NullPointerException");
        assertThat(event.getFailedStepId()).isEqualTo("step-3");
    }

    @Test
    void workflowFailedEvent_shouldReturnCorrectEventType() {
        WorkflowFailedEvent event = WorkflowFailedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.failed");
    }

    // --- WorkflowCancelledEvent ---

    @Test
    void workflowCancelledEvent_shouldBuildWithAllFields() {
        WorkflowCancelledEvent event = WorkflowCancelledEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .reason("User requested cancellation")
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getReason()).isEqualTo("User requested cancellation");
    }

    @Test
    void workflowCancelledEvent_shouldReturnCorrectEventType() {
        WorkflowCancelledEvent event = WorkflowCancelledEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.cancelled");
    }

    // --- WorkflowSuspendedEvent ---

    @Test
    void workflowSuspendedEvent_shouldBuildWithAllFields() {
        WorkflowSuspendedEvent event = WorkflowSuspendedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .reason("Waiting for approval signal")
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getReason()).isEqualTo("Waiting for approval signal");
    }

    @Test
    void workflowSuspendedEvent_shouldReturnCorrectEventType() {
        WorkflowSuspendedEvent event = WorkflowSuspendedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.suspended");
    }

    // --- WorkflowResumedEvent ---

    @Test
    void workflowResumedEvent_shouldBuildWithAggregateId() {
        WorkflowResumedEvent event = WorkflowResumedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
    }

    @Test
    void workflowResumedEvent_shouldReturnCorrectEventType() {
        WorkflowResumedEvent event = WorkflowResumedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.resumed");
    }

    // --- Common AbstractDomainEvent behavior ---

    @Test
    void allEvents_shouldHaveDefaultEventTimestamp() {
        WorkflowStartedEvent event = WorkflowStartedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventTimestamp()).isNotNull();
    }

    @Test
    void allEvents_shouldHaveDefaultEventVersion() {
        WorkflowCompletedEvent event = WorkflowCompletedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventVersion()).isEqualTo(1);
    }

    @Test
    void allEvents_shouldSupportMetadata() {
        WorkflowFailedEvent event = WorkflowFailedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .addMetadata("traceId", "trace-456")
                .build();

        assertThat(event.getMetadata()).containsEntry("traceId", "trace-456");
    }
}
