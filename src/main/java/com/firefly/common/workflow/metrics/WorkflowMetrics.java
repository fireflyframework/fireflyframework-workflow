/*
 * Copyright 2025 Firefly Software Solutions Inc
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

package com.firefly.common.workflow.metrics;

import com.firefly.common.workflow.model.StepStatus;
import com.firefly.common.workflow.model.WorkflowStatus;
import io.micrometer.core.instrument.*;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Provides Micrometer metrics for workflow execution monitoring.
 * <p>
 * This class tracks the following metrics:
 * <ul>
 *   <li><b>workflow.started</b> - Counter of workflows started (tags: workflowId, triggeredBy)</li>
 *   <li><b>workflow.completed</b> - Counter of workflows completed (tags: workflowId, status)</li>
 *   <li><b>workflow.duration</b> - Timer for workflow execution duration (tags: workflowId, status)</li>
 *   <li><b>workflow.active</b> - Gauge of currently active workflows (tags: workflowId)</li>
 *   <li><b>step.started</b> - Counter of steps started (tags: workflowId, stepId)</li>
 *   <li><b>step.completed</b> - Counter of steps completed (tags: workflowId, stepId, status)</li>
 *   <li><b>step.duration</b> - Timer for step execution duration (tags: workflowId, stepId, status)</li>
 *   <li><b>step.retries</b> - Counter of step retries (tags: workflowId, stepId)</li>
 * </ul>
 * <p>
 * All metrics are prefixed with "firefly.workflow." by default.
 */
@Slf4j
public class WorkflowMetrics {

    private static final String METRIC_PREFIX = "firefly.workflow.";
    
    // Tag names
    private static final String TAG_WORKFLOW_ID = "workflowId";
    private static final String TAG_STEP_ID = "stepId";
    private static final String TAG_STATUS = "status";
    private static final String TAG_TRIGGERED_BY = "triggeredBy";

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, AtomicInteger> activeWorkflows = new ConcurrentHashMap<>();

