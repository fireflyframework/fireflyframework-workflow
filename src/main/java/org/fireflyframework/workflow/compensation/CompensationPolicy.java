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

/**
 * Defines the compensation policy for saga-style rollback when a workflow step fails.
 * <p>
 * The compensation policy determines how the orchestrator handles compensation
 * of previously completed steps when a failure occurs:
 * <ul>
 *   <li><b>STRICT_SEQUENTIAL</b>: Compensates steps in reverse order of completion.
 *       Stops immediately if any compensation step fails.</li>
 *   <li><b>BEST_EFFORT</b>: Compensates all steps in reverse order regardless of
 *       individual failures. Collects all errors for reporting.</li>
 *   <li><b>SKIP</b>: No compensation is performed. The workflow fails without
 *       attempting to undo any completed steps.</li>
 * </ul>
 */
public enum CompensationPolicy {

    /**
     * Compensate steps in reverse order of completion.
     * Stop on first compensation failure.
     */
    STRICT_SEQUENTIAL,

    /**
     * Compensate all completed steps in reverse order.
     * Continue even if individual compensation steps fail, collecting all errors.
     */
    BEST_EFFORT,

    /**
     * Skip compensation entirely. No rollback is attempted.
     */
    SKIP
}
