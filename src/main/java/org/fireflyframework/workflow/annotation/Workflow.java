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

import org.fireflyframework.workflow.model.TriggerMode;
import org.springframework.stereotype.Component;

import java.lang.annotation.*;

/**
 * Marks a class as a workflow definition.
 * <p>
 * Classes annotated with @Workflow are automatically registered with the
 * workflow engine and can contain multiple steps defined with @WorkflowStep.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @Workflow(
 *     id = "order-processing",
 *     name = "Order Processing Workflow",
 *     triggerMode = TriggerMode.BOTH,
 *     triggerEventType = "order.created"
 * )
 * public class OrderProcessingWorkflow {
 *
 *     @WorkflowStep(id = "validate", name = "Validate Order", order = 1)
 *     public Mono<OrderValidation> validateOrder(WorkflowContext ctx) {
 *         // validation logic
 *     }
 *
 *     @WorkflowStep(id = "process", name = "Process Order", order = 2)
 *     public Mono<ProcessedOrder> processOrder(WorkflowContext ctx) {
 *         // processing logic
 *     }
 * }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface Workflow {

    /**
     * Unique identifier for this workflow.
     * <p>
     * If not specified, defaults to the simple class name in kebab-case.
     *
     * @return the workflow ID
     */
    String id() default "";

    /**
     * Human-readable name of the workflow.
     * <p>
     * If not specified, defaults to the simple class name.
     *
     * @return the workflow name
     */
    String name() default "";

    /**
     * Description of what this workflow does.
     *
     * @return the description
     */
    String description() default "";

    /**
     * Version of this workflow definition.
     *
     * @return the version string
     */
    String version() default "1.0.0";

    /**
     * How this workflow can be triggered.
     *
     * @return the trigger mode
     */
    TriggerMode triggerMode() default TriggerMode.BOTH;

    /**
     * Event type that can trigger this workflow (for ASYNC/BOTH modes).
     * <p>
     * Supports glob patterns for flexible event matching.
     *
     * @return the trigger event type
     */
    String triggerEventType() default "";

    /**
     * Maximum time in milliseconds for the entire workflow to complete.
     * <p>
     * If 0, uses the default timeout from configuration.
     *
     * @return timeout in milliseconds
     */
    long timeoutMs() default 0;

    /**
     * Maximum number of retry attempts for failed steps.
     *
     * @return max retry attempts
     */
    int maxRetries() default 3;

    /**
     * Initial delay in milliseconds before retrying a failed step.
     *
     * @return retry delay in milliseconds
     */
    long retryDelayMs() default 1000;

    /**
     * Whether to publish lifecycle events for this workflow.
     *
     * @return true to publish events
     */
    boolean publishEvents() default true;
}
