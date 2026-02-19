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

package org.fireflyframework.workflow.properties;

import org.fireflyframework.eda.annotation.PublisherType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration properties for the Workflow Engine library.
 */
@ConfigurationProperties(prefix = "firefly.workflow")
@Validated
@Data
public class WorkflowProperties {

    /**
     * Whether the workflow engine is enabled.
     */
    private boolean enabled = true;

    /**
     * Default timeout for workflow execution.
     */
    @NotNull
    private Duration defaultTimeout = Duration.ofHours(1);

    /**
     * Default timeout for step execution.
     */
    @NotNull
    private Duration defaultStepTimeout = Duration.ofMinutes(5);

    /**
     * Whether to enable metrics collection.
     */
    private boolean metricsEnabled = true;

    /**
     * Whether to enable health checks.
     */
    private boolean healthEnabled = true;

    /**
     * State persistence configuration.
     */
    @Valid
    @NotNull
    private StateConfig state = new StateConfig();

    /**
     * Event publishing configuration.
     */
    @Valid
    @NotNull
    private EventConfig events = new EventConfig();

    /**
     * Retry configuration.
     */
    @Valid
    @NotNull
    private RetryConfig retry = new RetryConfig();

    /**
     * REST API configuration.
     */
    @Valid
    @NotNull
    private ApiConfig api = new ApiConfig();

    /**
     * Resilience configuration (Circuit Breaker, Rate Limiter, Bulkhead, Time Limiter).
     */
    @Valid
    @NotNull
    private ResilienceConfig resilience = new ResilienceConfig();

    /**
     * Scheduling configuration for cron-based workflows.
     */
    @Valid
    @NotNull
    private SchedulingConfig scheduling = new SchedulingConfig();

    /**
     * Dead Letter Queue (DLQ) configuration.
     */
    @Valid
    @NotNull
    private DlqConfig dlq = new DlqConfig();

    /**
     * Crash recovery configuration.
     */
    @Valid
    @NotNull
    private RecoveryConfig recovery = new RecoveryConfig();

    /**
     * Event sourcing configuration for durable execution.
     */
    @Valid
    @NotNull
    private EventSourcingConfig eventsourcing = new EventSourcingConfig();

    /**
     * Signal delivery configuration.
     */
    @Valid
    @NotNull
    private SignalConfig signals = new SignalConfig();

    /**
     * Durable timer configuration.
     */
    @Valid
    @NotNull
    private TimerConfig timers = new TimerConfig();

    /**
     * Child workflow configuration.
     */
    @Valid
    @NotNull
    private ChildWorkflowConfig childWorkflows = new ChildWorkflowConfig();

    /**
     * Compensation (saga rollback) configuration.
     */
    @Valid
    @NotNull
    private CompensationConfig compensation = new CompensationConfig();

    /**
     * Search attribute configuration.
     */
    @Valid
    @NotNull
    private SearchAttributeConfig searchAttributes = new SearchAttributeConfig();

    /**
     * Heartbeat configuration.
     */
    @Valid
    @NotNull
    private HeartbeatConfig heartbeat = new HeartbeatConfig();

    /**
     * State persistence configuration.
     */
    @Data
    public static class StateConfig {

        /**
         * Whether to persist workflow state.
         */
        private boolean enabled = true;

        /**
         * Default TTL for workflow instance state.
         */
        @NotNull
        private Duration defaultTtl = Duration.ofDays(7);

        /**
         * TTL for completed workflow instances.
         */
        @NotNull
        private Duration completedTtl = Duration.ofDays(1);

        /**
         * Key prefix for cache entries.
         */
        private String keyPrefix = "workflow";

        /**
         * Whether to enable state compression.
         */
        private boolean compressionEnabled = false;
    }

    /**
     * Event publishing configuration.
     */
    @Data
    public static class EventConfig {

        /**
         * Whether to publish workflow events.
         */
        private boolean enabled = true;

        /**
         * Publisher type to use for workflow events.
         */
        @NotNull
        private PublisherType publisherType = PublisherType.AUTO;

        /**
         * Connection ID for the event publisher.
         */
        private String connectionId = "default";

        /**
         * Default destination for workflow events.
         */
        private String defaultDestination = "workflow-events";

        /**
         * Prefix for event types.
         */
        private String eventTypePrefix = "workflow";

        /**
         * Whether to publish step-level events.
         */
        private boolean publishStepEvents = true;

        /**
         * Whether to include workflow context in events.
         */
        private boolean includeContext = false;

        /**
         * Whether to include step output in events.
         */
        private boolean includeOutput = true;
    }

    /**
     * Retry configuration.
     */
    @Data
    public static class RetryConfig {

