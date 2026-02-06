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
 * Represents the status of a workflow instance.
 */
public enum WorkflowStatus {

    /**
     * Workflow instance has been created but not yet started.
     */
    PENDING,

    /**
     * Workflow instance is currently executing.
     */
    RUNNING,

    /**
     * Workflow instance is paused, waiting for external input or event.
     */
    WAITING,

    /**
     * Workflow instance has completed successfully.
     */
    COMPLETED,

    /**
     * Workflow instance has failed due to an error.
     */
    FAILED,

    /**
     * Workflow instance was cancelled by user or system.
     */
    CANCELLED,

    /**
     * Workflow instance has timed out.
     */
    TIMED_OUT,

    /**
     * Workflow instance has been suspended by an operator.
     * Can be resumed later to continue execution.
     */
    SUSPENDED;

    /**
     * Checks if the workflow is in a terminal state.
     *
     * @return true if the workflow has ended
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }

    /**
     * Checks if the workflow is currently active.
     *
     * @return true if the workflow is still running or waiting
     */
    public boolean isActive() {
        return this == PENDING || this == RUNNING || this == WAITING;
    }

    /**
     * Checks if the workflow can be suspended.
     *
     * @return true if the workflow can be suspended
     */
    public boolean canSuspend() {
        return this == RUNNING || this == WAITING || this == PENDING;
    }

    /**
     * Checks if the workflow can be resumed.
     *
     * @return true if the workflow can be resumed
     */
    public boolean canResume() {
        return this == SUSPENDED;
    }
}
