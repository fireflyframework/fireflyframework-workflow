# Getting Started

This guide walks through setting up the Firefly Workflow Engine from scratch, creating your first workflow, and understanding the core concepts.

## Prerequisites

- Java 21+
- Spring Boot 3.x application
- Maven 3.9+

## Dependencies

Add `fireflyframework-workflow` to your `pom.xml`:

```xml
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-workflow</artifactId>
    <version>26.02.06</version>
</dependency>
```

This transitively includes `fireflyframework-cache`, `fireflyframework-eda`, and `fireflyframework-eventsourcing`.

## Setting Up the Cache (Required)

The default execution mode uses `fireflyframework-cache` for state persistence. A `CacheAdapter` bean **must** be available for `CacheWorkflowStateStore` and `CacheStepStateStore` to be created. Without it, the workflow engine cannot persist state.

The auto-configuration uses `@ConditionalOnBean(CacheAdapter.class)` on both `workflowStateStore` and `stepStateStore` beans, so if no `CacheAdapter` is present, these beans will not be created and the engine will fail to wire.

### Option 1: Redis Cache

```yaml
firefly:
  cache:
    enabled: true
    redis:
      enabled: true
      host: localhost
      port: 6379
    default-cache-type: redis
```

### Option 2: In-Memory Cache (Development Only)

```yaml
firefly:
  cache:
    enabled: true
    default-cache-type: memory
```

### Verifying Cache Setup

If the workflow engine starts but you see errors about missing `WorkflowStateStore`, confirm that:

1. `firefly.cache.enabled` is `true`
2. A cache provider (Redis or in-memory) is configured
3. The `CacheAdapter` bean appears in your application context

## Enabling the Workflow Engine

The workflow engine is enabled by default when `firefly.workflow.enabled=true` (the default value). The `WorkflowEngineAutoConfiguration` class uses `@ConditionalOnProperty(prefix = "firefly.workflow", name = "enabled", havingValue = "true", matchIfMissing = true)`, so it activates automatically unless explicitly disabled.

```yaml
firefly:
  workflow:
    enabled: true
```

## Creating Your First Workflow

### Annotation-Based Workflow

The `@Workflow` annotation marks a class as a workflow definition. It includes `@Component`, so it is automatically registered as a Spring bean. Methods annotated with `@WorkflowStep` define the individual steps.

```java
import org.fireflyframework.workflow.annotation.*;
import org.fireflyframework.workflow.core.WorkflowContext;
import org.fireflyframework.workflow.model.WorkflowInstance;
import reactor.core.publisher.Mono;

import java.util.Map;

@Workflow(
    id = "order-processing",
    name = "Order Processing Workflow",
    version = "1.0.0"
)
public class OrderProcessingWorkflow {

    @WorkflowStep(id = "validate", name = "Validate Order", order = 1)
    public Mono<Map<String, Object>> validateOrder(WorkflowContext ctx) {
        String orderId = ctx.getInput("orderId", String.class);
        // Validation logic here
        return Mono.just(Map.of("valid", true, "orderId", orderId));
    }

    @WorkflowStep(id = "process", name = "Process Payment", order = 2,
                   dependsOn = {"validate"})
    public Mono<Map<String, Object>> processPayment(WorkflowContext ctx) {
        Map<String, Object> validation = ctx.getStepOutput("validate", Map.class);
        // Payment logic here
        return Mono.just(Map.of("paymentId", "PAY-001", "status", "charged"));
    }

    @WorkflowStep(id = "fulfill", name = "Fulfill Order", order = 3,
                   dependsOn = {"process"})
    public Mono<Map<String, Object>> fulfillOrder(WorkflowContext ctx) {
        // Fulfillment logic here
        return Mono.just(Map.of("trackingNumber", "TRK-12345"));
    }

    @OnWorkflowComplete
    public void onComplete(WorkflowContext ctx, WorkflowInstance instance) {
        System.out.println("Order workflow completed: " + instance.instanceId());
    }

    @OnWorkflowError
    public void onError(WorkflowContext ctx, WorkflowInstance instance, Throwable error) {
        System.err.println("Order workflow failed: " + error.getMessage());
    }
}
```

**`@Workflow` attributes:**

