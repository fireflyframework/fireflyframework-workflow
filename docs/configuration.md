# Configuration Reference

All configuration properties for the Firefly Workflow Engine use the prefix `firefly.workflow` and are defined in `WorkflowProperties`. This document lists every property with its type, default value, and description.

## Core Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `firefly.workflow.enabled` | `boolean` | `true` | Enable/disable the workflow engine |
| `firefly.workflow.default-timeout` | `Duration` | `1h` | Default timeout for entire workflow execution |
| `firefly.workflow.default-step-timeout` | `Duration` | `5m` | Default timeout for individual step execution |
| `firefly.workflow.metrics-enabled` | `boolean` | `true` | Enable Micrometer metrics collection |
| `firefly.workflow.health-enabled` | `boolean` | `true` | Enable Spring Boot Actuator health indicator |

## State Persistence (firefly.workflow.state)

Controls how workflow instance state is persisted in the cache.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `state.enabled` | `boolean` | `true` | Whether to persist workflow state |
| `state.default-ttl` | `Duration` | `7d` | Default TTL for workflow instance state entries |
| `state.completed-ttl` | `Duration` | `1d` | TTL for completed workflow instances |
| `state.key-prefix` | `String` | `"workflow"` | Cache key prefix for workflow entries |
| `state.compression-enabled` | `boolean` | `false` | Whether to compress state data |

## Event Publishing (firefly.workflow.events)

Controls workflow event publishing via `fireflyframework-eda`.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `events.enabled` | `boolean` | `true` | Whether to publish workflow events |
| `events.publisher-type` | `PublisherType` | `AUTO` | Publisher type (`AUTO`, `KAFKA`, `RABBITMQ`, `APPLICATION_EVENT`) |
| `events.connection-id` | `String` | `"default"` | Connection ID for the event publisher |
| `events.default-destination` | `String` | `"workflow-events"` | Default destination (topic/queue) for events |
| `events.event-type-prefix` | `String` | `"workflow"` | Prefix for event type names |
| `events.publish-step-events` | `boolean` | `true` | Whether to publish step-level events |
| `events.include-context` | `boolean` | `false` | Whether to include workflow context in events |
| `events.include-output` | `boolean` | `true` | Whether to include step output in events |

## Retry (firefly.workflow.retry)

Default retry configuration for workflow steps. Individual steps can override these via `@WorkflowStep` or `WorkflowStepDefinition.builder()`.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `retry.max-attempts` | `int` | `3` | Maximum number of retry attempts (min: 1) |
| `retry.initial-delay` | `Duration` | `1s` | Initial delay before first retry |
| `retry.max-delay` | `Duration` | `5m` | Maximum delay between retries |
| `retry.multiplier` | `double` | `2.0` | Multiplier for exponential backoff |

## REST API (firefly.workflow.api)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `api.enabled` | `boolean` | `true` | Whether to enable REST API endpoints |
| `api.base-path` | `String` | `"/api/v1/workflows"` | Base path for all workflow REST endpoints |
| `api.documentation-enabled` | `boolean` | `true` | Whether to enable Swagger/OpenAPI documentation |

## Resilience (firefly.workflow.resilience)

Per-step resilience configuration using Resilience4j. The application order is: TimeLimiter -> Bulkhead -> RateLimiter -> CircuitBreaker.

### Top-level

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `resilience.enabled` | `boolean` | `true` | Whether resilience features are enabled |

### Circuit Breaker (firefly.workflow.resilience.circuit-breaker)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `circuit-breaker.enabled` | `boolean` | `true` | Enable circuit breaker |
| `circuit-breaker.failure-rate-threshold` | `int` | `50` | Failure rate % to open circuit (1-100) |
| `circuit-breaker.slow-call-rate-threshold` | `int` | `100` | Slow call rate % to open circuit (1-100) |
| `circuit-breaker.slow-call-duration-threshold` | `Duration` | `60s` | Duration threshold for slow calls |
| `circuit-breaker.permitted-number-of-calls-in-half-open-state` | `int` | `10` | Permitted calls in half-open state |
| `circuit-breaker.minimum-number-of-calls` | `int` | `10` | Min calls before calculating failure rate |
| `circuit-breaker.sliding-window-type` | `String` | `"COUNT_BASED"` | `COUNT_BASED` or `TIME_BASED` |
| `circuit-breaker.sliding-window-size` | `int` | `100` | Window size (calls for COUNT, seconds for TIME) |
| `circuit-breaker.wait-duration-in-open-state` | `Duration` | `60s` | Wait before transitioning open to half-open |
| `circuit-breaker.automatic-transition-from-open-to-half-open-enabled` | `boolean` | `true` | Auto-transition from open to half-open |
| `circuit-breaker.record-exceptions` | `String[]` | `{}` | Exception class names recorded as failures |
| `circuit-breaker.ignore-exceptions` | `String[]` | `{}` | Exception class names to ignore |

