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

package com.firefly.common.workflow.rest.dto;

import com.firefly.common.workflow.model.StepExecution;
import com.firefly.common.workflow.model.WorkflowInstance;
import com.firefly.common.workflow.model.WorkflowStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response DTO for workflow status queries.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowStatusResponse {

    private String instanceId;
    private String workflowId;
    private String workflowName;
    private String workflowVersion;
    private WorkflowStatus status;
    private String currentStepId;
    private int progress;
    private String correlationId;
    private String triggeredBy;
    private List<StepStatusDto> steps;
    private String errorMessage;
    private String errorType;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;

    /**
     * Creates a response from a workflow instance.
     */
    public static WorkflowStatusResponse from(WorkflowInstance instance, int totalSteps) {
        return WorkflowStatusResponse.builder()
                .instanceId(instance.instanceId())
                .workflowId(instance.workflowId())
                .workflowName(instance.workflowName())
                .workflowVersion(instance.workflowVersion())
                .status(instance.status())
                .currentStepId(instance.currentStepId())
                .progress(instance.getProgress(totalSteps))
                .correlationId(instance.correlationId())
                .triggeredBy(instance.triggeredBy())
                .steps(instance.stepExecutions().stream()
                        .map(StepStatusDto::from)
                        .toList())
                .errorMessage(instance.errorMessage())
                .errorType(instance.errorType())
                .createdAt(instance.createdAt())
                .startedAt(instance.startedAt())
                .completedAt(instance.completedAt())
                .durationMs(instance.getDuration() != null ? instance.getDuration().toMillis() : null)
                .build();
    }

    /**
     * Step status DTO.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepStatusDto {
        private String stepId;
        private String stepName;
        private String status;
        private int attemptNumber;
        private String errorMessage;
        private Instant startedAt;
        private Instant completedAt;
        private Long durationMs;

        public static StepStatusDto from(StepExecution execution) {
            return StepStatusDto.builder()
                    .stepId(execution.stepId())
                    .stepName(execution.stepName())
                    .status(execution.status().name())
                    .attemptNumber(execution.attemptNumber())
                    .errorMessage(execution.errorMessage())
                    .startedAt(execution.startedAt())
                    .completedAt(execution.completedAt())
                    .durationMs(execution.getDuration() != null ? execution.getDuration().toMillis() : null)
                    .build();
        }
    }
}
