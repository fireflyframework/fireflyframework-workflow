# Durable Execution

Durable execution uses `fireflyframework-eventsourcing` to persist workflow state as an event stream. Every state transition is captured as a domain event, enabling replay, audit trails, and crash recovery from the event log. This mode provides Temporal.io-like capabilities for long-running business processes.

**Durable execution is OFF by default** (`firefly.workflow.eventsourcing.enabled: false`). It must be explicitly opted into.

## Prerequisites

Durable execution requires:

1. An `EventStore` bean from `fireflyframework-eventsourcing`
2. R2DBC database infrastructure (PostgreSQL recommended)
3. `firefly.workflow.eventsourcing.enabled` set to `true`

When enabled, `WorkflowEngineAutoConfiguration` creates an `EventSourcedWorkflowStateStore` bean with `@Primary`, which takes precedence over the cache-backed `CacheWorkflowStateStore`.

## Enabling Durable Execution

```yaml
firefly:
  workflow:
    eventsourcing:
      enabled: true
      snapshot-threshold: 20
      max-events-before-continue-as-new: 1000
```

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `eventsourcing.enabled` | `boolean` | `false` | Whether event sourcing is enabled |
| `eventsourcing.snapshot-threshold` | `int` | `20` | Events before taking a snapshot (min: 1) |
| `eventsourcing.max-events-before-continue-as-new` | `int` | `1000` | Events before triggering continue-as-new (min: 100) |

## WorkflowAggregate

The `WorkflowAggregate` extends `AggregateRoot` from `fireflyframework-eventsourcing`. Each workflow instance becomes an event-sourced aggregate with its own event stream.

### State Managed by the Aggregate

| Field | Type | Description |
|-------|------|-------------|
| `workflowId` | `String` | Workflow definition ID |
| `workflowName` | `String` | Human-readable name |
| `workflowVersion` | `String` | Version string |
| `status` | `WorkflowStatus` | Current status |
| `currentStepId` | `String` | Currently executing step |
| `context` | `Map<String, Object>` | Shared context data |
| `input` | `Map<String, Object>` | Workflow input |
| `output` | `Object` | Workflow output |
| `correlationId` | `String` | Correlation ID |
| `triggeredBy` | `String` | Trigger source |
| `dryRun` | `boolean` | Dry-run flag |
| `stepStates` | `Map<String, StepState>` | Per-step execution state |
| `completedStepOrder` | `List<String>` | Steps completed in order |
| `pendingSignals` | `Map<String, SignalData>` | Buffered signals |
| `signalWaiters` | `Map<String, String>` | Signal name to waiting step ID |
| `activeTimers` | `Map<String, TimerData>` | Active durable timers |
| `childWorkflows` | `Map<String, ChildWorkflowRef>` | Child workflow references |
| `sideEffects` | `Map<String, Object>` | Recorded side effect values |
| `searchAttributes` | `Map<String, Object>` | Custom search attributes |
| `lastHeartbeats` | `Map<String, Map<String, Object>>` | Last heartbeat per step |
| `errorMessage` | `String` | Error message (when failed) |
| `errorType` | `String` | Error type (when failed) |
| `failedStepId` | `String` | Step that caused failure |
| `compensating` | `boolean` | Whether compensation is in progress |
| `compensationPolicy` | `String` | Active compensation policy |
| `compensatedSteps` | `Map<String, CompensationStepResult>` | Completed compensation step results |

### Command Methods

