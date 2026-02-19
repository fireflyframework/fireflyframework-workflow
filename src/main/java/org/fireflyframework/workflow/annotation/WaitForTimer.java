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
 * Marks a workflow step method as waiting for a timer to fire before resuming.
 * <p>
 * When a step is annotated with @WaitForTimer, the workflow engine will suspend
 * execution and schedule a timer. The step resumes either after a relative duration
 * elapses or when an absolute instant is reached.
 * <p>
 * Exactly one of {@link #duration()} or {@link #fireAt()} should be specified.
 * If both are specified, {@link #fireAt()} takes precedence.
 * <p>
 * Example usage with relative duration:
 * <pre>
 * {@code
 * @WorkflowStep(id = "cooling-off-period")
 * @WaitForTimer(duration = "P3D")
 * public Mono<Void> waitForCoolingOff(WorkflowContext ctx) {
 *     return Mono.empty();
 * }
 * }
 * </pre>
 * <p>
 * Example usage with absolute instant:
 * <pre>
 * {@code
 * @WorkflowStep(id = "wait-until-market-open")
 * @WaitForTimer(fireAt = "2025-01-06T09:30:00Z")
 * public Mono<Void> waitForMarketOpen(WorkflowContext ctx) {
 *     return Mono.empty();
 * }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WaitForTimer {

    /**
     * Duration to wait before the timer fires, in ISO-8601 duration format.
     * <p>
     * Examples: "PT30M" (30 minutes), "PT1H" (1 hour), "P3D" (3 days).
     * If empty, {@link #fireAt()} must be specified.
     *
     * @return the duration in ISO-8601 format
     */
    String duration() default "";

    /**
     * Absolute instant at which the timer should fire, in ISO-8601 instant format.
     * <p>
     * Example: "2025-01-06T09:30:00Z".
     * If empty, {@link #duration()} must be specified.
     *
     * @return the absolute fire-at instant in ISO-8601 format
     */
    String fireAt() default "";
}
