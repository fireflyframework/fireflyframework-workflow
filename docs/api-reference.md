# API Reference

This document provides a complete reference for the REST API and Java API of the Firefly Workflow Engine.

## REST API

All endpoints are served at the configured base path (default: `/api/v1/workflows`). The base path is set by `firefly.workflow.api.base-path`.

The REST API is exposed by `WorkflowController` and `DeadLetterController`. The controller is created conditionally by `WorkflowEngineAutoConfiguration` when `firefly.workflow.api.enabled` is `true` (the default).

---

## Workflow Endpoints

### List Workflows

Lists all registered workflow definitions.

```http
GET /api/v1/workflows
```

**Response (200 OK):**

```json
[
  {
    "workflowId": "order-processing",
    "name": "Order Processing Workflow",
    "description": "Processes customer orders",
    "version": "1.0.0",
    "triggerMode": "BOTH",
    "stepCount": 3
  }
]
```

### Get Workflow Definition

Returns the full definition of a specific workflow, including all steps.

```http
GET /api/v1/workflows/{workflowId}
```

**Response (200 OK):**

```json
{
  "workflowId": "order-processing",
  "name": "Order Processing Workflow",
  "description": "Processes customer orders",
  "version": "1.0.0",
  "triggerMode": "BOTH",
  "steps": [
    {
      "stepId": "validate",
      "name": "Validate Order",
      "dependsOn": [],
      "triggerMode": "BOTH",
      "async": false
    }
  ]
}
```

**Response (404 Not Found):** Workflow definition not found.

### Get Workflow Topology

Returns the DAG structure (nodes and edges) for visualization with React Flow or Mermaid.js.

```http
GET /api/v1/workflows/{workflowId}/topology
```

**Response (200 OK):**

```json
{
  "workflowId": "order-processing",
  "workflowName": "Order Processing",
  "nodes": [
    {
      "id": "validate",
      "label": "Validate Order",
      "type": "step",
      "status": null,
      "async": false,
      "layer": 0
    },
    {
      "id": "process",
      "label": "Process Payment",
      "type": "step",
      "status": null,
      "async": false,
      "layer": 1
    }
  ],
  "edges": [
    {
      "source": "validate",
      "target": "process",
      "label": "dependsOn"
    }
  ],
  "metadata": {
    "totalSteps": 2,
    "maxParallelism": 1,
    "layerCount": 2
  }
}
```

### Start Workflow

Starts a new workflow instance.

```http
POST /api/v1/workflows/{workflowId}/start
Content-Type: application/json

{
  "input": {
    "orderId": "ORD-123",
    "amount": 99.99
  },
  "correlationId": "corr-456",
  "waitForCompletion": false,
  "waitTimeoutMs": 30000,
  "dryRun": false
}
```

**Request Body (`StartWorkflowRequest`):**

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `input` | `Map<String, Object>` | No | `{}` | Input data for the workflow |
| `correlationId` | `String` | No | `null` | Correlation ID for distributed tracing |
| `waitForCompletion` | `boolean` | No | `false` | Whether to wait for workflow to complete before responding |
| `waitTimeoutMs` | `long` | No | `30000` | Timeout in ms when `waitForCompletion` is `true` |
| `dryRun` | `boolean` | No | `false` | Run workflow without side effects |

The request body is optional. Sending an empty `POST` starts the workflow with no input.

**Response (201 Created):**

```json
{
  "instanceId": "a1b2c3d4-...",
  "workflowId": "order-processing",
  "status": "RUNNING",
  "currentStepId": "validate",
  "completedSteps": 0,
  "totalSteps": 3,
  "createdAt": "2026-02-20T10:00:00Z",
  "startedAt": "2026-02-20T10:00:00Z"
}
```

**Response (404 Not Found):** Workflow definition not found.

---

## Instance Endpoints

### Get Instance Status

```http
GET /api/v1/workflows/{workflowId}/instances/{instanceId}/status
```

**Response (200 OK):**

```json
{
  "instanceId": "a1b2c3d4-...",
  "workflowId": "order-processing",
  "status": "COMPLETED",
  "currentStepId": null,
  "completedSteps": 3,
  "totalSteps": 3,
  "createdAt": "2026-02-20T10:00:00Z",
  "startedAt": "2026-02-20T10:00:00Z",
  "completedAt": "2026-02-20T10:00:05Z"
}
```

### Collect Result