        /**
         * Maximum number of retry attempts.
         */
        @Min(1)
        private int maxAttempts = 3;

        /**
         * Initial delay before first retry.
         */
        @NotNull
        private Duration initialDelay = Duration.ofSeconds(1);

        /**
         * Maximum delay between retries.
         */
        @NotNull
        private Duration maxDelay = Duration.ofMinutes(5);

        /**
         * Multiplier for exponential backoff.
         */
        private double multiplier = 2.0;
    }

    /**
     * REST API configuration.
     */
    @Data
    public static class ApiConfig {

        /**
         * Whether to enable the REST API.
         */
        private boolean enabled = true;

        /**
         * Base path for workflow REST endpoints.
         */
        private String basePath = "/api/workflows";

        /**
         * Whether to enable Swagger/OpenAPI documentation.
         */
        private boolean documentationEnabled = true;
    }

    /**
     * Resilience configuration for Circuit Breaker, Rate Limiter, Bulkhead, and Time Limiter.
     */
    @Data
    public static class ResilienceConfig {

        /**
         * Whether resilience features are enabled.
         */
        private boolean enabled = true;

        /**
         * Circuit breaker configuration.
         */
        @Valid
        @NotNull
        private CircuitBreakerConfig circuitBreaker = new CircuitBreakerConfig();

        /**
         * Rate limiter configuration.
         */
        @Valid
        @NotNull
        private RateLimiterConfig rateLimiter = new RateLimiterConfig();

        /**
         * Bulkhead configuration.
         */
        @Valid
        @NotNull
        private BulkheadConfig bulkhead = new BulkheadConfig();

        /**
         * Time limiter configuration.
         */
        @Valid
        @NotNull
        private TimeLimiterConfig timeLimiter = new TimeLimiterConfig();
    }

    /**
     * Circuit breaker configuration.
     */
    @Data
    public static class CircuitBreakerConfig {

        /**
         * Whether circuit breaker is enabled.
         */
        private boolean enabled = true;

        /**
         * Failure rate threshold percentage (0-100) to open the circuit.
         */
        @Min(1)
        private int failureRateThreshold = 50;

        /**
         * Slow call rate threshold percentage (0-100) to open the circuit.
         */
        @Min(1)
        private int slowCallRateThreshold = 100;

        /**
         * Duration threshold for slow calls.
         */
        @NotNull
        private Duration slowCallDurationThreshold = Duration.ofSeconds(60);

        /**
         * Number of permitted calls in half-open state.
         */
        @Min(1)
        private int permittedNumberOfCallsInHalfOpenState = 10;

        /**
         * Minimum number of calls before calculating failure rate.
         */
        @Min(1)
        private int minimumNumberOfCalls = 10;

        /**
         * Sliding window type: COUNT_BASED or TIME_BASED.
         */
        private String slidingWindowType = "COUNT_BASED";

        /**
         * Sliding window size (number of calls for COUNT_BASED, seconds for TIME_BASED).
         */
        @Min(1)
        private int slidingWindowSize = 100;

        /**
         * Wait duration in open state before transitioning to half-open.
         */
        @NotNull
        private Duration waitDurationInOpenState = Duration.ofSeconds(60);

        /**
         * Whether to automatically transition from open to half-open.
         */
        private boolean automaticTransitionFromOpenToHalfOpenEnabled = true;

        /**
         * List of exception class names that should be recorded as failures.
         */
        private String[] recordExceptions = {};

        /**
         * List of exception class names that should be ignored.
         */
        private String[] ignoreExceptions = {};
    }

    /**
     * Rate limiter configuration.
     */
    @Data
    public static class RateLimiterConfig {

        /**
         * Whether rate limiter is enabled.
         */
        private boolean enabled = false;

        /**
         * Number of permissions available during one limit refresh period.
         */
        @Min(1)
        private int limitForPeriod = 50;

        /**
         * Period of a limit refresh.
         */
        @NotNull
        private Duration limitRefreshPeriod = Duration.ofSeconds(1);

        /**
         * Maximum time a thread waits for permission.
         */
        @NotNull
        private Duration timeoutDuration = Duration.ofSeconds(5);
    }

    /**
     * Bulkhead configuration for limiting concurrent executions.
     */
    @Data
    public static class BulkheadConfig {

        /**
         * Whether bulkhead is enabled.
         */
        private boolean enabled = false;

        /**
         * Maximum number of concurrent calls.
         */
        @Min(1)
        private int maxConcurrentCalls = 25;

        /**
         * Maximum wait duration for a permit.
         */
        @NotNull
        private Duration maxWaitDuration = Duration.ofMillis(0);
    }

    /**
     * Time limiter configuration for timeout handling.
     */
    @Data
    public static class TimeLimiterConfig {

