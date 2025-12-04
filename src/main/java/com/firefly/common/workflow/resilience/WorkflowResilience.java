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
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.github.resilience4j.reactor.timelimiter.operator.TimeLimiterOperator;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Provides Resilience4j decorators for workflow and step execution.
 * <p>
 * This service manages circuit breakers, rate limiters, bulkheads, and time limiters
 * for workflow steps, providing fault tolerance and resilience patterns.
 */
@Slf4j
public class WorkflowResilience {

    private final WorkflowProperties.ResilienceConfig config;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final BulkheadRegistry bulkheadRegistry;
    private final TimeLimiterRegistry timeLimiterRegistry;
    
    private final Map<String, CircuitBreaker> stepCircuitBreakers = new ConcurrentHashMap<>();
    private final Map<String, RateLimiter> stepRateLimiters = new ConcurrentHashMap<>();
    private final Map<String, Bulkhead> stepBulkheads = new ConcurrentHashMap<>();
    private final Map<String, TimeLimiter> stepTimeLimiters = new ConcurrentHashMap<>();

    public WorkflowResilience(
            WorkflowProperties properties,
            @Nullable MeterRegistry meterRegistry) {
        
        this.config = properties.getResilience();
        
        // Initialize registries with default configurations
        this.circuitBreakerRegistry = createCircuitBreakerRegistry();
        this.rateLimiterRegistry = createRateLimiterRegistry();
        this.bulkheadRegistry = createBulkheadRegistry();
        this.timeLimiterRegistry = createTimeLimiterRegistry();
        
        log.info("RESILIENCE_INIT: circuitBreaker={}, rateLimiter={}, bulkhead={}, timeLimiter={}",
                config.getCircuitBreaker().isEnabled(),
                config.getRateLimiter().isEnabled(),
                config.getBulkhead().isEnabled(),
                config.getTimeLimiter().isEnabled());
    }

    // ... continued in next part

