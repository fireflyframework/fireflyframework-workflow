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
 * Marks a method as a workflow query handler.
 * <p>
 * Query handlers provide a read-only view into the current state of a running
 * workflow instance. They are invoked synchronously and must not modify workflow
 * state. Queries are useful for building dashboards, status pages, and monitoring
 * tools that need to inspect workflow progress.
 * <p>
 * Query methods should be side-effect free and return the current state or
 * computed view of the workflow's internal data.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @WorkflowQuery("order-status")
 * public OrderStatus getOrderStatus(WorkflowContext ctx) {
 *     return new OrderStatus(
 *         ctx.getVariable("orderId", String.class),
 *         ctx.getVariable("currentStep", String.class),
 *         ctx.getVariable("progress", Double.class)
 *     );
 * }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WorkflowQuery {

    /**
     * The name of the query.
     * <p>
     * This name is used to invoke the query on a running workflow instance.
     * It must be unique within a workflow definition.
     *
     * @return the query name
     */
    String value();
}
