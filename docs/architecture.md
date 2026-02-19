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

## Event Sourcing Architecture (Durable Execution)

When durable execution is enabled (`firefly.workflow.eventsourcing.enabled: true`), the workflow engine uses an event-sourced persistence model built on **fireflyframework-eventsourcing**. This provides full durability, replay capability, and audit history for workflow instances.

### WorkflowAggregate as AggregateRoot

Each workflow instance is modeled as a `WorkflowAggregate` that extends `AggregateRoot` from fireflyframework-eventsourcing. All state changes are expressed as immutable domain events stored in the R2DBC event store.

```mermaid
graph TB
    subgraph "WorkflowAggregate (extends AggregateRoot)"
        STATE[State<br/>status, stepExecutions, context,<br/>pendingSignals, activeTimers,<br/>childWorkflows, searchAttributes]
        COMMANDS[Commands<br/>start, completeStep, failStep,<br/>receiveSignal, fireTimer,<br/>spawnChildWorkflow, cancel]
        HANDLERS[Event Handlers<br/>onWorkflowStarted, onStepCompleted,<br/>onSignalReceived, onTimerFired, ...]
    end

    subgraph "Event Store (R2DBC)"
        EVENTS[(workflow_events)]
        SNAPSHOTS[(workflow_snapshots)]
    end

    COMMANDS -->|emit| EVENTS
    EVENTS -->|replay| HANDLERS
    HANDLERS -->|mutate| STATE
    STATE -->|periodic| SNAPSHOTS
```

### Event History as Source of Truth

The event store is the authoritative source for all workflow state. Every command on the `WorkflowAggregate` produces one or more domain events (~22 event types):

| Event | Trigger | Key Data |
|-------|---------|----------|
| `WorkflowStartedEvent` | Workflow begins | workflowId, definition snapshot, input |
| `StepStartedEvent` | Step begins executing | stepId, input, attemptNumber |
| `StepCompletedEvent` | Step succeeds | stepId, output, durationMs |
| `StepFailedEvent` | Step fails | stepId, error, retryable |
| `SignalReceivedEvent` | External signal arrives | signalName, payload |
| `TimerFiredEvent` | Timer fires | timerId |
| `ChildWorkflowSpawnedEvent` | Child created | childInstanceId, input |
| `ChildWorkflowCompletedEvent` | Child finishes | childInstanceId, output |
| `SideEffectRecordedEvent` | Non-deterministic capture | sideEffectId, value |
| `HeartbeatRecordedEvent` | Step progress report | stepId, details |
| `ContinueAsNewEvent` | History reset | newInput, previousRunOutput |
| `CompensationStartedEvent` | Rollback begins | failedStepId, policy |
| `SearchAttributeUpdatedEvent` | Attribute set | key, value |
| `WorkflowCompletedEvent` | All steps done | output, durationMs |
| `WorkflowFailedEvent` | Unrecoverable failure | error, failedStepId |
| `WorkflowCancelledEvent` | User cancels | reason |

### State Reconstruction from Events

The aggregate state is fully reconstructable from the event history. Each event type has a corresponding `on()` handler that performs a pure state mutation:

```
1. Load latest snapshot (if exists) → WorkflowAggregate at version N
2. Load events from version N+1 → current
3. Apply each event via on() handler → full current state
4. Ready for new commands
```

```java
// Simplified state reconstruction
WorkflowAggregate aggregate = new WorkflowAggregate();

// Apply snapshot (if available)
if (snapshot != null) {
    aggregate.restoreFromSnapshot(snapshot);
}

// Replay events since snapshot
for (DomainEvent event : eventsSinceSnapshot) {
    aggregate.apply(event);  // pure state mutation, no side effects
}

// Aggregate is now at current state, ready for commands
aggregate.completeStep("validate", output);
```

### Snapshot Optimization

To avoid replaying the entire event history on every load, the engine takes periodic snapshots of aggregate state:

- **Default threshold**: Snapshot every 20 events (configurable via `firefly.workflow.eventsourcing.snapshot-threshold`)
- **Storage**: Snapshots are stored in the `workflow_snapshots` table
- **Reconstruction**: Load snapshot + replay only events since the snapshot

```mermaid
graph LR
    subgraph "Without Snapshots"
        E1[Event 1] --> E2[Event 2] --> E3[...] --> EN[Event N]
    end

    subgraph "With Snapshots"
        S1[Snapshot @ v20] --> E21[Event 21] --> E22[Event 22] --> E23[...]
    end
```

For long-running workflows that accumulate thousands of events, the `continue-as-new` mechanism resets the event history entirely (see [Advanced Features](advanced-features.md)).

### Integration with fireflyframework-eventsourcing

The workflow engine reuses the full eventsourcing infrastructure:

| Component | Usage |
|-----------|-------|
| `AggregateRoot` | Base class for `WorkflowAggregate` |
| `EventStore` | Persists domain events to R2DBC `workflow_events` table |
| `SnapshotStore` | Persists snapshots to `workflow_snapshots` table |
| `Outbox` | Reliable event publishing via transactional outbox pattern |
| `Projections` | Materializes read models (timers, search attributes) |
| `Optimistic Concurrency` | Prevents conflicting concurrent updates to the same aggregate |

### Cache as Read-Through Optimization

With event sourcing enabled, the existing fireflyframework-cache layer transitions from being the primary state store to a **read-through cache**:

```mermaid
graph LR
    subgraph "Read Path"
        API[REST API / Query] --> CACHE[(Cache<br/>Redis/Caffeine)]
        CACHE -->|miss| ES[Event Store]
        ES -->|reconstruct| AGG[WorkflowAggregate]
        AGG -->|populate| CACHE
    end

    subgraph "Write Path"
        CMD[Command] --> AGG2[WorkflowAggregate]
        AGG2 -->|append events| ES2[Event Store]
        AGG2 -->|invalidate/update| CACHE2[(Cache)]
    end
```

- **Reads**: Cache is checked first; on miss, the aggregate is reconstructed from events and the cache is populated
- **Writes**: Events are appended to the event store (source of truth), then the cache is updated or invalidated
- **Existing cache-only workflows**: Continue to work unchanged when `eventsourcing.enabled` is `false` (the default)

### Database Schema

The durable execution engine adds the following R2DBC tables (managed via Flyway):

| Table | Purpose |
|-------|---------|
| `workflow_events` | Domain events (uses eventsourcing's `events` table schema) |
| `workflow_snapshots` | Aggregate snapshots (uses eventsourcing's `snapshots` table schema) |
| `workflow_timers` | Timer projection (timerId, instanceId, fireAt, status) |
| `workflow_search_attributes` | Search attribute projection (instanceId, attributes JSONB, indexed) |

## Next Steps

- [Getting Started](getting-started.md) - Step-by-step tutorial
- [Advanced Features](advanced-features.md) - Resilience4j, choreography, and more
- [Configuration Reference](configuration.md) - All configuration options
- [API Reference](api-reference.md) - REST and Java API documentation