| Command | Event Produced | Description |
|---------|----------------|-------------|
| `start(...)` | `WorkflowStartedEvent` | Start workflow (requires `PENDING` status) |
| `complete(output)` | `WorkflowCompletedEvent` | Complete workflow |
| `fail(errorMessage, errorType, failedStepId)` | `WorkflowFailedEvent` | Mark workflow as failed |
| `cancel(reason)` | `WorkflowCancelledEvent` | Cancel workflow |
| `suspend(reason)` | `WorkflowSuspendedEvent` | Suspend workflow |
| `resume()` | `WorkflowResumedEvent` | Resume suspended workflow |
| `startStep(stepId, stepName, input, attemptNumber)` | `StepStartedEvent` | Start a step |
| `completeStep(stepId, output, durationMs)` | `StepCompletedEvent` | Complete a step |
| `failStep(stepId, errorMessage, errorType, attemptNumber, retryable)` | `StepFailedEvent` | Fail a step |
| `skipStep(stepId, reason)` | `StepSkippedEvent` | Skip a step |
| `retryStep(stepId, attemptNumber, delayMs)` | `StepRetriedEvent` | Retry a step |
| `receiveSignal(signalName, payload)` | `SignalReceivedEvent` | Receive an external signal |
| `consumeSignal(signalName, consumedByStepId)` | `SignalConsumedEvent` | Consume a buffered signal |
| `registerSignalWaiter(signalName, waitingStepId)` | `SignalWaiterRegisteredEvent` | Register a step as a signal waiter |
| `registerTimer(timerId, fireAt, data)` | `TimerRegisteredEvent` | Register a durable timer |
| `fireTimer(timerId)` | `TimerFiredEvent` | Fire a registered timer |
| `spawnChildWorkflow(childInstanceId, childWorkflowId, input, parentStepId)` | `ChildWorkflowSpawnedEvent` | Spawn a child workflow |
| `completeChildWorkflow(childInstanceId, output, success)` | `ChildWorkflowCompletedEvent` | Complete a child workflow |
| `recordSideEffect(sideEffectId, value)` | `SideEffectRecordedEvent` | Record a side effect value |
| `heartbeat(stepId, details)` | `HeartbeatRecordedEvent` | Record a heartbeat |
| `continueAsNew(newInput, completedRunOutput)` | `ContinueAsNewEvent` | Continue as a new execution |
| `startCompensation(failedStepId, compensationPolicy)` | `CompensationStartedEvent` | Start compensation |
| `completeCompensationStep(stepId, success, errorMessage)` | `CompensationStepCompletedEvent` | Complete a compensation step |
| `upsertSearchAttribute(key, value)` | `SearchAttributeUpdatedEvent` | Update a search attribute |

All commands that modify state require the workflow to be in a non-terminal state (not `COMPLETED`, `FAILED`, `CANCELLED`, or `TIMED_OUT`), except for step-level commands which may be applied during execution.

## Domain Events

The durable execution engine uses 24 domain events that together capture the complete workflow lifecycle:

### Workflow Lifecycle Events

| Event | Description |
|-------|-------------|
| `WorkflowStartedEvent` | Workflow execution started |
| `WorkflowCompletedEvent` | Workflow completed successfully |
| `WorkflowFailedEvent` | Workflow failed with error |
| `WorkflowCancelledEvent` | Workflow cancelled |
| `WorkflowSuspendedEvent` | Workflow suspended |
| `WorkflowResumedEvent` | Workflow resumed |

### Step Events

| Event | Description |
|-------|-------------|
| `StepStartedEvent` | Step execution started |
| `StepCompletedEvent` | Step completed, output merged into context |
| `StepFailedEvent` | Step failed with error |
| `StepSkippedEvent` | Step skipped (condition evaluated to `false`) |
| `StepRetriedEvent` | Step scheduled for retry |

### Signal Events

| Event | Description |
|-------|-------------|
| `SignalReceivedEvent` | External signal received and buffered |
| `SignalConsumedEvent` | Buffered signal consumed by a step |
| `SignalWaiterRegisteredEvent` | Step registered as waiting for a signal |

### Timer Events

| Event | Description |
|-------|-------------|
| `TimerRegisteredEvent` | Durable timer registered with fire time |
| `TimerFiredEvent` | Timer fired, removed from active timers |

### Child Workflow Events

| Event | Description |
|-------|-------------|
| `ChildWorkflowSpawnedEvent` | Child workflow spawned from parent step |
| `ChildWorkflowCompletedEvent` | Child workflow completed |

### Side Effect and Heartbeat Events

| Event | Description |
|-------|-------------|
| `SideEffectRecordedEvent` | Non-deterministic value recorded for replay |
| `HeartbeatRecordedEvent` | Step progress heartbeat recorded |

### Compensation Events

| Event | Description |
|-------|-------------|
| `CompensationStartedEvent` | Saga compensation started |
| `CompensationStepCompletedEvent` | Compensation step completed |

### Search and Continuation Events

| Event | Description |
|-------|-------------|
| `SearchAttributeUpdatedEvent` | Search attribute upserted |
| `ContinueAsNewEvent` | Workflow continued as new execution |

---

## Signals

Signals allow external systems to send named data to running workflow instances. Steps can block until a signal arrives, and signals are buffered if they arrive before any step waits for them.

### Sending Signals

Via REST API:

```http
POST /api/v1/workflows/{workflowId}/instances/{instanceId}/signal
Content-Type: application/json

{
  "signalName": "approval-received",
  "payload": {
    "approvedBy": "manager@company.com",
    "approved": true
  }
}
```

