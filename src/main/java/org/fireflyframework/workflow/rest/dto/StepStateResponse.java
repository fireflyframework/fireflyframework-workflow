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

import org.fireflyframework.workflow.model.StepState;
import org.fireflyframework.workflow.model.StepStatus;

import java.time.Instant;
import java.util.Map;

/**
 * Response DTO for step state.
 */
public record StepStateResponse(
        String stepId,
        String stepName,
        String workflowId,
        String instanceId,
        StepStatus status,
        String waitingForEvent,
        String triggeredBy,
        Map<String, Object> input,
        Object output,
        String errorMessage,
        int attemptNumber,
        int maxRetries,
        long durationMs,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt
) {

    /**
     * Creates a response from a StepState.
     */
    public static StepStateResponse from(StepState state) {
        return new StepStateResponse(
                state.stepId(),
                state.stepName(),
                state.workflowId(),
                state.instanceId(),
                state.status(),
                state.waitingForEvent(),
                state.triggeredBy(),
                state.input(),
                state.output(),
                state.errorMessage(),
                state.attemptNumber(),
                state.maxRetries(),
                state.getDuration().toMillis(),
                state.createdAt(),
                state.startedAt(),
                state.completedAt()
        );
    }
}
