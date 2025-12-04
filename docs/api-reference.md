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
      "order": 1,
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

