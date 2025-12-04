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

package com.firefly.common.workflow.exception;

/**
 * Exception thrown when step execution fails.
 */
public class StepExecutionException extends WorkflowException {

    private final String stepId;

    public StepExecutionException(String message) {
        super(message);
        this.stepId = null;
    }

    public StepExecutionException(String message, Throwable cause) {
        super(message, cause);
        this.stepId = null;
    }

    public StepExecutionException(String stepId, String message) {
        super("Step '" + stepId + "' execution failed: " + message);
        this.stepId = stepId;
    }

    public StepExecutionException(String stepId, String message, Throwable cause) {
        super("Step '" + stepId + "' execution failed: " + message, cause);
        this.stepId = stepId;
    }

    public String getStepId() {
        return stepId;
    }
}
