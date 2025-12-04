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

package com.firefly.common.workflow.model;

/**
 * Defines how a workflow step can be triggered.
 * <p>
 * This enum supports the unified invocation pattern for workflow steps,
 * allowing steps to be triggered via events (the primary pattern for
 * event-driven choreography) or programmatically (for direct API calls).
 * <p>
 * <b>Design Philosophy:</b>
 * Event-driven invocation is the PRIMARY and recommended pattern in
 * lib-workflow-engine. Programmatic invocation is supported as a
 * secondary option for specific use cases.
 *
 * @see com.firefly.common.workflow.annotation.WorkflowStep#triggerMode()
 */
public enum StepTriggerMode {

    /**
     * Step is triggered by events (PRIMARY pattern).
     * <p>
     * This is the recommended mode for event-driven choreography.
     * The step listens for its configured {@code inputEventType} and
     * executes when that event is received.
     * <p>
     * Example use cases:
     * <ul>
     *   <li>Reacting to domain events from other services</li>
     *   <li>Chaining steps via output/input event types</li>
     *   <li>Decoupled, asynchronous workflow execution</li>
     * </ul>
     */
    EVENT,

    /**
     * Step is invoked programmatically via API.
     * <p>
     * The step is executed through direct method calls or REST API
     * invocations. This mode is useful for:
     * <ul>
     *   <li>Synchronous request-response patterns</li>
     *   <li>Testing and debugging</li>
     *   <li>Integration with non-event-driven systems</li>
     * </ul>
     */
    PROGRAMMATIC,

    /**
     * Step supports both event-driven and programmatic invocation.
     * <p>
     * This is the default mode, providing maximum flexibility.
     * The step can be triggered by events OR invoked programmatically.
     * <p>
     * Use this when:
     * <ul>
     *   <li>The step needs to support multiple invocation patterns</li>
     *   <li>Migrating from programmatic to event-driven patterns</li>
     *   <li>Building flexible, reusable workflow components</li>
     * </ul>
     */
    BOTH;

    /**
     * Checks if this mode allows event-driven triggering.
     *
     * @return true if events can trigger this step
     */
    public boolean allowsEventTrigger() {
        return this == EVENT || this == BOTH;
    }

    /**
     * Checks if this mode allows programmatic invocation.
     *
     * @return true if the step can be invoked programmatically
     */
    public boolean allowsProgrammaticTrigger() {
        return this == PROGRAMMATIC || this == BOTH;
    }
}

