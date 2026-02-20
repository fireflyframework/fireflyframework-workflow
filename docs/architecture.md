# Architecture

This document describes the architecture of the Firefly Workflow Engine: its components, execution model, state management, and auto-configuration.

## System Architecture

```
                        ┌────────────────────────────┐
                        │  WorkflowController        │  REST API Layer
                        │  DeadLetterController      │
                        └──────────────┬─────────────┘
                                       │
                        ┌──────────────▼─────────────┐
                        │  WorkflowService           │  Service Layer (DTO mapping)
                        └──────────────┬─────────────┘
                                       │
                        ┌──────────────▼─────────────┐
                        │  WorkflowEngine            │  Orchestration Facade
                        └──────────────┬─────────────┘
                                       │
            ┌──────────────────────────┼─────────────────────────────┐
            │                          │                             │
┌───────────▼───────────┐ ┌────────────▼────────────┐ ┌──────────────▼──────────────┐
│  WorkflowRegistry     │ │  WorkflowExecutor       │ │  WorkflowEventPublisher     │
│  (definitions store)  │ │  (step execution)       │ │  (via fireflyframework-     │
└───────────────────────┘ └────────────┬────────────┘ │   eda)                      │
                                       │              └─────────────────────────────┘
                           ┌───────────▼──────────┐
                           │  WorkflowTopology    │  DAG / Kahn's Algorithm
                           └───────────┬──────────┘
                                       │
                ┌──────────────────────┼─────────────────────┐
                │                                            │
┌───────────────▼──────────────┐          ┌──────────────────▼─────────────────┐
│  CacheWorkflowStateStore     │          │  EventSourcedWorkflowStateStore    │
│  CacheStepStateStore         │          │  (opt-in, @Primary when enabled)   │
│  (default, requires          │          │  Uses: fireflyframework-           │
│   CacheAdapter)              │          │   eventsourcing EventStore         │
└──────────────────────────────┘          └────────────────────────────────────┘
```

## Core Components

### WorkflowEngine

The main facade for all workflow operations. It delegates to the registry, executor, state store, and event publisher.

**Key methods:**

| Method | Description |
|--------|-------------|
| `startWorkflow(workflowId, input, ...)` | Creates and executes a new workflow instance |
| `getStatus(workflowId, instanceId)` | Returns the current workflow instance state |
| `collectResult(workflowId, instanceId, resultType)` | Returns the output of a completed workflow |
| `cancelWorkflow(workflowId, instanceId)` | Cancels a running workflow |
| `retryWorkflow(workflowId, instanceId)` | Retries a failed workflow from the failed step |
| `suspendWorkflow(workflowId, instanceId, reason)` | Suspends a running workflow |
| `resumeWorkflow(workflowId, instanceId)` | Resumes a suspended workflow |
| `triggerStep(workflowId, instanceId, stepId, input, triggeredBy)` | Triggers a specific step |
| `getStepState(workflowId, instanceId, stepId)` | Returns step-level state (requires StepStateStore) |
| `getStepStates(workflowId, instanceId)` | Returns all step states for an instance |
| `getWorkflowState(workflowId, instanceId)` | Returns comprehensive workflow state (dashboard view) |
| `findInstances(workflowId)` | Lists all instances of a workflow |
| `findActiveInstances()` | Lists all running/waiting instances |
| `findSuspendedInstances()` | Lists all suspended instances |
| `findByCorrelationId(correlationId)` | Finds instances by correlation ID |
| `registerWorkflow(definition)` | Registers a workflow definition |
| `unregisterWorkflow(workflowId)` | Removes a workflow definition |

### WorkflowExecutor

Executes workflow steps according to the topology. For each execution layer computed by `WorkflowTopology`, the executor:

1. Evaluates SpEL conditions on each step
2. Applies resilience decorators (if `WorkflowResilience` is available)
3. Invokes the step handler (annotation method or `StepHandler<T>` bean)
4. Records step execution results
5. Saves state via the state store
6. Publishes step events
7. Calls lifecycle callbacks (`@OnStepComplete`)

