# Configuration Reference

This document provides a complete reference for all configuration options available in the Firefly Workflow Engine.

## Configuration Prefix

All configuration properties use the prefix `firefly.workflow`.

## Core Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `true` | Enable/disable the workflow engine |
| `default-timeout` | Duration | `PT1H` | Default timeout for workflow execution |
| `default-step-timeout` | Duration | `PT5M` | Default timeout for step execution |
| `metrics-enabled` | boolean | `true` | Enable Micrometer metrics |
| `health-enabled` | boolean | `true` | Enable health indicator |

### Example

```yaml
firefly:
  workflow:
    enabled: true
    default-timeout: PT1H
    default-step-timeout: PT5M
    metrics-enabled: true
    health-enabled: true
```

## State Configuration

Configuration for workflow and step state persistence.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `state.enabled` | boolean | `true` | Enable state persistence |
| `state.default-ttl` | Duration | `P7D` | TTL for workflow instance state |
| `state.completed-ttl` | Duration | `P1D` | TTL for completed workflow state |
| `state.key-prefix` | String | `workflow` | Prefix for cache keys |
| `state.compression-enabled` | boolean | `false` | Enable state compression |

### Cache Key Patterns

- Workflow instance: `{key-prefix}:{workflowId}:{instanceId}`
- Workflow state: `{key-prefix}:state:{workflowId}:{instanceId}`
- Step state: `{key-prefix}:step:{workflowId}:{instanceId}:{stepId}`

### Example

```yaml
firefly:
  workflow:
    state:
      enabled: true
      default-ttl: P7D
      completed-ttl: P1D
      key-prefix: workflow
      compression-enabled: false
```

## Event Configuration

Configuration for workflow event publishing.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `events.enabled` | boolean | `true` | Enable event publishing |
| `events.publisher-type` | PublisherType | `AUTO` | Event publisher type |
| `events.connection-id` | String | `default` | Connection ID for publisher |
| `events.default-destination` | String | `workflow-events` | Default event destination |
| `events.event-type-prefix` | String | `workflow` | Prefix for event types |
| `events.publish-step-events` | boolean | `true` | Publish step lifecycle events |
| `events.include-context` | boolean | `false` | Include context in events |
| `events.include-output` | boolean | `true` | Include step output in events |

### Publisher Types

| Type | Description |
|------|-------------|
| `AUTO` | Auto-detect based on available dependencies |
| `APPLICATION_EVENT` | Spring ApplicationEvent (in-memory) |
| `KAFKA` | Apache Kafka |
| `RABBITMQ` | RabbitMQ |
| `NOOP` | No-op publisher (disabled) |

### Example

```yaml
firefly:
  workflow:
    events:
      enabled: true
      publisher-type: AUTO
      connection-id: default
      default-destination: workflow-events
      event-type-prefix: workflow
      publish-step-events: true
      include-context: false
      include-output: true
```

## Retry Configuration

Configuration for step retry behavior.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `retry.max-attempts` | int | `3` | Maximum retry attempts |
| `retry.initial-delay` | Duration | `PT1S` | Initial delay before first retry |
| `retry.max-delay` | Duration | `PT5M` | Maximum delay between retries |
| `retry.multiplier` | double | `2.0` | Multiplier for exponential backoff |

### Example

```yaml
firefly:
  workflow:
    retry:
      max-attempts: 3
      initial-delay: PT1S
      max-delay: PT5M
      multiplier: 2.0
```

## API Configuration

Configuration for the REST API.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `api.enabled` | boolean | `true` | Enable REST API |
| `api.base-path` | String | `/api/workflows` | Base path for endpoints |

## Scheduling Configuration

Configuration for cron-based workflow scheduling.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `scheduling.enabled` | boolean | `true` | Enable scheduled workflows |
| `scheduling.pool-size` | int | `5` | Thread pool size for scheduler |
| `scheduling.thread-name-prefix` | String | `workflow-scheduler-` | Thread name prefix |
| `scheduling.wait-for-tasks-to-complete-on-shutdown` | boolean | `true` | Wait for tasks on shutdown |
| `scheduling.await-termination-seconds` | int | `30` | Timeout for shutdown wait |

### Example