Via `SignalService`:

```java
Mono<SignalResult> result = signalService.sendSignal(
    instanceId, "approval-received",
    Map.of("approvedBy", "manager@company.com", "approved", true)
);
```

### Waiting for Signals

Use `@WaitForSignal` on a step method:

```java
@WorkflowStep(id = "wait-for-approval", dependsOn = {"submit-request"})
@WaitForSignal(
    name = "approval-received",
    timeoutDuration = "P7D",
    timeoutAction = FAIL
)
public Mono<Map<String, Object>> waitForApproval(WorkflowContext ctx) {
    Map<String, Object> signal = ctx.getSignal("approval-received");
    boolean approved = (boolean) signal.get("approved");
    return Mono.just(Map.of("approved", approved));
}
```

**`@WaitForSignal` attributes:**

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `name` | `String` | Required | Signal name to wait for |
| `timeoutDuration` | `String` | `""` | ISO-8601 duration before timeout |
| `timeoutAction` | `TimeoutAction` | `FAIL` | Action on timeout: `FAIL`, `ESCALATE`, `SKIP` |

### Signal Buffering

Signals that arrive before a step waits for them are stored in the `pendingSignals` buffer. When a step declares `@WaitForSignal`, the buffer is checked first. The buffer size is configurable:

```yaml
firefly:
  workflow:
    signals:
      enabled: true
      buffer-size: 100
```

### Consuming Signals Programmatically

Consuming a signal returns the buffered payload and persists a `SignalConsumedEvent` to the aggregate, removing it from the pending buffer:

```java
Mono<Map<String, Object>> payload = signalService.consumeSignal(instanceId, "approval-received");
```

---

## Durable Timers

Timers are persisted as domain events and survive process restarts. The `TimerSchedulerService` polls for due timers and fires them.

### Using @WaitForTimer

```java
@WorkflowStep(id = "handle-reminder")
@WaitForTimer(duration = "P3D")
public Mono<Map<String, Object>> handleReminder(WorkflowContext ctx) {
    return notificationService.sendReminder(ctx.getInput("email", String.class))
        .map(result -> Map.of("reminderSent", true));
}
```

**`@WaitForTimer` attributes:**

| Attribute | Type | Description |
|-----------|------|-------------|
| `duration` | `String` | ISO-8601 duration (e.g., `"PT1H"`, `"P3D"`) |
| `fireAt` | `String` | ISO-8601 instant (e.g., `"2026-03-01T09:00:00Z"`) |

Use either `duration` (relative) or `fireAt` (absolute), not both.

### Timer Configuration

```yaml
firefly:
  workflow:
    timers:
      enabled: true
      poll-interval: 1s
      batch-size: 50
```

The `poll-interval` controls how often the scheduler checks for due timers. The `batch-size` limits how many timers are processed per poll cycle.

---

## Composite Waits

Composite wait annotations combine multiple conditions on a single step.

### @WaitForAll (Join Pattern)

Blocks the step until ALL specified conditions are satisfied:

```java
@WorkflowStep(id = "process-after-approvals", dependsOn = {"submit-request"})
@WaitForAll(
    signals = {
        @WaitForSignal(name = "manager-approval", timeoutDuration = "P7D"),
        @WaitForSignal(name = "finance-approval", timeoutDuration = "P7D")
    }
)
public Mono<Map<String, Object>> processAfterApprovals(WorkflowContext ctx) {
    Map<String, Object> managerApproval = ctx.getSignal("manager-approval");
    Map<String, Object> financeApproval = ctx.getSignal("finance-approval");
    return Mono.just(Map.of(
        "managerApproved", managerApproval.get("approved"),
        "financeApproved", financeApproval.get("approved")
    ));
}
```

Signals and timers can be combined:

```java
@WaitForAll(
    signals = {@WaitForSignal(name = "approval")},
    timers = {@WaitForTimer(duration = "PT1H")}
)
```

### @WaitForAny (Race Pattern)

Blocks the step until ANY ONE of the specified conditions is satisfied:

```java
@WorkflowStep(id = "wait-for-response", dependsOn = {"send-request"})
@WaitForAny(
    signals = {@WaitForSignal(name = "response-received")},
    timers = {@WaitForTimer(duration = "PT24H")}
)
public Mono<Map<String, Object>> handleResponse(WorkflowContext ctx) {
    Map<String, Object> signal = ctx.getSignal("response-received");
    if (signal != null) {
        return Mono.just(Map.of("responseReceived", true, "data", signal));
    }
    return Mono.just(Map.of("responseReceived", false, "timedOut", true));
}
```