### Rate Limiter (firefly.workflow.resilience.rate-limiter)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `rate-limiter.enabled` | `boolean` | `false` | Enable rate limiter |
| `rate-limiter.limit-for-period` | `int` | `50` | Permissions per refresh period |
| `rate-limiter.limit-refresh-period` | `Duration` | `1s` | Period for limit refresh |
| `rate-limiter.timeout-duration` | `Duration` | `5s` | Max wait time for permission |

### Bulkhead (firefly.workflow.resilience.bulkhead)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `bulkhead.enabled` | `boolean` | `false` | Enable bulkhead |
| `bulkhead.max-concurrent-calls` | `int` | `25` | Maximum concurrent calls |
| `bulkhead.max-wait-duration` | `Duration` | `0ms` | Max wait for a permit |

### Time Limiter (firefly.workflow.resilience.time-limiter)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `time-limiter.enabled` | `boolean` | `true` | Enable time limiter |
| `time-limiter.timeout-duration` | `Duration` | `5m` | Timeout for step execution |
| `time-limiter.cancel-running-future` | `boolean` | `true` | Cancel the running future on timeout |

## Scheduling (firefly.workflow.scheduling)

Configuration for `@ScheduledWorkflow` cron-based execution.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `scheduling.enabled` | `boolean` | `true` | Enable workflow scheduling |
| `scheduling.pool-size` | `int` | `5` | Thread pool size for scheduled tasks |
| `scheduling.thread-name-prefix` | `String` | `"workflow-scheduler-"` | Thread name prefix |
| `scheduling.wait-for-tasks-to-complete-on-shutdown` | `boolean` | `true` | Wait for tasks on shutdown |
| `scheduling.await-termination-seconds` | `int` | `30` | Shutdown wait timeout in seconds |

## Dead Letter Queue (firefly.workflow.dlq)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `dlq.enabled` | `boolean` | `true` | Enable DLQ |
| `dlq.max-replay-attempts` | `int` | `3` | Max replay attempts before giving up |
| `dlq.retention-period` | `Duration` | `30d` | Retention period for DLQ entries |
| `dlq.auto-save-on-failure` | `boolean` | `true` | Auto-save failed workflows to DLQ |
| `dlq.include-stack-trace` | `boolean` | `true` | Include full stack traces in DLQ entries |

## Crash Recovery (firefly.workflow.recovery)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `recovery.enabled` | `boolean` | `true` | Enable crash recovery |
| `recovery.stale-threshold` | `Duration` | `5m` | How long a RUNNING instance must be stale before recovery |

## Event Sourcing / Durable Execution (firefly.workflow.eventsourcing)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `eventsourcing.enabled` | `boolean` | **`false`** | Whether event sourcing is enabled (opt-in) |
| `eventsourcing.snapshot-threshold` | `int` | `20` | Events before taking a snapshot (min: 1) |
| `eventsourcing.max-events-before-continue-as-new` | `int` | `1000` | Events before triggering continue-as-new (min: 100) |

## Signals (firefly.workflow.signals)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `signals.enabled` | `boolean` | `true` | Enable signal delivery |
| `signals.buffer-size` | `int` | `100` | Max buffered signals per workflow instance |

## Durable Timers (firefly.workflow.timers)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `timers.enabled` | `boolean` | `true` | Enable durable timers |
| `timers.poll-interval` | `Duration` | `1s` | How often the timer scheduler polls for due timers |
| `timers.batch-size` | `int` | `50` | Max timers to process per poll cycle |