```yaml
firefly:
  workflow:
    scheduling:
      enabled: true
      pool-size: 5
      thread-name-prefix: workflow-scheduler-
      wait-for-tasks-to-complete-on-shutdown: true
      await-termination-seconds: 30
```

## Dead Letter Queue (DLQ) Configuration

Configuration for failed workflow management.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `dlq.enabled` | boolean | `true` | Enable DLQ |
| `dlq.max-replay-attempts` | int | `3` | Max replay attempts |
| `dlq.retention-period` | Duration | `P30D` | Retention period for DLQ entries |
| `dlq.auto-save-on-failure` | boolean | `true` | Auto-save failed workflows to DLQ |
| `dlq.include-stack-trace` | boolean | `true` | Include stack traces in entries |

### Example

```yaml
firefly:
  workflow:
    dlq:
      enabled: true
      max-replay-attempts: 3
      retention-period: P30D
      auto-save-on-failure: true
      include-stack-trace: true
```

## Resilience Configuration

### Circuit Breaker Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `resilience.circuit-breaker.enabled` | boolean | `true` | Enable circuit breaker |
| `resilience.circuit-breaker.failure-rate-threshold` | int | `50` | Failure rate % to open circuit |
| `resilience.circuit-breaker.slow-call-rate-threshold` | int | `100` | Slow call rate % to open circuit |
| `resilience.circuit-breaker.slow-call-duration-threshold` | Duration | `PT60S` | Duration to consider slow |
| `resilience.circuit-breaker.permitted-number-of-calls-in-half-open-state` | int | `10` | Calls allowed in half-open |
| `resilience.circuit-breaker.minimum-number-of-calls` | int | `10` | Min calls before calculating rate |
| `resilience.circuit-breaker.sliding-window-type` | String | `COUNT_BASED` | `COUNT_BASED` or `TIME_BASED` |
| `resilience.circuit-breaker.sliding-window-size` | int | `100` | Window size (calls or seconds) |
| `resilience.circuit-breaker.wait-duration-in-open-state` | Duration | `PT60S` | Wait before half-open |
| `resilience.circuit-breaker.automatic-transition-from-open-to-half-open-enabled` | boolean | `true` | Auto transition |
| `resilience.circuit-breaker.record-exceptions` | String[] | `[]` | Exceptions to record as failures |
| `resilience.circuit-breaker.ignore-exceptions` | String[] | `[]` | Exceptions to ignore |

### Rate Limiter Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `resilience.rate-limiter.enabled` | boolean | `false` | Enable rate limiter |
| `resilience.rate-limiter.limit-for-period` | int | `50` | Calls per period |
| `resilience.rate-limiter.limit-refresh-period` | Duration | `PT1S` | Period duration |
| `resilience.rate-limiter.timeout-duration` | Duration | `PT5S` | Wait timeout for permit |

### Bulkhead Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `resilience.bulkhead.enabled` | boolean | `false` | Enable bulkhead |
| `resilience.bulkhead.max-concurrent-calls` | int | `25` | Max concurrent calls |
| `resilience.bulkhead.max-wait-duration` | Duration | `PT0S` | Wait duration for permit |

### Time Limiter Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `resilience.time-limiter.enabled` | boolean | `true` | Enable time limiter |
| `resilience.time-limiter.timeout-duration` | Duration | `PT5M` | Timeout duration |
| `resilience.time-limiter.cancel-running-future` | boolean | `true` | Cancel on timeout |

### Complete Resilience Example

```yaml
firefly:
  workflow:
    resilience:
      enabled: true
      circuit-breaker:
        enabled: true
        failure-rate-threshold: 50
        slow-call-rate-threshold: 100
        slow-call-duration-threshold: PT60S
        permitted-number-of-calls-in-half-open-state: 10
        minimum-number-of-calls: 10
        sliding-window-type: COUNT_BASED
        sliding-window-size: 100
        wait-duration-in-open-state: PT60S
        automatic-transition-from-open-to-half-open-enabled: true
      rate-limiter:
        enabled: false
        limit-for-period: 50
        limit-refresh-period: PT1S
        timeout-duration: PT5S
      bulkhead:
        enabled: false
        max-concurrent-calls: 25
        max-wait-duration: PT0S
      time-limiter:
        enabled: true
        timeout-duration: PT5M
        cancel-running-future: true
```

