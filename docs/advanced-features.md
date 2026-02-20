# Advanced Features

This guide covers advanced features of the Firefly Workflow Engine including step dependencies, DAG execution, parallel steps, SpEL conditions, Resilience4j integration, scheduled workflows, Dead Letter Queue, crash recovery, dry-run mode, and suspension/resumption.

## Step Dependencies and DAG Execution

### dependsOn Attribute

The `dependsOn` attribute on `@WorkflowStep` declares explicit step dependencies. The `WorkflowTopology` class builds a directed acyclic graph (DAG) and computes execution layers using Kahn's algorithm for topological sorting.

```java
@Workflow(id = "order-fulfillment")
public class OrderFulfillmentWorkflow {

    @WorkflowStep(id = "validate")
    public Mono<Map<String, Object>> validate(WorkflowContext ctx) {
        return Mono.just(Map.of("valid", true));
    }

    @WorkflowStep(id = "check-inventory", dependsOn = {"validate"})
    public Mono<Map<String, Object>> checkInventory(WorkflowContext ctx) {
        return Mono.just(Map.of("inStock", true));
    }

    @WorkflowStep(id = "process-payment", dependsOn = {"validate"})
    public Mono<Map<String, Object>> processPayment(WorkflowContext ctx) {
        return Mono.just(Map.of("paid", true));
    }

    @WorkflowStep(id = "ship", dependsOn = {"check-inventory", "process-payment"})
    public Mono<Map<String, Object>> ship(WorkflowContext ctx) {
        return Mono.just(Map.of("shipped", true));
    }
}
```

### Execution Layers

Steps are organized into execution layers based on their dependencies:

| Layer | Steps | Description |
|-------|-------|-------------|
| 0 | `validate` | Root steps (no dependencies) |
| 1 | `check-inventory`, `process-payment` | Steps depending only on Layer 0 |
| 2 | `ship` | Steps depending on Layer 1 |

Steps in the same layer can execute in parallel when marked `async = true`.

### DAG Validation

`WorkflowTopology` validates dependencies at registration time:

- **Missing dependencies**: All referenced step IDs must exist in the workflow. A reference to a nonexistent step throws `WorkflowValidationException`.
- **Circular dependencies**: Cycles in the dependency graph are detected during topological sorting and cause a `WorkflowValidationException`.

### Fallback to Order-Based Execution

If no steps in a workflow use `dependsOn`, the topology falls back to order-based sequential execution using the `order` attribute for backward compatibility. When both `dependsOn` and `order` are present on a step, dependencies take precedence.

### Topology Visualization

The topology endpoint returns nodes, edges, and layer metadata for frontend visualization:

```http
GET /api/v1/workflows/order-fulfillment/topology
```

The response includes `nodes` (steps with layer assignments), `edges` (dependency relationships), and `metadata` (total steps, max parallelism, layer count).

---

## Async Parallel Steps

Steps in the same dependency layer execute in parallel when marked `async = true`.

```java
@Workflow(id = "parallel-workflow")
public class ParallelWorkflow {

    @WorkflowStep(id = "fetch-user", async = true)
    public Mono<User> fetchUser(WorkflowContext ctx) {
        return userService.findById(ctx.getInput("userId", String.class));
    }

    @WorkflowStep(id = "fetch-products", async = true)
    public Mono<List<Product>> fetchProducts(WorkflowContext ctx) {
        return productService.findAll();
    }

    @WorkflowStep(id = "fetch-inventory", async = true)
    public Mono<Inventory> fetchInventory(WorkflowContext ctx) {
        return inventoryService.getAvailable();
    }

    @WorkflowStep(
        id = "create-order",
        dependsOn = {"fetch-user", "fetch-products", "fetch-inventory"}
    )
    public Mono<Order> createOrder(WorkflowContext ctx) {
        User user = ctx.getStepOutput("fetch-user", User.class);
        List<Product> products = ctx.getStepOutput("fetch-products", List.class);
        Inventory inventory = ctx.getStepOutput("fetch-inventory", Inventory.class);
        return orderService.create(user, products, inventory);
    }
}
```

