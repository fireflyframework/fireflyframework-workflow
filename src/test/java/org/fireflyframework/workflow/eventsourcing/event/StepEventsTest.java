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
 * Unit tests for step domain events (7-11).
 */
class StepEventsTest {

    private static final UUID AGGREGATE_ID = UUID.randomUUID();

    // --- StepStartedEvent ---

    @Test
    void stepStartedEvent_shouldBuildWithAllFields() {
        Map<String, Object> input = Map.of("orderId", "ORD-001");

        StepStartedEvent event = StepStartedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .stepId("validate-order")
                .stepName("Validate Order")
                .input(input)
                .attemptNumber(1)
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getStepId()).isEqualTo("validate-order");
        assertThat(event.getStepName()).isEqualTo("Validate Order");
        assertThat(event.getInput()).isEqualTo(input);
        assertThat(event.getAttemptNumber()).isEqualTo(1);
    }

    @Test
    void stepStartedEvent_shouldReturnCorrectEventType() {
        StepStartedEvent event = StepStartedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.step.started");
    }

    // --- StepCompletedEvent ---

    @Test
    void stepCompletedEvent_shouldBuildWithAllFields() {
        StepCompletedEvent event = StepCompletedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .stepId("process-payment")
                .output(Map.of("transactionId", "TXN-789"))
                .durationMs(1200L)
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getStepId()).isEqualTo("process-payment");
        assertThat(event.getOutput()).isEqualTo(Map.of("transactionId", "TXN-789"));
        assertThat(event.getDurationMs()).isEqualTo(1200L);
    }

    @Test
    void stepCompletedEvent_shouldReturnCorrectEventType() {
        StepCompletedEvent event = StepCompletedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.step.completed");
    }

    // --- StepFailedEvent ---

    @Test
    void stepFailedEvent_shouldBuildWithAllFields() {
        StepFailedEvent event = StepFailedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .stepId("send-notification")
                .errorMessage("Connection refused")
                .errorType("ConnectException")
                .attemptNumber(3)
                .retryable(true)
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getStepId()).isEqualTo("send-notification");
        assertThat(event.getErrorMessage()).isEqualTo("Connection refused");
        assertThat(event.getErrorType()).isEqualTo("ConnectException");
        assertThat(event.getAttemptNumber()).isEqualTo(3);
        assertThat(event.isRetryable()).isTrue();
    }

    @Test
    void stepFailedEvent_shouldReturnCorrectEventType() {
        StepFailedEvent event = StepFailedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.step.failed");
    }

    @Test
    void stepFailedEvent_shouldSupportNonRetryableFailure() {
        StepFailedEvent event = StepFailedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .stepId("validate-input")
                .retryable(false)
                .build();

        assertThat(event.isRetryable()).isFalse();
    }

    // --- StepSkippedEvent ---

    @Test
    void stepSkippedEvent_shouldBuildWithAllFields() {
        StepSkippedEvent event = StepSkippedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .stepId("send-sms")
                .reason("SMS notifications disabled")
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getStepId()).isEqualTo("send-sms");
        assertThat(event.getReason()).isEqualTo("SMS notifications disabled");
    }

    @Test
    void stepSkippedEvent_shouldReturnCorrectEventType() {
        StepSkippedEvent event = StepSkippedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.step.skipped");
    }

    // --- StepRetriedEvent ---

    @Test
    void stepRetriedEvent_shouldBuildWithAllFields() {
        StepRetriedEvent event = StepRetriedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .stepId("call-external-api")
                .attemptNumber(2)
                .delayMs(5000L)
                .build();

        assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
        assertThat(event.getStepId()).isEqualTo("call-external-api");
        assertThat(event.getAttemptNumber()).isEqualTo(2);
        assertThat(event.getDelayMs()).isEqualTo(5000L);
    }

    @Test
    void stepRetriedEvent_shouldReturnCorrectEventType() {
        StepRetriedEvent event = StepRetriedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventType()).isEqualTo("workflow.step.retried");
    }

    // --- Common behavior ---

    @Test
    void allStepEvents_shouldHaveDefaultEventTimestamp() {
        StepStartedEvent event = StepStartedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .build();

        assertThat(event.getEventTimestamp()).isNotNull();
    }

    @Test
    void allStepEvents_shouldSupportMetadata() {
        StepCompletedEvent event = StepCompletedEvent.builder()
                .aggregateId(AGGREGATE_ID)
                .addMetadata("spanId", "span-789")
                .build();

        assertThat(event.getMetadata()).containsEntry("spanId", "span-789");
    }
}
