# Testing

This guide covers testing strategies for workflows built with the Firefly Workflow Engine, including unit testing, integration testing with Testcontainers, and durable execution end-to-end testing.

## Test Dependencies

The project uses the following test dependencies (inherited from `fireflyframework-parent`):

- **JUnit 5** -- Test framework
- **Mockito** -- Mocking framework with `@ExtendWith(MockitoExtension.class)`
- **AssertJ** -- Fluent assertions via `assertThat()`
- **Reactor Test** -- `StepVerifier` for reactive stream assertions
- **Spring Boot Test** -- `@SpringBootTest` for integration tests
- **Testcontainers** -- Docker-based containers for Redis, Kafka, and PostgreSQL

## Unit Testing

### Testing WorkflowEngine

Unit tests mock the collaborating components (`WorkflowRegistry`, `WorkflowExecutor`, `WorkflowStateStore`, `WorkflowEventPublisher`) and test the engine facade in isolation.

```java
import org.fireflyframework.workflow.core.*;
import org.fireflyframework.workflow.event.WorkflowEventPublisher;
import org.fireflyframework.workflow.model.*;
import org.fireflyframework.workflow.properties.WorkflowProperties;
import org.fireflyframework.workflow.state.WorkflowStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowEngineTest {

    @Mock private WorkflowRegistry registry;
    @Mock private WorkflowExecutor executor;
    @Mock private WorkflowStateStore stateStore;
    @Mock private WorkflowEventPublisher eventPublisher;

    private WorkflowProperties properties;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        properties = new WorkflowProperties();
        properties.setEnabled(true);
        properties.setDefaultTimeout(Duration.ofMinutes(5));
        properties.setDefaultStepTimeout(Duration.ofSeconds(30));
        engine = new WorkflowEngine(registry, executor, stateStore,
                                     eventPublisher, properties);
    }

    @Test
    void shouldStartWorkflow() {
        WorkflowDefinition definition = createTestDefinition();
        when(registry.get("test-workflow")).thenReturn(Optional.of(definition));
        when(stateStore.save(any(WorkflowInstance.class)))
            .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(eventPublisher.publishWorkflowStarted(any())).thenReturn(Mono.empty());
        when(executor.executeWorkflow(any(), any())).thenAnswer(inv -> {
            WorkflowInstance instance = inv.getArgument(1);
            return Mono.just(instance.complete(Map.of("result", "done")));
        });

        StepVerifier.create(
            engine.startWorkflow("test-workflow", Map.of("key", "value"))
        )
        .assertNext(instance -> {
            assertThat(instance.status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(instance.workflowId()).isEqualTo("test-workflow");
        })
        .verifyComplete();
    }
}
```

### Testing StepHandler Implementations

`StepHandler` implementations are plain Spring beans that can be tested with mock `WorkflowContext` objects:

```java
import org.fireflyframework.workflow.core.StepHandler;
import org.fireflyframework.workflow.core.WorkflowContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ValidateOrderStepHandlerTest {

    @Mock private WorkflowContext context;
    @Mock private ValidationService validationService;

    @Test
    void shouldValidateOrder() {
        when(context.getInput("orderId", String.class)).thenReturn("ORD-123");
        when(validationService.validate("ORD-123"))
            .thenReturn(Mono.just(new ValidationResult(true)));

        ValidateOrderStepHandler handler = new ValidateOrderStepHandler(validationService);

        StepVerifier.create(handler.execute(context))
            .assertNext(result -> assertThat(result.isValid()).isTrue())
            .verifyComplete();
    }

    @Test
    void shouldSkipForInternalSource() {
        when(context.getInput("source", String.class)).thenReturn("internal");

        ValidateOrderStepHandler handler = new ValidateOrderStepHandler(validationService);

        assertThat(handler.shouldSkip(context)).isTrue();
    }
}
```

### Testing WorkflowContext

`WorkflowContext` is a concrete class that can be instantiated directly:

```java
import org.fireflyframework.workflow.core.WorkflowContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowContextTest {

    @Test
    void shouldManageContextData() {
        WorkflowContext ctx = new WorkflowContext(
            "inst-1", "wf-1", "corr-1",
            Map.of("orderId", "ORD-123", "amount", 99.99)
        );

        assertThat(ctx.getInput("orderId", String.class)).isEqualTo("ORD-123");
        assertThat(ctx.getInput("amount", Double.class)).isEqualTo(99.99);

        ctx.set("processed", true);
        assertThat(ctx.has("processed")).isTrue();
        assertThat(ctx.get("processed", Boolean.class)).isTrue();

        ctx.remove("processed");
        assertThat(ctx.has("processed")).isFalse();
    }
}
```