Collects the result of a completed workflow. Returns different responses depending on the workflow status.

```http
GET /api/v1/workflows/{workflowId}/instances/{instanceId}/collect
```

**Response (200 OK -- Completed):**

```json
{
  "status": "COMPLETED",
  "result": {
    "orderId": "ORD-123",
    "processed": true
  }
}
```

**Response (202 Accepted -- Still Running):**

```json
{
  "status": "RUNNING",
  "message": "Workflow is still running"
}
```

**Response (500 -- Failed or Cancelled):**

```json
{
  "status": "FAILED",
  "error": "Payment gateway timeout"
}
```

### Cancel Workflow

Cancels a running workflow instance. Only non-terminal workflows can be cancelled.

```http
POST /api/v1/workflows/{workflowId}/instances/{instanceId}/cancel
```

**Response (200 OK):** Returns the updated `WorkflowStatusResponse` with status `CANCELLED`.

**Response (400 Bad Request):** Workflow is already in a terminal state.

### Retry Workflow

Retries a failed workflow from the failed step. Only `FAILED` workflows can be retried.

```http
POST /api/v1/workflows/{workflowId}/instances/{instanceId}/retry
```

**Response (200 OK):** Returns the updated `WorkflowStatusResponse` with status `RUNNING`.

**Response (400 Bad Request):** Workflow is not in `FAILED` status.

### Suspend Workflow

Suspends a running or waiting workflow. Useful during incidents or downstream outages.

```http
POST /api/v1/workflows/{workflowId}/instances/{instanceId}/suspend
Content-Type: application/json

{
  "reason": "Downstream service outage"
}
```

The request body is optional. If omitted, no reason is recorded.

**Response (200 OK):** Returns the updated `WorkflowStatusResponse` with status `SUSPENDED`.

**Response (400 Bad Request):** Workflow cannot be suspended in its current status.

### Resume Workflow

Resumes a suspended workflow. Execution continues from where it was suspended.

```http
POST /api/v1/workflows/{workflowId}/instances/{instanceId}/resume
```

**Response (200 OK):** Returns the updated `WorkflowStatusResponse` with status `RUNNING`.

**Response (400 Bad Request):** Workflow is not in `SUSPENDED` status.

### List Suspended Instances

Lists all suspended workflow instances across all workflow definitions.

```http
GET /api/v1/workflows/suspended
```

**Response (200 OK):** Array of `WorkflowStatusResponse` objects.

### List Instances

Lists all instances of a specific workflow, optionally filtered by status.

```http
GET /api/v1/workflows/{workflowId}/instances
GET /api/v1/workflows/{workflowId}/instances?status=RUNNING
```

**Query Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `status` | `WorkflowStatus` | Filter by status (optional) |

### Get Instance Topology

Returns the DAG structure with step execution statuses populated for progress visualization.

```http
GET /api/v1/workflows/{workflowId}/instances/{instanceId}/topology
```

**Response:** Same structure as the workflow topology endpoint, but `status` fields on nodes are populated with the actual step execution status.

---

## Step Endpoints

### Trigger Step

Triggers execution of a specific step. This supports step-level choreography where individual steps are invoked independently.

```http
POST /api/v1/workflows/{workflowId}/instances/{instanceId}/steps/{stepId}/trigger
Content-Type: application/json

{
  "input": {
    "additionalData": "value"
  }
}
```

The request body is optional.

**Response (200 OK):** Returns the updated `WorkflowStatusResponse`.

**Response (400 Bad Request):** Step execution failed (`StepExecutionException`).

**Response (404 Not Found):** Workflow or instance not found.

### Get Step State

Returns the state of a specific step within a workflow instance. Requires step state tracking to be enabled (a `StepStateStore` bean must be available).

```http
GET /api/v1/workflows/{workflowId}/instances/{instanceId}/steps/{stepId}
```

**Response (200 OK):**

```json
{
  "workflowId": "order-processing",
  "instanceId": "a1b2c3d4-...",
  "stepId": "validate",
  "status": "COMPLETED",
  "triggeredBy": "workflow",
  "input": {"orderId": "ORD-123"},
  "output": {"valid": true},
  "startedAt": "2026-02-20T10:00:00Z",
  "completedAt": "2026-02-20T10:00:01Z",
  "durationMs": 1000
}
```

**Response (501 Not Implemented):** Step state tracking is not enabled.

### Get All Step States

