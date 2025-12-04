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
 * Marks a workflow to be executed on a schedule using cron expressions.
 * <p>
 * This annotation can be used on classes annotated with @Workflow to define
 * periodic execution schedules. Multiple schedules can be defined for the
 * same workflow using multiple annotations.
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @Workflow(id = "daily-report")
 * @ScheduledWorkflow(cron = "0 0 2 * * ?", zone = "America/New_York")
 * public class DailyReportWorkflow {
 *     // workflow steps...
 * }
 *
 * // Multiple schedules
 * @Workflow(id = "batch-processing")
 * @ScheduledWorkflow(cron = "0 0 6 * * ?")  // 6 AM daily
 * @ScheduledWorkflow(cron = "0 0 18 * * ?") // 6 PM daily
 * public class BatchProcessingWorkflow {
 *     // workflow steps...
 * }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(ScheduledWorkflows.class)
public @interface ScheduledWorkflow {

    /**
     * Cron expression defining the schedule.
     * <p>
     * Uses Spring's cron expression format:
     * <ul>
     *   <li>second (0-59)</li>
     *   <li>minute (0-59)</li>
     *   <li>hour (0-23)</li>
     *   <li>day of month (1-31)</li>
     *   <li>month (1-12 or JAN-DEC)</li>
     *   <li>day of week (0-7 or SUN-SAT, 0 and 7 are Sunday)</li>
     * </ul>
     * <p>
     * Examples:
     * <ul>
     *   <li>"0 0 * * * *" - every hour</li>
     *   <li>"0 0 8 * * *" - daily at 8 AM</li>
     *   <li>"0 0 2 * * MON-FRI" - weekdays at 2 AM</li>
     *   <li>"0 0 0 1 * *" - first day of month at midnight</li>
     * </ul>
     *
     * @return the cron expression
     */
    String cron();

    /**
     * Timezone for the cron expression.
     * <p>
     * If not specified, uses the system default timezone.
     *
     * @return timezone ID (e.g., "America/New_York", "UTC")
     */
    String zone() default "";

    /**
     * Whether this schedule is enabled.
     * <p>
     * Can be disabled via configuration properties.
     *
     * @return true if enabled
     */
    boolean enabled() default true;

    /**
     * Fixed delay in milliseconds between executions.
     * <p>
     * Alternative to cron expression. If set (> 0), uses fixed delay scheduling
     * instead of cron.
     *
     * @return fixed delay in milliseconds, or 0 to use cron
     */
    long fixedDelay() default 0;

    /**
     * Fixed rate in milliseconds between executions.
     * <p>
     * Alternative to cron expression. If set (> 0), uses fixed rate scheduling
     * instead of cron.
     *
     * @return fixed rate in milliseconds, or 0 to use cron
     */
    long fixedRate() default 0;

    /**
     * Initial delay in milliseconds before first execution.
     * <p>
     * Applies to both cron and fixed delay/rate schedules.
     *
     * @return initial delay in milliseconds
     */
    long initialDelay() default 0;

    /**
     * Default input data as JSON string.
     * <p>
     * This JSON will be parsed and used as input for scheduled workflow executions.
     *
     * @return JSON string of input data
     */
    String input() default "{}";

    /**
     * Description of this schedule.
     *
     * @return schedule description
     */
    String description() default "";
}
