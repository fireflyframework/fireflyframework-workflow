# Firefly Workflow Engine

[![CI](https://github.com/fireflyframework/fireflyframework-workflow/actions/workflows/ci.yml/badge.svg)](https://github.com/fireflyframework/fireflyframework-workflow/actions/workflows/ci.yml)

[![Java](https://img.shields.io/badge/Java-21-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Resilience4j](https://img.shields.io/badge/Resilience4j-2.x-orange.svg)](https://resilience4j.readme.io/)

A production-ready, **event-driven** workflow orchestration library for the Firefly Framework. This library enables **microservice choreography** through event-based communication, where workflows are triggered by domain events and individual steps can independently listen for and emit events.

### What This Library Solves

In distributed microservice architectures, coordinating multi-step business processes across services is challenging. This library provides:

- **Decoupled Process Orchestration**: Define business workflows that span multiple services without tight coupling. Each step can be triggered independently by events, enabling true choreography patterns where services communicate through events rather than direct calls.
- **Durable State Management**: Workflow and step states are persisted to Redis (via `fireflyframework-cache`), ensuring processes survive service restarts and enabling long-running workflows that may span hours or days.
- **Production-Grade Resilience**: Built-in circuit breakers, rate limiters, bulkheads, and time limiters (via Resilience4j) protect your workflows from cascading failures and resource exhaustion.
- **Full Observability**: OpenTelemetry tracing and Micrometer metrics provide visibility into workflow execution, step durations, failure rates, and system health.

### Workflow Engine vs. Transactional Engine

> **Important**: This is a **business process workflow engine**, NOT a saga/transactional engine.

| Aspect | fireflyframework-workflow-engine | lib-transactional-engine |
|--------|---------------------|--------------------------|
| **Purpose** | Orchestrate multi-step business processes | Manage distributed transactions with compensation |
| **Consistency** | Eventually consistent, event-driven | ACID-like guarantees with saga patterns |
| **Failure Handling** | Retry, skip, or fail steps | Automatic compensation/rollback |
| **Use Cases** | Order processing, onboarding flows, approval workflows | Payment processing, inventory updates, financial transfers |

Use `lib-transactional-engine` when you need distributed transaction guarantees with automatic compensation. Use this library when you need flexible, event-driven business process orchestration.

## ✨ Key Features

| Feature                         | Description |
|---------------------------------|-------------|
| ** Event-Driven Architecture**  | Workflows triggered by events, steps that listen for and emit events |
| ** Step-Level Choreography**    | Independent step triggering via events with per-step state persistence |
| ** Dependency Management**      | Explicit step dependencies via `dependsOn` with DAG validation |
| ** Annotation-Based Workflows** | Define workflows using `@Workflow` and `@WorkflowStep` annotations |
| ** State Persistence**          | Persist workflow and step states using Redis via `fireflyframework-cache` |
| ** Resilience4j Integration**   | Circuit breaker, rate limiter, bulkhead, and time limiter |
| ** Parallel Execution**         | Execute async steps concurrently within dependency layers |
| ** Conditional Steps**          | Skip steps based on SpEL expressions |
| ** Observability**              | OpenTelemetry tracing and Micrometer metrics |
| ** REST API**                   | Standardized API for workflow and step operations |
| ** Reactive Architecture**      | Built on Project Reactor for non-blocking execution |
| ** Scheduled Workflows**        | Cron-based workflow scheduling with `@ScheduledWorkflow` |
| ** Suspension & Resumption**    | Pause and resume workflows during incidents |
| ** Dry-Run Mode**               | Test workflows without executing side effects |
| ** Topology Visualization**     | DAG visualization for React Flow / Mermaid.js |
| ** Dead Letter Queue**          | DLQ management for failed workflow replay |

## 📚 Documentation

| Document | Description |
|----------|-------------|
| [Getting Started](docs/getting-started.md) | Step-by-step tutorial for new users |
| [Architecture](docs/architecture.md) | System architecture and component diagrams |
| [Advanced Features](docs/advanced-features.md) | Resilience4j, choreography, async execution, SpEL |
| [Configuration Reference](docs/configuration.md) | Complete configuration options |
| [API Reference](docs/api-reference.md) | REST API and Java API documentation |

## 🚀 Quick Start

### Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-workflow-engine</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Define an Event-Driven Workflow

The recommended pattern is to define workflows that are triggered by events and use **explicit step dependencies** via `dependsOn`:

```java
@Workflow(
    id = "order-processing",
    name = "Order Processing Workflow",
    triggerMode = TriggerMode.ASYNC,           // Triggered by events
    triggerEventType = "order.created"          // Listens for this event
)
public class OrderProcessingWorkflow {

    @WorkflowStep(
        id = "validate",
        name = "Validate Order",
        triggerMode = StepTriggerMode.EVENT,    // Event-driven (recommended)
        inputEventType = "order.created",       // Step triggered by event
        outputEventType = "order.validated"     // Emits event on completion
    )
    public Mono<Map<String, Object>> validateOrder(WorkflowContext ctx) {
        String orderId = ctx.getInput("orderId", String.class);
        return Mono.just(Map.of("valid", true, "orderId", orderId));
    }

    @WorkflowStep(
        id = "process",
        name = "Process Order",
        dependsOn = {"validate"},               // Explicit dependency (recommended)
        triggerMode = StepTriggerMode.EVENT,
        inputEventType = "order.validated",     // Triggered by previous step's event
        outputEventType = "order.processed"
    )
    public Mono<Map<String, Object>> processOrder(WorkflowContext ctx) {
        return Mono.just(Map.of("processed", true));
    }
}
```

> **Note**: The `dependsOn` attribute is the **recommended approach** for controlling step execution order. It provides explicit dependency management with DAG validation, cycle detection, and better maintainability compared to the legacy `order` attribute.

### Trigger via Event

Publish an event to trigger the workflow (using `fireflyframework-eda`):

```java
@Service
@RequiredArgsConstructor
public class OrderService {
    private final EventPublisher eventPublisher;

    public Mono<Void> createOrder(Order order) {
        // Publishing this event triggers the workflow
        return eventPublisher.publish(
            Map.of("orderId", order.getId(), "amount", order.getAmount()),
            "orders",                           // destination/topic
            Map.of("eventType", "order.created")
        );
    }
}
```

### Or Start via REST API

Workflows with `triggerMode = TriggerMode.BOTH` can also be started via REST:

```bash
curl -X POST http://localhost:8080/api/workflows/order-processing/start \
  -H "Content-Type: application/json" \
  -d '{"input": {"orderId": "ORD-12345", "amount": 150.00}}'
```

For detailed examples and tutorials, see the [Getting Started Guide](docs/getting-started.md).

## 📡 Event-Driven Workflows

Event-driven workflows are the **primary and recommended pattern** for using this library. They enable:

- **Loose coupling** between services through event-based communication
- **Step-level choreography** where each step can be independently triggered
- **Automatic workflow triggering** when specific events are received
- **Event chaining** where step completion events trigger subsequent steps

### How It Works

```
┌─────────────────┐     order.created       ┌──────────────────┐
│  Order Service  │ ───────────────────────▶│  Workflow Engine │
└─────────────────┘                         └────────┬─────────┘
                                                     │
                                                     ▼
                                            ┌──────────────────┐
                                            │ Validate Step    │
                                            │ (inputEventType: │
                                            │  order.created)  │
                                            └────────┬─────────┘
                                                     │ order.validated
                                                     ▼
                                            ┌──────────────────┐
                                            │ Process Step     │
                                            │ (inputEventType: │
                                            │ order.validated) │
                                            └────────┬─────────┘
                                                     │ order.processed
                                                     ▼
                                            ┌──────────────────┐
                                            │ Ship Step        │
                                            │ (inputEventType: │
                                            │ order.processed) │
                                            └──────────────────┘
```

### Key Annotations

| Annotation Attribute | Description |
|---------------------|-------------|
| `@Workflow(triggerEventType = "...")` | Event type that triggers the entire workflow |
| `@Workflow(triggerMode = TriggerMode.ASYNC)` | Workflow can only be triggered by events |
| `@Workflow(triggerMode = TriggerMode.BOTH)` | Workflow can be triggered by events or REST API |
| `@WorkflowStep(dependsOn = {"..."})` | **Recommended**: Explicit step dependencies |
| `@WorkflowStep(triggerMode = StepTriggerMode.EVENT)` | Step invocation pattern (EVENT, PROGRAMMATIC, BOTH) |
| `@WorkflowStep(inputEventType = "...")` | Event type that triggers this specific step |
| `@WorkflowStep(outputEventType = "...")` | Event type emitted when step completes |

### Event Publisher Configuration

Configure the event publisher in `application.yml`:

```yaml
firefly:
  workflow:
    events:
      enabled: true
      publisher-type: KAFKA              # KAFKA, RABBITMQ, or APPLICATION_EVENT
      default-destination: workflow-events
      publish-step-events: true

  # fireflyframework-eda configuration for Kafka
  eda:
    publishers:
      kafka:
        default:
          enabled: true
          bootstrap-servers: localhost:9092
```

For more details, see [Advanced Features: Event-Driven Workflows](docs/advanced-features.md#step-level-choreography).

## 🔗 Step Dependencies with `dependsOn`

The `dependsOn` attribute is the **recommended approach** for controlling step execution order. It provides:

- **Explicit Dependencies**: Clearly declare which steps must complete before a step can execute
- **DAG Validation**: Automatic detection of circular dependencies and missing steps
- **Layer-Based Execution**: Steps are organized into execution layers for optimal parallel execution
- **Better Maintainability**: Self-documenting code that clearly shows step relationships

### Example with Dependencies

```java
@Workflow(id = "order-fulfillment")
public class OrderFulfillmentWorkflow {

    @WorkflowStep(id = "validate")  // Root step - no dependencies
    public Mono<Map<String, Object>> validate(WorkflowContext ctx) { ... }

    @WorkflowStep(id = "check-inventory", dependsOn = {"validate"})
    public Mono<Map<String, Object>> checkInventory(WorkflowContext ctx) { ... }

    @WorkflowStep(id = "process-payment", dependsOn = {"validate"})
    public Mono<Map<String, Object>> processPayment(WorkflowContext ctx) { ... }

    @WorkflowStep(id = "ship", dependsOn = {"check-inventory", "process-payment"})
    public Mono<Map<String, Object>> ship(WorkflowContext ctx) { ... }
}
```

This creates the following execution DAG:

```
        ┌─────────────┐
        │  validate   │  Layer 0
        └──────┬──────┘
               │
       ┌───────┴───────┐
       ▼               ▼
┌─────────────┐ ┌─────────────┐
│check-inventory│ │process-payment│  Layer 1 (parallel)
└──────┬──────┘ └──────┬──────┘
       │               │
       └───────┬───────┘
               ▼
        ┌─────────────┐
        │    ship     │  Layer 2
        └─────────────┘
```

> **Migration Note**: The `order` attribute is still supported for backward compatibility, but `dependsOn` is preferred for new workflows. When both are specified, dependencies take precedence.

For more details, see [Advanced Features: Step Dependencies](docs/advanced-features.md#step-dependencies-with-dependson).

## 🔧 Configuration

Basic configuration in `application.yml`:

```yaml
firefly:
  workflow:
    enabled: true
    default-timeout: PT1H
    default-step-timeout: PT5M
    metrics-enabled: true
    health-enabled: true

    resilience:
      enabled: true
      circuit-breaker:
        enabled: true
        failure-rate-threshold: 50
      rate-limiter:
        enabled: false
      bulkhead:
        enabled: false
      time-limiter:
        enabled: true
        timeout-duration: PT5M
```

For complete configuration options, see the [Configuration Reference](docs/configuration.md).

## 🏗️ Architecture Overview

The library follows a **layered architecture** with clear separation of concerns, designed for loose coupling, testability, and production resilience.

```
┌─────────────────────────────────────────────────────────────────┐
│                        Client Applications                      │
│                    (REST API / Event Triggers)                  │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                        WorkflowEngine                           │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │  REST API   │  │   Events    │  │  Programmatic API       │  │
│  │ Controller  │  │  Listener   │  │  (WorkflowEngine)       │  │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                      WorkflowExecutor                           │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │  Resilience │  │   Tracing   │  │       Metrics           │  │
│  │  (Res4j)    │  │   (OTel)    │  │     (Micrometer)        │  │
│  └─────────────┘  └─────────────┘  └─────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                     Infrastructure Layer                        │
│  ┌─────────────────────┐      ┌─────────────────────────────┐   │
│  │   fireflyframework-cache  │      │      fireflyframework-eda         │   │
│  │   (Redis/Caffeine)  │      │   (Kafka/RabbitMQ/SNS)      │   │
│  └─────────────────────┘      └─────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### Layer Responsibilities

#### API Layer: WorkflowEngine + Entry Points

The `WorkflowEngine` class serves as the **main facade** for all workflow operations. It provides a unified API regardless of how workflows are triggered:

| Entry Point | Purpose |
|-------------|---------|
| **WorkflowController** | REST API for starting, querying, and managing workflows |
| **WorkflowEventListener** | Listens for domain events and triggers workflows/steps |
| **WorkflowEngine (direct)** | Programmatic API for service-to-service calls |

This design enables **multiple trigger mechanisms** while keeping the core logic centralized. The `WorkflowEventListener` is the key enabler for event-driven choreography—it receives events from `fireflyframework-eda`, matches them to workflow `triggerEventType` or step `inputEventType` annotations, and initiates execution.

#### Execution Layer: WorkflowExecutor + Cross-Cutting Concerns

The `WorkflowExecutor` handles the actual step execution with all cross-cutting concerns applied:

| Component | Responsibility |
|-----------|----------------|
| **WorkflowExecutor** | Manages step ordering, parallel execution, SpEL condition evaluation, retry logic, and state persistence |
| **WorkflowResilience** | Decorates step execution with Resilience4j patterns (circuit breaker, rate limiter, bulkhead, time limiter) |
| **WorkflowTracer** | Creates OpenTelemetry spans for distributed tracing across workflow and step boundaries |
| **WorkflowMetrics** | Records Micrometer metrics for workflow/step counts, durations, and active instances |

**Why this separation?** The `WorkflowEngine` handles "what to do" (start, cancel, retry, query), while `WorkflowExecutor` handles "how to do it" (execute steps with resilience, tracing, and metrics). This allows the execution layer to be enhanced with new cross-cutting concerns without modifying the API layer.

#### Infrastructure Layer: Pluggable Backends

The infrastructure layer uses **Firefly's shared libraries** to abstract away specific technologies:

| Library | Purpose | Why This Choice |
|---------|---------|-----------------|
| **fireflyframework-cache** | State persistence via `CacheAdapter` interface | Provides a unified API for Redis (distributed) or Caffeine (in-memory). Workflows can run in clustered environments with Redis, or locally with Caffeine for development/testing. |
| **fireflyframework-eda** | Event publishing via `EventPublisherFactory` | Abstracts Kafka, RabbitMQ, and SNS behind a common interface. Workflows can emit events to any message broker without code changes. |

**State Persistence Design**: The `CacheWorkflowStateStore` and `CacheStepStateStore` use a key-based pattern with indices for efficient querying:
- Instance data: `workflow:instance:{instanceId}`
- Workflow index: `workflow:index:{workflowId}:{instanceId}`
- Status index: `workflow:status:{status}:{instanceId}`
- Correlation index: `workflow:correlation:{correlationId}:{instanceId}`

This enables queries like "find all running instances of workflow X" or "find the workflow for correlation ID Y" without scanning all records.

### Event-Driven Choreography Architecture

The event-driven design enables **true microservice choreography**:

```
┌──────────────┐    order.created     ┌───────────────────────┐
│ Order Service│ ────────────────────▶│ WorkflowEventListener │
└──────────────┘                      └─────────┬─────────────┘
                                                │ matches triggerEventType
                                                ▼
                                      ┌───────────────────┐
                                      │  WorkflowEngine   │
                                      │  startWorkflow()  │
                                      └─────────┬─────────┘
                                                │
                                                ▼
                                      ┌───────────────────┐
                                      │ Step: validate    │──▶ publishes order.validated
                                      └───────────────────┘
                                                │
                                                ▼
                                      ┌───────────────────┐
                                      │ Step: process     │──▶ publishes payment.processed
                                      │ (inputEventType:  │
                                      │  order.validated) │
                                      └───────────────────┘
```

Each step can independently listen for events (`inputEventType`) and emit events (`outputEventType`). This enables:
- **Loose coupling**: Steps don't know about each other, only about events
- **Scalability**: Steps can be processed by different service instances
- **Flexibility**: New steps can be added by listening to existing events
- **Resilience**: Failed steps can be retried independently

For detailed architecture diagrams and sequence flows, see the [Architecture Documentation](docs/architecture.md).

## Plugin Architecture Integration

Workflow definitions can be exposed as **ProcessPlugins** for use within the Firefly Plugin Architecture. This enables workflow-backed business processes to be dynamically resolved and executed via the `ProcessPluginExecutor`.

### How It Works

When `fireflyframework-application` is on the classpath, the `WorkflowPluginLoader` automatically:
1. Discovers all registered workflow definitions
2. Creates `WorkflowProcessPlugin` adapters for each
3. Registers them with the `ProcessPluginRegistry`

### Creating a Workflow-Backed Plugin

```java
// Define a workflow with plugin exposure metadata
WorkflowDefinition workflow = WorkflowDefinition.builder()
    .workflowId("account-creation-workflow")
    .name("Account Creation Workflow")
    .metadata(Map.of("exposeAsPlugin", true))  // Expose as plugin
    .addStep(...)
    .build();

// Or explicitly create a WorkflowProcessPlugin
WorkflowProcessPlugin plugin = WorkflowProcessPlugin.builder()
    .workflowDefinition(workflow)
    .workflowEngine(workflowEngine)
    .processId("createAccount")  // ProcessPlugin ID
    .version("1.0.0")
    .build();
```

### Configuration

```yaml
firefly:
  workflow:
    plugin:
      enabled: true
      priority: 5  # Loader priority (lower = higher priority)
      process-id-prefix: ""  # Optional prefix for process IDs
```

### Context Mapping

The `WorkflowProcessPlugin` maps `ProcessExecutionContext` to workflow input:
- `_appContext` - The ApplicationExecutionContext
- `_partyId`, `_tenantId`, `_contractId`, `_productId` - From AppContext
- All input map entries passed directly to workflow

For more details, see the [Plugin Architecture Documentation](../fireflyframework-application/README.md#plugin-architecture).

##  Dependencies

| Dependency | Purpose |
|------------|---------|
| `fireflyframework-cache` | State persistence (Redis or Caffeine) |
| `fireflyframework-eda` | Event publishing/listening (Kafka, RabbitMQ, SNS) |
| Spring Boot 3.x | Application framework |
| Spring WebFlux | Reactive web support |
| Project Reactor | Reactive programming |
| Resilience4j | Circuit breaker, rate limiter, bulkhead |
| Micrometer | Metrics and tracing |

##  Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

##  License

Copyright 2024-2026 Firefly Software Solutions Inc. Licensed under the [Apache License 2.0](LICENSE).