## Child Workflows (firefly.workflow.child-workflows)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `child-workflows.enabled` | `boolean` | `true` | Enable child workflows |
| `child-workflows.max-depth` | `int` | `5` | Maximum nesting depth |

## Compensation (firefly.workflow.compensation)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `compensation.enabled` | `boolean` | `true` | Enable compensation (saga rollback) |
| `compensation.default-policy` | `String` | `"STRICT_SEQUENTIAL"` | Default policy: `STRICT_SEQUENTIAL`, `BEST_EFFORT`, or `SKIP` |

## Search Attributes (firefly.workflow.search-attributes)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `search-attributes.enabled` | `boolean` | `true` | Enable search attributes |

## Heartbeat (firefly.workflow.heartbeat)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `heartbeat.enabled` | `boolean` | `true` | Enable heartbeat tracking |
| `heartbeat.throttle-interval` | `Duration` | `5s` | Minimum interval between heartbeat updates |

## Cache Configuration (fireflyframework-cache)

The workflow engine requires a `CacheAdapter` bean from `fireflyframework-cache`. These properties are configured under the `firefly.cache` prefix:

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `firefly.cache.enabled` | `boolean` | `true` | Enable the cache module |
| `firefly.cache.redis.enabled` | `boolean` | `false` | Enable Redis cache provider |
| `firefly.cache.redis.host` | `String` | `"localhost"` | Redis host |
| `firefly.cache.redis.port` | `int` | `6379` | Redis port |
| `firefly.cache.default-cache-type` | `String` | `"memory"` | Default cache type (`redis` or `memory`) |

## Complete YAML Example

```yaml
firefly:
  # Cache configuration (required for default mode)
  cache:
    enabled: true
    redis:
      enabled: true
      host: localhost
      port: 6379
    default-cache-type: redis

  # Workflow engine configuration
  workflow:
    enabled: true
    default-timeout: 1h
    default-step-timeout: 5m
    metrics-enabled: true
    health-enabled: true

    state:
      enabled: true
      default-ttl: 7d
      completed-ttl: 1d
      key-prefix: workflow
      compression-enabled: false

    events:
      enabled: true
      publisher-type: AUTO
      connection-id: default
      default-destination: workflow-events
      event-type-prefix: workflow
      publish-step-events: true
      include-context: false
      include-output: true

    retry:
      max-attempts: 3
      initial-delay: 1s
      max-delay: 5m
      multiplier: 2.0

    api:
      enabled: true
      base-path: /api/v1/workflows
      documentation-enabled: true

    resilience:
      enabled: true
      circuit-breaker:
        enabled: true
        failure-rate-threshold: 50
        slow-call-rate-threshold: 100
        slow-call-duration-threshold: 60s
        permitted-number-of-calls-in-half-open-state: 10
        minimum-number-of-calls: 10
        sliding-window-type: COUNT_BASED
        sliding-window-size: 100
        wait-duration-in-open-state: 60s
        automatic-transition-from-open-to-half-open-enabled: true
      rate-limiter:
        enabled: false
        limit-for-period: 50
        limit-refresh-period: 1s
        timeout-duration: 5s
      bulkhead:
        enabled: false
        max-concurrent-calls: 25
        max-wait-duration: 0ms
      time-limiter:
        enabled: true
        timeout-duration: 5m
        cancel-running-future: true

    scheduling:
      enabled: true
      pool-size: 5
      thread-name-prefix: workflow-scheduler-
      wait-for-tasks-to-complete-on-shutdown: true
      await-termination-seconds: 30

    dlq:
      enabled: true
      max-replay-attempts: 3
      retention-period: 30d
      auto-save-on-failure: true
      include-stack-trace: true

    recovery:
      enabled: true
      stale-threshold: 5m

    # Durable execution (opt-in, default: false)
    eventsourcing:
      enabled: false
      snapshot-threshold: 20
      max-events-before-continue-as-new: 1000

    signals:
      enabled: true
      buffer-size: 100

    timers:
      enabled: true
      poll-interval: 1s
      batch-size: 50

    child-workflows:
      enabled: true
      max-depth: 5

    compensation:
      enabled: true
      default-policy: STRICT_SEQUENTIAL

    search-attributes:
      enabled: true

    heartbeat:
      enabled: true
      throttle-interval: 5s
```
