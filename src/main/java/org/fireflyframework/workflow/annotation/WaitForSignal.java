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

package org.fireflyframework.workflow.annotation;

import java.lang.annotation.*;

/**
 * Marks a workflow step method as waiting for an external signal before resuming.
 * <p>
 * When a step is annotated with @WaitForSignal, the workflow engine will suspend
 * execution of the step and wait for the named signal to be delivered. This enables
 * human-in-the-loop patterns, external system callbacks, and inter-workflow communication.
 * <p>
 * An optional timeout can be configured to handle cases where the signal never arrives.
 * The timeout behavior is controlled by {@link #timeoutAction()}.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @WorkflowStep(id = "wait-for-approval")
 * @WaitForSignal(name = "manager-approval", timeoutDuration = "P7D", timeoutAction = "ESCALATE")
 * public Mono<ApprovalResult> waitForApproval(WorkflowContext ctx) {
 *     return ctx.getSignalData(ApprovalResult.class);
 * }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WaitForSignal {

    /**
     * The name of the signal to wait for.
     * <p>
     * This must match the signal name used when delivering the signal
     * to the workflow instance.
     *
     * @return the signal name
     */
    String name();

    /**
     * Maximum duration to wait for the signal, in ISO-8601 duration format.
     * <p>
     * Examples: "PT30M" (30 minutes), "PT1H" (1 hour), "P7D" (7 days).
     * If empty, the step will wait indefinitely.
     *
     * @return the timeout duration in ISO-8601 format
     */
    String timeoutDuration() default "";

    /**
     * Action to take when the signal wait times out.
     * <p>
     * Supported values:
     * <ul>
     *   <li>{@code FAIL} - Fail the step with a timeout error (default)</li>
     *   <li>{@code ESCALATE} - Escalate the step for manual intervention</li>
     *   <li>{@code SKIP} - Skip the step and continue the workflow</li>
     * </ul>
     *
     * @return the timeout action
     */
    String timeoutAction() default "FAIL";
}
