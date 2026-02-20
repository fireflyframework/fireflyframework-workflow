/*
 * Copyright 2024-2026 Firefly Software Solutions Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fireflyframework.workflow.testcontainers.durableexecution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import org.fireflyframework.cache.config.CacheAutoConfiguration;
import org.fireflyframework.cache.config.RedisCacheAutoConfiguration;
import org.fireflyframework.eda.config.FireflyEdaAutoConfiguration;
import org.fireflyframework.eda.config.FireflyEdaKafkaPublisherAutoConfiguration;
import org.fireflyframework.eventsourcing.annotation.DomainEvent;
import org.fireflyframework.eventsourcing.store.ConcurrencyException;
import org.fireflyframework.eventsourcing.store.EventStore;
import org.fireflyframework.workflow.child.ChildWorkflowService;
import org.fireflyframework.workflow.continueasnew.ContinueAsNewService;
import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.eventsourcing.event.*;
import org.fireflyframework.workflow.eventsourcing.store.EventSourcedWorkflowStateStore;
import org.fireflyframework.workflow.model.StepStatus;
import org.fireflyframework.workflow.model.WorkflowStatus;
import org.fireflyframework.workflow.query.WorkflowQueryService;
import org.fireflyframework.workflow.search.SearchAttributeProjection;
import org.fireflyframework.workflow.search.WorkflowSearchService;
import org.fireflyframework.workflow.signal.SignalService;
import org.fireflyframework.workflow.timer.WorkflowTimerProjection;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for the durable execution engine.
 * <p>
 * These tests validate that all durable execution primitives work correctly
 * against real infrastructure: PostgreSQL (EventStore via R2DBC + Flyway),
 * Redis (CacheAdapter), and Kafka (EDA event publishing).
 * <p>
 * Each test exercises a complete round-trip through the event-sourced
 * aggregate, persisting events to PostgreSQL and loading them back to
 * verify full state reconstruction.
 */
