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
 * Marks a workflow step method as spawning a child workflow.
 * <p>
 * When a step is annotated with @ChildWorkflow, the workflow engine will start
 * a child workflow instance with the specified workflow ID. The parent step can
 * optionally block until the child workflow completes, enabling hierarchical
 * workflow composition.
 * <p>
 * If {@link #waitForCompletion()} is {@code true} (default), the parent step
 * will suspend until the child workflow reaches a terminal state. If {@code false},
 * the parent step continues immediately after spawning the child.
 * <p>
 * An optional timeout can be configured to limit how long the parent waits
 * for the child workflow to complete.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @WorkflowStep(id = "run-sub-process")
 * @ChildWorkflow(workflowId = "payment-processing", waitForCompletion = true, timeoutMs = 300000)
 * public Mono<PaymentResult> runPaymentSubProcess(WorkflowContext ctx) {
 *     return ctx.getChildWorkflowResult(PaymentResult.class);
 * }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ChildWorkflow {

    /**
     * The ID of the child workflow to spawn.
     * <p>
     * This must match the {@link Workflow#id()} of a registered workflow definition.
     *
     * @return the child workflow ID
     */
    String workflowId();

    /**
     * Whether to block the parent step until the child workflow completes.
     * <p>
     * If {@code true}, the parent step suspends and resumes when the child
     * workflow reaches a terminal state (completed, failed, or cancelled).
     * If {@code false}, the parent step continues immediately after spawning.
     *
     * @return true to wait for child completion
     */
    boolean waitForCompletion() default true;

    /**
     * Maximum time in milliseconds to wait for the child workflow to complete.
     * <p>
     * Only applicable when {@link #waitForCompletion()} is {@code true}.
     * If 0, no timeout is applied and the parent waits indefinitely.
     *
     * @return timeout in milliseconds (0 = no timeout)
     */
    long timeoutMs() default 0;
}