Returns all step states for a workflow instance.

```http
GET /api/v1/workflows/{workflowId}/instances/{instanceId}/steps
```

**Response (200 OK):** Array of `StepStateResponse` objects. Returns an empty array if step state tracking is not enabled.

### Get Workflow State (Dashboard View)

Returns comprehensive workflow state including all step tracking information. Useful for dashboard UIs.

```http
GET /api/v1/workflows/{workflowId}/instances/{instanceId}/state
```

**Response (200 OK):**

```json
{
  "workflowId": "order-processing",
  "instanceId": "a1b2c3d4-...",
  "status": "RUNNING",
  "totalSteps": 3,
  "completedSteps": ["validate"],
  "failedSteps": [],
  "pendingSteps": ["process-payment", "ship"],
  "skippedSteps": [],
  "currentStepId": "process-payment",
  "nextStepId": "ship",
  "createdAt": "2026-02-20T10:00:00Z",
  "updatedAt": "2026-02-20T10:00:01Z"
}
```

**Response (501 Not Implemented):** Step state tracking is not enabled.

---

## Dead Letter Queue (DLQ) Endpoints

Endpoints for managing failed workflow entries. Served by `DeadLetterController` at `{base-path}/dlq`. The controller is created when `firefly.workflow.dlq.enabled` is `true` and a `DeadLetterService` bean is available.

### List DLQ Entries

```http
GET /api/v1/workflows/dlq
GET /api/v1/workflows/dlq?workflowId=order-processing
```

**Query Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `workflowId` | `String` | Filter by workflow ID (optional) |

**Response (200 OK):** Array of `DeadLetterEntry` objects.

### Get DLQ Count

```http
GET /api/v1/workflows/dlq/count
```

**Response (200 OK):**

```json
{
  "count": 5
}
```

### Get DLQ Entry

```http
GET /api/v1/workflows/dlq/{entryId}
```

**Response (200 OK):** A single `DeadLetterEntry` object.

**Response (404 Not Found):** Entry not found.

### Replay DLQ Entry

Replays a specific failed workflow or step from the DLQ. Optionally accepts modified input.

```http
POST /api/v1/workflows/dlq/{entryId}/replay
Content-Type: application/json

{
  "retryToken": "new-token"
}
```

The request body is optional. If provided, the keys are merged into the workflow input.

**Response (200 OK):**

```json
{
  "entryId": "dlq-123",
  "success": true,
  "instanceId": "new-inst-456",
  "errorMessage": null
}
```

**Response (500):** Replay failed. The `ReplayResult` is returned with `success: false`.

### Replay All DLQ Entries for a Workflow

```http
POST /api/v1/workflows/dlq/replay?workflowId=order-processing
```

**Response (200 OK):** Stream of `ReplayResult` objects.

### Delete DLQ Entry

```http
DELETE /api/v1/workflows/dlq/{entryId}
```

**Response (204 No Content):** Entry deleted.

**Response (404 Not Found):** Entry not found.

### Delete DLQ Entries by Workflow ID

```http
DELETE /api/v1/workflows/dlq?workflowId=order-processing
```

**Response (200 OK):**

```json
{
  "deleted": 3
}
```

### Delete All DLQ Entries

```http
DELETE /api/v1/workflows/dlq/all
```

**Response (200 OK):**

```json
{
  "deleted": 15
}
```

---

## Durable Execution Endpoints

The following endpoints require durable execution to be enabled (`firefly.workflow.eventsourcing.enabled: true`). If the required service is not available, these endpoints return `501 Not Implemented`.

### Send Signal

Sends a named signal to a running workflow instance. If no step is currently waiting for this signal, it is buffered for later consumption.

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

**Request Body (`SendSignalRequest`):**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `signalName` | `String` | Yes (`@NotBlank`) | Signal name matching `@WaitForSignal(name = ...)` |
| `payload` | `Map<String, Object>` | No | Data to deliver with the signal |

**Response (200 OK):** Returns a `SignalResult` object.

**Response (501 Not Implemented):** `SignalService` is not configured.

### Execute Query

Executes a named query against a workflow instance. Queries are read-only and do not modify workflow state.

```http
GET /api/v1/workflows/{workflowId}/instances/{instanceId}/query/{queryName}
```

**Built-in Query Names:**

