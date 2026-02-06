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

package org.fireflyframework.workflow.testcontainers;

import org.fireflyframework.cache.config.CacheAutoConfiguration;
import org.fireflyframework.cache.config.RedisCacheAutoConfiguration;
import org.fireflyframework.cache.core.CacheAdapter;
import org.fireflyframework.cache.core.CacheType;
import org.fireflyframework.eda.annotation.PublisherType;
import org.fireflyframework.eda.config.FireflyEdaAutoConfiguration;
import org.fireflyframework.eda.config.FireflyEdaKafkaPublisherAutoConfiguration;
import org.fireflyframework.eda.publisher.EventPublisher;
import org.fireflyframework.eda.publisher.EventPublisherFactory;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Combined integration tests for workflow engine using both fireflyframework-eda and fireflyframework-cache.
 * <p>
 * These tests verify that the workflow engine can successfully use both libraries together
 * with real Kafka and Redis instances running in containers.
 */
@Testcontainers
@SpringBootTest(classes = TestApplication.class)
@Import({
        CacheAutoConfiguration.class,
        RedisCacheAutoConfiguration.class,
        FireflyEdaAutoConfiguration.class,
        FireflyEdaKafkaPublisherAutoConfiguration.class,
        WorkflowIntegrationTest.ResilienceTestConfig.class
})
@ContextConfiguration(initializers = WorkflowIntegrationTest.Initializer.class)
@DisplayName("Workflow Engine Integration Tests (Kafka + Redis)")
class WorkflowIntegrationTest {

    /**
     * Test configuration that provides Resilience4j beans required by fireflyframework-eda.
     */
    @TestConfiguration
    static class ResilienceTestConfig {
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

    private static final String WORKFLOW_EVENTS_TOPIC_PREFIX = "workflow-events-";
    private String workflowEventsTopic;

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    /**
     * Initializer that sets properties before Spring context loads.
     */
    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            // Start containers if not already started
            if (!redis.isRunning()) {
                redis.start();
            }
            if (!kafka.isRunning()) {
                kafka.start();
            }

            Map<String, Object> props = new HashMap<>();
            // Redis configuration for fireflyframework-cache
            props.put("firefly.cache.enabled", "true");
            props.put("firefly.cache.redis.enabled", "true");
            props.put("firefly.cache.redis.host", redis.getHost());
            props.put("firefly.cache.redis.port", redis.getMappedPort(6379));
            props.put("firefly.cache.default-cache-type", "REDIS");

