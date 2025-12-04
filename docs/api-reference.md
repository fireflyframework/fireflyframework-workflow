# API Reference

This document provides a complete reference for the REST API and Java API of the Firefly Workflow Engine.

## REST API

The REST API is available at the configured base path (default: `/api/v1/workflows`).

### Base Path Configuration

```yaml
firefly:
  workflow:
    api:
      base-path: /api/v1/workflows
```

---

## Workflow Endpoints

### List Workflows

Lists all registered workflow definitions.

```http
GET /api/v1/workflows
```

**Response:**

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

Gets details of a specific workflow definition.

```http
GET /api/v1/workflows/{workflowId}
```

**Response:**

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
      "triggerMode": "EVENT",
      "async": false
    },
    {
      "stepId": "process-payment",
      "name": "Process Payment",
      "dependsOn": ["validate"],
      "triggerMode": "EVENT",
      "async": false
    }
  ]
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
  "waitTimeoutMs": 30000
}
```

**Request Body:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `input` | object | No | Input data for the workflow |
| `correlationId` | string | No | Correlation ID for tracking |
| `waitForCompletion` | boolean | No | Wait for workflow to complete |
| `waitTimeoutMs` | long | No | Timeout when waiting (default: 30000) |
| `dryRun` | boolean | No | Run without side effects (default: false) |

**Response (201 Created):**

```json
{
  "instanceId": "inst-789",
  "workflowId": "order-processing",
  "status": "RUNNING",
  "currentStepId": "validate",
  "completedSteps": 0,
  "totalSteps": 3,
  "createdAt": "2025-01-15T10:30:00Z",
  "startedAt": "2025-01-15T10:30:00Z"
}
```

---

## Instance Endpoints

### Get Instance Status

Gets the status of a workflow instance.

```http
GET /api/v1/workflows/{workflowId}/instances/{instanceId}/status
```

**Response:**

```json
{
  "instanceId": "inst-789",
  "workflowId": "order-processing",
  "status": "COMPLETED",
  "currentStepId": null,
  "completedSteps": 3,
  "totalSteps": 3,
  "createdAt": "2025-01-15T10:30:00Z",
  "startedAt": "2025-01-15T10:30:00Z",
  "completedAt": "2025-01-15T10:30:05Z"
}
```

### Collect Result

Collects the result of a completed workflow.

```http
GET /api/v1/workflows/{workflowId}/instances/{instanceId}/collect
```

**Response (200 OK - Completed):**

```json
{
  "status": "COMPLETED",
  "result": {
    "orderId": "ORD-123",
    "processed": true
  }
}
```

**Response (202 Accepted - Still Running):**

```json
{
  "status": "RUNNING",
  "message": "Workflow is still running"
}
```

### Cancel Workflow

Cancels a running workflow instance.

```http
POST /api/v1/workflows/{workflowId}/instances/{instanceId}/cancel
```

**Response:**

```json
{
  "instanceId": "inst-789",
  "workflowId": "order-processing",
  "status": "CANCELLED"
}
```

### Retry Workflow

Retries a failed workflow from the failed step.

```http
POST /api/v1/workflows/{workflowId}/instances/{instanceId}/retry
```

**Response:**

```json
{
  "instanceId": "inst-789",
  "workflowId": "order-processing",
  "status": "RUNNING",
  "currentStepId": "process-payment"
}
```

### Suspend Workflow

Suspends a running workflow. Useful during incidents or downstream outages.

```http
POST /api/v1/workflows/{workflowId}/instances/{instanceId}/suspend
Content-Type: application/json

