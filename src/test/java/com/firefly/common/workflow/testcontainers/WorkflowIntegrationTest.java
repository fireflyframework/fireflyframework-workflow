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

import com.firefly.common.cache.core.CacheAdapter;
import com.firefly.common.cache.core.CacheType;
import com.firefly.common.cache.manager.FireflyCacheManager;
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
 * Combined integration tests for workflow engine using both lib-common-eda and lib-common-cache.
 * <p>
 * These tests verify that the workflow engine can successfully use both libraries together
 * with real Kafka and Redis instances running in containers.
 */
@Testcontainers
@SpringBootTest(classes = TestApplication.class)
@DisplayName("Workflow Engine Integration Tests (Kafka + Redis)")
class WorkflowIntegrationTest {

    private static final String WORKFLOW_EVENTS_TOPIC = "workflow-events";
    private static final String WORKFLOW_CACHE_NAME = "workflow-state";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Redis configuration for lib-common-cache
        registry.add("firefly.cache.enabled", () -> "true");
        registry.add("firefly.cache.redis.enabled", () -> "true");
        registry.add("firefly.cache.redis.host", redis::getHost);
        registry.add("firefly.cache.redis.port", () -> redis.getMappedPort(6379));
        registry.add("firefly.cache.default-type", () -> "redis");

        // Kafka configuration for lib-common-eda
        registry.add("firefly.eda.enabled", () -> "true");
        registry.add("firefly.eda.publishers.enabled", () -> "true");
        registry.add("firefly.eda.publishers.kafka.default.enabled", () -> "true");
        registry.add("firefly.eda.publishers.kafka.default.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private FireflyCacheManager cacheManager;

    @Autowired
    private EventPublisherFactory eventPublisherFactory;

    private CacheAdapter cacheAdapter;
    private EventPublisher kafkaPublisher;
    private KafkaConsumer<String, String> testConsumer;
    private final List<String> receivedMessages = new CopyOnWriteArrayList<>();
    private final AtomicBoolean consumerRunning = new AtomicBoolean(false);
    private Thread consumerThread;

    @BeforeEach
    void setUp() {
        cacheAdapter = cacheManager.getCache(WORKFLOW_CACHE_NAME);
        kafkaPublisher = eventPublisherFactory.getPublisher(PublisherType.KAFKA, "default");
        cacheAdapter.clear().block();
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
        testConsumer.subscribe(Collections.singletonList(WORKFLOW_EVENTS_TOPIC));

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
        assertThat(cacheAdapter.getCacheType()).isEqualTo(CacheType.REDIS);

        assertThat(kafkaPublisher).isNotNull();
        assertThat(kafkaPublisher.getPublisherType()).isEqualTo(PublisherType.KAFKA);
    }