            // Kafka configuration for fireflyframework-eda
            props.put("firefly.eda.enabled", "true");
            props.put("firefly.eda.publishers.enabled", "true");
            props.put("firefly.eda.publishers.kafka.default.enabled", "true");
            props.put("firefly.eda.publishers.kafka.default.bootstrap-servers", kafka.getBootstrapServers());

            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("testcontainers", props));
        }
    }

    @Autowired
    private CacheAdapter cacheAdapter;

    @Autowired
    private EventPublisherFactory eventPublisherFactory;

    private EventPublisher kafkaPublisher;
    private KafkaConsumer<String, String> testConsumer;
    private final List<String> receivedMessages = new CopyOnWriteArrayList<>();
    private final AtomicBoolean consumerRunning = new AtomicBoolean(false);
    private Thread consumerThread;

    @BeforeEach
    void setUp() {
        kafkaPublisher = eventPublisherFactory.getPublisher(PublisherType.KAFKA, "default");
        cacheAdapter.clear().block();
        receivedMessages.clear();
        // Use unique topic per test to avoid message pollution
        workflowEventsTopic = WORKFLOW_EVENTS_TOPIC_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        startTestConsumer();
    }

    @AfterEach
    void tearDown() {
        stopTestConsumer();
    }

    private void startTestConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        testConsumer = new KafkaConsumer<>(props);
        testConsumer.subscribe(Collections.singletonList(workflowEventsTopic));

        consumerRunning.set(true);
        consumerThread = new Thread(() -> {
            while (consumerRunning.get()) {
                ConsumerRecords<String, String> records = testConsumer.poll(Duration.ofMillis(100));
                records.forEach(record -> receivedMessages.add(record.value()));
            }
        });
        consumerThread.start();
    }

    private void stopTestConsumer() {
        consumerRunning.set(false);
        if (consumerThread != null) {
            try {
                consumerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (testConsumer != null) {
            testConsumer.close();
        }
    }

    @Test
    @DisplayName("Should have both cache and publisher available")
    void shouldHaveBothServicesAvailable() {
        assertThat(cacheAdapter).isNotNull();
        assertThat(cacheAdapter.isAvailable()).isTrue();
        // Cache type can be REDIS or AUTO (when using SmartCacheAdapter with L1+L2)
        assertThat(cacheAdapter.getCacheType()).isIn(CacheType.REDIS, CacheType.AUTO);

        assertThat(kafkaPublisher).isNotNull();
        assertThat(kafkaPublisher.getPublisherType()).isEqualTo(PublisherType.KAFKA);
    }

    @Test
    @DisplayName("Should simulate workflow execution with cache and events")
    void shouldSimulateWorkflowExecution() throws InterruptedException {
        String workflowId = "wf-" + UUID.randomUUID();

        // 1. Start workflow - cache initial state and publish event
        Map<String, Object> initialState = new LinkedHashMap<>();
        initialState.put("workflowId", workflowId);
        initialState.put("status", "STARTED");
        initialState.put("currentStep", 0);
        initialState.put("startTime", System.currentTimeMillis());

        StepVerifier.create(cacheAdapter.put(workflowId, initialState))
                .verifyComplete();

        String startEvent = String.format("{\"type\":\"WORKFLOW_STARTED\",\"workflowId\":\"%s\"}", workflowId);
        Map<String, Object> startHeaders = Map.of("partition_key", workflowId);
        StepVerifier.create(kafkaPublisher.publish(startEvent, workflowEventsTopic, startHeaders))
                .verifyComplete();

        // 2. Execute steps - update cache and publish events for each step
        for (int step = 1; step <= 3; step++) {
            // Update cache with step progress
            Map<String, Object> stepState = new LinkedHashMap<>();
            stepState.put("workflowId", workflowId);
            stepState.put("status", "RUNNING");
            stepState.put("currentStep", step);
            stepState.put("lastUpdated", System.currentTimeMillis());

            StepVerifier.create(cacheAdapter.put(workflowId, stepState))
                    .verifyComplete();

            // Publish step event
            String stepEvent = String.format(
                    "{\"type\":\"STEP_COMPLETED\",\"workflowId\":\"%s\",\"step\":%d}",
                    workflowId, step);
            Map<String, Object> stepHeaders = Map.of("partition_key", workflowId);
            StepVerifier.create(kafkaPublisher.publish(stepEvent, workflowEventsTopic, stepHeaders))
                    .verifyComplete();
        }

        // 3. Complete workflow - update final state and publish completion event
        Map<String, Object> finalState = new LinkedHashMap<>();
        finalState.put("workflowId", workflowId);
        finalState.put("status", "COMPLETED");
        finalState.put("currentStep", 3);
        finalState.put("endTime", System.currentTimeMillis());

        StepVerifier.create(cacheAdapter.put(workflowId, finalState))
                .verifyComplete();

        String completeEvent = String.format("{\"type\":\"WORKFLOW_COMPLETED\",\"workflowId\":\"%s\"}", workflowId);
        Map<String, Object> completeHeaders = Map.of("partition_key", workflowId);
        StepVerifier.create(kafkaPublisher.publish(completeEvent, workflowEventsTopic, completeHeaders))
                .verifyComplete();

        // 4. Verify final state in cache
        StepVerifier.create(cacheAdapter.get(workflowId))
                .assertNext(result -> {
                    assertThat(result).isPresent();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> state = (Map<String, Object>) result.get();
                    assertThat(state.get("status")).isEqualTo("COMPLETED");
                    assertThat(state.get("currentStep")).isEqualTo(3);
                })
                .verifyComplete();

        // 5. Verify all events were published
        boolean received = waitForMessages(5, 15); // 1 start + 3 steps + 1 complete
        assertThat(received).isTrue();
        assertThat(receivedMessages).hasSize(5);
        assertThat(receivedMessages.get(0)).contains("WORKFLOW_STARTED");
        assertThat(receivedMessages.get(4)).contains("WORKFLOW_COMPLETED");
    }

    @Test
    @DisplayName("Should handle workflow state recovery from cache")
    void shouldHandleWorkflowStateRecovery() {
        String workflowId = "wf-recovery-" + UUID.randomUUID();

        // Simulate a workflow that was interrupted
        Map<String, Object> interruptedState = new LinkedHashMap<>();
        interruptedState.put("workflowId", workflowId);
        interruptedState.put("status", "RUNNING");
        interruptedState.put("currentStep", 2);
        interruptedState.put("context", Map.of("orderId", "ORD-123", "retryCount", 1));

        // Store state in cache
        StepVerifier.create(cacheAdapter.put(workflowId, interruptedState))
                .verifyComplete();

        // Simulate recovery - read state from cache
        StepVerifier.create(cacheAdapter.get(workflowId))
                .assertNext(result -> {
                    assertThat(result).isPresent();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> state = (Map<String, Object>) result.get();
                    assertThat(state.get("status")).isEqualTo("RUNNING");
                    assertThat(state.get("currentStep")).isEqualTo(2);

                    @SuppressWarnings("unchecked")
                    Map<String, Object> context = (Map<String, Object>) state.get("context");
                    assertThat(context.get("orderId")).isEqualTo("ORD-123");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should use cache for workflow deduplication")
    void shouldUseCacheForDeduplication() {
        String idempotencyKey = "idempotency-" + UUID.randomUUID();

        // First request - should succeed
        StepVerifier.create(cacheAdapter.putIfAbsent(idempotencyKey, "processing"))
                .assertNext(success -> assertThat(success).isTrue())
                .verifyComplete();

        // Duplicate request - should fail (already exists)
        StepVerifier.create(cacheAdapter.putIfAbsent(idempotencyKey, "processing"))
                .assertNext(success -> assertThat(success).isFalse())
                .verifyComplete();
    }

    private boolean waitForMessages(int expectedCount, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (receivedMessages.size() < expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        return receivedMessages.size() >= expectedCount;
    }
}

