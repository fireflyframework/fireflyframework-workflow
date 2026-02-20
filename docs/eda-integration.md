# Event-Driven Architecture (EDA) Integration

The Firefly Workflow Engine integrates with `fireflyframework-eda` for bidirectional event communication. Workflows can be **triggered by external events** arriving from Kafka or RabbitMQ, and they **publish lifecycle events** back to the message broker. Steps can form **choreography chains** where one step's output event triggers the next step's input.

## How It Works

```
External Systems                    Workflow Engine                     Message Broker
       │                                  │                                  │
       │  Kafka/RabbitMQ message          │                                  │
       ├─────────────────────────────────▶│                                  │
       │                                  │  WorkflowEventListener           │
       │                                  │  (4-phase processing)            │
       │                                  │                                  │
       │                                  │  1. Resume WAITING workflows     │
       │                                  │  2. Resume waiting steps         │
       │                                  │  3. Trigger steps by inputEvent  │
       │                                  │  4. Trigger workflows by event   │
       │                                  │                                  │
       │                                  │  WorkflowEventPublisher          │
       │                                  │──────────────────────────────────▶│
       │                                  │  Lifecycle events published      │
       │                                  │  Step output events published    │
       │                                  │                                  │
```

Two components handle the two directions:

| Component | Direction | Purpose |
|-----------|-----------|---------|
| `WorkflowEventListener` | Inbound | Receives events from the broker (via Spring `@EventListener`) and triggers workflows or steps |
| `WorkflowEventPublisher` | Outbound | Publishes workflow and step lifecycle events to the broker |

## Triggering Workflows from Events

### Annotation Configuration

Use `triggerEventType` on `@Workflow` to specify which event type starts the workflow:

```java
@Workflow(
    id = "order-processing",
    name = "Order Processing",
    triggerMode = TriggerMode.BOTH,         // REST API + events (default)
    triggerEventType = "order.created",     // glob pattern
    publishEvents = true
)
public class OrderProcessingWorkflow {

    @WorkflowStep(id = "validate", order = 1)
    public Mono<Map<String, Object>> validate(WorkflowContext ctx) {
        // Event payload is available as workflow input
        String orderId = ctx.getInput("orderId", String.class);
        return Mono.just(Map.of("valid", true, "orderId", orderId));
    }
}
```

When an event with type `"order.created"` arrives from Kafka or RabbitMQ, the `WorkflowEventListener` automatically starts a new instance of this workflow.

### TriggerMode

The `TriggerMode` enum controls how a workflow can be started:

| Mode | REST API | Events | Use Case |
|------|----------|--------|----------|
| `SYNC` | Yes | No | API-only workflows |
| `ASYNC` | No | Yes | Event-only workflows |
| `BOTH` | Yes | Yes | Either path (default) |

Only workflows with `TriggerMode.ASYNC` or `TriggerMode.BOTH` are eligible for event-based triggering. The `WorkflowRegistry` checks `supportsAsyncTrigger()` before allowing event triggers.

### Glob Pattern Matching

The `triggerEventType` attribute supports glob-style patterns for matching event types:

| Pattern | Matches | Does Not Match |
|---------|---------|----------------|
| `order.created` | `order.created` (exact) | `order.updated` |
| `order.*` | `order.created`, `order.updated`, `order.deleted` | `payment.created` |
| `*.created` | `order.created`, `payment.created` | `order.updated` |
| `order.?` | `order.X` (single char) | `order.created` |

Patterns are compiled to regular expressions at workflow registration time and cached in the `WorkflowRegistry`. The compilation rules: `.` is escaped to `\.`, `*` becomes `.*`, `?` becomes `.`, and the result is anchored with `^...$`.

### Event-to-Input Mapping

When a workflow is triggered by an event, the `WorkflowEventListener` builds the workflow input from the `EventEnvelope`:

