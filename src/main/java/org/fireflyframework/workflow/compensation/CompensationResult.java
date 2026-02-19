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

package org.fireflyframework.workflow.compensation;

import java.util.List;

/**
 * Captures the outcome of a compensation (saga rollback) operation.
 * <p>
 * Contains the details of each compensated step, whether all compensations
 * succeeded, and any errors that occurred during the process.
 *
 * @param instanceId       the workflow instance ID
 * @param policy           the compensation policy that was applied
 * @param failedStepId     the step ID whose failure triggered compensation
 * @param compensatedSteps the list of compensation step outcomes in execution order
 * @param allSuccessful    true if all compensation steps succeeded
 * @param errors           the list of error messages from failed compensation steps
 */
public record CompensationResult(
        String instanceId,
        CompensationPolicy policy,
        String failedStepId,
        List<CompensatedStep> compensatedSteps,
        boolean allSuccessful,
        List<String> errors
) {

    /**
     * Represents the outcome of compensating a single step.
     *
     * @param stepId       the step ID that was compensated
     * @param success      whether the compensation succeeded
     * @param errorMessage the error message if compensation failed, null otherwise
     */
    public record CompensatedStep(String stepId, boolean success, String errorMessage) {
    }
}
