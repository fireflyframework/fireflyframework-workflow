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

package org.fireflyframework.workflow.model;

/**
 * Represents the status of a workflow step execution.
 */
public enum StepStatus {

    /**
     * Step has been scheduled but not yet started.
     */
    PENDING,

    /**
     * Step is currently executing.
     */
    RUNNING,

    /**
     * Step is waiting for external input or event.
     */
    WAITING,

    /**
     * Step has completed successfully.
     */
    COMPLETED,

    /**
     * Step has failed due to an error.
     */
    FAILED,

    /**
     * Step was skipped based on condition evaluation.
     */
    SKIPPED,

    /**
     * Step has timed out.
     */
    TIMED_OUT,

    /**
     * Step is being retried after a failure.
     */
    RETRYING;

    /**
     * Checks if the step is in a terminal state.
     *
     * @return true if the step has ended
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == SKIPPED || this == TIMED_OUT;
    }

    /**
     * Checks if the step is currently active.
     *
     * @return true if the step is still running or waiting
     */
    public boolean isActive() {
        return this == PENDING || this == RUNNING || this == WAITING || this == RETRYING;
    }

    /**
     * Checks if the step completed successfully.
     *
     * @return true if the step succeeded or was skipped
     */
    public boolean isSuccessful() {
        return this == COMPLETED || this == SKIPPED;
    }
}
