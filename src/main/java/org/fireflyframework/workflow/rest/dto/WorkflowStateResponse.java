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

package org.fireflyframework.workflow.rest.dto;

import org.fireflyframework.workflow.model.StepHistory;
import org.fireflyframework.workflow.model.WorkflowState;
import org.fireflyframework.workflow.model.WorkflowStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response DTO for comprehensive workflow state (dashboard view).
 * <p>
 * Provides a complete view of workflow progress including:
 * - Which steps have completed, failed, or been skipped
 * - Current and next steps
 * - Full execution history
 */
public record WorkflowStateResponse(
        String workflowId,
        String workflowName,
        String workflowVersion,
        String instanceId,
        String correlationId,
        WorkflowStatus status,
        String currentStepId,
        String nextStepId,
        String waitingForEvent,
        List<String> completedSteps,
        List<String> failedSteps,
        List<String> skippedSteps,
        List<String> pendingSteps,
        List<StepHistoryResponse> stepHistory,
        Map<String, Object> input,
        Object output,
        String errorMessage,
        String triggeredBy,
        int totalSteps,
        int progress,
        long durationMs,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {

    /**
     * Creates a response from a WorkflowState.
     */
    public static WorkflowStateResponse from(WorkflowState state) {
        List<StepHistoryResponse> history = state.stepHistory().stream()
                .map(StepHistoryResponse::from)
                .toList();
        
        return new WorkflowStateResponse(
                state.workflowId(),
                state.workflowName(),
                state.workflowVersion(),
                state.instanceId(),
                state.correlationId(),
                state.status(),
                state.currentStepId(),
                state.nextStepId(),
                state.waitingForEvent(),
                state.completedSteps(),
                state.failedSteps(),
                state.skippedSteps(),
                state.pendingSteps(),
                history,
                state.input(),
                state.output(),
                state.errorMessage(),
                state.triggeredBy(),
                state.totalSteps(),
                state.getProgress(),
                state.getDuration().toMillis(),
                state.createdAt(),
                state.startedAt(),
                state.completedAt()
        );
    }

    /**
     * Step history response DTO.
     */
    public record StepHistoryResponse(
            String stepId,
            String stepName,
            String status,
            String triggeredBy,
            Instant startedAt,
            Instant completedAt,
            long durationMs,
            String errorMessage,
            int attemptNumber
    ) {
        public static StepHistoryResponse from(StepHistory history) {
            return new StepHistoryResponse(
                    history.stepId(),
                    history.stepName(),
                    history.status().name(),
                    history.triggeredBy(),
                    history.startedAt(),
                    history.completedAt(),
                    history.durationMs(),
                    history.errorMessage(),
                    history.attemptNumber()
            );
        }
    }
}
