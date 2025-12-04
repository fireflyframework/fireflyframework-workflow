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

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Request DTO for starting a workflow instance.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StartWorkflowRequest {

    /**
     * Input data for the workflow.
     */
    private Map<String, Object> input = new HashMap<>();

    /**
     * Optional correlation ID for tracing across systems.
     */
    private String correlationId;

    /**
     * Whether to wait for workflow completion (sync mode).
     */
    private boolean waitForCompletion = false;

    /**
     * Maximum wait time in milliseconds (for sync mode).
     */
    private long waitTimeoutMs = 30000;

    /**
     * Whether to run in dry-run mode.
     * <p>
     * In dry-run mode, steps can skip actual side effects like external API calls,
     * database writes, and message publishing. Useful for testing workflow configuration
     * and flow without executing actual business logic.
     */
    private boolean dryRun = false;
}