```java
// The input map contains:
{
    "_eventType": "order.created",          // Event type
    "_eventDestination": "orders",          // Source topic/queue
    "_eventTimestamp": "2026-02-20T...",     // Event timestamp

    // Event payload fields merged directly (if payload is a Map)
    "orderId": "ORD-123",
    "customerId": "CUST-456",
    "amount": 99.99,

    // Event headers prefixed with "header_"
    "header_correlationId": "abc-123",
    "header_source": "order-service"
}
```

If the event payload is not a `Map`, it is placed under the `"payload"` key instead.

### Correlation ID Extraction

The listener extracts a correlation ID from the event in this priority order:

1. `envelope.transactionId()`
2. `envelope.headers().get("correlationId")`
3. `envelope.metadata().correlationId()`

The correlation ID is passed to `startWorkflow()` for tracking across systems.

## Step Choreography

Steps can form event-driven chains using `inputEventType` and `outputEventType`. When a step completes, its `outputEventType` is published to the broker. A downstream step with a matching `inputEventType` is then triggered.

### Defining a Choreography Chain

```java
@Workflow(
    id = "event-driven-order",
    triggerMode = TriggerMode.ASYNC,
    triggerEventType = "order.created"
)
public class EventDrivenOrderWorkflow {

    @WorkflowStep(
        id = "validateOrder",
        order = 1,
        outputEventType = "order.validated"       // Published on completion
    )
    public Mono<Map<String, Object>> validateOrder(WorkflowContext ctx) {
        String orderId = ctx.getInput("orderId", String.class);
        return Mono.just(Map.of("orderId", orderId, "valid", true));
    }

    @WorkflowStep(
        id = "processPayment",
        order = 2,
        inputEventType = "order.validated",       // Triggered by this event
        outputEventType = "payment.processed"     // Published on completion
    )
    public Mono<Map<String, Object>> processPayment(WorkflowContext ctx) {
        return Mono.just(Map.of("paymentId", "PAY-001", "charged", true));
    }

    @WorkflowStep(
        id = "shipOrder",
        order = 3,
        inputEventType = "payment.processed"      // Triggered by this event
    )
    public Mono<Map<String, Object>> shipOrder(WorkflowContext ctx) {
        return Mono.just(Map.of("trackingNumber", "TRK-12345"));
    }
}
```

### How It Flows

```
External event "order.created"
    │
    ▼
WorkflowEventListener
    │  Matches triggerEventType="order.created"
    ▼
Start workflow, execute "validateOrder"
    │  Step completes → publishes "order.validated"
    ▼
WorkflowEventPublisher.publishCustomEvent("order.validated", output, headers)
    │  Published to Kafka topic "workflow-events"
    ▼
WorkflowEventListener receives "order.validated"
    │  Matches inputEventType="order.validated" on step "processPayment"
    ▼
Execute "processPayment"
    │  Step completes → publishes "payment.processed"
    ▼
WorkflowEventListener receives "payment.processed"
    │  Matches inputEventType="payment.processed" on step "shipOrder"
    ▼
Execute "shipOrder"
    │  No outputEventType → workflow completes
```

### Step Output Event Format

When a step with `outputEventType` completes, the `WorkflowExecutor` publishes the step's output value as the event payload with these headers:

```
Headers:
  workflowId:    "event-driven-order"
  instanceId:    "a1b2c3d4-..."
  stepId:        "validateOrder"
  correlationId: "abc-123"       (if present on the workflow instance)
```

The event is published to the configured `default-destination` (default: `"workflow-events"`).

### Step-Level vs Workflow-Level Matching

| Matching Type | Where | Pattern Type | Example |
|--------------|-------|--------------|---------|
| Workflow trigger | `@Workflow(triggerEventType=...)` | Glob (regex) | `order.*` |
| Step input | `@WorkflowStep(inputEventType=...)` | Exact string | `order.validated` |

Workflow-level `triggerEventType` supports glob patterns. Step-level `inputEventType` uses **exact string equality** (no glob matching).

### StepTriggerMode

Each step has a `triggerMode` that controls whether it can be triggered by events:

| Mode | Event Trigger | API Trigger | Description |
|------|--------------|-------------|-------------|
| `EVENT` | Yes | No | Only triggered by `inputEventType` events |
| `PROGRAMMATIC` | No | Yes | Only triggered via API or `WorkflowEngine.triggerStep()` |
| `BOTH` | Yes | Yes | Supports either (default) |

```java
@WorkflowStep(
    id = "wait-for-payment",
    triggerMode = StepTriggerMode.EVENT,
    inputEventType = "payment.completed"
)
public Mono<PaymentResult> handlePayment(WorkflowContext ctx) {
    return Mono.just(ctx.getInput("paymentData", PaymentResult.class));
}
```

## Publishing Lifecycle Events

The `WorkflowEventPublisher` publishes events at each workflow and step lifecycle transition.

### Published Event Types

**Workflow events:**

| Factory Method | Event Type String | When Published |
|---------------|-------------------|----------------|
| `WorkflowEvent.workflowStarted(instance)` | `workflow.started` | Workflow begins execution |
| `WorkflowEvent.workflowCompleted(instance)` | `workflow.completed` | Workflow finishes successfully |
| `WorkflowEvent.workflowFailed(instance)` | `workflow.failed` | Workflow fails |
| `WorkflowEvent.workflowCancelled(instance)` | `workflow.cancelled` | Workflow is cancelled |
| `WorkflowEvent.workflowSuspended(instance, reason)` | `workflow.suspended` | Workflow is suspended |
| `WorkflowEvent.workflowResumed(instance)` | `workflow.resumed` | Workflow resumes |

**Step events** (gated by `events.publish-step-events`):

| Factory Method | Event Type String | When Published |
|---------------|-------------------|----------------|
| `WorkflowEvent.stepStarted(instance, step)` | `workflow.step.started` | Step begins execution |
| `WorkflowEvent.stepCompleted(instance, step)` | `workflow.step.completed` | Step finishes |
| `WorkflowEvent.stepFailed(instance, step)` | `workflow.step.failed` | Step fails |
| `WorkflowEvent.stepRetrying(instance, step)` | `workflow.step.retrying` | Step retrying |

### WorkflowEvent Record

All events are published as `WorkflowEvent` records:

```java
public record WorkflowEvent(
    WorkflowEventType eventType,     // Enum: WORKFLOW_STARTED, STEP_COMPLETED, etc.
    String workflowId,               // Workflow definition ID
    String instanceId,               // Instance ID
    String correlationId,            // Correlation ID (nullable)
    String stepId,                   // Step ID (null for workflow-level events)
    Object payload,                  // Step output or workflow input/output
    ErrorInfo error,                 // Error details (null if no error)
    Map<String, Object> metadata,    // Additional context (duration, attempt, etc.)
    Instant timestamp                // Event timestamp
)
```

### Message Headers

Each published event includes these message headers:

| Header | Value | Example |
|--------|-------|---------|
| `eventType` | Dot-notation event type | `workflow.step.completed` |
| `workflowId` | Workflow definition ID | `order-processing` |
| `instanceId` | Workflow instance ID | `a1b2c3d4-...` |
| `source` | Fixed value | `workflow-engine` |
| `correlationId` | Correlation ID (if present) | `abc-123` |
| `stepId` | Step ID (if step event) | `validate` |

### Error Handling

Publishing failures are silently swallowed to prevent event publishing from failing the workflow:

```java
return publisher.publish(event, destination, headers)
    .onErrorResume(e -> Mono.empty());
```

This means event publishing is **best-effort**: a Kafka outage will not cause workflows to fail, but events may be lost during the outage.

## The WorkflowEventListener (4-Phase Processing)

When an `EventEnvelope` arrives (bridged from Kafka/RabbitMQ via the `fireflyframework-eda` Spring `ApplicationEvent` mechanism), the `WorkflowEventListener` processes it in four sequential phases:

### Phase 1: Resume WAITING Workflows

Loads all workflows with `WorkflowStatus.WAITING` and checks if the event should resume them:

- **Correlation ID match**: The event's correlation ID matches the instance's correlation ID
- **Waiting-for-event match**: The instance context has `_waitingForEvent` that glob-matches the event type

On match, the event type is stored as `_resumeEvent` and the payload as `_resumeEventData` in the instance context, and the workflow resumes from `WAITING` to `RUNNING`.

### Phase 2: Resume Waiting Steps

Only runs if `StepStateStore` is available. Calls `stepStateStore.findStepsWaitingForEvent(eventType)` to find steps whose `waitingForEvent` field matches. Each match is resumed via `workflowEngine.triggerStep()`.

### Phase 3: Trigger Steps by Input Event

Calls `workflowEngine.findStepsByInputEvent(eventType)` which scans all registered workflows for steps with a matching `inputEventType` (exact string equality).

For each match:
1. If a correlation ID exists in the event, it looks for an existing non-terminal instance by correlation ID and calls `triggerStep()` on it
2. If no instance is found (or no correlation ID), it starts a new workflow instance if the workflow `supportsAsyncTrigger()`

### Phase 4: Trigger Workflows by Event

Calls `workflowEngine.findWorkflowsByTriggerEvent(eventType)` which delegates to `WorkflowRegistry.findByTriggerEvent()`. This uses the precompiled glob patterns to match and filters by `supportsAsyncTrigger()`.

For each matching workflow, `startWorkflow()` is called with:
- Input built from the event envelope
- Correlation ID extracted from the event
- `triggeredBy` set to `"event:<eventType>"`

### Error Handling

All errors in the listener are swallowed at the top level:

```java
.onErrorResume(e -> Mono.empty())
```

This prevents a bad event from crashing the listener. Errors are logged but do not propagate.

## Configuration

### Enabling EDA Integration

EDA integration requires both the `fireflyframework-eda` module configured and the workflow event properties set:

```yaml
# Step 1: Configure the message broker (fireflyframework-eda)
firefly:
  eda:
    enabled: true
    publishers:
      kafka:
        default:
          enabled: true
          bootstrap-servers: localhost:9092

# Step 2: Configure the workflow event properties
firefly:
  workflow:
    enabled: true
    events:
      enabled: true                      # Publish lifecycle events (default: true)
      publisher-type: AUTO               # AUTO, KAFKA, RABBITMQ, APPLICATION_EVENT
      connection-id: default             # Named connection from eda config
      default-destination: workflow-events  # Kafka topic name (default)
      event-type-prefix: workflow        # Prefix for event type strings
      publish-step-events: true          # Include step-level events (default: true)
      include-context: false             # Include full workflow context (default: false)
      include-output: true               # Include step/workflow output (default: true)
      listen-enabled: true               # Enable WorkflowEventListener (default: true)
```

### Auto-Configuration Beans

| Bean | Condition | Effect |
|------|-----------|--------|
| `WorkflowEventPublisher` | `EventPublisherFactory` bean present | Publishes lifecycle events to broker |
| `WorkflowEventListener` | `events.listen-enabled=true` (default) | Receives events and triggers workflows/steps |
| `StepStateStore` | `CacheAdapter` present + `step-state.enabled=true` | Enables step-level event handling (Phase 2) |

The `WorkflowEventPublisher` requires `EventPublisherFactory` from `fireflyframework-eda`. If the EDA module is not configured, the publisher bean is not created, and the engine runs without event publishing.

