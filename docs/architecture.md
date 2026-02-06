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
        CACHE[(fireflyframework-cache<br/>Redis/Caffeine)]
        EDA[fireflyframework-eda<br/>Kafka/RabbitMQ]
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

- Manages step execution order via topology-based layers
- Handles parallel step execution within dependency layers
- Evaluates SpEL conditions
- Applies retry logic
- Persists step and workflow state
- Publishes step events

### WorkflowTopology

Manages the dependency graph (DAG) of workflow steps:

- Builds directed acyclic graph from `dependsOn` declarations
- Validates dependencies exist and detects cycles
- Computes execution layers using Kahn's algorithm
- Enables parallel execution of independent steps

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

## Topology-Based Execution

The workflow engine uses a **topology-based execution model** where steps are organized into execution layers based on their dependencies. This is the recommended approach for controlling step execution order.

### Dependency Graph (DAG)

Steps declare dependencies using the `dependsOn` attribute, forming a Directed Acyclic Graph (DAG):

```mermaid
graph TD
    subgraph "Workflow: order-fulfillment"
        A[validate] --> B[check-inventory]
        A --> C[process-payment]
        B --> D[ship]
        C --> D
        D --> E[notify]
    end
```

### Execution Layers

The `WorkflowTopology` class uses **Kahn's algorithm** to compute execution layers:

```mermaid
graph LR
    subgraph "Layer 0"
        L0[validate]
    end
    subgraph "Layer 1 (parallel)"
        L1A[check-inventory]
        L1B[process-payment]
    end
    subgraph "Layer 2"
        L2[ship]
    end
    subgraph "Layer 3"
        L3[notify]
    end

    L0 --> L1A
    L0 --> L1B
    L1A --> L2
    L1B --> L2
    L2 --> L3
```

**Key Properties:**
- **Layer 0**: Contains root steps (no dependencies)
- **Subsequent Layers**: Steps whose dependencies are all in previous layers
- **Parallel Execution**: Steps within the same layer can execute concurrently
- **Deterministic Order**: Steps within a layer are sorted by `order` attribute

### Topology Validation

The topology is validated during workflow registration:

```mermaid
sequenceDiagram
    participant Registry as WorkflowRegistry
    participant Topology as WorkflowTopology
    participant Validator as Validation

    Registry->>Topology: new WorkflowTopology(definition)
    Topology->>Topology: buildGraph()
    Registry->>Topology: validate()
    Topology->>Validator: validateDependenciesExist()
    alt Missing dependency
        Validator-->>Registry: WorkflowValidationException
    end
    Topology->>Validator: validateNoCycles()
    alt Cycle detected
        Validator-->>Registry: WorkflowValidationException
    end
    Topology-->>Registry: Validation passed
    Registry->>Registry: Register workflow
```

**Validation Checks:**
1. **Missing Dependencies**: All referenced step IDs must exist in the workflow
2. **Cycle Detection**: No circular dependencies allowed (uses DFS with recursion stack)

### Kahn's Algorithm Implementation

The algorithm builds execution layers as follows:

1. Calculate in-degree (number of dependencies) for each step
2. Add all steps with in-degree 0 to the first layer
3. For each step in the current layer:
   - Mark as processed
   - Decrement in-degree of dependent steps
4. Repeat until all steps are processed

```java
// Simplified algorithm
while (processed.size() < totalSteps) {
    List<Step> currentLayer = steps.stream()
        .filter(s -> inDegree.get(s) == 0 && !processed.contains(s))
        .collect(toList());

    layers.add(currentLayer);

    for (Step step : currentLayer) {
        processed.add(step);
        for (Step dependent : getDependents(step)) {
            inDegree.put(dependent, inDegree.get(dependent) - 1);
        }
    }
}
```

### Backward Compatibility

If no steps have explicit dependencies, the engine falls back to **order-based execution**:

```mermaid
graph LR
    subgraph "Order-Based (Legacy)"
        O1[order=1] --> O2[order=2] --> O3[order=3]
    end
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

## Cache Key Structure

State is stored in the cache with the following key patterns:

| Key Pattern | Description |
|-------------|-------------|
| `workflow:{workflowId}:{instanceId}` | Workflow instance data |
| `workflow:state:{workflowId}:{instanceId}` | Enriched workflow state |
| `workflow:step:{workflowId}:{instanceId}:{stepId}` | Individual step state |

## Resilience4j Integration

The resilience layer wraps step execution with multiple patterns:

```mermaid
graph LR
    subgraph "Resilience Decorators"
        CB[Circuit Breaker]
        RL[Rate Limiter]
        BH[Bulkhead]
        TL[Time Limiter]
    end

    STEP[Step Execution] --> TL
    TL --> BH
    BH --> RL
    RL --> CB
    CB --> HANDLER[Step Handler]
```

### Circuit Breaker States

```mermaid
stateDiagram-v2
    [*] --> CLOSED
    CLOSED --> OPEN: Failure rate > threshold
    OPEN --> HALF_OPEN: Wait duration elapsed
    HALF_OPEN --> CLOSED: Permitted calls succeed
    HALF_OPEN --> OPEN: Permitted calls fail
```

## Auto-Configuration

The library uses Spring Boot auto-configuration:

```mermaid
graph TB
    subgraph "Auto-Configuration Classes"
        WAC[WorkflowAutoConfiguration]
        WEAC[WorkflowEngineAutoConfiguration]
        WRAC[WorkflowResilienceAutoConfiguration]
    end

    subgraph "Beans Created"
        WE[WorkflowEngine]
        WX[WorkflowExecutor]
        WR[WorkflowResilience]
        WC[WorkflowController]
        WM[WorkflowMetrics]
        WT[WorkflowTracer]
        WH[WorkflowHealthIndicator]
        WEP[WorkflowEventPublisher]
    end

    WAC --> WE
    WAC --> WX
    WAC --> WC
    WAC --> WM
    WAC --> WT
    WAC --> WH
    WAC --> WEP
    WEAC --> WE
    WRAC --> WR
```

## Event Flow

```mermaid
graph TB
    subgraph "Workflow Events"
        WS[workflow.started]
        WC[workflow.completed]
        WF[workflow.failed]
        WX[workflow.cancelled]
    end

    subgraph "Step Events"
        SS[workflow.step.started]
        SC[workflow.step.completed]
        SF[workflow.step.failed]
        SR[workflow.step.retrying]
    end

    subgraph "Custom Events"
        CE[outputEventType<br/>e.g., order.validated]
    end

    START((Start)) --> WS
    WS --> SS
    SS --> SC
    SC --> CE
    SC --> SS
    SS --> SF
    SF --> SR
    SR --> SS
    SC --> WC
    SF --> WF
    CANCEL((Cancel)) --> WX
```

## Integration Points

### fireflyframework-cache

Used for state persistence:
- Workflow instance state
- Step execution state
- Workflow state aggregation

Supports:
- Redis (production)
- Caffeine (development/testing)

### fireflyframework-eda

Used for event publishing and listening:
- Workflow lifecycle events
- Step lifecycle events
- Custom step output events
- Workflow trigger events

Supports:
- Kafka
- RabbitMQ
- SNS/SQS
- Application Events (in-memory)

## Thread Model

The workflow engine uses Project Reactor for non-blocking execution:

```mermaid
graph TB
    subgraph "Request Thread"
        REQ[HTTP Request]
    end

    subgraph "Reactor Schedulers"
        BOUND[boundedElastic<br/>Blocking I/O]
        PARALLEL[parallel<br/>CPU-bound]
    end

    subgraph "Execution"
        STEP1[Step 1]
        STEP2[Step 2]
        STEP3[Step 3]
    end

    REQ --> STEP1
    STEP1 -->|async| BOUND
    STEP2 -->|async| BOUND
    STEP3 -->|sync| PARALLEL
```

## Next Steps

- [Getting Started](getting-started.md) - Step-by-step tutorial
- [Advanced Features](advanced-features.md) - Resilience4j, choreography, and more
- [Configuration Reference](configuration.md) - All configuration options
- [API Reference](api-reference.md) - REST and Java API documentation