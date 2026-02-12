# Firefly Framework - Workflow Engine

[![CI](https://github.com/fireflyframework/fireflyframework-workflow/actions/workflows/ci.yml/badge.svg)](https://github.com/fireflyframework/fireflyframework-workflow/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green.svg)](https://spring.io/projects/spring-boot)

> Workflow orchestration library with AOP-based definitions, state persistence, scheduling, and event-driven step execution.

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [License](#license)

## Overview

Firefly Framework Workflow Engine provides a lightweight, annotation-driven workflow orchestration system for reactive Spring Boot microservices. Workflows are defined using `@Workflow` and `@WorkflowStep` annotations, with automatic topology resolution, state persistence, and event-driven execution.

The engine supports step dependencies, retry policies, scheduled workflow execution, dead letter queues for failed steps, and lifecycle callbacks (`@OnStepComplete`, `@OnWorkflowComplete`, `@OnWorkflowError`). State is persisted through the Firefly cache module, enabling workflow resumption after service restarts.

Built-in REST controllers expose workflow management endpoints for starting, suspending, and monitoring workflows, along with dead letter queue management for error recovery.

## Features

- `@Workflow` and `@WorkflowStep` annotations for declarative workflow definitions
- AOP-based workflow execution via `WorkflowAspect`
- Workflow topology with step dependency resolution
- Configurable retry policies per step
- Step trigger modes: automatic, manual, event-driven
- `@ScheduledWorkflow` for cron-based workflow scheduling
- Lifecycle callbacks: `@OnStepComplete`, `@OnWorkflowComplete`, `@OnWorkflowError`
- Cache-backed state persistence for workflows and steps
- Dead letter queue with `DeadLetterService` for failed step recovery
- REST controllers for workflow management and dead letter operations
- Workflow event publishing via EDA integration
- Micrometer metrics for workflow execution tracking
- OpenTelemetry tracing for distributed workflow visibility
- Resilience configuration with circuit breakers
- Health indicator for workflow engine status

## Requirements

- Java 21+
- Spring Boot 3.x
- Maven 3.9+

## Installation

```xml
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-workflow</artifactId>
    <version>26.02.03</version>
</dependency>
```

## Quick Start

```java
import org.fireflyframework.workflow.annotation.*;

@Workflow(name = "order-fulfillment")
@Component
public class OrderFulfillmentWorkflow {

    @WorkflowStep(name = "validate-order", order = 1)
    public Mono<ValidationResult> validate(WorkflowContext context) {
        return orderValidator.validate(context.get("orderId"));
    }

    @WorkflowStep(name = "reserve-inventory", order = 2, retryPolicy = @RetryPolicy(maxAttempts = 3))
    public Mono<ReservationResult> reserve(WorkflowContext context) {
        return inventoryService.reserve(context.get("orderId"));
    }

    @OnWorkflowComplete
    public Mono<Void> onComplete(WorkflowContext context) {
        return notificationService.notifyOrderReady(context.get("orderId"));
    }
}
```

## Configuration

```yaml
firefly:
  workflow:
    state-store:
      type: cache
    dead-letter:
      enabled: true
      max-retries: 5
    scheduling:
      enabled: true
    metrics:
      enabled: true
    tracing:
      enabled: true
```

## Documentation

Additional documentation is available in the [docs/](docs/) directory:

- [Getting Started](docs/getting-started.md)
- [Architecture](docs/architecture.md)
- [Configuration](docs/configuration.md)
- [Api Reference](docs/api-reference.md)
- [Advanced Features](docs/advanced-features.md)

## Contributing

Contributions are welcome. Please read the [CONTRIBUTING.md](CONTRIBUTING.md) guide for details on our code of conduct, development process, and how to submit pull requests.

## License

Copyright 2024-2026 Firefly Software Solutions Inc.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