The executor receives optional dependencies via constructor injection: `StepStateStore`, `WorkflowTracer`, `WorkflowMetrics`, `WorkflowResilience`, and `DeadLetterStore`.

### WorkflowRegistry

Stores and retrieves `WorkflowDefinition` instances. Supports:

- Registration by workflow ID
- Lookup by ID (latest version) or by ID + version
- Lookup by trigger event type
- Unregistration

`WorkflowAspect` scans `@Workflow` annotated beans at startup and registers them with the registry.

### WorkflowTopology

Builds and validates the step dependency graph. Given a `WorkflowDefinition`, it:

1. **Builds the graph**: Creates adjacency lists for dependencies (`dependencyGraph`) and reverse dependencies (`reverseDependencyGraph`)
2. **Validates**: Checks that all referenced step IDs exist and that no circular dependencies exist (DFS with recursion stack)
3. **Computes execution layers** using Kahn's algorithm:
   - Layer 0: Steps with no dependencies (root steps)
   - Layer N: Steps whose dependencies are all in layers 0..N-1
   - Steps within a layer are sorted by `order` for determinism

If no steps use `dependsOn`, falls back to order-based grouping where each unique `order` value becomes a layer.

### WorkflowAspect

A Spring AOP component that scans the application context for beans annotated with `@Workflow`. For each one, it:

1. Reads the `@Workflow` annotation attributes
2. Scans methods for `@WorkflowStep`, `@OnStepComplete`, `@OnWorkflowComplete`, `@OnWorkflowError`
3. Builds a `WorkflowDefinition` with `WorkflowStepDefinition` entries
4. Registers the definition with `WorkflowRegistry`

### WorkflowService

Service layer between the REST controller and `WorkflowEngine`. Handles:

- DTO conversion (`WorkflowInstance` to `WorkflowStatusResponse`, etc.)
- Wait-for-completion polling logic
- Dry-run passthrough

### WorkflowEventPublisher

Publishes workflow lifecycle events using `fireflyframework-eda` `EventPublisherFactory`. Events are sent to the configured destination (`firefly.workflow.events.default-destination`, default: `"workflow-events"`). Published events include workflow lifecycle transitions (`workflow.started`, `workflow.completed`, `workflow.failed`, etc.) and step lifecycle transitions (`workflow.step.started`, `workflow.step.completed`, etc.). When a step has an `outputEventType`, the executor also publishes a custom event with the step's output as payload, enabling step choreography chains.

Publishing failures are silently swallowed (`onErrorResume(e -> Mono.empty())`) to prevent broker outages from failing workflows. This bean is only created when `EventPublisherFactory` is available from `fireflyframework-eda`.

### WorkflowEventListener

Receives inbound events from Kafka or RabbitMQ (bridged via Spring `@EventListener` on `EventEnvelope`) and processes them in four sequential phases:

1. **Resume WAITING workflows** -- matches by correlation ID or `_waitingForEvent` context key (glob pattern)
2. **Resume waiting steps** -- calls `stepStateStore.findStepsWaitingForEvent(eventType)` (requires `StepStateStore`)
3. **Trigger steps by input event** -- exact string match on `WorkflowStepDefinition.inputEventType()` across all registered workflows
4. **Trigger workflows by trigger event** -- glob pattern match on `WorkflowDefinition.triggerEventType()`, filtered by `supportsAsyncTrigger()`

All errors in the listener are swallowed to prevent bad events from crashing the listener. The bean is enabled by default (`events.listen-enabled=true`).

For the complete EDA integration guide, see [EDA Integration](eda-integration.md).

## State Management

### WorkflowStateStore Interface

The `WorkflowStateStore` interface defines the contract for persisting workflow instances:

| Method | Description |
|--------|-------------|
| `save(instance)` | Persists a workflow instance |
| `save(instance, ttl)` | Persists with explicit TTL |
| `findById(instanceId)` | Loads by instance ID |
| `findByWorkflowAndInstanceId(workflowId, instanceId)` | Loads by both IDs |
| `findByWorkflowId(workflowId)` | Lists instances for a workflow |
| `findByStatus(status)` | Lists instances by status |
| `findByWorkflowIdAndStatus(workflowId, status)` | Combined filter |
| `findByCorrelationId(correlationId)` | Finds by correlation ID |
| `findActiveInstances()` | Lists RUNNING/WAITING instances |
| `findStaleInstances(maxAge)` | Finds stale instances for recovery |
| `exists(instanceId)` | Checks existence |
| `delete(instanceId)` | Deletes an instance |
| `deleteByWorkflowId(workflowId)` | Deletes all instances for a workflow |
| `updateStatus(instanceId, expected, newStatus)` | Atomic status transition |
| `countByWorkflowId(workflowId)` | Count instances |
| `countByWorkflowIdAndStatus(workflowId, status)` | Count by status |
| `isHealthy()` | Health check |

### CacheWorkflowStateStore (Default)

Uses `fireflyframework-cache` `CacheAdapter` for persistence. Requires a `CacheAdapter` bean. Stores workflow instances as cache entries with configurable TTL and key prefix (`firefly.workflow.state.key-prefix`, default: `"workflow"`).

### EventSourcedWorkflowStateStore (Durable)

Created when `firefly.workflow.eventsourcing.enabled=true` and an `EventStore` bean is available. Marked with `@Primary` to override the cache-backed store.

**How it works:**

- `findById(instanceId)`: Loads the `WorkflowAggregate` by replaying its event stream from the `EventStore`, then converts to `WorkflowInstance`
- `save(instance)`: Returns the instance as-is (state is managed via aggregate events, not direct saves)
- `findByWorkflowId`, `findByStatus`, `findActiveInstances`, etc.: Return empty results (require read-side projections not yet built)
- `delete(instanceId)`: Returns `false` (events are immutable)
- `updateStatus(instanceId, expected, newStatus)`: Loads aggregate, validates expected status, applies transition command, saves aggregate

Additional aggregate-level methods:

| Method | Description |
|--------|-------------|
| `loadAggregate(aggregateId)` | Loads a `WorkflowAggregate` by replaying events |
| `saveAggregate(aggregate)` | Appends uncommitted events with optimistic concurrency |
| `toWorkflowInstance(aggregate)` | Converts aggregate state to `WorkflowInstance` |

## Model Classes

### WorkflowDefinition

A record containing the workflow blueprint:

| Field | Type | Description |
|-------|------|-------------|
| `workflowId` | `String` | Unique identifier |
| `name` | `String` | Human-readable name |
| `description` | `String` | Description |
| `version` | `String` | Version (default: `"1.0.0"`) |
| `steps` | `List<WorkflowStepDefinition>` | Ordered step list |
| `triggerMode` | `TriggerMode` | `SYNC`, `ASYNC`, or `BOTH` |
| `triggerEventType` | `String` | Event type for async triggering |
| `timeout` | `Duration` | Workflow timeout (default: 1h) |
| `retryPolicy` | `RetryPolicy` | Default retry policy |
| `metadata` | `Map<String, Object>` | Additional metadata |
| `workflowBean` | `Object` | Bean instance (set by WorkflowAspect) |
| `onStepCompleteMethod` | `Method` | Callback method |
| `onWorkflowCompleteMethod` | `Method` | Callback method |
| `onWorkflowErrorMethod` | `Method` | Callback method |

### WorkflowInstance

A record representing a running or completed workflow:

| Field | Type | Description |
|-------|------|-------------|
| `instanceId` | `String` | Unique instance identifier |
| `workflowId` | `String` | Definition ID |
| `workflowName` | `String` | Workflow name |
| `workflowVersion` | `String` | Version at start time |
| `status` | `WorkflowStatus` | Current status |
| `currentStepId` | `String` | Currently executing step |
| `context` | `Map<String, Object>` | Shared context data |
| `input` | `Map<String, Object>` | Original input |
| `output` | `Object` | Final output |
| `stepExecutions` | `List<StepExecution>` | Step execution records |
| `errorMessage` | `String` | Error message (if failed) |
| `errorType` | `String` | Error type (if failed) |
| `correlationId` | `String` | Correlation ID |
| `triggeredBy` | `String` | Trigger source |
| `createdAt` | `Instant` | Creation timestamp |
| `startedAt` | `Instant` | Start timestamp |
| `completedAt` | `Instant` | Completion timestamp |

### WorkflowStatus

| Status | Terminal | Active | Can Suspend | Can Resume |
|--------|----------|--------|-------------|------------|
| `PENDING` | No | Yes | Yes | No |
| `RUNNING` | No | Yes | Yes | No |
| `WAITING` | No | Yes | Yes | No |
| `SUSPENDED` | No | No | No | Yes |
| `COMPLETED` | Yes | No | No | No |
| `FAILED` | Yes | No | No | No |
| `CANCELLED` | Yes | No | No | No |
| `TIMED_OUT` | Yes | No | No | No |

### StepExecution

A record representing the execution of a single step:

| Field | Type | Description |
|-------|------|-------------|
| `executionId` | `String` | Execution identifier |
| `stepId` | `String` | Step identifier |
| `stepName` | `String` | Step name |
| `status` | `StepStatus` | Step status |
| `input` | `Map<String, Object>` | Step input |
| `output` | `Object` | Step output |
| `errorMessage` | `String` | Error message |
| `errorType` | `String` | Error type |
| `attemptNumber` | `int` | Retry attempt number |
| `startedAt` | `Instant` | Start timestamp |
| `completedAt` | `Instant` | Completion timestamp |

## Auto-Configuration

`WorkflowEngineAutoConfiguration` creates all beans conditionally. The table below shows each bean, its conditions, and dependencies.

| Bean | Class | Condition |
|------|-------|-----------|
| `workflowStateStore` | `CacheWorkflowStateStore` | `@ConditionalOnBean(CacheAdapter.class)` |
| `stepStateStore` | `CacheStepStateStore` | `@ConditionalOnBean(CacheAdapter.class)` + `step-state.enabled=true` |
| `workflowEventPublisher` | `WorkflowEventPublisher` | `@ConditionalOnBean(EventPublisherFactory.class)` |
| `workflowRegistry` | `WorkflowRegistry` | Always |
| `workflowExecutor` | `WorkflowExecutor` | Requires `WorkflowStateStore`, `WorkflowEventPublisher` |
| `workflowEngine` | `WorkflowEngine` | Requires `WorkflowRegistry`, `WorkflowExecutor`, `WorkflowStateStore` |
| `workflowEventListener` | `WorkflowEventListener` | `events.listen-enabled=true` (default: true) |
| `workflowAspect` | `WorkflowAspect` | Always |
| `workflowService` | `WorkflowService` | Always |
| `eventSourcedWorkflowStateStore` | `EventSourcedWorkflowStateStore` | `@Primary`, requires `EventStore`, `eventsourcing.enabled=true` |
| `signalService` | `SignalService` | Requires `EventSourcedWorkflowStateStore`, `signals.enabled=true` |
| `workflowQueryService` | `WorkflowQueryService` | Requires `EventSourcedWorkflowStateStore` |
| `workflowTimerProjection` | `WorkflowTimerProjection` | `timers.enabled=true` |
| `timerSchedulerService` | `TimerSchedulerService` | Requires `WorkflowTimerProjection` + `EventSourcedWorkflowStateStore` |
| `childWorkflowService` | `ChildWorkflowService` | Requires `EventSourcedWorkflowStateStore`, `child-workflows.enabled=true` |
| `compensationOrchestrator` | `CompensationOrchestrator` | Requires `EventSourcedWorkflowStateStore`, `compensation.enabled=true` |
| `searchAttributeProjection` | `SearchAttributeProjection` | `search-attributes.enabled=true` |
| `workflowSearchService` | `WorkflowSearchService` | Requires `SearchAttributeProjection` + `EventSourcedWorkflowStateStore` |
| `continueAsNewService` | `ContinueAsNewService` | Requires `EventSourcedWorkflowStateStore` |
| `workflowController` | `WorkflowController` | `api.enabled=true` (default: true) |
| `workflowEngineHealthIndicator` | `WorkflowEngineHealthIndicator` | `health-indicator-enabled=true` |
| `workflowTaskScheduler` | `ThreadPoolTaskScheduler` | `scheduling.enabled=true` |
| `workflowScheduler` | `WorkflowScheduler` | `scheduling.enabled=true` |
| `deadLetterStore` | `CacheDeadLetterStore` | `@ConditionalOnBean(CacheAdapter.class)`, `dlq.enabled=true` |
| `deadLetterService` | `DeadLetterService` | Requires `DeadLetterStore`, `dlq.enabled=true` |
| `deadLetterController` | `DeadLetterController` | Requires `DeadLetterService`, `api.enabled=true` |
| `workflowRecoveryService` | `WorkflowRecoveryService` | `recovery.enabled=true` |