### Testing Model Records

The `WorkflowInstance`, `StepExecution`, and `RetryPolicy` records can be tested directly:

```java
import org.fireflyframework.workflow.model.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowStatusTest {

    @Test
    void shouldIdentifyTerminalStatuses() {
        assertThat(WorkflowStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(WorkflowStatus.FAILED.isTerminal()).isTrue();
        assertThat(WorkflowStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(WorkflowStatus.TIMED_OUT.isTerminal()).isTrue();
        assertThat(WorkflowStatus.RUNNING.isTerminal()).isFalse();
        assertThat(WorkflowStatus.PENDING.isTerminal()).isFalse();
    }

    @Test
    void shouldCheckSuspendAndResumeGuards() {
        assertThat(WorkflowStatus.RUNNING.canSuspend()).isTrue();
        assertThat(WorkflowStatus.WAITING.canSuspend()).isTrue();
        assertThat(WorkflowStatus.SUSPENDED.canResume()).isTrue();
        assertThat(WorkflowStatus.COMPLETED.canSuspend()).isFalse();
        assertThat(WorkflowStatus.RUNNING.canResume()).isFalse();
    }
}
```

---

## Test Configuration

### TestConfig

The `TestConfig` class provides minimal beans for unit and integration tests that do not require infrastructure dependencies:

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fireflyframework.workflow.properties.WorkflowProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

@TestConfiguration
public class TestConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    public WorkflowProperties workflowProperties() {
        WorkflowProperties properties = new WorkflowProperties();
        properties.setEnabled(true);
        properties.setDefaultTimeout(Duration.ofMinutes(5));
        properties.setDefaultStepTimeout(Duration.ofSeconds(30));

        WorkflowProperties.StateConfig stateConfig = new WorkflowProperties.StateConfig();
        stateConfig.setEnabled(true);
        stateConfig.setDefaultTtl(Duration.ofHours(1));
        stateConfig.setKeyPrefix("test-workflow");
        properties.setState(stateConfig);

        WorkflowProperties.EventConfig eventConfig = new WorkflowProperties.EventConfig();
        eventConfig.setEnabled(true);
        eventConfig.setPublishStepEvents(true);
        eventConfig.setDefaultDestination("test-workflow-events");
        properties.setEvents(eventConfig);

        WorkflowProperties.RetryConfig retryConfig = new WorkflowProperties.RetryConfig();
        retryConfig.setMaxAttempts(3);
        retryConfig.setInitialDelay(Duration.ofMillis(100));
        properties.setRetry(retryConfig);

        return properties;
    }
}
```

---

## Integration Testing with Testcontainers

### TestcontainersConfig

The `TestcontainersConfig` class provides shared Redis and Kafka containers:

```java
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

public class TestcontainersConfig {

    private static final String REDIS_IMAGE = "redis:7-alpine";
    private static final String KAFKA_IMAGE = "confluentinc/cp-kafka:7.5.0";

    private static GenericContainer<?> redisContainer;
    private static KafkaContainer kafkaContainer;

    public static synchronized GenericContainer<?> getRedisContainer() {
        if (redisContainer == null) {
            redisContainer = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(6379)
                    .withReuse(true);
            redisContainer.start();
        }
        return redisContainer;
    }

