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
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Kafka EDA using fireflyframework-eda with Testcontainers.
 * <p>
 * These tests verify that the workflow engine can successfully use the
 * fireflyframework-eda library with a real Kafka instance running in a container.
 */
@Testcontainers
@SpringBootTest(classes = TestApplication.class)
@Import({FireflyEdaAutoConfiguration.class, FireflyEdaKafkaPublisherAutoConfiguration.class, KafkaEdaIntegrationTest.ResilienceTestConfig.class})
@ContextConfiguration(initializers = KafkaEdaIntegrationTest.Initializer.class)
@DisplayName("Kafka EDA Integration Tests (fireflyframework-eda)")
class KafkaEdaIntegrationTest {

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

    private static final String TEST_TOPIC_PREFIX = "workflow-events-test-";
    private String testTopic;

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    /**
     * Initializer that sets Kafka properties before Spring context loads.
     * This ensures the FireflyEdaKafkaPublisherAutoConfiguration conditions are evaluated correctly.
     */
    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            // Start container if not already started
            if (!kafka.isRunning()) {
                kafka.start();
            }

            Map<String, Object> props = new HashMap<>();
            props.put("firefly.eda.enabled", "true");
            props.put("firefly.eda.publishers.enabled", "true");
            props.put("firefly.eda.publishers.kafka.default.enabled", "true");
            props.put("firefly.eda.publishers.kafka.default.bootstrap-servers", kafka.getBootstrapServers());

            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("testcontainers", props));
        }
    }

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
        receivedMessages.clear();
        // Use unique topic per test to avoid message pollution between tests
        testTopic = TEST_TOPIC_PREFIX + UUID.randomUUID().toString().substring(0, 8);
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
        testConsumer.subscribe(Collections.singletonList(testTopic));

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
    @DisplayName("Should get Kafka publisher from EventPublisherFactory")
    void shouldGetKafkaPublisher() {
        assertThat(kafkaPublisher).isNotNull();
        assertThat(kafkaPublisher.getPublisherType()).isEqualTo(PublisherType.KAFKA);
    }

    @Test
    @DisplayName("Should publish simple event to Kafka")
    void shouldPublishSimpleEvent() throws InterruptedException {
        String eventPayload = "{\"type\":\"WORKFLOW_STARTED\",\"instanceId\":\"wf-123\"}";

        // EventPublisher.publish(event, destination) - event first, then destination
        StepVerifier.create(kafkaPublisher.publish(eventPayload, testTopic))
                .verifyComplete();

        // Wait for message to be consumed
        boolean received = waitForMessages(1, 10);
        assertThat(received).isTrue();
        assertThat(receivedMessages).anyMatch(msg -> msg.contains("WORKFLOW_STARTED"));
    }

    @Test
    @DisplayName("Should publish event with partition key to Kafka")
    void shouldPublishEventWithPartitionKey() throws InterruptedException {
        String eventPayload = "{\"type\":\"STEP_COMPLETED\",\"instanceId\":\"wf-456\",\"stepId\":\"step-1\"}";
        Map<String, Object> headers = Map.of("partition_key", "workflow-instance-456");

        StepVerifier.create(kafkaPublisher.publish(eventPayload, testTopic, headers))
                .verifyComplete();

        boolean received = waitForMessages(1, 10);
        assertThat(received).isTrue();
        assertThat(receivedMessages).anyMatch(msg -> msg.contains("STEP_COMPLETED"));
    }

    @Test
    @DisplayName("Should publish multiple events to Kafka")
    void shouldPublishMultipleEvents() throws InterruptedException {
        List<String> events = List.of(
                "{\"type\":\"WORKFLOW_STARTED\",\"instanceId\":\"wf-789\"}",
                "{\"type\":\"STEP_STARTED\",\"instanceId\":\"wf-789\",\"stepId\":\"step-1\"}",
                "{\"type\":\"STEP_COMPLETED\",\"instanceId\":\"wf-789\",\"stepId\":\"step-1\"}",
                "{\"type\":\"WORKFLOW_COMPLETED\",\"instanceId\":\"wf-789\"}"
        );

        for (String event : events) {
            StepVerifier.create(kafkaPublisher.publish(event, testTopic))
                    .verifyComplete();
        }

        boolean received = waitForMessages(4, 15);
        assertThat(received).isTrue();
        assertThat(receivedMessages).hasSize(4);
    }

    @Test
    @DisplayName("Should publish workflow state change events")
    void shouldPublishWorkflowStateChangeEvents() throws InterruptedException {
        Map<String, Object> workflowEvent = new LinkedHashMap<>();
        workflowEvent.put("eventType", "WORKFLOW_STATE_CHANGED");
        workflowEvent.put("instanceId", "wf-state-test");
        workflowEvent.put("previousState", "RUNNING");
        workflowEvent.put("newState", "COMPLETED");
        workflowEvent.put("timestamp", System.currentTimeMillis());

        String eventJson = toJson(workflowEvent);
        Map<String, Object> headers = Map.of("partition_key", "wf-state-test");

        StepVerifier.create(kafkaPublisher.publish(eventJson, testTopic, headers))
                .verifyComplete();

        boolean received = waitForMessages(1, 10);
        assertThat(received).isTrue();
        assertThat(receivedMessages.get(0)).contains("WORKFLOW_STATE_CHANGED");
        assertThat(receivedMessages.get(0)).contains("wf-state-test");
    }

    @Test
    @DisplayName("Should get auto-selected publisher (should be Kafka when available)")
    void shouldGetAutoSelectedPublisher() {
        EventPublisher autoPublisher = eventPublisherFactory.getPublisher(PublisherType.AUTO, "default");
        assertThat(autoPublisher).isNotNull();
        // When Kafka is configured, AUTO should select Kafka
        assertThat(autoPublisher.getPublisherType()).isEqualTo(PublisherType.KAFKA);
    }

    @Test
    @DisplayName("Should handle concurrent event publishing")
    void shouldHandleConcurrentPublishing() throws InterruptedException {
        int eventCount = 20;
        CountDownLatch latch = new CountDownLatch(eventCount);
        List<String> publishedEvents = new CopyOnWriteArrayList<>();

        for (int i = 0; i < eventCount; i++) {
            final int index = i;
            String event = "{\"type\":\"CONCURRENT_EVENT\",\"index\":" + index + "}";
            publishedEvents.add(event);

            kafkaPublisher.publish(event, testTopic)
                    .doFinally(signal -> latch.countDown())
                    .subscribe();
        }

        boolean allPublished = latch.await(30, TimeUnit.SECONDS);
        assertThat(allPublished).isTrue();

        boolean received = waitForMessages(eventCount, 30);
        assertThat(received).isTrue();
        assertThat(receivedMessages).hasSize(eventCount);
    }

    private boolean waitForMessages(int expectedCount, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
        while (receivedMessages.size() < expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        return receivedMessages.size() >= expectedCount;
    }

    private String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(entry.getKey()).append("\":");
            Object value = entry.getValue();
            if (value instanceof String) {
                sb.append("\"").append(value).append("\"");
            } else {
                sb.append(value);
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}