The `WorkflowEventListener` is enabled by default. Set `events.listen-enabled=false` to disable it (useful in services that only publish events but don't consume them).

### Publisher Type Resolution

When `publisher-type` is `AUTO` (the default), the `EventPublisherFactory` selects the appropriate publisher based on which broker is configured:

| Configured Broker | Publisher Selected |
|-------------------|--------------------|
| Kafka only | Kafka publisher |
| RabbitMQ only | RabbitMQ publisher |
| Both | Kafka publisher (preferred) |
| Neither | `APPLICATION_EVENT` (Spring events only, no external broker) |

## Complete Example: Event-Driven Order Pipeline

This example shows a workflow that is triggered by an `order.created` event from Kafka, processes the order through validation, payment, and fulfillment steps using choreography, and publishes lifecycle events back to Kafka.

### Workflow Definition

```java
@Workflow(
    id = "order-pipeline",
    name = "Event-Driven Order Pipeline",
    triggerMode = TriggerMode.ASYNC,
    triggerEventType = "order.created",
    publishEvents = true
)
public class OrderPipelineWorkflow {

    @WorkflowStep(
        id = "validate",
        order = 1,
        outputEventType = "order.validated"
    )
    public Mono<Map<String, Object>> validate(WorkflowContext ctx) {
        String orderId = ctx.getInput("orderId", String.class);
        BigDecimal amount = ctx.getInput("amount", BigDecimal.class);

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return Mono.error(new IllegalArgumentException("Invalid amount"));
        }

        return Mono.just(Map.of(
            "orderId", orderId,
            "amount", amount,
            "validatedAt", Instant.now().toString()
        ));
    }

    @WorkflowStep(
        id = "charge",
        order = 2,
        inputEventType = "order.validated",
        outputEventType = "payment.charged"
    )
    public Mono<Map<String, Object>> charge(WorkflowContext ctx) {
        String orderId = ctx.getInput("orderId", String.class);
        return paymentService.charge(orderId)
            .map(receipt -> Map.of(
                "paymentId", receipt.id(),
                "status", "charged"
            ));
    }

    @WorkflowStep(
        id = "fulfill",
        order = 3,
        inputEventType = "payment.charged"
    )
    public Mono<Map<String, Object>> fulfill(WorkflowContext ctx) {
        String orderId = ctx.getInput("orderId", String.class);
        return fulfillmentService.ship(orderId)
            .map(shipment -> Map.of(
                "trackingNumber", shipment.trackingNumber()
            ));
    }

    @OnWorkflowComplete
    public void onComplete(WorkflowContext ctx, WorkflowInstance instance) {
        log.info("Order pipeline completed: {}", instance.instanceId());
    }
}
```

### Configuration

```yaml
firefly:
  cache:
    enabled: true
    redis:
      enabled: true
      host: localhost
      port: 6379
    default-cache-type: redis

  eda:
    enabled: true
    publishers:
      kafka:
        default:
          enabled: true
          bootstrap-servers: localhost:9092

  workflow:
    enabled: true
    events:
      enabled: true
      publisher-type: KAFKA
      connection-id: default
      default-destination: workflow-events
      publish-step-events: true
      listen-enabled: true
    state:
      default-ttl: 7d
```

### Event Flow Sequence

1. An external service publishes `order.created` to Kafka
2. `fireflyframework-eda` consumer receives it and bridges to Spring `ApplicationEvent`
3. `WorkflowEventListener.handleEvent()` receives the `EventEnvelope`
4. Phase 4 matches `triggerEventType="order.created"` on `order-pipeline`
5. `startWorkflow()` is called with event payload as input, `triggeredBy="event:order.created"`
6. Step `validate` executes and completes
7. `WorkflowEventPublisher` publishes `workflow.step.completed` (lifecycle event)
8. `WorkflowExecutor.publishStepOutputEvent()` publishes `order.validated` (choreography event)
9. `WorkflowEventListener` receives `order.validated`
10. Phase 3 matches `inputEventType="order.validated"` on step `charge`
11. Step `charge` executes, publishes `payment.charged`
12. Step `fulfill` executes (triggered by `payment.charged`)
13. `WorkflowEventPublisher` publishes `workflow.completed`

## Next Steps

- [Getting Started](getting-started.md) -- Prerequisites and first workflow
- [Architecture](architecture.md) -- Internal components and execution model
- [Configuration](configuration.md) -- Complete property reference
- [Advanced Features](advanced-features.md) -- DAG execution, resilience, scheduling
- [API Reference](api-reference.md) -- REST and Java API
- [Testing](testing.md) -- Unit and integration testing