This produces the following execution plan:

| Layer | Steps | Execution |
|-------|-------|-----------|
| 0 | `fetch-user`, `fetch-products`, `fetch-inventory` | Parallel (all `async = true`) |
| 1 | `create-order` | Sequential (waits for all Layer 0 steps) |

Outputs from parallel steps are available via `ctx.getStepOutput()`. If any parallel step fails, the workflow fails.

---

## SpEL Conditions

Steps can define a `condition` attribute with a Spring Expression Language (SpEL) expression. The step executes only when the expression evaluates to `true`. If it evaluates to `false`, the step is skipped.

### Available SpEL Variables

| Variable | Type | Description |
|----------|------|-------------|
| `#ctx` | `WorkflowContext` | The full context object |
| `#input` | `Map<String, Object>` | The workflow input map |
| `#data` | `Map<String, Object>` | The shared context data map |

### Examples

```java
// Execute only for premium customers
@WorkflowStep(
    id = "premium-processing",
    condition = "#input['tier'] == 'premium'"
)

// Skip if amount is zero or negative
@WorkflowStep(
    id = "process-payment",
    condition = "#input['amount'] > 0"
)

// Check shared context data set by a previous step
@WorkflowStep(
    id = "send-notification",
    condition = "#data['validated'] == true"
)

// Use context methods
@WorkflowStep(
    id = "optional-step",
    condition = "#ctx.has('skipOptional') ? !#ctx.get('skipOptional', Boolean.class) : true"
)
```

### Setting Context Data for Conditions

Set data in one step for use in a condition on a subsequent step:

```java
@WorkflowStep(id = "validate", order = 1)
public Mono<Map<String, Object>> validate(WorkflowContext ctx) {
    boolean isValid = performValidation();
    ctx.set("validated", isValid);
    return Mono.just(Map.of("valid", isValid));
}

@WorkflowStep(
    id = "process",
    order = 2,
    condition = "#data['validated'] == true"
)
public Mono<Map<String, Object>> process(WorkflowContext ctx) {
    return Mono.just(Map.of("processed", true));
}
```

---

## Step Trigger Modes

The `StepTriggerMode` enum controls how a step can be invoked:

| Mode | `allowsEventTrigger()` | `allowsProgrammaticTrigger()` | Description |
|------|------------------------|-------------------------------|-------------|
| `EVENT` | `true` | `false` | Triggered by events only |
| `PROGRAMMATIC` | `false` | `true` | Invoked via API only |
| `BOTH` | `true` | `true` | Supports both (default) |

### Event-Driven Choreography

Steps with `inputEventType` and `outputEventType` form an event-driven chain:

```java
@WorkflowStep(
    id = "validate",
    triggerMode = StepTriggerMode.EVENT,
    outputEventType = "order.validated"
)
public Mono<Map<String, Object>> validate(WorkflowContext ctx) {
    return Mono.just(Map.of("valid", true));
}

@WorkflowStep(
    id = "process-payment",
    triggerMode = StepTriggerMode.EVENT,
    inputEventType = "order.validated",
    outputEventType = "payment.processed"
)
public Mono<Map<String, Object>> processPayment(WorkflowContext ctx) {
    return Mono.just(Map.of("paymentId", "PAY-123"));
}
```

When a step completes and has an `outputEventType`, the engine publishes an event with that type. Steps listening for that event via `inputEventType` are triggered.

### Manual Step Triggering

Steps with `PROGRAMMATIC` or `BOTH` trigger mode can be triggered via the REST API or `WorkflowEngine.triggerStep()`:

```java
workflowEngine.triggerStep(
    "order-processing", instanceId, "process-payment",
    Map.of("paymentMethod", "credit"), "api"
);
```

---

## Resilience4j Integration

The workflow engine integrates with Resilience4j for per-step fault tolerance. Resilience patterns are applied with the following order: **TimeLimiter -> Bulkhead -> RateLimiter -> CircuitBreaker**.