This pattern is useful for implementing timeout-or-response races.

---

## Child Workflows

Parent workflows can spawn child workflows, wait for their completion, and use their results. Each child workflow is a separate `WorkflowAggregate` instance with its own event history.

### Using @ChildWorkflow

```java
@WorkflowStep(id = "process-items", dependsOn = {"validate-order"})
@ChildWorkflow(workflowId = "item-processing", waitForCompletion = true)
public Mono<Map<String, Object>> processItems(WorkflowContext ctx) {
    List<String> itemIds = ctx.getInput("itemIds", List.class);

    List<Mono<Object>> children = itemIds.stream()
        .map(itemId -> ctx.startChildWorkflow(
            "item-processing",
            Map.of("itemId", itemId)
        ))
        .toList();

    return Flux.merge(children)
        .collectList()
        .map(results -> Map.of("processedItems", results));
}
```

**`@ChildWorkflow` attributes:**

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `workflowId` | `String` | Required | Child workflow definition ID |
| `waitForCompletion` | `boolean` | `true` | Wait for child to complete |
| `timeoutMs` | `long` | `0` | Timeout for child completion |

### Parent-Child Lifecycle

1. Parent records `ChildWorkflowSpawnedEvent` when spawning a child
2. Child is a separate `WorkflowAggregate` with its own event history
3. On child completion, the engine calls `parent.completeChildWorkflow()`
4. Cancelling the parent cascades to all active children

### Nesting Depth Limit

```yaml
firefly:
  workflow:
    child-workflows:
      enabled: true
      max-depth: 5
```

---

## Compensation

When a workflow step fails, the engine can run compensation logic on previously completed steps in reverse order, providing saga-like rollback behavior.

### Defining Compensation Steps

Use `@CompensationStep` to declare compensation logic:

```java
@Workflow(id = "order-fulfillment")
public class OrderFulfillmentWorkflow {

    @WorkflowStep(id = "reserve-inventory", dependsOn = {"validate"})
    public Mono<Map<String, Object>> reserveInventory(WorkflowContext ctx) {
        return inventoryService.reserve(ctx.getInput("items", List.class))
            .map(res -> Map.of("reservationId", res.id()));
    }

    @CompensationStep(compensates = "reserve-inventory")
    public Mono<Void> releaseInventory(WorkflowContext ctx) {
        String reservationId = ctx.getStepOutput("reserve-inventory", Map.class)
            .get("reservationId").toString();
        return inventoryService.release(reservationId);
    }

    @WorkflowStep(id = "charge-payment", dependsOn = {"reserve-inventory"})
    public Mono<Map<String, Object>> chargePayment(WorkflowContext ctx) {
        return paymentService.charge(ctx.getInput("amount", BigDecimal.class))
            .map(res -> Map.of("chargeId", res.id()));
    }

    @CompensationStep(compensates = "charge-payment")
    public Mono<Void> refundPayment(WorkflowContext ctx) {
        String chargeId = ctx.getStepOutput("charge-payment", Map.class)
            .get("chargeId").toString();
        return paymentService.refund(chargeId);
    }
}
```

### Compensation Policies

The `CompensationOrchestrator` executes compensation in reverse order of completed steps using the configured policy:

| Policy | Behavior |
|--------|----------|
| `STRICT_SEQUENTIAL` | Compensate in reverse order, stop on first compensation error |
| `BEST_EFFORT` | Compensate all steps in reverse order, collect all errors |
| `SKIP` | No compensation, workflow fails immediately |

```yaml
firefly:
  workflow:
    compensation:
      enabled: true
      default-policy: STRICT_SEQUENTIAL
```

### Compensation Flow

1. `CompensationStartedEvent` is recorded with the failed step ID and policy. The aggregate enters the `compensating` state and records the `compensationPolicy`.
2. Completed steps are compensated in reverse order
3. Each successful compensation records a `CompensationStepCompletedEvent`, which is tracked in the aggregate's `compensatedSteps` map as a `CompensationStepResult` (containing `success` and optional `errorMessage`)
4. After all compensations complete, a `WorkflowFailedEvent` is recorded

The `StepHandler<T>` interface also supports compensation via the `compensate()` default method.

---

## Side Effects

