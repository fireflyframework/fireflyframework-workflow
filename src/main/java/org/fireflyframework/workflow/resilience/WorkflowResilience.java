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

package org.fireflyframework.workflow.resilience;

import org.fireflyframework.workflow.properties.WorkflowProperties;
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

    /**
     * Decorates a Mono with all enabled resilience patterns.
     *
     * @param workflowId the workflow ID
     * @param stepId the step ID
     * @param mono the Mono to decorate
     * @param <T> the result type
     * @return the decorated Mono
     */
    public <T> Mono<T> decorateStep(String workflowId, String stepId, Mono<T> mono) {
        if (!config.isEnabled()) {
            return mono;
        }

        String name = workflowId + ":" + stepId;
        Mono<T> decorated = mono;

        // Apply time limiter first (innermost) - using Reactor's native timeout
        // TimeLimiter is used for configuration and event publishing
        if (config.getTimeLimiter().isEnabled()) {
            TimeLimiter timeLimiter = getOrCreateTimeLimiter(name);
            var timeoutDuration = timeLimiter.getTimeLimiterConfig().getTimeoutDuration();
            decorated = decorated
                    .timeout(timeoutDuration)
                    .doOnError(java.util.concurrent.TimeoutException.class, e -> {
                        timeLimiter.onError(e);
                        log.warn("TIME_LIMITER_TIMEOUT: name={}, timeout={}", name, timeoutDuration);
                    })
                    .doOnSuccess(result -> timeLimiter.onSuccess())
                    .subscribeOn(Schedulers.boundedElastic());
        }

        // Apply bulkhead
        if (config.getBulkhead().isEnabled()) {
            Bulkhead bulkhead = getOrCreateBulkhead(name);
            decorated = decorated.transformDeferred(BulkheadOperator.of(bulkhead));
        }

        // Apply rate limiter
        if (config.getRateLimiter().isEnabled()) {
            RateLimiter rateLimiter = getOrCreateRateLimiter(name);
            decorated = decorated.transformDeferred(RateLimiterOperator.of(rateLimiter));
        }

        // Apply circuit breaker (outermost)
        if (config.getCircuitBreaker().isEnabled()) {
            CircuitBreaker circuitBreaker = getOrCreateCircuitBreaker(name);
            decorated = decorated.transformDeferred(CircuitBreakerOperator.of(circuitBreaker));
        }

        return decorated;
    }

    /**
     * Gets or creates a circuit breaker for the given step.
     */
    public CircuitBreaker getOrCreateCircuitBreaker(String name) {
        return stepCircuitBreakers.computeIfAbsent(name, n -> {
            CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(n);
            cb.getEventPublisher()
                    .onStateTransition(event ->
                            log.info("CIRCUIT_BREAKER_STATE: name={}, from={}, to={}",
                                    event.getCircuitBreakerName(),
                                    event.getStateTransition().getFromState(),
                                    event.getStateTransition().getToState()))
                    .onError(event ->
                            log.warn("CIRCUIT_BREAKER_ERROR: name={}, error={}",
                                    event.getCircuitBreakerName(),
                                    event.getThrowable().getMessage()))
                    .onSuccess(event ->
                            log.debug("CIRCUIT_BREAKER_SUCCESS: name={}, durationMs={}",
                                    event.getCircuitBreakerName(),
                                    event.getElapsedDuration().toMillis()));
            return cb;
        });
    }

    /**
     * Gets or creates a rate limiter for the given step.
     */
    public RateLimiter getOrCreateRateLimiter(String name) {
        return stepRateLimiters.computeIfAbsent(name, n -> {
            RateLimiter rl = rateLimiterRegistry.rateLimiter(n);
            rl.getEventPublisher()
                    .onSuccess(event ->
                            log.debug("RATE_LIMITER_SUCCESS: name={}", event.getRateLimiterName()))
                    .onFailure(event ->
                            log.warn("RATE_LIMITER_REJECTED: name={}", event.getRateLimiterName()));
            return rl;
        });
    }

    /**
     * Gets or creates a bulkhead for the given step.
     */
    public Bulkhead getOrCreateBulkhead(String name) {
        return stepBulkheads.computeIfAbsent(name, n -> {
            Bulkhead bh = bulkheadRegistry.bulkhead(n);
            bh.getEventPublisher()
                    .onCallPermitted(event ->
                            log.debug("BULKHEAD_PERMITTED: name={}", event.getBulkheadName()))
                    .onCallRejected(event ->
                            log.warn("BULKHEAD_REJECTED: name={}", event.getBulkheadName()))
                    .onCallFinished(event ->
                            log.debug("BULKHEAD_FINISHED: name={}", event.getBulkheadName()));
            return bh;
        });
    }

    /**
     * Gets or creates a time limiter for the given step.
     */
    public TimeLimiter getOrCreateTimeLimiter(String name) {
        return stepTimeLimiters.computeIfAbsent(name, n -> {
            TimeLimiter tl = timeLimiterRegistry.timeLimiter(n);
            tl.getEventPublisher()
                    .onSuccess(event ->
                            log.debug("TIME_LIMITER_SUCCESS: name={}", event.getTimeLimiterName()))
                    .onTimeout(event ->
                            log.warn("TIME_LIMITER_TIMEOUT: name={}", event.getTimeLimiterName()))
                    .onError(event ->
                            log.warn("TIME_LIMITER_ERROR: name={}, error={}",
                                    event.getTimeLimiterName(),
                                    event.getThrowable().getMessage()));
            return tl;
        });
    }

    /**
     * Gets the circuit breaker state for a step.
     */
    public CircuitBreaker.State getCircuitBreakerState(String workflowId, String stepId) {
        String name = workflowId + ":" + stepId;
        CircuitBreaker cb = stepCircuitBreakers.get(name);
        return cb != null ? cb.getState() : null;
    }

    /**
     * Resets the circuit breaker for a step.
     */
    public void resetCircuitBreaker(String workflowId, String stepId) {
        String name = workflowId + ":" + stepId;
        CircuitBreaker cb = stepCircuitBreakers.get(name);
        if (cb != null) {
            cb.reset();
            log.info("CIRCUIT_BREAKER_RESET: name={}", name);
        }
    }

    /**
     * Gets circuit breaker metrics for a step.
     */
    public CircuitBreakerMetrics getCircuitBreakerMetrics(String workflowId, String stepId) {
        String name = workflowId + ":" + stepId;
        CircuitBreaker cb = stepCircuitBreakers.get(name);
        if (cb == null) {
            return null;
        }
        CircuitBreaker.Metrics metrics = cb.getMetrics();
        return new CircuitBreakerMetrics(
                cb.getState(),
                metrics.getFailureRate(),
                metrics.getSlowCallRate(),
                metrics.getNumberOfSuccessfulCalls(),
                metrics.getNumberOfFailedCalls(),
                metrics.getNumberOfSlowCalls(),
                metrics.getNumberOfNotPermittedCalls()
        );
    }

    /**
     * Gets all circuit breaker states.
     */
    public Map<String, CircuitBreaker.State> getAllCircuitBreakerStates() {
        Map<String, CircuitBreaker.State> states = new ConcurrentHashMap<>();
        stepCircuitBreakers.forEach((name, cb) -> states.put(name, cb.getState()));
        return states;
    }

    /**
     * Checks if resilience is enabled.
     */
    public boolean isEnabled() {
        return config.isEnabled();
    }

    /**
     * Checks if circuit breaker is enabled.
     */
    public boolean isCircuitBreakerEnabled() {
        return config.isEnabled() && config.getCircuitBreaker().isEnabled();
    }

    /**
     * Checks if rate limiter is enabled.
     */
    public boolean isRateLimiterEnabled() {
        return config.isEnabled() && config.getRateLimiter().isEnabled();
    }

    /**
     * Checks if bulkhead is enabled.
     */
    public boolean isBulkheadEnabled() {
        return config.isEnabled() && config.getBulkhead().isEnabled();
    }

    /**
     * Checks if time limiter is enabled.
     */
    public boolean isTimeLimiterEnabled() {
        return config.isEnabled() && config.getTimeLimiter().isEnabled();
    }

    // ==================== Registry Creation ====================

    private CircuitBreakerRegistry createCircuitBreakerRegistry() {
        var cbConfig = config.getCircuitBreaker();

        CircuitBreakerConfig.SlidingWindowType windowType =
                "TIME_BASED".equalsIgnoreCase(cbConfig.getSlidingWindowType())
                        ? CircuitBreakerConfig.SlidingWindowType.TIME_BASED
                        : CircuitBreakerConfig.SlidingWindowType.COUNT_BASED;

        CircuitBreakerConfig defaultConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(cbConfig.getFailureRateThreshold())
                .slowCallRateThreshold(cbConfig.getSlowCallRateThreshold())
                .slowCallDurationThreshold(cbConfig.getSlowCallDurationThreshold())
                .permittedNumberOfCallsInHalfOpenState(cbConfig.getPermittedNumberOfCallsInHalfOpenState())
                .minimumNumberOfCalls(cbConfig.getMinimumNumberOfCalls())
                .slidingWindowType(windowType)
                .slidingWindowSize(cbConfig.getSlidingWindowSize())
                .waitDurationInOpenState(cbConfig.getWaitDurationInOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(cbConfig.isAutomaticTransitionFromOpenToHalfOpenEnabled())
                .build();

        return CircuitBreakerRegistry.of(defaultConfig);
    }

    private RateLimiterRegistry createRateLimiterRegistry() {
        var rlConfig = config.getRateLimiter();

        RateLimiterConfig defaultConfig = RateLimiterConfig.custom()
                .limitForPeriod(rlConfig.getLimitForPeriod())
                .limitRefreshPeriod(rlConfig.getLimitRefreshPeriod())
                .timeoutDuration(rlConfig.getTimeoutDuration())
                .build();

        return RateLimiterRegistry.of(defaultConfig);
    }

    private BulkheadRegistry createBulkheadRegistry() {
        var bhConfig = config.getBulkhead();

        BulkheadConfig defaultConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(bhConfig.getMaxConcurrentCalls())
                .maxWaitDuration(bhConfig.getMaxWaitDuration())
                .build();

        return BulkheadRegistry.of(defaultConfig);
    }

    private TimeLimiterRegistry createTimeLimiterRegistry() {
        var tlConfig = config.getTimeLimiter();

        TimeLimiterConfig defaultConfig = TimeLimiterConfig.custom()
                .timeoutDuration(tlConfig.getTimeoutDuration())
                .cancelRunningFuture(tlConfig.isCancelRunningFuture())
                .build();

        return TimeLimiterRegistry.of(defaultConfig);
    }

    // ==================== Metrics Record ====================

    /**
     * Circuit breaker metrics snapshot.
     */
    public record CircuitBreakerMetrics(
            CircuitBreaker.State state,
            float failureRate,
            float slowCallRate,
            int successfulCalls,
            int failedCalls,
            int slowCalls,
            long notPermittedCalls
    ) {}

    // ==================== Getters for Registries ====================

    public CircuitBreakerRegistry getCircuitBreakerRegistry() {
        return circuitBreakerRegistry;
    }

    public RateLimiterRegistry getRateLimiterRegistry() {
        return rateLimiterRegistry;
    }

    public BulkheadRegistry getBulkheadRegistry() {
        return bulkheadRegistry;
    }

    public TimeLimiterRegistry getTimeLimiterRegistry() {
        return timeLimiterRegistry;
    }
}