| Query Name | Returns |
|------------|---------|
| `getStatus` | Current workflow status string |
| `getCurrentStep` | Currently executing step ID |
| `getStepHistory` | Map of step ID to step state summary |
| `getContext` | Workflow context data map |
| `getSearchAttributes` | Search attribute key-value map |
| `getInput` | Workflow input map |
| `getOutput` | Workflow output (null if not completed) |
| `getPendingSignals` | Set of pending signal names |
| `getActiveTimers` | Set of active timer IDs |
| `getChildWorkflows` | Map of child instance ID to child workflow summary |

**Response (200 OK):** The query result (type varies by query).

**Response (400 Bad Request):** Unknown query name.

**Response (501 Not Implemented):** `WorkflowQueryService` is not configured.

### Search by Attributes

Searches workflow instances by custom search attribute values. All parameters are combined with AND logic.

```http
GET /api/v1/workflows/search?customerId=CUST-123&region=us-east
```

**Response (200 OK):** Stream of `WorkflowInstance` objects matching all criteria.

**Response (501 Not Implemented):** `WorkflowSearchService` is not configured.

---

## HTTP Status Codes

| Code | Description |
|------|-------------|
| 200 | Success |
| 201 | Created (workflow started) |
| 202 | Accepted (workflow still running, returned by collect endpoint) |
| 204 | No Content (successful deletion) |
| 400 | Bad Request (invalid input, illegal state transition, unknown query) |
| 404 | Not Found (workflow definition, instance, or DLQ entry not found) |
| 500 | Internal Server Error |
| 501 | Not Implemented (step state tracking disabled, or durable execution service not available) |

---

## Java API

### WorkflowEngine

The `WorkflowEngine` class is the main programmatic interface for workflow operations. Inject it via constructor injection:

```java
@Service
public class OrderService {

    private final WorkflowEngine workflowEngine;

    public OrderService(WorkflowEngine workflowEngine) {
        this.workflowEngine = workflowEngine;
    }
}
```

#### Starting Workflows

```java
// Basic start (triggeredBy defaults to "api")
Mono<WorkflowInstance> instance = workflowEngine.startWorkflow(
    "order-processing",
    Map.of("orderId", "ORD-123")
);

// With correlation ID and trigger source
Mono<WorkflowInstance> instance = workflowEngine.startWorkflow(
    "order-processing",
    Map.of("orderId", "ORD-123"),
    "correlation-456",
    "api"
);

// With dry-run mode
Mono<WorkflowInstance> instance = workflowEngine.startWorkflow(
    "order-processing",
    Map.of("orderId", "ORD-123"),
    "correlation-456",
    "api",
    true  // dryRun
);
```

**`startWorkflow` overloads:**

| Signature | Description |
|-----------|-------------|
| `startWorkflow(workflowId, input)` | Basic start with trigger source `"api"` |
| `startWorkflow(workflowId, input, correlationId, triggeredBy)` | With correlation ID and trigger source |
| `startWorkflow(workflowId, input, correlationId, triggeredBy, dryRun)` | Full options including dry-run mode |

All overloads return `Mono<WorkflowInstance>`. They throw `WorkflowNotFoundException` if the workflow ID is not registered.

#### Querying Status

```java
// By workflow ID and instance ID
Mono<WorkflowInstance> status = workflowEngine.getStatus("order-processing", instanceId);

// By instance ID only
Mono<WorkflowInstance> status = workflowEngine.getStatus(instanceId);
```

Both overloads return `Mono.error(WorkflowNotFoundException)` if the instance is not found.

#### Collecting Results

```java
Mono<Order> result = workflowEngine.collectResult(
    "order-processing", instanceId, Order.class
);
```

Returns `Mono.error(IllegalStateException)` if:
- The workflow is not in a terminal state
- The workflow status is `FAILED` or `CANCELLED`
- The output cannot be cast to the requested type

Returns `Mono.empty()` if the output is null.

#### Cancelling, Suspending, Resuming

```java
// Cancel a running workflow
Mono<WorkflowInstance> cancelled = workflowEngine.cancelWorkflow(
    "order-processing", instanceId
);

// Suspend with reason
Mono<WorkflowInstance> suspended = workflowEngine.suspendWorkflow(
    "order-processing", instanceId, "Downstream service outage"
);

// Resume a suspended workflow
Mono<WorkflowInstance> resumed = workflowEngine.resumeWorkflow(
    "order-processing", instanceId
);

// Retry a failed workflow from the failed step
Mono<WorkflowInstance> retried = workflowEngine.retryWorkflow(
    "order-processing", instanceId
);
```

