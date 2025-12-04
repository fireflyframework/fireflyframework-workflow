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
| `api.documentation-enabled` | boolean | `true` | Enable OpenAPI documentation |

### Example

```yaml
firefly:
  workflow:
    api:
      enabled: true
      base-path: /api/workflows
      documentation-enabled: true
```

## Resilience Configuration

Configuration for Resilience4j patterns.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `resilience.enabled` | boolean | `true` | Enable resilience features |

### Example

```yaml
firefly:
  workflow:
    resilience:
      enabled: true
```

