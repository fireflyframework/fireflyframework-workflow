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

package org.fireflyframework.workflow.tracing;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for workflow tracing using OpenTelemetry via Micrometer.
 * <p>
 * This configuration is enabled when:
 * <ul>
 *   <li>Micrometer Observation API is on the classpath</li>
 *   <li>firefly.workflow.tracing.enabled is true (default)</li>
 * </ul>
 * <p>
 * The tracing integrates with OpenTelemetry through the Micrometer OTEL bridge,
 * providing automatic span creation for workflow and step executions.
 * <p>
 * Configuration properties:
 * <pre>
 * firefly:
 *   workflow:
 *     tracing:
 *       enabled: true  # Enable/disable tracing (default: true)
 * </pre>
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(ObservationRegistry.class)
@ConditionalOnProperty(prefix = "firefly.workflow.tracing", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkflowTracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WorkflowTracer workflowTracer(ObservationRegistry observationRegistry) {
        log.info("Configuring WorkflowTracer with OpenTelemetry support via Micrometer Observation");
        return new WorkflowTracer(observationRegistry);
    }
}