`cancelWorkflow` throws `IllegalStateException` if the workflow is already terminal.

`suspendWorkflow` throws `IllegalStateException` if `status.canSuspend()` returns `false`.

`resumeWorkflow` throws `IllegalStateException` if `status.canResume()` returns `false`.

`retryWorkflow` throws `IllegalStateException` if the workflow status is not `FAILED`.

#### Step Operations

```java
// Trigger a specific step
Mono<WorkflowInstance> result = workflowEngine.triggerStep(
    "order-processing", instanceId, "process-payment",
    Map.of("paymentMethod", "credit"), "api"
);

// Get single step state (requires StepStateStore)
Mono<StepState> state = workflowEngine.getStepState(
    "order-processing", instanceId, "validate"
);

// Get all step states (requires StepStateStore)
Flux<StepState> states = workflowEngine.getStepStates(
    "order-processing", instanceId
);

// Get comprehensive workflow state (requires StepStateStore)
Mono<WorkflowState> state = workflowEngine.getWorkflowState(
    "order-processing", instanceId
);

// Check if step state tracking is enabled
boolean enabled = workflowEngine.isStepStateTrackingEnabled();
```

Step state methods throw `UnsupportedOperationException` if no `StepStateStore` is available.

#### Finding Instances

```java
// All instances of a workflow
Flux<WorkflowInstance> instances = workflowEngine.findInstances("order-processing");

// By status
Flux<WorkflowInstance> running = workflowEngine.findInstances(
    "order-processing", WorkflowStatus.RUNNING
);

// All active instances (RUNNING or WAITING)
Flux<WorkflowInstance> active = workflowEngine.findActiveInstances();

// By correlation ID
Flux<WorkflowInstance> correlated = workflowEngine.findByCorrelationId("correlation-456");

// All suspended instances
Flux<WorkflowInstance> suspended = workflowEngine.findSuspendedInstances();
```

#### Workflow Registration

```java
// Register a workflow definition
workflowEngine.registerWorkflow(definition);

// Unregister
boolean removed = workflowEngine.unregisterWorkflow("order-processing");

// Get definition
Optional<WorkflowDefinition> def = workflowEngine.getWorkflowDefinition(
    "order-processing"
);

// Get all registered workflows
Collection<WorkflowDefinition> all = workflowEngine.getAllWorkflows();
```

#### Event-Based Discovery

```java
// Find workflows triggered by an event type
Flux<WorkflowDefinition> workflows = workflowEngine.findWorkflowsByTriggerEvent(
    "order.created"
);

// Find step states waiting for an event
Flux<StepState> waiting = workflowEngine.findStepsWaitingForEvent("payment.processed");

// Find step definitions matching an input event type
List<WorkflowStepMatch> matches = workflowEngine.findStepsByInputEvent("order.validated");
```

`WorkflowStepMatch` is a record containing the matched `WorkflowDefinition` and `WorkflowStepDefinition`.

#### Health Check

```java
Mono<Boolean> healthy = workflowEngine.isHealthy();
```

Delegates to `WorkflowStateStore.isHealthy()`.

---

### WorkflowContext

The `WorkflowContext` is passed to every step method and provides access to all workflow data.

#### Core Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `getInstanceId()` | `String` | Unique instance identifier |
| `getWorkflowId()` | `String` | Workflow definition ID |
| `getCorrelationId()` | `String` | Correlation ID for distributed tracing |
| `isDryRun()` | `boolean` | Whether this is a dry-run execution |
| `getInput(key, type)` | `<T> T` | Get typed input value by key |
| `getInput(key)` | `Object` | Get raw input value by key |
| `getAllInputs()` | `Map<String, Object>` | Get all input values |
| `getAllInput()` | `Map<String, Object>` | Alias for `getAllInputs()` |
| `getStepOutput(stepId, type)` | `<T> T` | Get typed output from a previous step |
| `getStepOutput(stepId)` | `Object` | Get raw output from a previous step |
| `get(key, type)` | `<T> T` | Get typed value from shared context |
| `get(key)` | `Object` | Get raw value from shared context |
| `getOrDefault(key, defaultValue)` | `String` | Get string value with default |
| `set(key, value)` | `void` | Set value in shared context |
| `remove(key)` | `void` | Remove value from shared context |
| `has(key)` | `boolean` | Check if key exists in shared context |
| `getAllData()` | `Map<String, Object>` | Get all shared context data |
| `merge(map)` | `void` | Merge map into shared context |
| `forNextStep(stepOutput)` | `WorkflowContext` | Create context for next step with output merged |