    public static synchronized KafkaContainer getKafkaContainer() {
        if (kafkaContainer == null) {
            kafkaContainer = new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE))
                    .withReuse(true);
            kafkaContainer.start();
        }
        return kafkaContainer;
    }

    public static void registerRedisProperties(DynamicPropertyRegistry registry) {
        GenericContainer<?> redis = getRedisContainer();
        registry.add("firefly.cache.enabled", () -> "true");
        registry.add("firefly.cache.redis.enabled", () -> "true");
        registry.add("firefly.cache.redis.host", redis::getHost);
        registry.add("firefly.cache.redis.port", () -> redis.getMappedPort(6379));
    }

    public static void registerKafkaProperties(DynamicPropertyRegistry registry) {
        KafkaContainer kafka = getKafkaContainer();
        registry.add("firefly.eda.enabled", () -> "true");
        registry.add("firefly.eda.publishers.enabled", () -> "true");
        registry.add("firefly.eda.publishers.kafka.default.enabled", () -> "true");
        registry.add("firefly.eda.publishers.kafka.default.bootstrap-servers",
                      kafka::getBootstrapServers);
        registry.add("firefly.eda.consumer.enabled", () -> "true");
        registry.add("firefly.eda.consumer.kafka.default.enabled", () -> "true");
        registry.add("firefly.eda.consumer.kafka.default.bootstrap-servers",
                      kafka::getBootstrapServers);
    }

    public static void registerAllProperties(DynamicPropertyRegistry registry) {
        registerRedisProperties(registry);
        registerKafkaProperties(registry);
    }
}
```

### TestApplication

Integration tests use a minimal `@SpringBootApplication` that excludes auto-configurations that conflict with the test infrastructure:

```java
@SpringBootApplication(
    scanBasePackages = "org.fireflyframework.workflow.testcontainers",
    exclude = {
        RedisAutoConfiguration.class,
        RedisReactiveAutoConfiguration.class,
        KafkaAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        R2dbcAutoConfiguration.class,
        // fireflyframework-eventsourcing auto-configs
        R2dbcBeansAutoConfiguration.class,
        EventStoreAutoConfiguration.class,
        SnapshotAutoConfiguration.class,
        EventSourcingAutoConfiguration.class,
        EventSourcingProjectionAutoConfiguration.class,
        EventSourcingHealthAutoConfiguration.class,
        EventSourcingMetricsAutoConfiguration.class,
        CircuitBreakerAutoConfiguration.class,
        MultiTenancyAutoConfiguration.class
    }
)
public class TestApplication { }
```

The eventsourcing auto-configurations are excluded because these integration tests do not provide the R2DBC infrastructure required by the event sourcing module. Individual tests use `@Import` to bring in only the auto-configurations they need.

### Redis Cache Integration Test

Tests verifying `CacheAdapter` integration with a real Redis instance:

```java
@Testcontainers
@SpringBootTest(classes = TestApplication.class)
@Import({CacheAutoConfiguration.class, RedisCacheAutoConfiguration.class,
         ResilienceTestConfig.class})
