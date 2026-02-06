# Getting Started with Firefly Workflow Engine

This guide walks you through setting up and using the Firefly Workflow Engine in your Spring Boot application. The library is designed primarily for **event-driven workflows** where workflows are triggered by events and steps communicate through event-based choreography.

## Prerequisites

- Java 21 or later
- Spring Boot 3.x application
- Maven or Gradle build tool
- Redis (for state persistence) or Caffeine (for in-memory)
- Kafka or RabbitMQ (recommended for event-driven workflows)

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>org.fireflyframework</groupId>
    <artifactId>fireflyframework-workflow-engine</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

The library auto-configures itself when added to your classpath. No additional configuration is required for basic usage.

## Step 1: Create Your First Workflow

Create a workflow class annotated with `@Workflow`:

```java
package com.example.workflows;

import org.fireflyframework.workflow.annotation.Workflow;
import org.fireflyframework.workflow.annotation.WorkflowStep;
import org.fireflyframework.workflow.core.WorkflowContext;
import org.fireflyframework.workflow.model.TriggerMode;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Workflow(
    id = "hello-world",
    name = "Hello World Workflow",
    description = "A simple workflow to demonstrate basic concepts",
    triggerMode = TriggerMode.BOTH
)
public class HelloWorldWorkflow {

    @WorkflowStep(
        id = "greet",
        name = "Generate Greeting"
        // No dependsOn = root step, executes first
    )
    public Mono<Map<String, Object>> greet(WorkflowContext ctx) {
        String name = ctx.getInput("name", String.class);
        String greeting = "Hello, " + (name != null ? name : "World") + "!";

        log.info("Generated greeting: {}", greeting);
        return Mono.just(Map.of("greeting", greeting));
    }

    @WorkflowStep(
        id = "log-result",
        name = "Log Result",
        dependsOn = {"greet"}  // Executes after "greet" completes
    )
    public Mono<Map<String, Object>> logResult(WorkflowContext ctx) {
        Map<String, Object> greetOutput = ctx.getStepOutput("greet", Map.class);
        String greeting = (String) greetOutput.get("greeting");

        log.info("Workflow completed with greeting: {}", greeting);
        return Mono.just(Map.of("success", true, "message", greeting));
    }
}
```

## Step 2: Event-Driven Workflows (Recommended)

Event-driven workflows are the **primary and recommended pattern** for using this library. They enable loose coupling between services through event-based communication.

### Configure Event Publishing

Add event configuration to your `application.yml`:

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

### Create an Event-Driven Workflow

```java
package com.example.workflows;

import org.fireflyframework.workflow.annotation.Workflow;
import org.fireflyframework.workflow.annotation.WorkflowStep;
import org.fireflyframework.workflow.core.WorkflowContext;
import org.fireflyframework.workflow.model.TriggerMode;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Workflow(
    id = "order-processing",
    name = "Order Processing Workflow",
    triggerMode = TriggerMode.ASYNC,        // Only triggered by events
    triggerEventType = "order.created"       // Listens for this event type
)
public class OrderProcessingWorkflow {

    @WorkflowStep(
        id = "validate",
        name = "Validate Order",
        triggerMode = StepTriggerMode.EVENT,  // Event-driven (recommended)
        inputEventType = "order.created",     // Step triggered by this event
        outputEventType = "order.validated"   // Emits this event on completion
    )
    public Mono<Map<String, Object>> validateOrder(WorkflowContext ctx) {
        String orderId = ctx.getInput("orderId", String.class);
        Double amount = ctx.getInput("amount", Double.class);

        log.info("Validating order: {} with amount: {}", orderId, amount);

        boolean isValid = amount != null && amount > 0;
        return Mono.just(Map.of(
            "valid", isValid,
            "orderId", orderId,
            "validatedAt", System.currentTimeMillis()
        ));
    }

    @WorkflowStep(
        id = "process-payment",
        name = "Process Payment",
        dependsOn = {"validate"},              // Explicit dependency (recommended)
        triggerMode = StepTriggerMode.EVENT,
        inputEventType = "order.validated",    // Triggered by previous step's event
        outputEventType = "payment.processed"
    )
    public Mono<Map<String, Object>> processPayment(WorkflowContext ctx) {
        Map<String, Object> validation = ctx.getStepOutput("validate", Map.class);
        String orderId = (String) validation.get("orderId");

        log.info("Processing payment for order: {}", orderId);

        return Mono.just(Map.of(
            "paymentId", "PAY-" + System.currentTimeMillis(),
            "orderId", orderId,
            "status", "COMPLETED"
        ));
    }

    @WorkflowStep(
        id = "ship-order",
        name = "Ship Order",
        dependsOn = {"process-payment"},       // Explicit dependency
        triggerMode = StepTriggerMode.EVENT,
        inputEventType = "payment.processed",
        outputEventType = "order.shipped"
    )
    public Mono<Map<String, Object>> shipOrder(WorkflowContext ctx) {
        Map<String, Object> payment = ctx.getStepOutput("process-payment", Map.class);
        String orderId = (String) payment.get("orderId");

        log.info("Shipping order: {}", orderId);

        return Mono.just(Map.of(
            "trackingNumber", "TRACK-" + System.currentTimeMillis(),
            "orderId", orderId,
            "shippedAt", System.currentTimeMillis()
        ));
    }
}
```