Side effects capture the result of non-deterministic operations so that they produce the same value during replay. This ensures deterministic behavior across event replays.

```java
@WorkflowStep(id = "generate-id")
public Mono<Map<String, Object>> generateId(WorkflowContext ctx) {
    // First execution: supplier runs, value stored as SideEffectRecordedEvent
    // Replay: stored value returned, supplier NOT called
    String uniqueId = ctx.sideEffect("order-id", () -> UUID.randomUUID().toString());
    Instant timestamp = ctx.sideEffect("created-at", () -> Instant.now());

    return Mono.just(Map.of(
        "orderId", uniqueId,
        "createdAt", timestamp.toString()
    ));
}
```

The `sideEffect(id, supplier)` method:
- On first execution: runs the supplier, records a `SideEffectRecordedEvent`, and returns the value
- On replay: returns the stored value from `WorkflowAggregate.getSideEffect(id)` without invoking the supplier

---

## Heartbeats

Long-running steps can report progress via heartbeats. This allows the engine to distinguish between a step that is stuck and one that is making progress, and provides resume information on recovery.

```java
@WorkflowStep(id = "process-large-dataset", timeoutMs = 3600000)
public Mono<Map<String, Object>> processLargeDataset(WorkflowContext ctx) {
    List<Record> records = loadRecords();

    for (int i = 0; i < records.size(); i++) {
        process(records.get(i));
        ctx.heartbeat(Map.of(
            "lastIndex", i,
            "progress", (i * 100) / records.size()
        ));
    }

    return Mono.just(Map.of("totalProcessed", records.size()));
}
```

Heartbeats are stored as `HeartbeatRecordedEvent` and throttled to avoid excessive writes:

```yaml
firefly:
  workflow:
    heartbeat:
      enabled: true
      throttle-interval: 5s
```

---

## Search Attributes

Search attributes are custom indexed fields that allow discovering and filtering workflow instances by business-relevant data.

### Setting Search Attributes

```java
@WorkflowStep(id = "process-order")
public Mono<Map<String, Object>> processOrder(WorkflowContext ctx) {
    ctx.upsertSearchAttribute("customerId", ctx.getInput("customerId", String.class));
    ctx.upsertSearchAttribute("region", ctx.getInput("region", String.class));
    ctx.upsertSearchAttribute("orderStatus", "PROCESSING");
    return orderService.process(ctx.getInput("orderId", String.class));
}
```

Each call records a `SearchAttributeUpdatedEvent`. A `SearchAttributeProjection` materializes these attributes into an indexed table for efficient querying.

### Searching

Via REST API:

```http
GET /api/v1/workflows/search?customerId=CUST-123&region=us-east
```

Via `WorkflowSearchService`:

```java
Flux<WorkflowInstance> instances = searchService.searchByAttributes(
    Map.of("customerId", "CUST-123", "region", "us-east")
);
```

```yaml
firefly:
  workflow:
    search-attributes:
      enabled: true
```

---

## Queries

Queries provide read-only inspection of running workflow state. The `WorkflowQueryService` loads the aggregate by replaying its event stream and executes the query against the reconstructed state.

### 10 Built-in Queries

| Query Name | Returns | Description |
|------------|---------|-------------|
| `getStatus` | `String` | Current workflow status name |
| `getCurrentStep` | `String` | Currently executing step ID |
| `getStepHistory` | `Map<String, Map<String, Object>>` | All step states with status and attempt info |
| `getContext` | `Map<String, Object>` | Workflow context data |
| `getSearchAttributes` | `Map<String, Object>` | Search attribute key-value pairs |
| `getInput` | `Map<String, Object>` | Workflow input |
| `getOutput` | `Object` | Workflow output (null if not completed) |
| `getPendingSignals` | `Set<String>` | Names of pending signals |
| `getActiveTimers` | `Set<String>` | IDs of active timers |
| `getChildWorkflows` | `Map<String, Map<String, Object>>` | Child workflow status summaries |

### Executing Queries

Via REST API:

```http
GET /api/v1/workflows/{workflowId}/instances/{instanceId}/query/getStepHistory
```

Via `WorkflowQueryService`:

```java
Mono<Object> result = queryService.executeQuery(instanceId, "getStepHistory");
```

Unrecognized query names return `400 Bad Request` with an error message.

---

## Continue-As-New

Long-running workflows accumulate events over time, which increases replay time. `ContinueAsNew` resets the event history by completing the current aggregate and starting a new one with the same workflow identity.

