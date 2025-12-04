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
 * Marks a method as a workflow step.
 * <p>
 * Methods annotated with @WorkflowStep define individual steps within a workflow.
 * They are executed in order based on the order attribute and receive the
 * WorkflowContext as their first parameter.
 * <p>
 * Step methods should return:
 * <ul>
 *   <li>Mono&lt;T&gt; for reactive step execution</li>
 *   <li>T for blocking step execution (automatically wrapped)</li>
 *   <li>void/Mono&lt;Void&gt; for steps without output</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @WorkflowStep(
 *     id = "validate-order",
 *     name = "Validate Order",
 *     order = 1,
 *     outputEventType = "order.validated"
 * )
 * public Mono<ValidationResult> validateOrder(WorkflowContext ctx) {
 *     Order order = ctx.getInput("order", Order.class);
 *     return validationService.validate(order);
 * }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WorkflowStep {

    /**
     * Unique identifier for this step within the workflow.
     * <p>
     * If not specified, defaults to the method name.
     *
     * @return the step ID
     */
    String id() default "";

    /**
     * Human-readable name of the step.
     * <p>
     * If not specified, defaults to the method name.
     *
     * @return the step name
     */
    String name() default "";

    /**
     * Description of what this step does.
     *
     * @return the description
     */
    String description() default "";

    /**
     * Execution order of this step within the workflow.
     * <p>
     * Steps with lower order values execute first.
     *
     * @return the execution order
     */
    int order() default 0;

    /**
     * Event type that can trigger this specific step (for event-driven steps).
     * <p>
     * If specified, this step can be triggered independently via events.
     *
     * @return the input event type
     */
    String inputEventType() default "";

    /**
     * Event type to publish when this step completes successfully.
     *
     * @return the output event type
     */
    String outputEventType() default "";

    /**
     * Maximum time in milliseconds for this step to complete.
     * <p>
     * If 0, uses the workflow's default timeout.
     *
     * @return timeout in milliseconds
     */
    long timeoutMs() default 0;

    /**
     * Maximum number of retry attempts for this step.
     * <p>
     * If -1, uses the workflow's default retry policy.
     *
     * @return max retry attempts
     */
    int maxRetries() default -1;

    /**
     * Initial delay in milliseconds before retrying.
     * <p>
     * If -1, uses the workflow's default retry delay.
     *
     * @return retry delay in milliseconds
     */
    long retryDelayMs() default -1;

    /**
     * SpEL expression for conditional execution.
     * <p>
     * If specified, the step is only executed if the expression evaluates to true.
     * Has access to the WorkflowContext via #ctx variable.
     *
     * @return the condition expression
     */
    String condition() default "";

    /**
     * Whether this step should execute asynchronously.
     * <p>
     * Async steps don't block the workflow and allow parallel execution.
     *
     * @return true for async execution
     */
    boolean async() default false;

    /**
     * Whether to compensate (rollback) this step if a later step fails.
     * <p>
     * If true, the step's compensation method will be called on workflow failure.
     *
     * @return true to enable compensation
     */
    boolean compensatable() default false;

    /**
     * Name of the compensation method to call on rollback.
     * <p>
     * If not specified and compensatable is true, looks for a method named
     * "compensate{StepName}" in the workflow class.
     *
     * @return the compensation method name
     */
    String compensationMethod() default "";
}