Additional auto-configuration classes:

- `WorkflowMetricsAutoConfiguration` -- creates `WorkflowMetrics` when Micrometer is present
- `WorkflowResilienceAutoConfiguration` -- creates `WorkflowResilience` when Resilience4j is present
- `WorkflowTracingAutoConfiguration` -- creates `WorkflowTracer` when OpenTelemetry is present

## Domain Events (Durable Execution)

When durable execution is enabled, the `WorkflowAggregate` produces 22 domain event types:

| # | Event | Category | Description |
|---|-------|----------|-------------|
| 1 | `WorkflowStartedEvent` | Lifecycle | Workflow execution started |
| 2 | `WorkflowCompletedEvent` | Lifecycle | Workflow completed successfully |
| 3 | `WorkflowFailedEvent` | Lifecycle | Workflow failed |
| 4 | `WorkflowCancelledEvent` | Lifecycle | Workflow cancelled |
| 5 | `WorkflowSuspendedEvent` | Lifecycle | Workflow suspended |
| 6 | `WorkflowResumedEvent` | Lifecycle | Workflow resumed |
| 7 | `StepStartedEvent` | Step | Step execution began |
| 8 | `StepCompletedEvent` | Step | Step completed |
| 9 | `StepFailedEvent` | Step | Step failed |
| 10 | `StepSkippedEvent` | Step | Step skipped |
| 11 | `StepRetriedEvent` | Step | Step retry |
| 12 | `SignalReceivedEvent` | Signal | External signal received |
| 13 | `TimerRegisteredEvent` | Timer | Timer scheduled |
| 14 | `TimerFiredEvent` | Timer | Timer fired |
| 15 | `ChildWorkflowSpawnedEvent` | Child | Child workflow started |
| 16 | `ChildWorkflowCompletedEvent` | Child | Child workflow finished |
| 17 | `SideEffectRecordedEvent` | Replay | Side effect value recorded |
| 18 | `HeartbeatRecordedEvent` | Heartbeat | Step heartbeat |
| 19 | `ContinueAsNewEvent` | Lifecycle | Workflow continued as new |
| 20 | `CompensationStartedEvent` | Compensation | Compensation started |
| 21 | `CompensationStepCompletedEvent` | Compensation | Compensation step done |
| 22 | `SearchAttributeUpdatedEvent` | Search | Search attribute updated |

## Next Steps

- [Getting Started](getting-started.md) -- Setup and first workflow
- [EDA Integration](eda-integration.md) -- Event-driven triggering, step choreography, lifecycle events
- [Configuration](configuration.md) -- Complete property reference
- [API Reference](api-reference.md) -- REST and Java API
- [Durable Execution](durable-execution.md) -- Event sourcing deep dive