Each pattern uses a unique identifier per step: `{workflowId}:{stepId}`, ensuring that failures in one step do not affect other steps.

### Circuit Breaker

Prevents cascading failures by stopping calls to failing steps.

```yaml
firefly:
  workflow:
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
```

State transitions: `CLOSED` -> `OPEN` (failure rate exceeded) -> `HALF_OPEN` (wait duration elapsed) -> `CLOSED` (test calls succeed) or back to `OPEN` (test calls fail).

### Rate Limiter

Controls the rate of step executions. Disabled by default.

```yaml
firefly:
  workflow:
    resilience:
      rate-limiter:
        enabled: true
        limit-for-period: 50
        limit-refresh-period: 1s
        timeout-duration: 5s
```

### Bulkhead

Limits concurrent step executions. Disabled by default.

```yaml
firefly:
  workflow:
    resilience:
      bulkhead:
        enabled: true
        max-concurrent-calls: 25
        max-wait-duration: 0ms
```

### Time Limiter

Enforces timeouts on step execution. Enabled by default.

```yaml
firefly:
  workflow:
    resilience:
      time-limiter:
        enabled: true
        timeout-duration: 5m
        cancel-running-future: true
```

---

## Retry Configuration

Retries are configured at three levels. Step-level overrides workflow-level, which overrides global configuration.

### Global Configuration

```yaml
firefly:
  workflow:
    retry:
      max-attempts: 3
      initial-delay: 1s
      max-delay: 5m
      multiplier: 2.0
```

The `multiplier` controls exponential backoff: each retry delay is multiplied by this factor, capped at `max-delay`.

### Workflow-Level Defaults

```java
@Workflow(
    id = "my-workflow",
    maxRetries = 5,
    retryDelayMs = 2000
)
public class MyWorkflow { ... }
```

### Step-Level Overrides

```java
@WorkflowStep(
    id = "call-external-api",
    maxRetries = 3,
    retryDelayMs = 1000
)
public Mono<Map<String, Object>> callExternalApi(WorkflowContext ctx) { ... }
```

A value of `-1` for `maxRetries` or `retryDelayMs` on `@WorkflowStep` means "use the workflow default."

---

## Scheduled Workflows

Workflows can be scheduled for automatic execution using `@ScheduledWorkflow`. The annotation is `@Repeatable`, so multiple schedules can be applied to one workflow.

### Cron-Based Scheduling

```java
@Workflow(id = "daily-report")
@ScheduledWorkflow(
    cron = "0 0 2 * * *",
    zone = "America/New_York",
    description = "Daily report generation",
    input = "{\"type\": \"daily\"}"
)
public class DailyReportWorkflow {
    @WorkflowStep(id = "generate")
    public Mono<Map<String, Object>> generate(WorkflowContext ctx) {
        return reportService.generateDaily();
    }
}
```

### Fixed Delay and Fixed Rate

```java
// Execute every 30 seconds after the previous run completes
@ScheduledWorkflow(fixedDelay = 30000)

// Execute every 60 seconds regardless of previous run duration
@ScheduledWorkflow(fixedRate = 60000, initialDelay = 5000)
```

### Multiple Schedules

```java
@Workflow(id = "multi-schedule")
@ScheduledWorkflow(cron = "0 0 * * * *", input = "{\"mode\": \"hourly\"}")
@ScheduledWorkflow(cron = "0 0 0 * * *", input = "{\"mode\": \"daily\"}")
public class MultiScheduleWorkflow { ... }
```

### Scheduling Configuration

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

---

## Dead Letter Queue (DLQ)

When a workflow fails after exhausting all retries, it is automatically saved to the Dead Letter Queue if `dlq.auto-save-on-failure` is `true` (the default). The DLQ is backed by `CacheDeadLetterStore`.

### Configuration

```yaml
firefly:
  workflow:
    dlq:
      enabled: true
      max-replay-attempts: 3
      retention-period: 30d
      auto-save-on-failure: true
      include-stack-trace: true
```