| Attribute | Default | Description |
|-----------|---------|-------------|
| `id` | Class name (kebab-case) | Unique workflow identifier |
| `name` | Simple class name | Human-readable name |
| `description` | `""` | Workflow description |
| `version` | `"1.0.0"` | Version string |
| `triggerMode` | `TriggerMode.BOTH` | `SYNC`, `ASYNC`, or `BOTH` |
| `triggerEventType` | `""` | Event type that starts this workflow |
| `timeoutMs` | `0` (uses config default) | Workflow timeout in ms |
| `maxRetries` | `3` | Default max retries for steps |
| `retryDelayMs` | `1000` | Initial retry delay in ms |
| `publishEvents` | `true` | Publish lifecycle events |

**`@WorkflowStep` attributes:**

| Attribute | Default | Description |
|-----------|---------|-------------|
| `id` | Method name | Unique step identifier within the workflow |
| `name` | Method name | Human-readable name |
| `description` | `""` | Step description |
| `dependsOn` | `{}` | Step IDs this step depends on |
| `order` | `0` | Execution order (lower first, used when no `dependsOn`) |
| `triggerMode` | `StepTriggerMode.BOTH` | `EVENT`, `PROGRAMMATIC`, or `BOTH` |
| `inputEventType` | `""` | Event type that triggers this step |
| `outputEventType` | `""` | Event type published on completion |
| `timeoutMs` | `0` (uses workflow default) | Step timeout in ms |
| `maxRetries` | `-1` (uses workflow default) | Max retries |
| `retryDelayMs` | `-1` (uses workflow default) | Initial retry delay in ms |
| `condition` | `""` | SpEL condition for conditional execution |
| `async` | `false` | Execute asynchronously within its layer |
| `compensatable` | `false` | Supports compensation on rollback |
| `compensationMethod` | `""` | Compensation method name |

Step methods can return `Mono<T>`, `T` (auto-wrapped in Mono), or `void`/`Mono<Void>`.

### StepHandler Interface

For reusable step logic across workflows, implement the `StepHandler<T>` interface:

```java
import org.fireflyframework.workflow.core.StepHandler;
import org.fireflyframework.workflow.core.WorkflowContext;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;

@Component("validateOrderStep")
public class ValidateOrderStepHandler implements StepHandler<ValidationResult> {

    private final OrderValidator validator;

    public ValidateOrderStepHandler(OrderValidator validator) {
        this.validator = validator;
    }

    @Override
    public Mono<ValidationResult> execute(WorkflowContext context) {
        String orderId = context.getInput("orderId", String.class);
        return validator.validate(orderId);
    }

    @Override
    public Mono<Void> compensate(WorkflowContext context) {
        // Optional: undo validation side effects on rollback
        return Mono.empty();
    }

    @Override
    public boolean shouldSkip(WorkflowContext context) {
        // Optional: skip this step conditionally
        return false;
    }
}
```

Reference the bean in a programmatic workflow definition with `handlerBeanName("validateOrderStep")`.

## Starting a Workflow

### Programmatically

Inject `WorkflowEngine` and call `startWorkflow`:

```java
import org.fireflyframework.workflow.core.WorkflowEngine;

@Service
public class OrderService {

    private final WorkflowEngine workflowEngine;

    public OrderService(WorkflowEngine workflowEngine) {
        this.workflowEngine = workflowEngine;
    }

    public Mono<String> placeOrder(String orderId, BigDecimal amount) {
        return workflowEngine.startWorkflow(
                "order-processing",
                Map.of("orderId", orderId, "amount", amount)
        ).map(instance -> instance.instanceId());
    }
}
```

**`startWorkflow` overloads:**

| Signature | Description |
|-----------|-------------|
| `startWorkflow(workflowId, input)` | Basic start, trigger source defaults to `"api"` |
| `startWorkflow(workflowId, input, correlationId, triggeredBy)` | With correlation ID and trigger source |
| `startWorkflow(workflowId, input, correlationId, triggeredBy, dryRun)` | Full options with dry-run mode |

### Via REST API

```
POST /api/v1/workflows/order-processing/start
Content-Type: application/json

{
  "input": {
    "orderId": "ORD-123",
    "amount": 99.99
  },
  "correlationId": "REQ-456",
  "waitForCompletion": false,
  "waitTimeoutMs": 30000,
  "dryRun": false
}
```