{
  "reason": "Downstream service outage"
}
```

**Response:**

```json
{
  "instanceId": "inst-789",
  "workflowId": "order-processing",
  "status": "SUSPENDED",
  "suspendedAt": "2025-01-15T10:35:00Z",
  "suspendReason": "Downstream service outage"
}
```

### Resume Workflow

Resumes a suspended workflow.

```http
POST /api/v1/workflows/{workflowId}/instances/{instanceId}/resume
```

**Response:**

```json
{
  "instanceId": "inst-789",
  "workflowId": "order-processing",
  "status": "RUNNING"
}
```

### Get Workflow Topology

Gets the workflow DAG (nodes and edges) for visualization with React Flow or Mermaid.js.

```http
GET /api/v1/workflows/{workflowId}/topology
```

**Response:**

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

### Get Instance Topology

Gets the topology with step execution status for progress visualization.

```http
GET /api/v1/workflows/{workflowId}/instances/{instanceId}/topology
```

**Response:** Same as above, but `status` fields are populated with step execution status.

### List Instances

Lists all instances of a workflow.

```http
GET /api/v1/workflows/{workflowId}/instances
GET /api/v1/workflows/{workflowId}/instances?status=RUNNING
```

**Query Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `status` | string | Filter by status (PENDING, RUNNING, COMPLETED, FAILED, CANCELLED) |

---

## Step Endpoints

### Trigger Step

Triggers execution of a specific step (step-level choreography).

```http
POST /api/v1/workflows/{workflowId}/instances/{instanceId}/steps/{stepId}/trigger
Content-Type: application/json

{
  "input": {
    "additionalData": "value"
  }
}
```

**Response:**

```json
{
  "instanceId": "inst-789",
  "workflowId": "order-processing",
  "status": "RUNNING",
  "currentStepId": "process-payment"
}
```

### Get Step State

Gets the state of a specific step.

```http
GET /api/v1/workflows/{workflowId}/instances/{instanceId}/steps/{stepId}
```

**Response:**

```json
{
  "workflowId": "order-processing",
  "instanceId": "inst-789",
  "stepId": "validate",
  "status": "COMPLETED",
  "triggeredBy": "workflow",
  "input": {"orderId": "ORD-123"},
  "output": {"valid": true},
  "startedAt": "2025-01-15T10:30:00Z",
  "completedAt": "2025-01-15T10:30:01Z",
  "durationMs": 1000
}
```

### Get All Step States

Gets all step states for a workflow instance.

```http
GET /api/v1/workflows/{workflowId}/instances/{instanceId}/steps
```

**Response:**

```json
[
  {
    "stepId": "validate",
    "status": "COMPLETED",
    "durationMs": 1000
  },
  {
    "stepId": "process-payment",
    "status": "RUNNING",
    "durationMs": null
  }
]
```

### Get Workflow State (Dashboard View)

Gets comprehensive workflow state including all step tracking.

```http
GET /api/v1/workflows/{workflowId}/instances/{instanceId}/state
```

**Response:**

```json
{
  "workflowId": "order-processing",
  "instanceId": "inst-789",
  "status": "RUNNING",
  "totalSteps": 3,
  "completedSteps": ["validate"],
  "failedSteps": [],
  "pendingSteps": ["process-payment", "ship"],
  "skippedSteps": [],
  "currentStepId": "process-payment",
  "nextStepId": "ship",
  "createdAt": "2025-01-15T10:30:00Z",
  "updatedAt": "2025-01-15T10:30:01Z"
}
```

---

## Dead Letter Queue (DLQ) Endpoints

Endpoints for managing failed workflow entries. Available at `/api/v1/workflows/dlq`.

### List DLQ Entries

```http
GET /api/v1/workflows/dlq
GET /api/v1/workflows/dlq?workflowId=order-processing
```

**Query Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `workflowId` | string | Filter by workflow ID |
| `instanceId` | string | Filter by instance ID |

**Response:**

```json
[
  {
    "id": "dlq-123",
    "workflowId": "order-processing",
    "instanceId": "inst-789",
    "stepId": "process-payment",
    "stepName": "Process Payment",
    "errorMessage": "Payment gateway timeout",
    "errorType": "java.util.concurrent.TimeoutException",
    "attemptCount": 3,
    "correlationId": "corr-456",
    "triggeredBy": "event:order.validated",
    "createdAt": "2025-01-15T10:35:00Z",
    "lastAttemptAt": "2025-01-15T10:35:30Z"
  }
]
```

### Get DLQ Entry

```http
GET /api/v1/workflows/dlq/{id}
```

### Replay DLQ Entry

Replays a failed workflow/step from the DLQ.

```http
POST /api/v1/workflows/dlq/{id}/replay
Content-Type: application/json

