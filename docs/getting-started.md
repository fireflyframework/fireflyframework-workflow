# Getting Started with Firefly Workflow Engine

This guide walks you through setting up and using the Firefly Workflow Engine in your Spring Boot application.

## Prerequisites

- Java 21 or later
- Spring Boot 3.x application
- Maven or Gradle build tool
- Redis (for state persistence) or Caffeine (for in-memory)
- Kafka/RabbitMQ (optional, for event-driven workflows)

## Installation

Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.firefly</groupId>
    <artifactId>lib-workflow-engine</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

The library auto-configures itself when added to your classpath. No additional configuration is required for basic usage.

## Step 1: Create Your First Workflow

Create a workflow class annotated with `@Workflow`:

```java
package com.example.workflows;

import com.firefly.common.workflow.annotation.Workflow;
import com.firefly.common.workflow.annotation.WorkflowStep;
import com.firefly.common.workflow.core.WorkflowContext;
import com.firefly.common.workflow.model.TriggerMode;
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
        name = "Generate Greeting",
        order = 1
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
        order = 2
    )
    public Mono<Map<String, Object>> logResult(WorkflowContext ctx) {
        Map<String, Object> greetOutput = ctx.getStepOutput("greet", Map.class);
        String greeting = (String) greetOutput.get("greeting");
        
        log.info("Workflow completed with greeting: {}", greeting);
        return Mono.just(Map.of("success", true, "message", greeting));
    }
}
```

## Step 2: Start the Workflow

### Option A: Via REST API

Start your Spring Boot application and call the REST endpoint:

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

### Option B: Programmatically

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

## Step 3: Check Workflow Status

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

## Step 4: Add Conditional Steps

Use SpEL expressions to conditionally execute steps:

```java
@WorkflowStep(
    id = "premium-processing",
    name = "Premium Processing",
    order = 3,
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

