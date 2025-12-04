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
 * Marks a method as a workflow error handler.
 * <p>
 * Methods annotated with @OnWorkflowError are called when a workflow
 * encounters an error. They can be used for error logging, compensation,
 * alerting, or fallback logic.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @OnWorkflowError(errorTypes = {ValidationException.class, PaymentException.class})
 * public void handleWorkflowError(WorkflowContext ctx, WorkflowInstance instance, Throwable error) {
 *     log.error("Workflow {} failed: {}", instance.workflowId(), error.getMessage());
 *     alertService.sendAlert(instance, error);
 * }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OnWorkflowError {

    /**
     * Error types to handle.
     * <p>
     * If empty, handles all error types.
     *
     * @return the error types to handle
     */
    Class<? extends Throwable>[] errorTypes() default {};

    /**
     * Step IDs to monitor for errors.
     * <p>
     * If empty, monitors all steps.
     *
     * @return the step IDs to monitor
     */
    String[] stepIds() default {};

    /**
     * Whether to execute this handler asynchronously.
     *
     * @return true for async execution
     */
    boolean async() default false;

    /**
     * Priority of this handler (higher = executed first).
     *
     * @return the priority
     */
    int priority() default 0;

    /**
     * Whether this handler should stop error propagation.
     * <p>
     * If true and the handler doesn't rethrow, the workflow will be
     * marked as completed instead of failed.
     *
     * @return true to suppress the error
     */
    boolean suppressError() default false;
}