## Duration Format

All duration properties use ISO-8601 duration format:

| Format | Description | Example |
|--------|-------------|---------|
| `PTnS` | n seconds | `PT30S` = 30 seconds |
| `PTnM` | n minutes | `PT5M` = 5 minutes |
| `PTnH` | n hours | `PT1H` = 1 hour |
| `PnD` | n days | `P7D` = 7 days |

Combined formats are also supported: `PT1H30M` = 1 hour 30 minutes

## Complete Configuration Example

```yaml
firefly:
  workflow:
    enabled: true
    default-timeout: PT1H
    default-step-timeout: PT5M
    metrics-enabled: true
    health-enabled: true

    state:
      enabled: true
      default-ttl: P7D
      completed-ttl: P1D
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
      initial-delay: PT1S
      max-delay: PT5M
      multiplier: 2.0

    api:
      enabled: true
      base-path: /api/workflows
      documentation-enabled: true

    resilience:
      enabled: true
      circuit-breaker:
        enabled: true
        failure-rate-threshold: 50
      rate-limiter:
        enabled: false
      bulkhead:
        enabled: false
      time-limiter:
        enabled: true
        timeout-duration: PT5M
```

## Annotation Configuration

Configuration can also be specified at the annotation level.

### @Workflow Annotation

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `id` | String | Class name | Unique workflow identifier |
| `name` | String | id | Human-readable name |
| `description` | String | `""` | Workflow description |
| `version` | String | `"1.0.0"` | Version number |
| `triggerMode` | TriggerMode | `BOTH` | `SYNC`, `ASYNC`, or `BOTH` |
| `triggerEventType` | String | `""` | Event type for async trigger |
| `timeoutMs` | long | `0` | Timeout in ms (0 = use config) |
| `maxRetries` | int | `3` | Default retry count for steps |
| `retryDelayMs` | long | `1000` | Default retry delay in ms |
| `publishEvents` | boolean | `true` | Publish lifecycle events |

### @WorkflowStep Annotation

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `id` | String | Method name | Unique step identifier |
| `name` | String | id | Human-readable name |
| `description` | String | `""` | Step description |
| `dependsOn` | String[] | `{}` | **Recommended**: Step IDs this step depends on |
| `triggerMode` | StepTriggerMode | `BOTH` | How step can be invoked (EVENT, PROGRAMMATIC, BOTH) |
| `order` | int | `0` | Execution order (legacy, prefer `dependsOn`) |
| `async` | boolean | `false` | Execute in parallel within layer |
| `timeoutMs` | long | `0` | Timeout (0 = inherit) |
| `maxRetries` | int | `-1` | Retry attempts (-1 = inherit) |
| `retryDelayMs` | long | `-1` | Retry delay (-1 = inherit) |
| `condition` | String | `""` | SpEL condition |
| `inputEventType` | String | `""` | Event to trigger step |
| `outputEventType` | String | `""` | Event to emit on completion |
| `compensatable` | boolean | `false` | Reserved for saga |
| `compensationMethod` | String | `""` | Reserved for saga |

### StepTriggerMode Enum

| Value | Description |
|-------|-------------|
| `EVENT` | Step is triggered by events only (recommended for choreography) |
| `PROGRAMMATIC` | Step is invoked via API only |
| `BOTH` | Supports both patterns (default) |

### Dependency Management

The `dependsOn` attribute is the **recommended approach** for controlling step execution order:

```java
// Root step (no dependencies)
@WorkflowStep(id = "validate")

// Step with single dependency
@WorkflowStep(id = "process", dependsOn = {"validate"})

// Step with multiple dependencies (waits for all)
@WorkflowStep(id = "ship", dependsOn = {"check-inventory", "process-payment"})
```

**Validation:**
- All referenced step IDs must exist in the workflow
- Circular dependencies are not allowed
- Validation errors throw `WorkflowValidationException`

## Next Steps

- [Getting Started](getting-started.md) - Basic tutorial
- [Architecture](architecture.md) - System design
- [Advanced Features](advanced-features.md) - Resilience4j, choreography, and more
- [API Reference](api-reference.md) - REST and Java API documentation