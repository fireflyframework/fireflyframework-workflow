/*
 * Copyright 2025 Firefly Software Solutions Inc
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

package com.firefly.common.workflow.testcontainers;

import com.firefly.common.eda.publisher.EventPublisher;
import com.firefly.common.eda.publisher.EventPublisherFactory;
import com.firefly.common.eda.publisher.PublisherType;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
 * Integration tests for Kafka EDA using lib-common-eda with Testcontainers.
 * <p>
 * These tests verify that the workflow engine can successfully use the
 * lib-common-eda library with a real Kafka instance running in a container.
 */
@Testcontainers
@SpringBootTest(classes = TestApplication.class)
@DisplayName("Kafka EDA Integration Tests (lib-common-eda)")
class KafkaEdaIntegrationTest {

    private static final String TEST_TOPIC = "workflow-events-test";

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("firefly.eda.enabled", () -> "true");
        registry.add("firefly.eda.publishers.enabled", () -> "true");
        registry.add("firefly.eda.publishers.kafka.default.enabled", () -> "true");
        registry.add("firefly.eda.publishers.kafka.default.bootstrap-servers", kafka::getBootstrapServers);
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
        testConsumer.subscribe(Collections.singletonList(TEST_TOPIC));

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

        StepVerifier.create(kafkaPublisher.publish(TEST_TOPIC, eventPayload))
                .verifyComplete();

        // Wait for message to be consumed
        boolean received = waitForMessages(1, 10);
        assertThat(received).isTrue();
        assertThat(receivedMessages).contains(eventPayload);
    }

    @Test
    @DisplayName("Should publish event with key to Kafka")
    void shouldPublishEventWithKey() throws InterruptedException {
        String key = "workflow-instance-456";
        String eventPayload = "{\"type\":\"STEP_COMPLETED\",\"instanceId\":\"wf-456\",\"stepId\":\"step-1\"}";

        StepVerifier.create(kafkaPublisher.publish(TEST_TOPIC, key, eventPayload))
                .verifyComplete();

        boolean received = waitForMessages(1, 10);
        assertThat(received).isTrue();
        assertThat(receivedMessages).contains(eventPayload);
    }

