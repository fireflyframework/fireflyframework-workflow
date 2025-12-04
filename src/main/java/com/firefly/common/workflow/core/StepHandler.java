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

package com.firefly.common.workflow.core;

import reactor.core.publisher.Mono;

/**
 * Interface for workflow step handlers.
 * <p>
 * Implementations of this interface contain the business logic for individual
 * workflow steps. Each step handler is responsible for performing a specific
 * task within the workflow.
 * <p>
 * Step handlers receive a WorkflowContext containing the workflow instance state
 * and can return any type of result which will be stored as the step output.
 * <p>
 * Example implementation:
 * <pre>
 * {@code
 * @Component("validateOrderStep")
 * public class ValidateOrderStepHandler implements StepHandler<ValidationResult> {
 *
 *     @Override
 *     public Mono<ValidationResult> execute(WorkflowContext context) {
 *         Order order = context.getInput("order", Order.class);
 *         return validationService.validate(order);
 *     }
 * }
 * }
 * </pre>
 *
 * @param <T> the type of result this handler produces
 */
public interface StepHandler<T> {

    /**
     * Executes the step logic.
     *
     * @param context the workflow context containing input data and instance state
     * @return a Mono containing the step result
     */
    Mono<T> execute(WorkflowContext context);

    /**
     * Called when the step needs to be compensated (rolled back).
     * <p>
     * This method is invoked when a later step in the workflow fails
     * and the step was marked as compensatable.
     *
     * @param context the workflow context
     * @return a Mono that completes when compensation is done
     */
    default Mono<Void> compensate(WorkflowContext context) {
        return Mono.empty();
    }

    /**
     * Checks if the step should be skipped based on context.
     * <p>
     * Override this method to implement conditional step execution.
     *
     * @param context the workflow context
     * @return true if the step should be skipped
     */
    default boolean shouldSkip(WorkflowContext context) {
        return false;
    }
}