Response (201 Created):

```json
{
  "instanceId": "a1b2c3d4-...",
  "workflowId": "order-processing",
  "status": "RUNNING",
  "currentStepId": "validate",
  "completedSteps": 0,
  "totalSteps": 3,
  "createdAt": "2026-02-20T10:00:00Z"
}
```

## Monitoring a Workflow

### Check Status

```java
workflowEngine.getStatus("order-processing", instanceId)
    .subscribe(instance -> {
        System.out.println("Status: " + instance.status());
        System.out.println("Current step: " + instance.currentStepId());
    });
```

### Collect Result

```java
workflowEngine.collectResult("order-processing", instanceId, Map.class)
    .subscribe(result -> System.out.println("Result: " + result));
```

`collectResult` returns `Mono.error(IllegalStateException)` if the workflow is not in a terminal state, failed, or was cancelled.

### REST API

```
GET /api/v1/workflows/order-processing/instances/{instanceId}/status
GET /api/v1/workflows/order-processing/instances/{instanceId}/collect
```

## Programmatic Workflow Definitions

For dynamic or configuration-driven workflows, use `WorkflowDefinition.builder()`:

```java
import org.fireflyframework.workflow.model.*;
import java.time.Duration;

WorkflowDefinition workflow = WorkflowDefinition.builder()
    .workflowId("dynamic-pipeline")
    .name("Dynamic Pipeline")
    .version("1.0.0")
    .triggerMode(TriggerMode.SYNC)
    .timeout(Duration.ofMinutes(30))
    .retryPolicy(new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofMinutes(5), 2.0))
    .addStep(WorkflowStepDefinition.builder()
        .stepId("step-1")
        .name("First Step")
        .order(1)
        .handlerBeanName("firstStepHandler")
        .timeout(Duration.ofMinutes(5))
        .build())
    .addStep(WorkflowStepDefinition.builder()
        .stepId("step-2a")
        .name("Parallel Step A")
        .order(2)
        .dependsOn("step-1")
        .handlerBeanName("parallelStepAHandler")
        .async(true)
        .build())
    .addStep(WorkflowStepDefinition.builder()
        .stepId("step-2b")
        .name("Parallel Step B")
        .order(2)
        .dependsOn("step-1")
        .handlerBeanName("parallelStepBHandler")
        .async(true)
        .build())
    .addStep(WorkflowStepDefinition.builder()
        .stepId("step-3")
        .name("Join Step")
        .order(3)
        .dependsOn("step-2a", "step-2b")
        .handlerBeanName("joinStepHandler")
        .build())
    .build();

workflowEngine.registerWorkflow(workflow);
```

## Step Dependencies and DAG Execution

When steps declare `dependsOn`, the `WorkflowTopology` class builds a DAG and computes execution layers using Kahn's algorithm for topological sorting:

```
Layer 0: [step-1]            -- no dependencies, executes first
Layer 1: [step-2a, step-2b]  -- both depend on step-1, execute in parallel
Layer 2: [step-3]            -- depends on step-2a AND step-2b
```

Steps within the same layer can execute concurrently when marked `async = true`. The topology is validated at registration time to detect missing dependency references and circular dependencies.

If no steps use `dependsOn`, the engine falls back to order-based sequential execution for backward compatibility.

## SpEL Conditions on Steps

Steps can define a `condition` attribute with a SpEL expression. The step executes only when the expression evaluates to `true`. If it evaluates to `false`, the step is skipped.

```java
@WorkflowStep(
    id = "send-premium-notification",
    name = "Send Premium Notification",
    order = 3,
    condition = "#ctx.getInput('customerType') == 'premium'"
)
public Mono<Void> sendPremiumNotification(WorkflowContext ctx) {
    return notificationService.sendPremium(ctx.getInput("customerId", String.class));
}
```

**Available SpEL variables:**

| Variable | Type | Description |
|----------|------|-------------|
| `#ctx` | `WorkflowContext` | The full context object |
| `#input` | `Map<String, Object>` | The workflow input map |
| `#data` | `Map<String, Object>` | The shared context data map |

## Step Trigger Modes