@ContextConfiguration(initializers = RedisCacheIntegrationTest.Initializer.class)
class RedisCacheIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(
        DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    static class Initializer implements
            ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            Map<String, Object> props = new HashMap<>();
            props.put("firefly.cache.enabled", "true");
            props.put("firefly.cache.redis.enabled", "true");
            props.put("firefly.cache.redis.host", redis.getHost());
            props.put("firefly.cache.redis.port", redis.getMappedPort(6379));
            ctx.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("testcontainers", props));
        }
    }

    @Autowired
    private CacheAdapter cacheAdapter;

    @Test
    void shouldPutAndGetFromCache() {
        StepVerifier.create(
            cacheAdapter.put("test-key", "test-value")
                .then(cacheAdapter.get("test-key", String.class))
        )
        .assertNext(value -> assertThat(value).isEqualTo("test-value"))
        .verifyComplete();
    }
}
```

Resilience4j registries are needed because `fireflyframework-eda` auto-configuration requires them. Provide them via a `@TestConfiguration`:

```java
@TestConfiguration
static class ResilienceTestConfig {
    @Bean CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults();
    }
    @Bean RetryRegistry retryRegistry() {
        return RetryRegistry.ofDefaults();
    }
    @Bean RateLimiterRegistry rateLimiterRegistry() {
        return RateLimiterRegistry.ofDefaults();
    }
    @Bean BulkheadRegistry bulkheadRegistry() {
        return BulkheadRegistry.ofDefaults();
    }
    @Bean TimeLimiterRegistry timeLimiterRegistry() {
        return TimeLimiterRegistry.ofDefaults();
    }
}
```

---

## Durable Execution E2E Testing

End-to-end tests for durable execution require PostgreSQL (for the `EventStore`), Redis (for `CacheAdapter`), and Kafka (for event publishing).

### DurableExecutionTestApplication

Durable execution tests use a separate `@SpringBootApplication` that lives in a sub-package to avoid component scan conflicts with `TestApplication`. It allows specific eventsourcing auto-configs to load while excluding others:

```java
@SpringBootApplication(
    scanBasePackages = "org.fireflyframework.workflow.testcontainers.durableexecution",
    exclude = {
        RedisAutoConfiguration.class,
        RedisReactiveAutoConfiguration.class,
        KafkaAutoConfiguration.class,
        // Exclude problematic eventsourcing auto-configs
        EventSourcingAutoConfiguration.class,
        EventSourcingProjectionAutoConfiguration.class,
        SnapshotAutoConfiguration.class,
        EventSourcingHealthAutoConfiguration.class,
        EventSourcingMetricsAutoConfiguration.class,
        CircuitBreakerAutoConfiguration.class,
        MultiTenancyAutoConfiguration.class
    }
)
public class DurableExecutionTestApplication { }
```

This allows `R2dbcBeansAutoConfiguration` and `EventStoreAutoConfiguration` to load, providing the `DatabaseClient`, `R2dbcEntityTemplate`, and `R2dbcEventStore` beans needed for a real PostgreSQL `EventStore`.

### DurableExecutionE2ETest

```java
@Testcontainers
@SpringBootTest(classes = DurableExecutionTestApplication.class)
@Import({
    CacheAutoConfiguration.class,
    RedisCacheAutoConfiguration.class,
    FireflyEdaAutoConfiguration.class,
    FireflyEdaKafkaPublisherAutoConfiguration.class,
    ResilienceTestConfig.class
})
@ContextConfiguration(initializers = DurableExecutionE2ETest.Initializer.class)
class DurableExecutionE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(
        DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Container
    static KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @Autowired private EventStore eventStore;
    @Autowired private EventSourcedWorkflowStateStore stateStore;

    @Test
    void shouldPersistAndReplayWorkflowAggregate() {
        UUID aggregateId = UUID.randomUUID();
        WorkflowAggregate aggregate = new WorkflowAggregate(aggregateId);

        aggregate.start("test-workflow", "Test Workflow", "1.0.0",
                Map.of("orderId", "ORD-123"), "corr-1", "test", false);
        aggregate.startStep("validate", "Validate", Map.of(), 1);
        aggregate.completeStep("validate", Map.of("valid", true), 100);
        aggregate.complete(Map.of("result", "done"));

        // Save and reload
        StepVerifier.create(
            stateStore.saveAggregate(aggregate)
                .then(stateStore.loadAggregate(aggregateId))
        )
        .assertNext(loaded -> {
            assertThat(loaded.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(loaded.getStepStates()).containsKey("validate");
            assertThat(loaded.getStepStates().get("validate").status())
                .isEqualTo(StepStatus.COMPLETED);
        })
        .verifyComplete();
    }
}
```

---

## Testing Event-Sourced Components

### Testing WorkflowAggregate

The `WorkflowAggregate` can be tested in isolation by invoking commands and verifying the resulting state and events:

```java
import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowAggregateTest {

    @Test
    void shouldTrackWorkflowLifecycle() {
        WorkflowAggregate aggregate = new WorkflowAggregate(UUID.randomUUID());

        aggregate.start("wf-1", "Workflow", "1.0.0",
                Map.of("key", "value"), "corr-1", "test", false);
        assertThat(aggregate.getStatus()).isEqualTo(WorkflowStatus.RUNNING);

        aggregate.complete(Map.of("result", "done"));
        assertThat(aggregate.getStatus()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(aggregate.getUncommittedEvents()).hasSize(2);
    }

    @Test
    void shouldRejectStartWhenNotPending() {
        WorkflowAggregate aggregate = new WorkflowAggregate(UUID.randomUUID());
        aggregate.start("wf-1", "Workflow", "1.0.0",
                Map.of(), null, "test", false);

        assertThatThrownBy(() ->
            aggregate.start("wf-1", "Workflow", "1.0.0",
                    Map.of(), null, "test", false)
        ).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldBufferSignals() {
        WorkflowAggregate aggregate = new WorkflowAggregate(UUID.randomUUID());
        aggregate.start("wf-1", "Workflow", "1.0.0",
                Map.of(), null, "test", false);

        aggregate.receiveSignal("approval", Map.of("approved", true));

        assertThat(aggregate.getPendingSignals()).containsKey("approval");
        assertThat(aggregate.getPendingSignals().get("approval").payload())
            .containsEntry("approved", true);
    }

    @Test
    void shouldRecordSideEffects() {
        WorkflowAggregate aggregate = new WorkflowAggregate(UUID.randomUUID());
        aggregate.start("wf-1", "Workflow", "1.0.0",
                Map.of(), null, "test", false);

        aggregate.recordSideEffect("order-id", "ORD-001");

        assertThat(aggregate.getSideEffect("order-id")).contains("ORD-001");
    }
}
```

### Testing SignalService

```java
@ExtendWith(MockitoExtension.class)
class SignalServiceTest {

    @Mock private EventSourcedWorkflowStateStore stateStore;

    @Test
    void shouldSendSignal() {
        WorkflowAggregate aggregate = new WorkflowAggregate(UUID.randomUUID());
        aggregate.start("wf-1", "Workflow", "1.0.0",
                Map.of(), null, "test", false);

        when(stateStore.loadAggregate(any(UUID.class)))
            .thenReturn(Mono.just(aggregate));
        when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
            .thenReturn(Mono.just(aggregate));

        SignalService service = new SignalService(stateStore);

        StepVerifier.create(
            service.sendSignal(aggregate.getId().toString(), "approval",
                    Map.of("approved", true))
        )
        .assertNext(result -> assertThat(result).isNotNull())
        .verifyComplete();
    }
}
```

### Testing WorkflowQueryService

```java
@ExtendWith(MockitoExtension.class)
class WorkflowQueryServiceTest {

    @Mock private EventSourcedWorkflowStateStore stateStore;

    @Test
    void shouldExecuteGetStatusQuery() {
        WorkflowAggregate aggregate = new WorkflowAggregate(UUID.randomUUID());
        aggregate.start("wf-1", "Workflow", "1.0.0",
                Map.of(), null, "test", false);

        when(stateStore.loadAggregate(aggregate.getId()))
            .thenReturn(Mono.just(aggregate));

        WorkflowQueryService service = new WorkflowQueryService(stateStore);

        StepVerifier.create(
            service.executeQuery(aggregate.getId().toString(), "getStatus")
        )
        .assertNext(result -> assertThat(result).isEqualTo("RUNNING"))
        .verifyComplete();
    }

    @Test
    void shouldRejectUnknownQuery() {
        WorkflowAggregate aggregate = new WorkflowAggregate(UUID.randomUUID());
        aggregate.start("wf-1", "Workflow", "1.0.0",
                Map.of(), null, "test", false);

        when(stateStore.loadAggregate(aggregate.getId()))
            .thenReturn(Mono.just(aggregate));

        WorkflowQueryService service = new WorkflowQueryService(stateStore);

        StepVerifier.create(
            service.executeQuery(aggregate.getId().toString(), "invalidQuery")
        )
        .expectError(IllegalArgumentException.class)
        .verify();
    }
}
```

---

## Existing Test Suite

The project includes the following test classes:

### Unit Tests

| Test Class | Tests |
|------------|-------|
| `WorkflowEngineTest` | WorkflowEngine facade methods |
| `WorkflowContextTest` | Context data management |
| `WorkflowContextDurableTest` | Durable execution context methods |
| `WorkflowRegistryTest` | Workflow registration and lookup |
| `WorkflowInstanceTest` | WorkflowInstance record operations |
| `StepExecutionTest` | StepExecution record operations |
| `RetryPolicyTest` | Retry policy calculations |
| `WorkflowEventTest` | Event publishing |
| `WorkflowControllerTest` | REST controller endpoints |
| `StepChoreographyTest` | Step-level choreography |
| `WorkflowAggregateTest` | Aggregate lifecycle commands |
| `WorkflowAggregateStepsTest` | Aggregate step operations |
| `WorkflowLifecycleEventsTest` | Lifecycle event classes |
| `StepEventsTest` | Step event classes |
| `AdvancedEventsTest` | Signal, timer, child workflow events |
| `WorkflowSnapshotTest` | Snapshot creation and restoration |
| `EventSourcedWorkflowStateStoreTest` | Event-sourced state store |
| `SignalServiceTest` | Signal sending and consuming |
| `WorkflowQueryServiceTest` | Query execution |
| `SearchAttributeProjectionTest` | Search attribute projection |
| `WorkflowSearchServiceTest` | Search by attributes |
| `TimerSchedulerServiceTest` | Timer scheduling |
| `WorkflowTimerProjectionTest` | Timer projection |
| `ChildWorkflowServiceTest` | Child workflow operations |
| `CompensationOrchestratorTest` | Compensation orchestration |
| `ContinueAsNewServiceTest` | Continue-as-new operations |

### Integration Tests

| Test Class | Infrastructure | Tests |
|------------|---------------|-------|
| `RedisCacheIntegrationTest` | Redis | CacheAdapter put/get operations |
| `KafkaEdaIntegrationTest` | Redis + Kafka | Event publishing via fireflyframework-eda |
| `WorkflowIntegrationTest` | Redis + Kafka | Full workflow execution |
| `StepChoreographyIntegrationTest` | Redis + Kafka | Step-level choreography |
| `BackwardCompatibilityTest` | -- | Compatibility between execution modes |
| `DurableExecutionIntegrationTest` | -- | Durable execution without infrastructure |
| `DurableExecutionE2ETest` | PostgreSQL + Redis + Kafka | Full durable execution round-trip |

---

## Next Steps

- [Getting Started](getting-started.md) -- Prerequisites, cache setup, first workflow
- [Architecture](architecture.md) -- Internal components and execution model
- [Configuration](configuration.md) -- Complete property reference with defaults
- [API Reference](api-reference.md) -- REST endpoints and Java API
- [Advanced Features](advanced-features.md) -- DAG execution, resilience, scheduling, DLQ
- [Durable Execution](durable-execution.md) -- Signals, timers, child workflows, compensation