#### Durable Execution Methods

These methods are available on `WorkflowContext` and require durable execution to be enabled.

| Method | Return Type | Description |
|--------|-------------|-------------|
| `sideEffect(id, supplier)` | `<T> T` | Record and replay non-deterministic values |
| `heartbeat(details)` | `void` | Report progress for long-running steps |
| `startChildWorkflow(workflowId, input)` | `Mono<Object>` | Spawn a child workflow |

---

### StepHandler Interface

The `StepHandler<T>` interface is used for reusable step logic across workflows.

```java
public interface StepHandler<T> {

    Mono<T> execute(WorkflowContext context);

    default Mono<Void> compensate(WorkflowContext context) {
        return Mono.empty();
    }

    default boolean shouldSkip(WorkflowContext context) {
        return false;
    }
}
```

| Method | Required | Description |
|--------|----------|-------------|
| `execute(context)` | Yes | Step business logic, returns `Mono<T>` |
| `compensate(context)` | No | Compensation logic for saga rollback (default: no-op) |
| `shouldSkip(context)` | No | Conditional skip logic (default: `false`) |

Register as a Spring bean and reference via `handlerBeanName`:

```java
@Component("validateOrderStep")
public class ValidateOrderStepHandler implements StepHandler<ValidationResult> {

    @Override
    public Mono<ValidationResult> execute(WorkflowContext context) {
        String orderId = context.getInput("orderId", String.class);
        return validator.validate(orderId);
    }
}
```

---

## Annotations

### @Workflow

Marks a class as a workflow definition. Includes `@Component`, so the class is automatically registered as a Spring bean.

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `id` | `String` | Class name (kebab-case) | Unique workflow identifier |
| `name` | `String` | Simple class name | Human-readable name |
| `description` | `String` | `""` | Workflow description |
| `version` | `String` | `"1.0.0"` | Version string |
| `triggerMode` | `TriggerMode` | `BOTH` | `SYNC`, `ASYNC`, or `BOTH` |
| `triggerEventType` | `String` | `""` | Event type that starts this workflow |
| `timeoutMs` | `long` | `0` | Workflow timeout in ms (0 = use config default) |
| `maxRetries` | `int` | `3` | Default max retries for steps |
| `retryDelayMs` | `long` | `1000` | Initial retry delay in ms |
| `publishEvents` | `boolean` | `true` | Publish lifecycle events |

### @WorkflowStep

Marks a method as a workflow step. The method can return `Mono<T>`, `T` (auto-wrapped in `Mono`), or `void`/`Mono<Void>`.

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `id` | `String` | Method name | Unique step ID within the workflow |
| `name` | `String` | Method name | Human-readable name |
| `description` | `String` | `""` | Step description |
| `dependsOn` | `String[]` | `{}` | Step IDs this step depends on |
| `order` | `int` | `0` | Execution order (lower first, used when no `dependsOn`) |
| `triggerMode` | `StepTriggerMode` | `BOTH` | `EVENT`, `PROGRAMMATIC`, or `BOTH` |
| `inputEventType` | `String` | `""` | Event type that triggers this step |
| `outputEventType` | `String` | `""` | Event type published on completion |
| `timeoutMs` | `long` | `0` | Step timeout in ms (0 = use workflow default) |
| `maxRetries` | `int` | `-1` | Max retries (-1 = use workflow default) |
| `retryDelayMs` | `long` | `-1` | Initial retry delay in ms (-1 = use workflow default) |
| `condition` | `String` | `""` | SpEL expression for conditional execution |
| `async` | `boolean` | `false` | Execute asynchronously within its dependency layer |
| `compensatable` | `boolean` | `false` | Supports compensation on rollback |
| `compensationMethod` | `String` | `""` | Method name for compensation logic |

### @ScheduledWorkflow

Schedules a workflow for automatic execution. `@Repeatable` -- multiple schedules can be applied to one workflow.

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `cron` | `String` | `""` | Cron expression (Spring format) |
| `zone` | `String` | `""` | Timezone (default: system default) |
| `enabled` | `String` | `"true"` | Whether this schedule is active |
| `fixedDelay` | `long` | `-1` | Fixed delay between runs in ms |
| `fixedRate` | `long` | `-1` | Fixed rate between runs in ms |
| `initialDelay` | `long` | `0` | Initial delay before first run in ms |
| `input` | `String` | `""` | JSON string input for the workflow |
| `description` | `String` | `""` | Description for logging |

