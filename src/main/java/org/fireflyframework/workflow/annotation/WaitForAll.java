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
 * Marks a workflow step method as waiting for <b>all</b> specified signals and timers
 * before resuming.
 * <p>
 * When a step is annotated with @WaitForAll, the workflow engine will suspend execution
 * and wait until every signal in {@link #signals()} has been received and every timer
 * in {@link #timers()} has fired. Only when all conditions are satisfied will the step
 * resume execution.
 * <p>
 * This is useful for join/barrier patterns where multiple external inputs or time
 * conditions must all be met before proceeding.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @WorkflowStep(id = "finalize-order")
 * @WaitForAll(
 *     signals = {
 *         @WaitForSignal(name = "payment-confirmed"),
 *         @WaitForSignal(name = "inventory-reserved")
 *     },
 *     timers = {
 *         @WaitForTimer(duration = "PT1H")
 *     }
 * )
 * public Mono<OrderResult> finalizeOrder(WorkflowContext ctx) {
 *     return orderService.finalize(ctx);
 * }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WaitForAll {

    /**
     * Signals that must all be received before the step resumes.
     * <p>
     * Every signal in this array must be delivered for the wait to complete.
     *
     * @return the array of signals to wait for
     */
    WaitForSignal[] signals() default {};

    /**
     * Timers that must all fire before the step resumes.
     * <p>
     * Every timer in this array must fire for the wait to complete.
     *
     * @return the array of timers to wait for
     */
    WaitForTimer[] timers() default {};
}