@Testcontainers
@SpringBootTest(classes = DurableExecutionTestApplication.class)
@Import({
        CacheAutoConfiguration.class,
        RedisCacheAutoConfiguration.class,
        FireflyEdaAutoConfiguration.class,
        FireflyEdaKafkaPublisherAutoConfiguration.class,
        DurableExecutionE2ETest.ResilienceTestConfig.class
})
@ContextConfiguration(initializers = DurableExecutionE2ETest.Initializer.class)
@DisplayName("Durable Execution E2E Tests (PostgreSQL + Redis + Kafka)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DurableExecutionE2ETest {

    // ========================================================================
    // Test Configuration
    // ========================================================================

    @TestConfiguration
    static class ResilienceTestConfig {

        private static final List<Class<?>> WORKFLOW_EVENT_TYPES = List.of(
                WorkflowStartedEvent.class, WorkflowCompletedEvent.class,
                WorkflowFailedEvent.class, WorkflowCancelledEvent.class,
                WorkflowSuspendedEvent.class, WorkflowResumedEvent.class,
                StepStartedEvent.class, StepCompletedEvent.class,
                StepFailedEvent.class, StepSkippedEvent.class, StepRetriedEvent.class,
                SignalReceivedEvent.class,
                TimerRegisteredEvent.class, TimerFiredEvent.class,
                ChildWorkflowSpawnedEvent.class, ChildWorkflowCompletedEvent.class,
                SideEffectRecordedEvent.class, HeartbeatRecordedEvent.class,
                ContinueAsNewEvent.class,
                CompensationStartedEvent.class, CompensationStepCompletedEvent.class,
                SearchAttributeUpdatedEvent.class
        );

        @Autowired
        void registerWorkflowEventSubtypes(ObjectMapper objectMapper) {
            // Register subtypes so Jackson can resolve @JsonTypeInfo type names
            for (Class<?> eventClass : WORKFLOW_EVENT_TYPES) {
                DomainEvent ann = eventClass.getAnnotation(DomainEvent.class);
                if (ann != null) {
                    objectMapper.registerSubtypes(new NamedType(eventClass, ann.value()));
                }
            }
            // The EventMixin uses @JsonTypeInfo(visible=true) which keeps "eventType"
            // in the JSON data during deserialization. Since event classes have getEventType()
            // but no setter, this causes an "unrecognized field" error.
            objectMapper.configure(
                    com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        }

        @Bean
        public CircuitBreakerRegistry circuitBreakerRegistry() {
            return CircuitBreakerRegistry.ofDefaults();
        }

        @Bean
        public RetryRegistry retryRegistry() {
            return RetryRegistry.ofDefaults();
        }

        @Bean
        public RateLimiterRegistry rateLimiterRegistry() {
            return RateLimiterRegistry.ofDefaults();
        }

        @Bean
        public BulkheadRegistry bulkheadRegistry() {
            return BulkheadRegistry.ofDefaults();
        }

        @Bean
        public TimeLimiterRegistry timeLimiterRegistry() {
            return TimeLimiterRegistry.ofDefaults();
        }
    }

    // ========================================================================
    // Testcontainers
    // ========================================================================

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:15-alpine"))
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    // ========================================================================
    // Context Initializer
    // ========================================================================

    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            if (!postgres.isRunning()) postgres.start();
            if (!redis.isRunning()) redis.start();
            if (!kafka.isRunning()) kafka.start();

            Map<String, Object> props = new HashMap<>();

            // R2DBC (EventStore)
            props.put("spring.r2dbc.url", String.format("r2dbc:postgresql://%s:%d/%s",
                    postgres.getHost(), postgres.getMappedPort(5432), postgres.getDatabaseName()));
            props.put("spring.r2dbc.username", postgres.getUsername());
            props.put("spring.r2dbc.password", postgres.getPassword());

            // Flyway (schema migrations from eventsourcing module)
            props.put("spring.flyway.enabled", "true");
            props.put("spring.flyway.url", postgres.getJdbcUrl());
            props.put("spring.flyway.user", postgres.getUsername());
            props.put("spring.flyway.password", postgres.getPassword());
            props.put("spring.flyway.baseline-on-migrate", "true");

            // Redis (Cache)
            props.put("firefly.cache.enabled", "true");
            props.put("firefly.cache.redis.enabled", "true");
            props.put("firefly.cache.redis.host", redis.getHost());
            props.put("firefly.cache.redis.port", redis.getMappedPort(6379));
            props.put("firefly.cache.default-cache-type", "REDIS");

            // Kafka (EDA)
            props.put("firefly.eda.enabled", "true");
            props.put("firefly.eda.publishers.enabled", "true");
            props.put("firefly.eda.publishers.kafka.default.enabled", "true");
            props.put("firefly.eda.publishers.kafka.default.bootstrap-servers", kafka.getBootstrapServers());

            // Durable Execution
            props.put("firefly.workflow.eventsourcing.enabled", "true");
            props.put("firefly.workflow.signals.enabled", "true");
            props.put("firefly.workflow.timers.enabled", "true");
            props.put("firefly.workflow.child-workflows.enabled", "true");
            props.put("firefly.workflow.compensation.enabled", "true");
            props.put("firefly.workflow.search-attributes.enabled", "true");
            props.put("firefly.workflow.heartbeat.enabled", "true");

            // Eventsourcing module
            props.put("firefly.eventsourcing.enabled", "true");
            props.put("firefly.eventsourcing.store.type", "r2dbc");
            props.put("firefly.eventsourcing.snapshot.enabled", "false");
            props.put("firefly.eventsourcing.publisher.enabled", "false");

            // Disable features that require full workflow engine beans (CacheAdapter-based state store etc.)
            props.put("firefly.workflow.api.enabled", "false");
            props.put("firefly.workflow.scheduling.enabled", "false");
            props.put("firefly.workflow.recovery.enabled", "false");
            props.put("firefly.workflow.health-indicator-enabled", "false");
            props.put("firefly.workflow.dlq.enabled", "false");

            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("testcontainers", props));
        }
    }

    // ========================================================================
    // Injected Services
    // ========================================================================

    @Autowired
    EventStore eventStore;

    @Autowired
    EventSourcedWorkflowStateStore stateStore;

    @Autowired
    SignalService signalService;

    @Autowired
    WorkflowQueryService queryService;

    @Autowired
    WorkflowSearchService searchService;

    @Autowired
    ChildWorkflowService childWorkflowService;

    @Autowired
    ContinueAsNewService continueAsNewService;

    @Autowired
    WorkflowTimerProjection timerProjection;

    @Autowired
    SearchAttributeProjection searchAttributeProjection;

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Creates a workflow aggregate, starts it, and saves it to PostgreSQL.
     */
    private WorkflowAggregate createAndSaveRunningWorkflow(UUID id, String workflowId) {
        WorkflowAggregate aggregate = new WorkflowAggregate(id);
        aggregate.start(workflowId, "Test Workflow", "1.0.0",
                Map.of("key", "value"), "corr-" + id, "test", false);
        stateStore.saveAggregate(aggregate).block();
        return aggregate;
    }

    // ========================================================================
    // Test 1: Aggregate Lifecycle — Create and Load from PostgreSQL
    // ========================================================================

    @Test
    @Order(1)
    @DisplayName("1. Aggregate lifecycle: create, step through, and reload from PostgreSQL")
    void aggregateLifecycle_createAndLoadFromPostgres() {
        UUID id = UUID.randomUUID();

        // Create and exercise the aggregate
        WorkflowAggregate aggregate = new WorkflowAggregate(id);
        aggregate.start("order-workflow", "Order Processing", "2.0.0",
                Map.of("orderId", "ORD-123"), "corr-001", "api", false);
        aggregate.startStep("validate", "Validate Order", Map.of("orderId", "ORD-123"), 1);
        aggregate.completeStep("validate", Map.of("valid", true), 50L);
        aggregate.startStep("process", "Process Payment", Map.of("amount", 99.99), 1);
        aggregate.completeStep("process", Map.of("transactionId", "TXN-456"), 200L);

        // Save to PostgreSQL
        StepVerifier.create(stateStore.saveAggregate(aggregate))
                .assertNext(saved -> assertThat(saved.getId()).isEqualTo(id))
                .verifyComplete();

        // Reload from PostgreSQL
        StepVerifier.create(stateStore.loadAggregate(id))
                .assertNext(loaded -> {
                    assertThat(loaded.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
                    assertThat(loaded.getWorkflowId()).isEqualTo("order-workflow");
                    assertThat(loaded.getWorkflowName()).isEqualTo("Order Processing");
                    assertThat(loaded.getWorkflowVersion()).isEqualTo("2.0.0");
                    assertThat(loaded.getCurrentStepId()).isEqualTo("process");
                    assertThat(loaded.getStepStates()).hasSize(2);
                    assertThat(loaded.getStepStates().get("validate").status()).isEqualTo(StepStatus.COMPLETED);
                    assertThat(loaded.getStepStates().get("process").status()).isEqualTo(StepStatus.COMPLETED);
                    assertThat(loaded.getCurrentVersion()).isEqualTo(aggregate.getCurrentVersion());
                })
                .verifyComplete();
    }

    // ========================================================================
    // Test 2: Signal Delivery — Persists and Buffers in EventStore
    // ========================================================================

    @Test
    @Order(2)
    @DisplayName("2. Signal delivery: persists signal in EventStore and buffers in aggregate")
    void signalDelivery_persistsAndBuffersInEventStore() {
        UUID id = UUID.randomUUID();
        createAndSaveRunningWorkflow(id, "signal-workflow");

        // Send a signal
        Map<String, Object> signalPayload = Map.of("approved", true, "approver", "manager");
        StepVerifier.create(signalService.sendSignal(id.toString(), "approval", signalPayload))
                .assertNext(result -> {
                    assertThat(result.delivered()).isTrue();
                    assertThat(result.signalName()).isEqualTo("approval");
                })
                .verifyComplete();

        // Reload and verify the signal is buffered
        StepVerifier.create(stateStore.loadAggregate(id))
                .assertNext(loaded -> {
                    assertThat(loaded.getPendingSignals()).containsKey("approval");
                    assertThat(loaded.getPendingSignals().get("approval").payload())
                            .containsEntry("approved", true)
                            .containsEntry("approver", "manager");
                })
                .verifyComplete();
    }

    // ========================================================================
    // Test 3: Signal Consumption — Returns Buffered Payload
    // ========================================================================

    @Test
    @Order(3)
    @DisplayName("3. Signal consumption: returns buffered payload from aggregate")
    void signalConsumption_returnsBufferedPayload() {
        UUID id = UUID.randomUUID();
        createAndSaveRunningWorkflow(id, "consume-signal-workflow");

        // Send a signal
        Map<String, Object> payload = Map.of("action", "retry", "count", 3);
        signalService.sendSignal(id.toString(), "retry-signal", payload).block();

        // Consume the signal
        StepVerifier.create(signalService.consumeSignal(id.toString(), "retry-signal"))
                .assertNext(consumed -> {
                    assertThat(consumed).containsEntry("action", "retry");
                    assertThat(consumed).containsEntry("count", 3);
                })
                .verifyComplete();
    }

    // ========================================================================
    // Test 4: Timer Registration — Tracked in Projection and Aggregate
    // ========================================================================

    @Test
    @Order(4)
    @DisplayName("4. Timer registration: tracked in projection and aggregate")
    void timerRegistration_trackedInProjectionAndAggregate() {
        UUID id = UUID.randomUUID();
        WorkflowAggregate aggregate = createAndSaveRunningWorkflow(id, "timer-workflow");

        // Reload the committed aggregate so we can apply new events cleanly
        aggregate = stateStore.loadAggregate(id).block();
        assertThat(aggregate).isNotNull();

        // Register a timer that fires in the past (already due)
        Instant fireAt = Instant.now().minusSeconds(10);
        Map<String, Object> timerData = Map.of("reminderType", "escalation");
        aggregate.registerTimer("escalation-timer", fireAt, timerData);

        // Save the aggregate with the timer event
        stateStore.saveAggregate(aggregate).block();

        // Update the timer projection from the aggregate state
        WorkflowAggregate reloaded = stateStore.loadAggregate(id).block();
        assertThat(reloaded).isNotNull();
        reloaded.getActiveTimers().forEach((timerId, timer) ->
                timerProjection.onTimerRegistered(id, timerId, timer.fireAt(), timer.data()));

        // Verify: projection finds due timers
        var dueTimers = timerProjection.findDueTimers(Instant.now(), 100);
        assertThat(dueTimers).anyMatch(t ->
                t.instanceId().equals(id) && t.timerId().equals("escalation-timer"));

        // Verify: aggregate has the timer
        assertThat(reloaded.getActiveTimers()).containsKey("escalation-timer");
        assertThat(reloaded.getActiveTimers().get("escalation-timer").data())
                .containsEntry("reminderType", "escalation");
    }

    // ========================================================================
    // Test 5: Child Workflow — Spawn and Complete
    // ========================================================================

    @Test
    @Order(5)
    @DisplayName("5. Child workflow: spawn and complete round-trip through PostgreSQL")
    void childWorkflow_spawnAndComplete() {
        UUID parentId = UUID.randomUUID();
        createAndSaveRunningWorkflow(parentId, "parent-workflow");

        // Spawn a child workflow
        Map<String, Object> childInput = Map.of("item", "widget", "quantity", 5);
        var spawnResult = childWorkflowService
                .spawnChildWorkflow(parentId, "fulfillment-step", "child-fulfillment", childInput)
                .block();

        assertThat(spawnResult).isNotNull();
        assertThat(spawnResult.spawned()).isTrue();
        String childInstanceId = spawnResult.childInstanceId();

        // Verify parent has child in its map
        WorkflowAggregate parent = stateStore.loadAggregate(parentId).block();
        assertThat(parent).isNotNull();
        assertThat(parent.getChildWorkflows()).containsKey(childInstanceId);
        assertThat(parent.getChildWorkflows().get(childInstanceId).completed()).isFalse();

        // Complete the child workflow
        Map<String, Object> childOutput = Map.of("shipped", true, "trackingId", "TRACK-789");
        childWorkflowService.completeChildWorkflow(
                UUID.fromString(childInstanceId), childOutput, true).block();

        // Reload parent and verify child completion
        WorkflowAggregate updatedParent = stateStore.loadAggregate(parentId).block();
        assertThat(updatedParent).isNotNull();
        var childRef = updatedParent.getChildWorkflows().get(childInstanceId);
        assertThat(childRef.completed()).isTrue();
        assertThat(childRef.success()).isTrue();
    }

    // ========================================================================
    // Test 6: Search Attributes — Index and Query
    // ========================================================================

    @Test
    @Order(6)
    @DisplayName("6. Search attributes: index via aggregate and query via projection")
    void searchAttributes_indexAndQuery() {
        UUID id = UUID.randomUUID();
        WorkflowAggregate aggregate = createAndSaveRunningWorkflow(id, "search-workflow");

        // Reload and set search attributes
        aggregate = stateStore.loadAggregate(id).block();
        assertThat(aggregate).isNotNull();
        aggregate.upsertSearchAttribute("customerId", "CUST-42");
        aggregate.upsertSearchAttribute("region", "us-east-1");
        stateStore.saveAggregate(aggregate).block();

        // Update the search attribute projection
        searchAttributeProjection.onSearchAttributeUpdated(id, "customerId", "CUST-42");
        searchAttributeProjection.onSearchAttributeUpdated(id, "region", "us-east-1");

        // Query by single attribute
        StepVerifier.create(searchService.searchByAttribute("customerId", "CUST-42").collectList())
                .assertNext(results -> {
                    assertThat(results).hasSize(1);
                    assertThat(results.get(0).instanceId()).isEqualTo(id.toString());
                })
                .verifyComplete();

        // Query by multiple attributes (AND)
        StepVerifier.create(searchService.searchByAttributes(
                        Map.of("customerId", "CUST-42", "region", "us-east-1")).collectList())
                .assertNext(results -> {
                    assertThat(results).hasSize(1);
                    assertThat(results.get(0).instanceId()).isEqualTo(id.toString());
                })
                .verifyComplete();
    }

    // ========================================================================
    // Test 7: Workflow Query — Built-in Queries
    // ========================================================================

    @Test
    @Order(7)
    @DisplayName("7. Workflow query: built-in queries return correct data from aggregate")
    void workflowQuery_builtInQueries() {
        UUID id = UUID.randomUUID();
        WorkflowAggregate aggregate = new WorkflowAggregate(id);
        aggregate.start("query-workflow", "Query Test", "1.0.0",
                Map.of("param", "value"), "corr-query", "test", false);
        aggregate.startStep("step-a", "Step A", Map.of(), 1);
        aggregate.completeStep("step-a", Map.of("result", "ok"), 100L);
        stateStore.saveAggregate(aggregate).block();

        // getStatus
        StepVerifier.create(queryService.executeQuery(id.toString(), "getStatus"))
                .assertNext(result -> assertThat(result).isEqualTo("RUNNING"))
                .verifyComplete();

        // getCurrentStep
        StepVerifier.create(queryService.executeQuery(id.toString(), "getCurrentStep"))
                .assertNext(result -> assertThat(result).isEqualTo("step-a"))
                .verifyComplete();

        // getStepHistory
        StepVerifier.create(queryService.executeQuery(id.toString(), "getStepHistory"))
                .assertNext(result -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Map<String, Object>> history = (Map<String, Map<String, Object>>) result;
                    assertThat(history).containsKey("step-a");
                    assertThat(history.get("step-a").get("status")).isEqualTo("COMPLETED");
                })
                .verifyComplete();

        // getInput
        StepVerifier.create(queryService.executeQuery(id.toString(), "getInput"))
                .assertNext(result -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> input = (Map<String, Object>) result;
                    assertThat(input).containsEntry("param", "value");
                })
                .verifyComplete();
    }

    // ========================================================================
    // Test 8: Continue-as-New — Resets Event History
    // ========================================================================

    @Test
    @Order(8)
    @DisplayName("8. Continue-as-new: completes old aggregate, creates new one with migrated state")
    void continueAsNew_resetsEventHistory() {
        UUID id = UUID.randomUUID();
        WorkflowAggregate aggregate = new WorkflowAggregate(id);
        aggregate.start("long-running-wf", "Long Running", "1.0.0",
                Map.of("iteration", 1), "corr-can", "scheduler", false);
        // Add a signal and timer to verify migration
        aggregate.receiveSignal("pending-approval", Map.of("from", "user-1"));
        aggregate.registerTimer("reminder", Instant.now().plusSeconds(3600), Map.of("type", "daily"));
        aggregate.startStep("poll", "Poll Step", Map.of(), 1);
        aggregate.completeStep("poll", Map.of("polled", true), 50L);
        stateStore.saveAggregate(aggregate).block();

        // Continue as new
        Map<String, Object> newInput = Map.of("iteration", 2);
        var result = continueAsNewService.continueAsNew(id, newInput).block();

        assertThat(result).isNotNull();
        assertThat(result.previousInstanceId()).isEqualTo(id.toString());
        assertThat(result.migratedSignals()).isEqualTo(1);
        assertThat(result.migratedTimers()).isEqualTo(1);

        // Verify old aggregate is completed
        StepVerifier.create(stateStore.loadAggregate(id))
                .assertNext(old -> assertThat(old.getStatus()).isEqualTo(WorkflowStatus.COMPLETED))
                .verifyComplete();

        // Verify new aggregate exists with migrated state
        UUID newId = UUID.fromString(result.newInstanceId());
        StepVerifier.create(stateStore.loadAggregate(newId))
                .assertNext(newAgg -> {
                    assertThat(newAgg.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
                    assertThat(newAgg.getWorkflowId()).isEqualTo("long-running-wf");
                    assertThat(newAgg.getInput()).containsEntry("iteration", 2);
                    // Signals migrated
                    assertThat(newAgg.getPendingSignals()).containsKey("pending-approval");
                    // Timers migrated
                    assertThat(newAgg.getActiveTimers()).containsKey("reminder");
                })
                .verifyComplete();
    }

    // ========================================================================
    // Test 9: Optimistic Concurrency — Detects Conflict
    // ========================================================================

    @Test
    @Order(9)
    @DisplayName("9. Optimistic concurrency: detects conflict on concurrent save")
    void optimisticConcurrency_detectsConflict() {
        UUID id = UUID.randomUUID();
        createAndSaveRunningWorkflow(id, "concurrency-workflow");

        // Load the same aggregate twice
        WorkflowAggregate first = stateStore.loadAggregate(id).block();
        WorkflowAggregate second = stateStore.loadAggregate(id).block();
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();

        // Modify both independently
        first.startStep("step-from-first", "First Writer", Map.of(), 1);
        second.startStep("step-from-second", "Second Writer", Map.of(), 1);

        // First save succeeds
        StepVerifier.create(stateStore.saveAggregate(first))
                .assertNext(saved -> assertThat(saved.getId()).isEqualTo(id))
                .verifyComplete();

        // Second save fails with ConcurrencyException
        StepVerifier.create(stateStore.saveAggregate(second))
                .expectError(ConcurrencyException.class)
                .verify();
    }

    // ========================================================================
    // Test 10: Event Replay — Reconstructs Full State
    // ========================================================================

    @Test
    @Order(10)
    @DisplayName("10. Event replay: 15+ events fully reconstructed from PostgreSQL")
    void eventReplay_reconstructsFullState() {
        UUID id = UUID.randomUUID();

        WorkflowAggregate aggregate = new WorkflowAggregate(id);
        // 1: WorkflowStartedEvent
        aggregate.start("replay-workflow", "Replay Test", "3.0.0",
                Map.of("batch", "B-100"), "corr-replay", "cli", false);
        // 2-3: Step validate
        aggregate.startStep("validate", "Validate", Map.of(), 1);
        aggregate.completeStep("validate", Map.of("valid", true), 30L);
        // 4-5: Step process
        aggregate.startStep("process", "Process", Map.of(), 1);
        aggregate.completeStep("process", Map.of("processed", true), 100L);
        // 6: Signal received
        aggregate.receiveSignal("data-ready", Map.of("source", "upstream"));
        // 7: Timer registered
        aggregate.registerTimer("timeout-timer", Instant.now().plusSeconds(600), Map.of("level", "warn"));
        // 8: Child workflow spawned
        aggregate.spawnChildWorkflow("child-001", "child-wf", Map.of("task", "sub"), "process");
        // 9: Child workflow completed
        aggregate.completeChildWorkflow("child-001", Map.of("done", true), true);
        // 10: Side effect recorded
        aggregate.recordSideEffect("random-seed", 42);
        // 11: Heartbeat recorded
        aggregate.heartbeat("process", Map.of("progress", 75));
        // 12: Search attribute set
        aggregate.upsertSearchAttribute("batchId", "B-100");
        // 13: Search attribute set (another)
        aggregate.upsertSearchAttribute("priority", "high");
        // 14-15: Step finalize
        aggregate.startStep("finalize", "Finalize", Map.of(), 1);
        aggregate.completeStep("finalize", Map.of("finalized", true), 50L);
        // 16: Another signal
        aggregate.receiveSignal("notification-ack", Map.of("acked", true));

        // Save all 16 events
        stateStore.saveAggregate(aggregate).block();

        // Reload from PostgreSQL — full event replay
        StepVerifier.create(stateStore.loadAggregate(id))
                .assertNext(loaded -> {
                    // Workflow identity
                    assertThat(loaded.getWorkflowId()).isEqualTo("replay-workflow");
                    assertThat(loaded.getWorkflowName()).isEqualTo("Replay Test");
                    assertThat(loaded.getWorkflowVersion()).isEqualTo("3.0.0");
                    assertThat(loaded.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
                    assertThat(loaded.getCorrelationId()).isEqualTo("corr-replay");

                    // Steps: validate, process, finalize — all completed
                    assertThat(loaded.getStepStates()).hasSize(3);
                    assertThat(loaded.getStepStates().get("validate").status()).isEqualTo(StepStatus.COMPLETED);
                    assertThat(loaded.getStepStates().get("process").status()).isEqualTo(StepStatus.COMPLETED);
                    assertThat(loaded.getStepStates().get("finalize").status()).isEqualTo(StepStatus.COMPLETED);
                    assertThat(loaded.getCurrentStepId()).isEqualTo("finalize");

                    // Signals: 2 pending
                    assertThat(loaded.getPendingSignals()).hasSize(2);
                    assertThat(loaded.getPendingSignals()).containsKey("data-ready");
                    assertThat(loaded.getPendingSignals()).containsKey("notification-ack");

                    // Timers: 1 active
                    assertThat(loaded.getActiveTimers()).hasSize(1);
                    assertThat(loaded.getActiveTimers()).containsKey("timeout-timer");

                    // Child workflows: 1, completed
                    assertThat(loaded.getChildWorkflows()).hasSize(1);
                    assertThat(loaded.getChildWorkflows().get("child-001").completed()).isTrue();
                    assertThat(loaded.getChildWorkflows().get("child-001").success()).isTrue();

                    // Side effects
                    assertThat(loaded.getSideEffects()).containsEntry("random-seed", 42);

                    // Heartbeats
                    assertThat(loaded.getLastHeartbeats()).containsKey("process");

                    // Search attributes
                    assertThat(loaded.getSearchAttributes()).containsEntry("batchId", "B-100");
                    assertThat(loaded.getSearchAttributes()).containsEntry("priority", "high");

                    // Version matches
                    assertThat(loaded.getCurrentVersion()).isEqualTo(15L);
                })
                .verifyComplete();
    }
}
