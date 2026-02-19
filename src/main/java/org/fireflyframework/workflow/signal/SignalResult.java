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

package org.fireflyframework.workflow.signal;

/**
 * Result of a signal delivery to a workflow instance.
 * <p>
 * Captures whether the signal was successfully delivered to the aggregate,
 * and whether any step that was waiting for this signal was resumed.
 *
 * @param instanceId   the workflow instance that received the signal
 * @param signalName   the name of the delivered signal
 * @param delivered    true if the signal was successfully stored in the aggregate
 * @param stepResumed  true if a waiting step was unblocked by this signal
 * @param resumedStepId the step ID that was resumed, or null if none
 */
public record SignalResult(
        String instanceId,
        String signalName,
        boolean delivered,
        boolean stepResumed,
        String resumedStepId
) {}
