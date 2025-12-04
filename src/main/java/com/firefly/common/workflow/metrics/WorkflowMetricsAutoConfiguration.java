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

package com.firefly.common.workflow.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for workflow metrics using Micrometer.
 * <p>
 * This configuration is enabled when:
 * <ul>
 *   <li>Micrometer MeterRegistry is on the classpath</li>
 *   <li>firefly.workflow.metrics-enabled is true (default)</li>
 * </ul>
 * <p>
 * The following metrics are exposed:
 * <ul>
 *   <li><b>firefly.workflow.started</b> - Counter of workflows started</li>
 *   <li><b>firefly.workflow.completed</b> - Counter of workflows completed</li>
 *   <li><b>firefly.workflow.failed</b> - Counter of workflows failed</li>
 *   <li><b>firefly.workflow.duration</b> - Timer for workflow execution duration</li>
 *   <li><b>firefly.workflow.active</b> - Gauge of currently active workflows</li>
 *   <li><b>firefly.workflow.step.started</b> - Counter of steps started</li>
 *   <li><b>firefly.workflow.step.completed</b> - Counter of steps completed</li>
 *   <li><b>firefly.workflow.step.duration</b> - Timer for step execution duration</li>
 *   <li><b>firefly.workflow.step.retries</b> - Counter of step retry attempts</li>
 *   <li><b>firefly.workflow.step.skipped</b> - Counter of steps skipped</li>
 * </ul>
 * <p>
 * Configuration properties:
 * <pre>
 * firefly:
 *   workflow:
 *     metrics-enabled: true  # Enable/disable metrics (default: true)
 * </pre>
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(prefix = "firefly.workflow", name = "metrics-enabled", havingValue = "true", matchIfMissing = true)
public class WorkflowMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WorkflowMetrics workflowMetrics(MeterRegistry meterRegistry) {
        log.info("Configuring WorkflowMetrics with Micrometer MeterRegistry");
        return new WorkflowMetrics(meterRegistry);
    }
}

