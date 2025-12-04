# Architecture Overview

This document describes the architecture of the Firefly Workflow Engine, including component diagrams, data flow, and integration points.

## High-Level Architecture

The workflow engine follows a layered architecture with clear separation of concerns:

```mermaid
graph TB
    subgraph "Client Layer"
        REST[REST API Controller]
        EVENT[Event Listener]
        PROG[Programmatic API]
    end
    
    subgraph "Core Engine"
        WE[WorkflowEngine]
        WX[WorkflowExecutor]
        WR[WorkflowResilience]
    end
    
    subgraph "Cross-Cutting Concerns"
        TRACE[WorkflowTracer<br/>OpenTelemetry]
        METRICS[WorkflowMetrics<br/>Micrometer]
        HEALTH[HealthIndicator]
    end
    
    subgraph "Infrastructure"
        CACHE[(lib-common-cache<br/>Redis/Caffeine)]
        EDA[lib-common-eda<br/>Kafka/RabbitMQ]
    end
    
    REST --> WE
    EVENT --> WE
    PROG --> WE
    WE --> WX
    WX --> WR
    WX --> TRACE
    WX --> METRICS
    WE --> CACHE
    WE --> EDA
    WR --> WX
```

## Component Descriptions

### WorkflowEngine

The main facade providing high-level API for workflow operations:

- `startWorkflow()` - Start a new workflow instance
- `getStatus()` - Get workflow instance status
- `triggerStep()` - Trigger a specific step (choreography)
- `cancelWorkflow()` - Cancel a running workflow
- `retryWorkflow()` - Retry a failed workflow
- `collectResult()` - Get the result of a completed workflow
- `getWorkflowState()` - Get enriched workflow state with step details

### WorkflowExecutor

Executes workflow steps with resilience patterns:

- Manages step execution order
- Handles parallel step execution
- Evaluates SpEL conditions
- Applies retry logic
- Persists step and workflow state
- Publishes step events

### WorkflowResilience

Decorates step execution with Resilience4j patterns:

- **Circuit Breaker**: Prevents cascading failures
- **Rate Limiter**: Controls execution rate
- **Bulkhead**: Limits concurrent executions
- **Time Limiter**: Enforces timeouts

## Workflow Execution Flow

```mermaid
sequenceDiagram
    participant Client
    participant Engine as WorkflowEngine
    participant Executor as WorkflowExecutor
    participant Resilience as WorkflowResilience
    participant Cache as State Store
    participant Events as Event Publisher
    
    Client->>Engine: startWorkflow(workflowId, input)
    Engine->>Engine: Generate instanceId
    Engine->>Cache: Save initial state
    Engine->>Executor: execute(definition, context)
    
    loop For each step
        Executor->>Executor: Evaluate condition
        alt Condition passes
            Executor->>Cache: Save step state (RUNNING)
            Executor->>Resilience: decorateStep(mono)
            Resilience->>Executor: Execute step handler
            Executor->>Cache: Save step state (COMPLETED)
            Executor->>Events: Publish step.completed
        else Condition fails
            Executor->>Cache: Save step state (SKIPPED)
        end
    end
    
    Executor->>Cache: Save workflow state (COMPLETED)
    Executor->>Events: Publish workflow.completed
    Engine->>Client: Return WorkflowInstance
```

## Step-Level Choreography

The engine supports step-level choreography where steps can be triggered independently:

```mermaid
graph LR
    subgraph "Workflow Instance"
        S1[Step 1<br/>validate]
        S2[Step 2<br/>process]
        S3[Step 3<br/>notify]
    end
    
    subgraph "Events"
        E1((order.created))
        E2((order.validated))
        E3((order.processed))
    end
    
    E1 -->|triggers| S1
    S1 -->|emits| E2
    E2 -->|triggers| S2
    S2 -->|emits| E3
    E3 -->|triggers| S3
```

### Step State Persistence

Each step maintains its own state independent of the workflow:

```mermaid
erDiagram
    WORKFLOW_STATE ||--o{ STEP_STATE : contains
    
    WORKFLOW_STATE {
        string workflowId
        string instanceId
        string status
        int totalSteps
        set completedSteps
        set failedSteps
        set pendingSteps
        string currentStepId
        timestamp createdAt
        timestamp updatedAt
    }
    
    STEP_STATE {
        string workflowId
        string instanceId
        string stepId
        string status
        string triggeredBy
        object input
        object output
        timestamp startedAt
        timestamp completedAt
        string errorMessage
        int attemptNumber
    }
```