        /**
         * Whether time limiter is enabled.
         */
        private boolean enabled = true;

        /**
         * Timeout duration for step execution.
         */
        @NotNull
        private Duration timeoutDuration = Duration.ofMinutes(5);

        /**
         * Whether to cancel the running future on timeout.
         */
        private boolean cancelRunningFuture = true;
    }

    /**
     * Scheduling configuration for cron-based workflow execution.
     */
    @Data
    public static class SchedulingConfig {

        /**
         * Whether workflow scheduling is enabled.
         */
        private boolean enabled = true;

        /**
         * Thread pool size for scheduled tasks.
         */
        @Min(1)
        private int poolSize = 5;

        /**
         * Thread name prefix for scheduled tasks.
         */
        private String threadNamePrefix = "workflow-scheduler-";

        /**
         * Whether to wait for scheduled tasks to complete on shutdown.
         */
        private boolean waitForTasksToCompleteOnShutdown = true;

        /**
         * Timeout in seconds to wait for tasks on shutdown.
         */
        @Min(0)
        private int awaitTerminationSeconds = 30;
    }

    /**
     * Dead Letter Queue (DLQ) configuration.
     */
    @Data
    public static class DlqConfig {

        /**
         * Whether DLQ is enabled.
         */
        private boolean enabled = true;

        /**
         * Maximum number of replay attempts before giving up.
         */
        @Min(1)
        private int maxReplayAttempts = 3;

        /**
         * Retention period for DLQ entries.
         */
        @NotNull
        private Duration retentionPeriod = Duration.ofDays(30);

        /**
         * Whether to automatically save failed workflows to DLQ.
         */
        private boolean autoSaveOnFailure = true;

        /**
         * Whether to include full stack traces in DLQ entries.
         */
        private boolean includeStackTrace = true;
    }

    /**
     * Configuration for crash recovery of stale workflow instances.
     */
    @Data
    public static class RecoveryConfig {

        /**
         * Whether crash recovery is enabled.
         */
        private boolean enabled = true;

        /**
         * How long a RUNNING instance must be stale (no updates) before recovery.
         */
        @NotNull
        private Duration staleThreshold = Duration.ofMinutes(5);
    }

    /**
     * Event sourcing configuration for durable execution.
     */
    @Data
    public static class EventSourcingConfig {

        /**
         * Whether event sourcing is enabled (opt-in, not on by default).
         */
        private boolean enabled = false;

        /**
         * Number of events before a snapshot is taken.
         */
        @Min(1)
        private int snapshotThreshold = 20;

        /**
         * Maximum number of events before triggering continue-as-new.
         */
        @Min(100)
        private int maxEventsBeforeContinueAsNew = 1000;
    }

    /**
     * Signal delivery configuration.
     */
    @Data
    public static class SignalConfig {

        /**
         * Whether signal delivery is enabled.
         */
        private boolean enabled = true;

        /**
         * Maximum number of signals that can be buffered per workflow instance.
         */
        @Min(1)
        private int bufferSize = 100;
    }

    /**
     * Durable timer configuration.
     */
    @Data
    public static class TimerConfig {

        /**
         * Whether durable timers are enabled.
         */
        private boolean enabled = true;

        /**
         * How often the timer scheduler polls for due timers.
         */
        @NotNull
        private Duration pollInterval = Duration.ofSeconds(1);

        /**
         * Maximum number of timers to process per poll cycle.
         */
        @Min(1)
        private int batchSize = 50;
    }

    /**
     * Child workflow configuration.
     */
    @Data
    public static class ChildWorkflowConfig {

        /**
         * Whether child workflows are enabled.
         */
        private boolean enabled = true;

        /**
         * Maximum depth of nested child workflows.
         */
        @Min(1)
        private int maxDepth = 5;
    }

    /**
     * Compensation (saga rollback) configuration.
     */
    @Data
    public static class CompensationConfig {

        /**
         * Whether compensation is enabled.
         */
        private boolean enabled = true;

        /**
         * Default compensation policy (e.g., STRICT_SEQUENTIAL).
         */
        private String defaultPolicy = "STRICT_SEQUENTIAL";
    }

    /**
     * Search attribute configuration.
     */
    @Data
    public static class SearchAttributeConfig {

        /**
         * Whether search attributes are enabled.
         */
        private boolean enabled = true;
    }

    /**
     * Heartbeat configuration.
     */
    @Data
    public static class HeartbeatConfig {

        /**
         * Whether heartbeating is enabled.
         */
        private boolean enabled = true;

        /**
         * Minimum interval between heartbeat updates (throttle).
         */
        @NotNull
        private Duration throttleInterval = Duration.ofSeconds(5);
    }
}
