# Firefly Framework - Workflow Engine

[![CI](https://github.com/fireflyframework/fireflyframework-workflow/actions/workflows/ci.yml/badge.svg)](https://github.com/fireflyframework/fireflyframework-workflow/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green.svg)](https://spring.io/projects/spring-boot)

> Reactive workflow orchestration for Spring Boot with annotation-driven definitions, DAG-based step execution, cache-backed state persistence, and opt-in durable execution via event sourcing.

---

## Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Architecture Overview](#architecture-overview)
- [Two Execution Modes](#two-execution-modes)
- [Configuration Summary](#configuration-summary)
- [Documentation](#documentation)
- [License](#license)

## Overview

`fireflyframework-workflow` is a workflow orchestration library for reactive Spring Boot microservices. Workflows are defined using `@Workflow` and `@WorkflowStep` annotations or built programmatically with `WorkflowDefinition.builder()`. Steps are organized into a directed acyclic graph (DAG) resolved via Kahn's algorithm, with parallel execution of independent steps within the same dependency layer.

State is persisted through `fireflyframework-cache` (the default mode) or through `fireflyframework-eventsourcing` for durable execution (opt-in). The engine publishes lifecycle events through `fireflyframework-eda`, exposes REST endpoints for management and monitoring, and integrates with Resilience4j for per-step circuit breakers, rate limiters, bulkheads, and time limiters.

## Key Features

**Core Engine**

- `@Workflow` / `@WorkflowStep` annotations for declarative definitions
- `WorkflowDefinition.builder()` for programmatic definitions
- DAG-based step execution with `dependsOn` and Kahn's algorithm
- SpEL conditional steps (`#ctx`, `#input`, `#data` variables)
- Step trigger modes: `EVENT`, `PROGRAMMATIC`, `BOTH`
- Async parallel steps within the same dependency layer
- Lifecycle callbacks: `@OnStepComplete`, `@OnWorkflowComplete`, `@OnWorkflowError`
- `@ScheduledWorkflow` for cron, fixedDelay, and fixedRate scheduling
- Dry-run mode for testing workflow configuration without side effects

**State Management**

- Cache-backed persistence via `fireflyframework-cache` `CacheAdapter` (default)
- Configurable TTL for workflow instances and completed workflows
- Step-level state tracking with `CacheStepStateStore`
- Crash recovery via `WorkflowRecoveryService` with configurable stale threshold

**Durable Execution (opt-in)**

- Event-sourced workflow state via `WorkflowAggregate` extending `AggregateRoot`
- 22 domain events covering the complete workflow lifecycle
- Signals: `@WaitForSignal`, `SignalService.sendSignal()`, `SignalService.consumeSignal()`
- Durable timers: `@WaitForTimer` with duration or absolute instant
- Composite waits: `@WaitForAll` (join pattern), `@WaitForAny` (race pattern)
- Child workflows: `@ChildWorkflow`, `ChildWorkflowService`
- Compensation: `@CompensationStep`, `CompensationOrchestrator` (STRICT_SEQUENTIAL, BEST_EFFORT, SKIP)
- Side effects with deterministic replay: `ctx.sideEffect()`
- Heartbeats: `ctx.heartbeat()` for progress tracking
- Search attributes: `WorkflowSearchService`, `SearchAttributeProjection`
- 10 built-in queries: `WorkflowQueryService`
- Continue-as-new: `ContinueAsNewService` for unbounded workflows
- Snapshots: `WorkflowSnapshot` for optimized replay
- Optimistic concurrency with version-based conflict detection

**REST API**

- Full CRUD for workflows and instances at `/api/v1/workflows`
- Topology endpoint for DAG visualization
- Signal, query, and search endpoints (require durable execution)
- Dead Letter Queue management endpoints

**Resilience**

- Per-step Resilience4j integration: CircuitBreaker, RateLimiter, Bulkhead, TimeLimiter
- Application order: TimeLimiter -> Bulkhead -> RateLimiter -> CircuitBreaker
- Dead Letter Queue with auto-save on failure and configurable replay

**Observability**

- Micrometer metrics for workflow and step execution
- OpenTelemetry tracing with span propagation
- Spring Boot Actuator health indicator

## Requirements

- Java 21+
- Spring Boot 3.x
- Maven 3.9+
- `fireflyframework-cache` (required for default cache-backed mode)
- `fireflyframework-eda` (required for event publishing)
- `fireflyframework-eventsourcing` (required only for durable execution mode)

## Installation

```xml
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-workflow</artifactId>
    <version>26.02.06</version>
</dependency>
```

The `fireflyframework-cache` and `fireflyframework-eda` dependencies are transitively included. You must configure a `CacheAdapter` bean for state persistence to work. See [Getting Started](docs/getting-started.md) for cache setup instructions.

## Quick Start

```java
@Workflow(id = "order-processing", name = "Order Processing")
public class OrderProcessingWorkflow {

    @WorkflowStep(id = "validate", name = "Validate Order", order = 1)
    public Mono<ValidationResult> validate(WorkflowContext ctx) {
        String orderId = ctx.getInput("orderId", String.class);
        return orderValidator.validate(orderId);
    }

    @WorkflowStep(id = "charge", name = "Charge Payment", order = 2,
                   dependsOn = {"validate"})
    public Mono<PaymentResult> charge(WorkflowContext ctx) {
        ValidationResult validation = ctx.getStepOutput("validate", ValidationResult.class);
        return paymentService.charge(validation.amount());
    }

    @WorkflowStep(id = "fulfill", name = "Fulfill Order", order = 3,
                   dependsOn = {"charge"})
    public Mono<FulfillmentResult> fulfill(WorkflowContext ctx) {
        return fulfillmentService.ship(ctx.getInput("orderId", String.class));
    }

    @OnWorkflowComplete
    public void onComplete(WorkflowContext ctx, WorkflowInstance instance) {
        log.info("Order {} completed", instance.instanceId());
    }
}
```

Start the workflow programmatically, via REST API, or from an external event:

```java
// Programmatic
workflowEngine.startWorkflow("order-processing", Map.of("orderId", "ORD-123"))
    .subscribe(instance -> log.info("Started: {}", instance.instanceId()));

// REST API
// POST /api/v1/workflows/order-processing/start
// { "input": { "orderId": "ORD-123" } }
```

Workflows can also be triggered by events from Kafka or RabbitMQ by setting `triggerEventType` on the `@Workflow` annotation. See [EDA Integration](docs/eda-integration.md) for the full guide.

## Architecture Overview

```
                    ┌──────────────────────┐
                    │  WorkflowController  │  REST API
                    └──────────┬───────────┘
                               │
                    ┌──────────▼───────────┐
                    │  WorkflowService     │  Service Layer
                    └──────────┬───────────┘
                               │
                    ┌──────────▼───────────┐
                    │  WorkflowEngine      │  Orchestration Facade
                    └──────────┬───────────┘
                               │
              ┌────────────────┼────────────────┐
              │                │                │
   ┌──────────▼───┐  ┌─────────▼───────┐  ┌─────▼────────────┐
   │  Workflow    │  │  Workflow       │  │  WorkflowEvent   │
   │  Registry    │  │  Executor       │  │  Publisher       │
   └──────────────┘  └─────────┬───────┘  └──────────────────┘
                               │
                   ┌───────────▼──────────┐
                   │  WorkflowTopology    │  DAG + Kahn's Algorithm
                   └───────────┬──────────┘
                               │
              ┌────────────────┼───────────────┐
              │                                │
   ┌──────────▼──────────┐      ┌──────────────▼─────────────┐
   │  CacheWorkflow      │      │  EventSourcedWorkflow      │
   │  StateStore         │      │  StateStore                │
   │  (default)          │      │  (durable, opt-in)         │
   └─────────────────────┘      └────────────────────────────┘
   Uses: fireflyframework-cache   Uses: fireflyframework-eventsourcing
```

## Two Execution Modes

### Cache Mode (Default)

The default execution mode uses `fireflyframework-cache` for state persistence. Workflow instances are stored as cache entries with configurable TTL. This mode is lightweight and suitable for most use cases.

**Requirements:** A `CacheAdapter` bean must be available (from `fireflyframework-cache`).

```yaml
firefly:
  cache:
    enabled: true
  workflow:
    enabled: true
    state:
      default-ttl: 7d
      completed-ttl: 1d
```

### Durable Execution Mode (Opt-in)

Durable execution uses `fireflyframework-eventsourcing` to persist workflow state as an event stream. Every state transition is captured as a domain event, enabling replay, audit trails, and crash recovery from the event log.

**Requirements:** An `EventStore` bean must be available (from `fireflyframework-eventsourcing`) and `firefly.workflow.eventsourcing.enabled` must be set to `true`.

```yaml
firefly:
  workflow:
    eventsourcing:
      enabled: true
      snapshot-threshold: 20
```

> **Important:** Durable execution is OFF by default (`enabled: false`). When enabled, a read-side projection (`workflow_instances_projection`) is automatically maintained to support query methods like `findByWorkflowId`, `findByStatus`, and `findActiveInstances`. The projection requires a `DatabaseClient` bean (R2DBC) and polls the event store on a configurable interval. If no `DatabaseClient` is available, query and count methods gracefully degrade to empty results.

## Configuration Summary

```yaml
firefly:
  workflow:
    enabled: true                    # Enable the workflow engine (default: true)
    default-timeout: 1h              # Workflow timeout (default: 1h)
    default-step-timeout: 5m         # Step timeout (default: 5m)
    metrics-enabled: true            # Micrometer metrics (default: true)
    health-enabled: true             # Actuator health indicator (default: true)

    state:
      default-ttl: 7d               # Instance TTL (default: 7d)
      completed-ttl: 1d             # Completed instance TTL (default: 1d)
      key-prefix: workflow           # Cache key prefix (default: workflow)

    api:
      enabled: true                  # REST API (default: true)
      base-path: /api/v1/workflows   # Base path (default: /api/v1/workflows)

    eventsourcing:
      enabled: false                 # Durable execution (default: false)
```

For the complete configuration reference, see [Configuration](docs/configuration.md).

## Documentation

- [Getting Started](docs/getting-started.md) -- Prerequisites, cache setup, first workflow, programmatic definitions
- [Architecture](docs/architecture.md) -- Components, execution model, state management, auto-configuration
- [EDA Integration](docs/eda-integration.md) -- Event-driven triggering, step choreography, lifecycle event publishing
- [Configuration](docs/configuration.md) -- Complete property reference with defaults
- [API Reference](docs/api-reference.md) -- REST endpoints, Java API, annotations
- [Advanced Features](docs/advanced-features.md) -- DAG execution, resilience, scheduling, DLQ, dry-run
- [Durable Execution](docs/durable-execution.md) -- Event sourcing, signals, timers, child workflows, compensation
- [Testing](docs/testing.md) -- Unit testing, integration testing, Testcontainers setup

## License

Copyright 2024-2026 Firefly Software Solutions Inc.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