    public WorkflowMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        log.info("WorkflowMetrics initialized with MeterRegistry: {}", meterRegistry.getClass().getSimpleName());
    }

    // ==================== Workflow Metrics ====================

    /**
     * Records that a workflow has started.
     *
     * @param workflowId the workflow ID
     * @param triggeredBy what triggered the workflow (e.g., "api", "event:order.created")
     */
    public void recordWorkflowStarted(String workflowId, String triggeredBy) {
        Counter.builder(METRIC_PREFIX + "started")
                .description("Number of workflows started")
                .tag(TAG_WORKFLOW_ID, workflowId)
                .tag(TAG_TRIGGERED_BY, normalizeTag(triggeredBy))
                .register(meterRegistry)
                .increment();

        // Increment active workflows gauge
        getActiveWorkflowsGauge(workflowId).incrementAndGet();
        
        log.debug("METRIC: workflow.started workflowId={}, triggeredBy={}", workflowId, triggeredBy);
    }

    /**
     * Records that a workflow has completed.
     *
     * @param workflowId the workflow ID
     * @param status the final workflow status
     * @param duration the execution duration
     */
    public void recordWorkflowCompleted(String workflowId, WorkflowStatus status, Duration duration) {
        String statusTag = status.name().toLowerCase();
        
        Counter.builder(METRIC_PREFIX + "completed")
                .description("Number of workflows completed")
                .tag(TAG_WORKFLOW_ID, workflowId)
                .tag(TAG_STATUS, statusTag)
                .register(meterRegistry)
                .increment();

        Timer.builder(METRIC_PREFIX + "duration")
                .description("Workflow execution duration")
                .tag(TAG_WORKFLOW_ID, workflowId)
                .tag(TAG_STATUS, statusTag)
                .register(meterRegistry)
                .record(duration);

        // Decrement active workflows gauge
        AtomicInteger active = activeWorkflows.get(workflowId);
        if (active != null && active.get() > 0) {
            active.decrementAndGet();
        }
        
        log.debug("METRIC: workflow.completed workflowId={}, status={}, durationMs={}", 
                workflowId, status, duration.toMillis());
    }

    /**
     * Records a workflow failure.
     *
     * @param workflowId the workflow ID
     * @param duration the execution duration before failure
     */
    public void recordWorkflowFailed(String workflowId, Duration duration) {
        recordWorkflowCompleted(workflowId, WorkflowStatus.FAILED, duration);
        
        Counter.builder(METRIC_PREFIX + "failed")
                .description("Number of workflows failed")
                .tag(TAG_WORKFLOW_ID, workflowId)
                .register(meterRegistry)
                .increment();
    }

    // ==================== Step Metrics ====================

    /**
     * Records that a step has started.
     *
     * @param workflowId the workflow ID
     * @param stepId the step ID
     */
    public void recordStepStarted(String workflowId, String stepId) {
        Counter.builder(METRIC_PREFIX + "step.started")
                .description("Number of steps started")
                .tag(TAG_WORKFLOW_ID, workflowId)
                .tag(TAG_STEP_ID, stepId)
                .register(meterRegistry)
                .increment();
        
        log.debug("METRIC: step.started workflowId={}, stepId={}", workflowId, stepId);
    }

    /**
     * Records that a step has completed.
     *
     * @param workflowId the workflow ID
     * @param stepId the step ID
     * @param status the step status
     * @param duration the execution duration
     */
    public void recordStepCompleted(String workflowId, String stepId, StepStatus status, Duration duration) {
        String statusTag = status.name().toLowerCase();
        
        Counter.builder(METRIC_PREFIX + "step.completed")
                .description("Number of steps completed")
                .tag(TAG_WORKFLOW_ID, workflowId)
                .tag(TAG_STEP_ID, stepId)
                .tag(TAG_STATUS, statusTag)
                .register(meterRegistry)
                .increment();

        Timer.builder(METRIC_PREFIX + "step.duration")
                .description("Step execution duration")
                .tag(TAG_WORKFLOW_ID, workflowId)
                .tag(TAG_STEP_ID, stepId)
                .tag(TAG_STATUS, statusTag)
                .register(meterRegistry)
                .record(duration);
        
        log.debug("METRIC: step.completed workflowId={}, stepId={}, status={}, durationMs={}", 
                workflowId, stepId, status, duration.toMillis());
    }

    /**
     * Records a step retry attempt.
     *
     * @param workflowId the workflow ID
     * @param stepId the step ID
     * @param attemptNumber the current attempt number
     */
    public void recordStepRetry(String workflowId, String stepId, int attemptNumber) {
        Counter.builder(METRIC_PREFIX + "step.retries")
                .description("Number of step retry attempts")
                .tag(TAG_WORKFLOW_ID, workflowId)
                .tag(TAG_STEP_ID, stepId)
                .register(meterRegistry)
                .increment();
        
        log.debug("METRIC: step.retry workflowId={}, stepId={}, attempt={}", 
                workflowId, stepId, attemptNumber);
    }

    /**
     * Records a step that was skipped.
     *
     * @param workflowId the workflow ID
     * @param stepId the step ID
     * @param reason the reason for skipping
     */
    public void recordStepSkipped(String workflowId, String stepId, String reason) {
        Counter.builder(METRIC_PREFIX + "step.skipped")
                .description("Number of steps skipped")
                .tag(TAG_WORKFLOW_ID, workflowId)
                .tag(TAG_STEP_ID, stepId)
                .register(meterRegistry)
                .increment();
        
        log.debug("METRIC: step.skipped workflowId={}, stepId={}, reason={}", 
                workflowId, stepId, reason);
    }

    // ==================== Helper Methods ====================

    private AtomicInteger getActiveWorkflowsGauge(String workflowId) {
        return activeWorkflows.computeIfAbsent(workflowId, id -> {
            AtomicInteger gauge = new AtomicInteger(0);
            Gauge.builder(METRIC_PREFIX + "active", gauge, AtomicInteger::get)
                    .description("Number of currently active workflow instances")
                    .tag(TAG_WORKFLOW_ID, id)
                    .register(meterRegistry);
            return gauge;
        });
    }

    private String normalizeTag(String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }
        // Replace special characters that might cause issues in metric systems
        return value.replaceAll("[^a-zA-Z0-9._-]", "_").toLowerCase();
    }

    /**
     * Creates a timer sample for measuring duration.
     *
     * @return a new timer sample
     */
    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * Stops a timer sample and records the duration.
     *
     * @param sample the timer sample
     * @param workflowId the workflow ID
     * @param stepId the step ID (or null for workflow-level timing)
     * @param status the status
     */
    public void stopTimer(Timer.Sample sample, String workflowId, String stepId, String status) {
        Timer.Builder builder = Timer.builder(METRIC_PREFIX + (stepId != null ? "step.duration" : "duration"))
                .description(stepId != null ? "Step execution duration" : "Workflow execution duration")
                .tag(TAG_WORKFLOW_ID, workflowId)
                .tag(TAG_STATUS, status.toLowerCase());
        
        if (stepId != null) {
            builder.tag(TAG_STEP_ID, stepId);
        }
        
        sample.stop(builder.register(meterRegistry));
    }
}