### @OnStepComplete

Called after a step completes successfully.

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `stepIds` | `String[]` | `{}` | Filter by step IDs (empty = all steps) |
| `async` | `boolean` | `true` | Execute callback asynchronously |
| `priority` | `int` | `0` | Execution priority (lower = higher priority) |

### @OnWorkflowComplete

Called when the workflow completes successfully.

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `async` | `boolean` | `true` | Execute callback asynchronously |
| `priority` | `int` | `0` | Execution priority |

### @OnWorkflowError

Called when the workflow fails.

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `errorTypes` | `Class<?>[]` | `{}` | Filter by exception types (empty = all errors) |
| `stepIds` | `String[]` | `{}` | Filter by step IDs (empty = all steps) |
| `async` | `boolean` | `false` | Execute callback asynchronously |
| `priority` | `int` | `0` | Execution priority |
| `suppressError` | `boolean` | `false` | Suppress the error (prevent workflow failure) |

### Durable Execution Annotations

| Annotation | Attributes | Description |
|------------|------------|-------------|
| `@WaitForSignal` | `name`, `timeoutDuration` (ISO-8601), `timeoutAction` (`FAIL`/`ESCALATE`/`SKIP`) | Pause step until a named signal arrives |
| `@WaitForTimer` | `duration` (ISO-8601), `fireAt` (ISO-8601 instant) | Pause step until a timer fires |
| `@WaitForAll` | `signals` (`WaitForSignal[]`), `timers` (`WaitForTimer[]`) | Wait for ALL conditions (join pattern) |
| `@WaitForAny` | `signals` (`WaitForSignal[]`), `timers` (`WaitForTimer[]`) | Wait for ANY condition (race pattern) |
| `@ChildWorkflow` | `workflowId`, `waitForCompletion` (default `true`), `timeoutMs` | Spawn a child workflow from a step |
| `@CompensationStep` | `compensates` (step ID) | Declare compensation logic for a step |

---

## Model Types

### WorkflowStatus Enum

| Status | Terminal | Active | Can Suspend | Can Resume | Description |
|--------|----------|--------|-------------|------------|-------------|
| `PENDING` | No | No | No | No | Created but not started |
| `RUNNING` | No | Yes | Yes | No | Currently executing |
| `WAITING` | No | Yes | Yes | No | Waiting for external event |
| `SUSPENDED` | No | No | No | Yes | Paused by operator |
| `COMPLETED` | Yes | No | No | No | Successfully completed |
| `FAILED` | Yes | No | No | No | Failed with error |
| `CANCELLED` | Yes | No | No | No | Cancelled by user |
| `TIMED_OUT` | Yes | No | No | No | Timed out |

### TriggerMode Enum

| Value | Description |
|-------|-------------|
| `SYNC` | Synchronous trigger only |
| `ASYNC` | Asynchronous trigger only |
| `BOTH` | Both sync and async (default) |

### StepTriggerMode Enum

| Value | `allowsEventTrigger()` | `allowsProgrammaticTrigger()` | Description |
|-------|------------------------|-------------------------------|-------------|
| `EVENT` | `true` | `false` | Triggered by events only |
| `PROGRAMMATIC` | `false` | `true` | Invoked via API only |
| `BOTH` | `true` | `true` | Supports both (default) |

### CompensationPolicy Enum

| Value | Description |
|-------|-------------|
| `STRICT_SEQUENTIAL` | Compensate in reverse order, stop on first failure |
| `BEST_EFFORT` | Compensate all steps in reverse order, collect all errors |
| `SKIP` | No compensation, workflow fails immediately |

---

## Next Steps

- [Getting Started](getting-started.md) -- Prerequisites, cache setup, first workflow
- [Architecture](architecture.md) -- Internal components and execution model
- [Configuration](configuration.md) -- Complete property reference with defaults
- [Advanced Features](advanced-features.md) -- DAG execution, resilience, scheduling, DLQ
- [Durable Execution](durable-execution.md) -- Signals, timers, child workflows, compensation
- [Testing](testing.md) -- Unit and integration testing strategies
