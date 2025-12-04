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

package com.firefly.common.workflow.annotation;

import java.lang.annotation.*;

/**
 * Marks a method as a step completion callback.
 * <p>
 * Methods annotated with @OnStepComplete are called after a step
 * completes successfully. They can be used for logging, metrics,
 * or post-processing of step results.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @OnStepComplete(stepIds = {"validate-order", "process-payment"})
 * public void onCriticalStepComplete(WorkflowContext ctx, StepExecution execution) {
 *     log.info("Critical step {} completed", execution.stepId());
 *     metricsService.recordStepCompletion(execution);
 * }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnStepComplete {

    /**
     * Step IDs to listen for completion events.
     * <p>
     * If empty, the callback is triggered for all steps.
     *
     * @return the step IDs to monitor
     */
    String[] stepIds() default {};

    /**
     * Whether to execute this callback asynchronously.
     *
     * @return true for async execution
     */
    boolean async() default true;

    /**
     * Priority of this callback (higher = executed first).
     *
     * @return the priority
     */
    int priority() default 0;
}
