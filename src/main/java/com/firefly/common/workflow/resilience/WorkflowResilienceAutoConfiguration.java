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

package com.firefly.common.workflow.resilience;

import com.firefly.common.workflow.properties.WorkflowProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.lang.Nullable;

/**
 * Auto-configuration for Workflow Resilience (Resilience4j integration).
 */
@AutoConfiguration
@ConditionalOnClass(CircuitBreakerRegistry.class)
@ConditionalOnProperty(prefix = "firefly.workflow.resilience", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class WorkflowResilienceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WorkflowResilience workflowResilience(
            WorkflowProperties properties,
            @Nullable MeterRegistry meterRegistry) {
        
        log.info("Configuring WorkflowResilience with Resilience4j");
        return new WorkflowResilience(properties, meterRegistry);
    }

    /**
     * Binds Resilience4j metrics to Micrometer.
     */
    @Bean
    @ConditionalOnClass(MeterBinder.class)
    @ConditionalOnProperty(prefix = "firefly.workflow", name = "metrics-enabled", havingValue = "true", matchIfMissing = true)
    public MeterBinder resilience4jMeterBinder(WorkflowResilience workflowResilience) {
        return registry -> {
            // Register circuit breaker metrics
            io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics
                    .ofCircuitBreakerRegistry(workflowResilience.getCircuitBreakerRegistry())
                    .bindTo(registry);

            // Register rate limiter metrics
            io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics
                    .ofRateLimiterRegistry(workflowResilience.getRateLimiterRegistry())
                    .bindTo(registry);

            // Register bulkhead metrics
            io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics
                    .ofBulkheadRegistry(workflowResilience.getBulkheadRegistry())
                    .bindTo(registry);

            // Register time limiter metrics
            io.github.resilience4j.micrometer.tagged.TaggedTimeLimiterMetrics
                    .ofTimeLimiterRegistry(workflowResilience.getTimeLimiterRegistry())
                    .bindTo(registry);

            log.info("Resilience4j metrics bound to Micrometer registry");
        };
    }
}