{
  "modifiedInput": {
    "retryToken": "new-token"
  }
}
```

**Response:**

```json
{
  "entryId": "dlq-123",
  "success": true,
  "instanceId": "inst-new-456",
  "errorMessage": null
}
```

### Delete DLQ Entry

```http
DELETE /api/v1/workflows/dlq/{id}
```

### Get DLQ Count

```http
GET /api/v1/workflows/dlq/count
GET /api/v1/workflows/dlq/count?workflowId=order-processing
```

**Response:**

```json
{
  "count": 5
}
```

---

## Status Codes

| Code | Description |
|------|-------------|
| 200 | Success |
| 201 | Created (workflow started) |
| 202 | Accepted (workflow still running) |
| 400 | Bad Request (invalid input) |
| 404 | Not Found (workflow or instance not found) |
| 500 | Internal Server Error |
| 501 | Not Implemented (step state tracking disabled) |

---

## Java API

The `WorkflowEngine` class provides the main programmatic interface.

### Injection

```java
@Autowired
private WorkflowEngine workflowEngine;
```

### Starting Workflows

```java
// Simple start
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
```

### Getting Status

```java
// By workflow ID and instance ID
Mono<WorkflowInstance> status = workflowEngine.getStatus(
    "order-processing",
    instanceId
);

// By instance ID only
Mono<WorkflowInstance> status = workflowEngine.getStatus(instanceId);
```

### Collecting Results

```java
Mono<Order> result = workflowEngine.collectResult(
    "order-processing",
    instanceId,
    Order.class
);
```

### Triggering Steps

```java
Mono<WorkflowInstance> result = workflowEngine.triggerStep(
    "order-processing",
    instanceId,
    "process-payment",
    Map.of("paymentMethod", "credit"),
    "api"
);
```

### Step State

```java
// Get single step state
Mono<StepState> state = workflowEngine.getStepState(
    "order-processing",
    instanceId,
    "validate"
);

// Get all step states
Flux<StepState> states = workflowEngine.getStepStates(
    "order-processing",
    instanceId
);

// Get comprehensive workflow state
Mono<WorkflowState> state = workflowEngine.getWorkflowState(
    "order-processing",
    instanceId
);
```

### Cancelling and Retrying

```java
// Cancel a running workflow
Mono<WorkflowInstance> cancelled = workflowEngine.cancelWorkflow(
    "order-processing",
    instanceId
);

// Retry a failed workflow
Mono<WorkflowInstance> retried = workflowEngine.retryWorkflow(
    "order-processing",
    instanceId
);
```

### Finding Instances

```java
// All instances of a workflow
Flux<WorkflowInstance> instances = workflowEngine.findInstances(
    "order-processing"
);

// By status
Flux<WorkflowInstance> running = workflowEngine.findInstances(
    "order-processing",
    WorkflowStatus.RUNNING
);

// Active instances (running or waiting)
Flux<WorkflowInstance> active = workflowEngine.findActiveInstances();

// By correlation ID
Flux<WorkflowInstance> correlated = workflowEngine.findByCorrelationId(
    "correlation-456"
);
```

### Workflow Registration

```java
// Register a workflow definition
workflowEngine.registerWorkflow(definition);

// Unregister
boolean removed = workflowEngine.unregisterWorkflow("order-processing");

// Get definition
Optional<WorkflowDefinition> def = workflowEngine.getWorkflowDefinition(
    "order-processing"
);

// Get all workflows
Collection<WorkflowDefinition> all = workflowEngine.getAllWorkflows();
```

### Event-Based Discovery

```java
// Find workflows triggered by an event
Flux<WorkflowDefinition> workflows = workflowEngine.findWorkflowsByTriggerEvent(
    "order.created"
);