### Automatic Continue-As-New

Configure a threshold to trigger continue-as-new automatically:

```yaml
firefly:
  workflow:
    eventsourcing:
      max-events-before-continue-as-new: 1000
```

When the event count exceeds this threshold:
1. The current aggregate is marked `COMPLETED` with a `ContinueAsNewEvent`
2. A new aggregate is created with the same `workflowId` and `correlationId`
3. The `ContinueAsNewEvent` carries the `previousRunId` for linking
4. Active timers and pending signals are migrated to the new run

---

## Snapshots

Snapshots optimize replay performance by capturing the complete aggregate state at a point in time. When loading an aggregate, the engine loads the snapshot first and then replays only events after the snapshot version.

```yaml
firefly:
  workflow:
    eventsourcing:
      snapshot-threshold: 20
```

A snapshot is taken every `snapshot-threshold` events. The `WorkflowSnapshot` class captures all aggregate state including step states, pending signals, signal waiters, active timers, child workflows, side effects, search attributes, heartbeats, completed step order, error tracking fields (`errorMessage`, `errorType`, `failedStepId`), and compensation state (`compensating`, `compensationPolicy`, `compensatedSteps`).

Snapshots are restored via `WorkflowAggregate.restoreFromSnapshot()`, which directly sets all fields without replaying events.

---

## Optimistic Concurrency

The `EventSourcedWorkflowStateStore` uses optimistic concurrency control when saving aggregates. The `saveAggregate()` method computes the expected version from `currentVersion - uncommittedEvents.size()` and passes it to `EventStore.appendEvents()`. If another process has modified the aggregate since it was loaded, the event store returns a `ConcurrencyException`.

The `updateStatus()` method loads the aggregate, validates the expected status, applies the transition command, and saves. If the current status does not match the expected status, it returns `false` without modifying the aggregate.

---

## Read-Side Projection

The `EventSourcedWorkflowStateStore` supports query, count, and delete operations through a read-side projection that maintains a denormalized `workflow_instances_projection` PostgreSQL table.

### Architecture

The projection is fed by polling the event store's global stream:

```
EventStore --poll--> WorkflowProjectionScheduler --> WorkflowInstanceProjection --> workflow_instances_projection table
                                                                                          |
                                                                                          v
EventSourcedWorkflowStateStore <--query-- WorkflowProjectionRepository
```

- **WorkflowProjectionScheduler** polls the event store on a configurable interval (default: 1s)
- **WorkflowInstanceProjection** processes events and updates the projection table
- **WorkflowProjectionRepository** queries the projection table for instance IDs
- Query methods load the full aggregate from the event store and convert to `WorkflowInstance`
- Count methods use direct SQL COUNT against the projection table
- Delete methods soft-delete by setting `deleted = TRUE` in the projection

### Configuration

```yaml
firefly:
  workflow:
    eventsourcing:
      projection-poll-interval: 1s   # How often to poll for new events
      projection-batch-size: 100      # Max events per poll cycle
```

### Eventual Consistency

The projection is **eventually consistent** -- there is a small delay (up to the poll interval) between an event being saved and the projection being updated. The `findById()` method is always strongly consistent since it loads directly from the event store.

### Graceful Degradation

When `DatabaseClient` is not available (e.g., no R2DBC dependency), the projection beans are not created and all query/count methods return empty/zero. This preserves backward compatibility.

## Known Limitations

### Delete Operations Are Soft-Delete

The `delete()` and `deleteByWorkflowId()` methods soft-delete rows in the projection table (setting `deleted = TRUE`). The underlying event stream is preserved -- events are immutable.

### Save Operations Are Pass-Through

The `save(WorkflowInstance)` and `save(WorkflowInstance, Duration)` methods return the instance as-is. In the event-sourced model, state is managed via aggregate commands, not direct saves.

### Individual Instance Lookup Works

`findById(instanceId)` works by loading the aggregate from the event store and converting it to a `WorkflowInstance`. The instance ID must be a valid UUID string.

---

## Next Steps

- [Getting Started](getting-started.md) -- Prerequisites, cache setup, first workflow
- [Architecture](architecture.md) -- Internal components and execution model
- [Configuration](configuration.md) -- Complete property reference with defaults
- [API Reference](api-reference.md) -- REST endpoints and Java API
- [Advanced Features](advanced-features.md) -- DAG execution, resilience, scheduling, DLQ
- [Testing](testing.md) -- Unit and integration testing strategies
