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
 * Marks a method as a compensation handler for a specific workflow step.
 * <p>
 * Compensation steps are invoked during saga-style rollback when a downstream
 * step fails and previously completed steps need to be undone. Each compensation
 * step is linked to the forward step it compensates via {@link #compensates()}.
 * <p>
 * Compensation steps are executed in reverse order of the original step execution
 * during rollback. Only steps that have successfully completed will have their
 * compensation handlers invoked.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @WorkflowStep(id = "reserve-inventory", compensatable = true)
 * public Mono<Reservation> reserveInventory(WorkflowContext ctx) {
 *     return inventoryService.reserve(ctx.getInput("orderId", String.class));
 * }
 *
 * @CompensationStep(compensates = "reserve-inventory")
 * public Mono<Void> releaseInventory(WorkflowContext ctx) {
 *     return inventoryService.release(ctx.getStepOutput("reserve-inventory", Reservation.class));
 * }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CompensationStep {

    /**
     * The ID of the workflow step that this compensation handler compensates.
     * <p>
     * This must match the {@link WorkflowStep#id()} of the step to be
     * compensated during rollback.
     *
     * @return the ID of the step this compensates
     */
    String compensates();
}