// Find steps waiting for an event
Flux<StepState> waiting = workflowEngine.findStepsWaitingForEvent(
    "payment.processed"
);

// Find step definitions by input event
List<WorkflowStepMatch> matches = workflowEngine.findStepsByInputEvent(
    "order.validated"
);
```

### Health Check

```java
Mono<Boolean> healthy = workflowEngine.isHealthy();
```

---

## WorkflowInstance Record

The `WorkflowInstance` record contains workflow execution state:

| Field | Type | Description |
|-------|------|-------------|
| `instanceId` | String | Unique instance identifier |
| `workflowId` | String | Workflow definition ID |
| `workflowName` | String | Human-readable name |
| `workflowVersion` | String | Version number |
| `status` | WorkflowStatus | Current status |
| `currentStepId` | String | Currently executing step |
| `context` | WorkflowContext | Execution context |
| `input` | Map | Input data |
| `output` | Object | Final output |
| `stepExecutions` | List | Step execution history |
| `errorMessage` | String | Error message if failed |
| `errorDetails` | String | Detailed error info |
| `correlationId` | String | Correlation ID |
| `triggeredBy` | String | Trigger source |
| `createdAt` | Instant | Creation timestamp |
| `startedAt` | Instant | Start timestamp |
| `completedAt` | Instant | Completion timestamp |

---

## WorkflowStatus Enum

| Status | Terminal | Description |
|--------|----------|-------------|
| `PENDING` | No | Created but not started |
| `RUNNING` | No | Currently executing |
| `WAITING` | No | Waiting for external event |
| `SUSPENDED` | No | Paused by operator |
| `COMPLETED` | Yes | Successfully completed |
| `FAILED` | Yes | Failed with error |
| `CANCELLED` | Yes | Cancelled by user |

---

## StepState Record

| Field | Type | Description |
|-------|------|-------------|
| `workflowId` | String | Workflow ID |
| `instanceId` | String | Instance ID |
| `stepId` | String | Step ID |
| `status` | StepStatus | Step status |
| `triggeredBy` | String | How step was triggered |
| `input` | Map | Step input |
| `output` | Object | Step output |
| `errorMessage` | String | Error if failed |
| `startedAt` | Instant | Start time |
| `completedAt` | Instant | Completion time |
| `durationMs` | Long | Execution duration |
| `attemptNumber` | int | Retry attempt number |

---

## StepStatus Enum

| Status | Description |
|--------|-------------|
| `PENDING` | Not yet executed |
| `RUNNING` | Currently executing |
| `COMPLETED` | Successfully completed |
| `FAILED` | Failed with error |
| `SKIPPED` | Skipped (condition false) |
| `RETRYING` | Retrying after failure |

---

## StepTriggerMode Enum

| Mode | Description |
|------|-------------|
| `EVENT` | Step is triggered by events only (recommended for choreography) |
| `PROGRAMMATIC` | Step is invoked via API only |
| `BOTH` | Supports both patterns (default) |

---

## WorkflowStepDefinition Fields

Step definitions returned by the API include:

| Field | Type | Description |
|-------|------|-------------|
| `stepId` | String | Unique step identifier |
| `name` | String | Human-readable name |
| `description` | String | Step description |
| `dependsOn` | String[] | Step IDs this step depends on |
| `triggerMode` | StepTriggerMode | How step can be invoked |
| `order` | int | Execution order (legacy) |
| `async` | boolean | Execute in parallel |
| `inputEventType` | String | Event to trigger step |
| `outputEventType` | String | Event to emit on completion |
| `condition` | String | SpEL condition |
| `timeoutMs` | long | Timeout in milliseconds |
| `maxRetries` | int | Retry attempts |
| `retryDelayMs` | long | Retry delay |

---

## Next Steps

- [Getting Started](getting-started.md) - Basic tutorial
- [Architecture](architecture.md) - System design
- [Advanced Features](advanced-features.md) - Resilience4j, choreography, and more
- [Configuration Reference](configuration.md) - All configuration options