Each step has a `triggerMode` that controls how it can be invoked:

| Mode | Description |
|------|-------------|
| `EVENT` | Triggered by events matching `inputEventType` |
| `PROGRAMMATIC` | Triggered via API or `WorkflowEngine.triggerStep()` |
| `BOTH` | Supports both patterns (default) |

```java
@WorkflowStep(
    id = "wait-for-payment",
    name = "Wait for Payment",
    triggerMode = StepTriggerMode.EVENT,
    inputEventType = "payment.completed",
    outputEventType = "order.payment-received"
)
public Mono<PaymentResult> handlePayment(WorkflowContext ctx) {
    return Mono.just(ctx.getInput("paymentData", PaymentResult.class));
}
```

When `outputEventType` is specified, the engine publishes an event with that type after the step completes, enabling downstream steps or external systems to react.

## Using WorkflowContext

The `WorkflowContext` is passed to every step method and provides access to all workflow data:

```java
@WorkflowStep(id = "process", dependsOn = {"validate"})
public Mono<Map<String, Object>> process(WorkflowContext ctx) {
    // Workflow metadata
    String instanceId = ctx.getInstanceId();
    String workflowId = ctx.getWorkflowId();
    String correlationId = ctx.getCorrelationId();
    boolean isDryRun = ctx.isDryRun();

    // Typed input access
    String orderId = ctx.getInput("orderId", String.class);
    Double amount = ctx.getInput("amount", Double.class);

    // All inputs
    Map<String, Object> allInputs = ctx.getAllInputs();

    // Output from a previous step
    Map<String, Object> prevOutput = ctx.getStepOutput("validate", Map.class);

    // Shared context (read/write between steps)
    ctx.set("processedAt", Instant.now().toString());
    String timestamp = ctx.get("processedAt", String.class);
    String defaultVal = ctx.getOrDefault("missing", "fallback");
    boolean hasKey = ctx.has("processedAt");
    ctx.remove("tempKey");

    return Mono.just(Map.of("processed", true));
}
```

## Lifecycle Callbacks

Add callbacks to respond to workflow and step lifecycle events:

```java
@Workflow(id = "my-workflow")
public class MyWorkflow {

    // Called after each step completes (all steps, or filter by stepIds)
    @OnStepComplete
    public void onStepComplete(WorkflowContext ctx, StepExecution step) {
        System.out.println("Step " + step.stepId() + " completed");
    }

    // Called when the workflow completes successfully
    @OnWorkflowComplete
    public void onComplete(WorkflowContext ctx, WorkflowInstance instance) {
        System.out.println("Workflow " + instance.instanceId() + " completed");
    }

    // Called when the workflow fails (can filter by errorTypes and stepIds)
    @OnWorkflowError(suppressError = false)
    public void onError(WorkflowContext ctx, WorkflowInstance instance, Throwable error) {
        System.err.println("Workflow failed: " + error.getMessage());
    }
}
```

`@OnStepComplete` supports filtering by `stepIds` to only receive callbacks for specific steps. `@OnWorkflowError` supports filtering by `errorTypes` and `stepIds`, and can optionally suppress errors with `suppressError = true`.

## Enabling Durable Execution

For workflows requiring guaranteed completion, crash recovery from an event log, or advanced features like signals, timers, and child workflows, enable durable execution:

```yaml
firefly:
  workflow:
    eventsourcing:
      enabled: true
```

This requires an `EventStore` bean from `fireflyframework-eventsourcing`. When enabled, `EventSourcedWorkflowStateStore` is created with `@Primary`, taking precedence over `CacheWorkflowStateStore`.

For the complete guide, see [Durable Execution](durable-execution.md).

## Next Steps

- [Architecture](architecture.md) -- Internal components and execution model
- [EDA Integration](eda-integration.md) -- Event-driven triggering, step choreography, lifecycle events
- [Configuration](configuration.md) -- Complete property reference with defaults
- [API Reference](api-reference.md) -- REST endpoints and Java API
- [Advanced Features](advanced-features.md) -- Resilience, scheduling, DLQ, dry-run
- [Durable Execution](durable-execution.md) -- Signals, timers, child workflows, compensation
- [Testing](testing.md) -- Unit and integration testing strategies
