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

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Request DTO for sending an external signal to a workflow instance.
 * <p>
 * Signals allow external systems to communicate data to running workflows,
 * enabling patterns like human-task completion, external event notification,
 * and inter-workflow coordination.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SendSignalRequest {

    /**
     * The name of the signal to send.
     * <p>
     * This must match the signal name that the workflow step is waiting for.
     */
    @NotBlank(message = "Signal name is required")
    private String signalName;

    /**
     * The payload data to deliver with the signal.
     */
    private Map<String, Object> payload = new HashMap<>();
}