### Trigger the Workflow via Event

Publish an event to trigger the workflow using `fireflyframework-eda`:

```java
@Service
@RequiredArgsConstructor
public class OrderService {
    private final EventPublisher eventPublisher;

    public Mono<Void> createOrder(String orderId, Double amount) {
        // Publishing this event automatically triggers the workflow
        return eventPublisher.publish(
            Map.of("orderId", orderId, "amount", amount),
            "orders",                           // Kafka topic or destination
            Map.of("eventType", "order.created")
        );
    }
}
```

### How Event Flow Works

```
┌─────────────────┐     order.created       ┌────────────────────────────────────┐
│  Order Service  │ ───────────────────────▶│  WorkflowEventListener             │
└─────────────────┘                         │  (receives event, starts           │
                                            │   workflow instance)               │
                                            └────────────────┬───────────────────┘
                                                             │
                                                             ▼
                                            ┌────────────────────────────────────┐
                                            │  Validate Step                     │
                                            │  inputEventType: order.created     │
                                            │  outputEventType: order.validated  │
                                            └────────────────┬───────────────────┘
                                                             │ publishes order.validated
                                                             ▼
                                            ┌────────────────────────────────────┐
                                            │  Process Payment Step              │
                                            │  inputEventType: order.validated   │
                                            │  outputEventType: payment.processed│
                                            └────────────────┬───────────────────┘
                                                             │ publishes payment.processed
                                                             ▼
                                            ┌────────────────────────────────────┐
                                            │  Ship Order Step                   │
                                            │  inputEventType: payment.processed │
                                            │  outputEventType: order.shipped    │
                                            └────────────────────────────────────┘
```

## Step 3: Start Workflows via REST API or Programmatically

For workflows with `triggerMode = TriggerMode.BOTH`, you can also start them directly:

### Via REST API

```bash
curl -X POST http://localhost:8080/api/workflows/hello-world/start \
  -H "Content-Type: application/json" \
  -d '{"input": {"name": "Developer"}}'
```

Response:
```json
{
  "instanceId": "550e8400-e29b-41d4-a716-446655440000",
  "workflowId": "hello-world",
  "status": "RUNNING"
}
```

### Programmatically

Inject `WorkflowEngine` and start the workflow:

```java
@Service
@RequiredArgsConstructor
public class GreetingService {

    private final WorkflowEngine workflowEngine;

    public Mono<WorkflowInstance> greet(String name) {
        return workflowEngine.startWorkflow(
            "hello-world",
            Map.of("name", name)
        );
    }
}
```

## Step 4: Check Workflow Status

```bash
curl http://localhost:8080/api/workflows/hello-world/instances/{instanceId}/status
```

Response:
```json
{
  "instanceId": "550e8400-e29b-41d4-a716-446655440000",
  "workflowId": "hello-world",
  "status": "COMPLETED",
  "progress": 100,
  "stepExecutions": [
    {"stepId": "greet", "status": "COMPLETED"},
    {"stepId": "log-result", "status": "COMPLETED"}
  ]
}
```

## Step 5: Add Conditional Steps

Use SpEL expressions to conditionally execute steps:

```java
@WorkflowStep(
    id = "premium-processing",
    name = "Premium Processing",
    dependsOn = {"validate"},                  // Explicit dependency
    condition = "#input['tier'] == 'premium'"
)
public Mono<Map<String, Object>> premiumProcessing(WorkflowContext ctx) {
    // Only executes if input.tier == 'premium'
    return Mono.just(Map.of("premiumApplied", true));
}
```

SpEL variables available:
- `#ctx` - The WorkflowContext object
- `#input` - Map of workflow input values
- `#data` - Map of shared context data

## Step 6: Add Parallel Steps

Use `dependsOn` with `async = true` to execute steps in parallel. Steps in the same execution layer run concurrently:

```java
// Layer 0: Both steps have no dependencies, execute in parallel
@WorkflowStep(id = "fetch-user", async = true)
public Mono<User> fetchUser(WorkflowContext ctx) {
    return userService.findById(ctx.getInput("userId", String.class));
}

@WorkflowStep(id = "fetch-products", async = true)
public Mono<List<Product>> fetchProducts(WorkflowContext ctx) {
    return productService.findAll();
}

// Layer 1: Depends on both parallel steps
@WorkflowStep(id = "combine", dependsOn = {"fetch-user", "fetch-products"})
public Mono<Order> combine(WorkflowContext ctx) {
    User user = ctx.getStepOutput("fetch-user", User.class);
    List<Product> products = ctx.getStepOutput("fetch-products", List.class);
    return Mono.just(new Order(user, products));
}
```

## Step 7: Add Retry Logic

Configure automatic retries for steps that may fail:

```java
@WorkflowStep(
    id = "call-external-api",
    name = "Call External API",
    maxRetries = 3,
    retryDelayMs = 1000  // 1 second between retries
)
public Mono<Map<String, Object>> callExternalApi(WorkflowContext ctx) {
    return externalApiClient.call()
        .map(response -> Map.of("data", response));
}
```

## Step 8: Using WorkflowContext

The `WorkflowContext` provides access to all workflow data:

```java
@WorkflowStep(id = "process", dependsOn = {"validate"})
public Mono<Map<String, Object>> process(WorkflowContext ctx) {
    // Workflow metadata
    String instanceId = ctx.getInstanceId();
    String workflowId = ctx.getWorkflowId();
    String correlationId = ctx.getCorrelationId();

    // Get typed input
    String orderId = ctx.getInput("orderId", String.class);
    Double amount = ctx.getInput("amount", Double.class);

    // Get output from previous step
    Map<String, Object> prevOutput = ctx.getStepOutput("validate", Map.class);

    // Set shared context data for subsequent steps
    ctx.set("processedAt", Instant.now());

    // Get shared context data
    Instant timestamp = ctx.get("processedAt", Instant.class);
    String defaultVal = ctx.getOrDefault("missing", "default");

    return Mono.just(Map.of("processed", true));
}
```

## Step 9: Lifecycle Hooks

Add hooks to respond to workflow events:

```java
@Workflow(id = "my-workflow")
public class MyWorkflow {

    @OnStepComplete
    public void onStepComplete(WorkflowContext ctx, StepExecution step) {
        log.info("Step {} completed with status {}",
            step.stepId(), step.status());
    }

    @OnWorkflowComplete
    public void onWorkflowComplete(WorkflowContext ctx) {
        log.info("Workflow {} completed successfully", ctx.getInstanceId());
    }

    @OnWorkflowError
    public void onWorkflowError(WorkflowContext ctx, Throwable error) {
        log.error("Workflow {} failed: {}",
            ctx.getInstanceId(), error.getMessage());
    }
}
```

## Basic Configuration

Add to your `application.yml`:

```yaml
firefly:
  workflow:
    enabled: true
    default-timeout: PT1H        # 1 hour workflow timeout
    default-step-timeout: PT5M   # 5 minute step timeout
    metrics-enabled: true
    health-enabled: true

    state:
      enabled: true
      default-ttl: P7D           # Keep state for 7 days

    events:
      enabled: true
      publish-step-events: true

    api:
      enabled: true
      base-path: /api/workflows
```

## Next Steps

- [Architecture Overview](architecture.md) - Understand the system design
- [Advanced Features](advanced-features.md) - Resilience4j, choreography, and more
- [Configuration Reference](configuration.md) - All configuration options
- [API Reference](api-reference.md) - REST and Java API documentation