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
 * Marks a workflow step method as waiting for <b>any</b> of the specified signals or timers
 * before resuming.
 * <p>
 * When a step is annotated with @WaitForAny, the workflow engine will suspend execution
 * and wait until at least one signal in {@link #signals()} has been received or at least
 * one timer in {@link #timers()} has fired. The step resumes as soon as any single
 * condition is satisfied.
 * <p>
 * This is useful for race/select patterns where the first arriving input determines
 * the continuation path.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @WorkflowStep(id = "wait-for-response")
 * @WaitForAny(
 *     signals = {
 *         @WaitForSignal(name = "customer-approved"),
 *         @WaitForSignal(name = "customer-rejected")
 *     },
 *     timers = {
 *         @WaitForTimer(duration = "P7D")
 *     }
 * )
 * public Mono<ResponseResult> handleResponse(WorkflowContext ctx) {
 *     // Triggered by whichever signal or timer fires first
 *     return ctx.getResumeResult(ResponseResult.class);
 * }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WaitForAny {

    /**
     * Signals where any single arrival triggers the step to resume.
     * <p>
     * The step resumes as soon as any one of these signals is delivered.
     *
     * @return the array of signals to wait for
     */
    WaitForSignal[] signals() default {};

    /**
     * Timers where any single firing triggers the step to resume.
     * <p>
     * The step resumes as soon as any one of these timers fires.
     *
     * @return the array of timers to wait for
     */
    WaitForTimer[] timers() default {};
}