### REST API

```http
GET  /api/v1/workflows/dlq                        # List entries
GET  /api/v1/workflows/dlq?workflowId=my-workflow  # Filter by workflow
GET  /api/v1/workflows/dlq/count                   # Get count
GET  /api/v1/workflows/dlq/{entryId}               # Get entry
POST /api/v1/workflows/dlq/{entryId}/replay        # Replay entry
POST /api/v1/workflows/dlq/replay?workflowId=...   # Replay all for workflow
DELETE /api/v1/workflows/dlq/{entryId}              # Delete entry
DELETE /api/v1/workflows/dlq?workflowId=...         # Delete by workflow
DELETE /api/v1/workflows/dlq/all                    # Delete all
```

### Programmatic Access

```java
@Autowired
private DeadLetterService deadLetterService;

// List all entries
Flux<DeadLetterEntry> entries = deadLetterService.getAllEntries();

// List by workflow ID
Flux<DeadLetterEntry> entries = deadLetterService.getEntriesByWorkflowId("order-processing");

// Replay
Mono<ReplayResult> result = deadLetterService.replay("dlq-123");

// Replay with modified input
Mono<ReplayResult> result = deadLetterService.replay("dlq-123", Map.of("retryToken", "new"));

// Replay all for a workflow
Flux<ReplayResult> results = deadLetterService.replayByWorkflowId("order-processing");

// Get count
Mono<Long> count = deadLetterService.getCount();

// Delete
Mono<Boolean> deleted = deadLetterService.delete("dlq-123");
Mono<Long> count = deadLetterService.deleteByWorkflowId("order-processing");
Mono<Long> count = deadLetterService.deleteAll();
```

---

## Crash Recovery

The `WorkflowRecoveryService` detects and recovers stale workflow instances that were interrupted by process crashes. A workflow instance is considered stale if it has been in `RUNNING` status longer than the configured threshold.

### Configuration

```yaml
firefly:
  workflow:
    recovery:
      enabled: true
      stale-threshold: 5m
```

The recovery service is created by `WorkflowEngineAutoConfiguration` when `recovery.enabled` is `true`. It uses `WorkflowStateStore.findStaleInstances(Duration)` to locate stale instances.

**Limitation with durable execution:** `EventSourcedWorkflowStateStore.findStaleInstances()` returns `Flux.empty()` because it requires read-side projections that are not yet built. In durable execution mode, crash recovery relies on event replay rather than stale instance detection.

---

## Dry-Run Mode

Dry-run mode allows testing workflow definitions without executing side effects. The `isDryRun()` flag is set on the `WorkflowContext` and can be checked in step methods.

### Starting in Dry-Run Mode

Via REST API:

```http
POST /api/v1/workflows/order-processing/start
Content-Type: application/json

{
  "input": {"orderId": "TEST-123"},
  "dryRun": true
}
```

Via Java API:

```java
Mono<WorkflowInstance> instance = workflowEngine.startWorkflow(
    "order-processing",
    Map.of("orderId", "TEST-123"),
    null,       // correlationId
    "test",     // triggeredBy
    true        // dryRun
);
```

### Checking Dry-Run in Steps

```java
@WorkflowStep(id = "send-email")
public Mono<Map<String, Object>> sendEmail(WorkflowContext ctx) {
    if (ctx.isDryRun()) {
        log.info("DRY-RUN: Would send email to {}", ctx.getInput("email"));
        return Mono.just(Map.of("sent", false, "dryRun", true));
    }
    return emailService.send(ctx.getInput("email", String.class))
        .map(result -> Map.of("sent", true));
}
```

The workflow engine sets `_dryRun` in both the input map and the context data, so `ctx.isDryRun()` is available in all steps.

---

## Workflow Suspension and Resumption

Workflows can be suspended during incidents or downstream outages and resumed later.

### Status Guards

The `WorkflowStatus` enum provides guard methods:

- `canSuspend()` returns `true` for `RUNNING` and `WAITING`
- `canResume()` returns `true` for `SUSPENDED`

### Suspending

```java
Mono<WorkflowInstance> suspended = workflowEngine.suspendWorkflow(
    "order-processing", instanceId, "Downstream payment service outage"
);
```

A suspended workflow will not execute any further steps until resumed. The suspension reason is optional.

### Resuming

```java
Mono<WorkflowInstance> resumed = workflowEngine.resumeWorkflow(
    "order-processing", instanceId
);
```

On resume, the engine loads the workflow definition (matching the version the instance was started with) and continues execution from the current step.

### Finding Suspended Instances

```java
Flux<WorkflowInstance> suspended = workflowEngine.findSuspendedInstances();
```

Via REST API:

```http
GET /api/v1/workflows/suspended
```

---

## Lifecycle Callbacks

Lifecycle callbacks allow responding to workflow and step events within the workflow class.

### @OnStepComplete

Called after each step completes. Can filter by specific step IDs.

```java
@OnStepComplete(stepIds = {"validate", "process"})
public void onStepComplete(WorkflowContext ctx, StepExecution step) {
    log.info("Step {} completed with status {}", step.stepId(), step.status());
}
```

| Attribute | Default | Description |
|-----------|---------|-------------|
| `stepIds` | `{}` | Filter by step IDs (empty = all steps) |
| `async` | `true` | Execute asynchronously |
| `priority` | `0` | Execution priority (lower = higher) |

### @OnWorkflowComplete

Called when the workflow completes successfully.

```java
@OnWorkflowComplete
public void onComplete(WorkflowContext ctx, WorkflowInstance instance) {
    log.info("Workflow {} completed", instance.instanceId());
}
```

### @OnWorkflowError

Called when the workflow fails. Can filter by exception types and step IDs. Can optionally suppress the error.

```java
@OnWorkflowError(
    errorTypes = {TimeoutException.class},
    stepIds = {"process-payment"},
    suppressError = false
)
public void onError(WorkflowContext ctx, WorkflowInstance instance, Throwable error) {
    log.error("Payment step timed out: {}", error.getMessage());
}
```

When `suppressError = true`, the error is caught and the workflow does not transition to `FAILED`.

---

## Programmatic Step Handlers

For reusable step logic across multiple workflows, implement the `StepHandler<T>` interface and register it as a Spring bean.

```java
@Component("validateOrderStep")
public class ValidateOrderStepHandler implements StepHandler<ValidationResult> {

    private final ValidationService validationService;

    public ValidateOrderStepHandler(ValidationService validationService) {
        this.validationService = validationService;
    }

    @Override
    public Mono<ValidationResult> execute(WorkflowContext context) {
        String orderId = context.getInput("orderId", String.class);
        return validationService.validate(orderId);
    }

    @Override
    public Mono<Void> compensate(WorkflowContext context) {
        return Mono.empty();
    }

    @Override
    public boolean shouldSkip(WorkflowContext context) {
        return "internal".equals(context.getInput("source", String.class));
    }
}
```

Reference the bean in a programmatic workflow definition:

```java
WorkflowDefinition definition = WorkflowDefinition.builder()
    .workflowId("programmatic-workflow")
    .name("Programmatic Workflow")
    .addStep(WorkflowStepDefinition.builder()
        .stepId("validate")
        .name("Validate Order")
        .order(1)
        .handlerBeanName("validateOrderStep")
        .build())
    .build();

workflowEngine.registerWorkflow(definition);
```

---

## Next Steps

- [Getting Started](getting-started.md) -- Prerequisites, cache setup, first workflow
- [Architecture](architecture.md) -- Internal components and execution model
- [Configuration](configuration.md) -- Complete property reference with defaults
- [API Reference](api-reference.md) -- REST endpoints and Java API
- [Durable Execution](durable-execution.md) -- Signals, timers, child workflows, compensation
- [Testing](testing.md) -- Unit and integration testing strategies
