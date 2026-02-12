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

package org.fireflyframework.workflow.metrics;

import org.fireflyframework.observability.metrics.FireflyMetricsSupport;
import org.fireflyframework.workflow.model.StepStatus;
import org.fireflyframework.workflow.model.WorkflowStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides workflow execution metrics via {@link FireflyMetricsSupport}.
 * All metrics are prefixed with {@code firefly.workflow.*}.
 */
@Slf4j
public class WorkflowMetrics extends FireflyMetricsSupport {

    private final ConcurrentHashMap<String, AtomicInteger> activeWorkflows = new ConcurrentHashMap<>();

    public WorkflowMetrics(MeterRegistry meterRegistry) {
        super(meterRegistry, "workflow");
        log.info("WorkflowMetrics initialized");
    }

    // ==================== Workflow Metrics ====================

    public void recordWorkflowStarted(String workflowId, String triggeredBy) {
        counter("started",
                "workflow.id", workflowId,
                "triggered.by", normalizeTag(triggeredBy))
                .increment();

        getActiveWorkflowsGauge(workflowId).incrementAndGet();
        log.debug("METRIC: workflow.started workflowId={}, triggeredBy={}", workflowId, triggeredBy);
    }

    public void recordWorkflowCompleted(String workflowId, WorkflowStatus status, Duration duration) {
        String statusTag = status.name().toLowerCase();

        counter("completed",
                "workflow.id", workflowId,
                "status", statusTag)
                .increment();

        timer("duration",
                "workflow.id", workflowId,
                "status", statusTag)
                .record(duration);

        AtomicInteger active = activeWorkflows.get(workflowId);
        if (active != null && active.get() > 0) {
            active.decrementAndGet();
        }

        log.debug("METRIC: workflow.completed workflowId={}, status={}, durationMs={}",
                workflowId, status, duration.toMillis());
    }

    public void recordWorkflowFailed(String workflowId, Duration duration) {
        recordWorkflowCompleted(workflowId, WorkflowStatus.FAILED, duration);

        counter("failed", "workflow.id", workflowId).increment();
    }

    // ==================== Step Metrics ====================

    public void recordStepStarted(String workflowId, String stepId) {
        counter("step.started",
                "workflow.id", workflowId,
                "step.id", stepId)
                .increment();

        log.debug("METRIC: step.started workflowId={}, stepId={}", workflowId, stepId);
    }

    public void recordStepCompleted(String workflowId, String stepId, StepStatus status, Duration duration) {
        String statusTag = status.name().toLowerCase();

        counter("step.completed",
                "workflow.id", workflowId,
                "step.id", stepId,
                "status", statusTag)
                .increment();

        timer("step.duration",
                "workflow.id", workflowId,
                "step.id", stepId,
                "status", statusTag)
                .record(duration);

        log.debug("METRIC: step.completed workflowId={}, stepId={}, status={}, durationMs={}",
                workflowId, stepId, status, duration.toMillis());
    }

    public void recordStepRetry(String workflowId, String stepId, int attemptNumber) {
        counter("step.retries",
                "workflow.id", workflowId,
                "step.id", stepId)
                .increment();

        log.debug("METRIC: step.retry workflowId={}, stepId={}, attempt={}",
                workflowId, stepId, attemptNumber);
    }

    public void recordStepSkipped(String workflowId, String stepId, String reason) {
        counter("step.skipped",
                "workflow.id", workflowId,
                "step.id", stepId)
                .increment();

        log.debug("METRIC: step.skipped workflowId={}, stepId={}, reason={}",
                workflowId, stepId, reason);
    }

    // ==================== Helper Methods ====================

    private AtomicInteger getActiveWorkflowsGauge(String workflowId) {
        return activeWorkflows.computeIfAbsent(workflowId, id -> {
            AtomicInteger ref = new AtomicInteger(0);
            gauge("active", ref, AtomicInteger::get, "workflow.id", id);
            return ref;
        });
    }

    private String normalizeTag(String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase();
    }

    /**
     * Creates a timer sample for measuring duration.
     */
    public Timer.Sample startTimer() {
        MeterRegistry reg = registry();
        return reg != null ? Timer.start(reg) : null;
    }

    /**
     * Stops a timer sample and records the duration.
     */
    public void stopTimer(Timer.Sample sample, String workflowId, String stepId, String status) {
        if (sample == null) return;

        String metricName = stepId != null ? "step.duration" : "duration";
        Timer t;
        if (stepId != null) {
            t = timer(metricName, "workflow.id", workflowId, "step.id", stepId, "status", status.toLowerCase());
        } else {
            t = timer(metricName, "workflow.id", workflowId, "status", status.toLowerCase());
        }
        sample.stop(t);
    }
}
