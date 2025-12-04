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

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers configuration for integration tests.
 * <p>
 * Provides Redis and Kafka containers for testing the workflow engine
 * with real infrastructure dependencies.
 */
public class TestcontainersConfig {

    private static final String REDIS_IMAGE = "redis:7-alpine";
    private static final String KAFKA_IMAGE = "confluentinc/cp-kafka:7.5.0";

    private static GenericContainer<?> redisContainer;
    private static KafkaContainer kafkaContainer;

    /**
     * Gets or creates a shared Redis container.
     */
    public static synchronized GenericContainer<?> getRedisContainer() {
        if (redisContainer == null) {
            redisContainer = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
                    .withExposedPorts(6379)
                    .withReuse(true);
            redisContainer.start();
        }
        return redisContainer;
    }

    /**
     * Gets or creates a shared Kafka container.
     */
    public static synchronized KafkaContainer getKafkaContainer() {
        if (kafkaContainer == null) {
            kafkaContainer = new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE))
                    .withReuse(true);
            kafkaContainer.start();
        }
        return kafkaContainer;
    }

    /**
     * Gets the Redis connection URL.
     */
    public static String getRedisUrl() {
        GenericContainer<?> container = getRedisContainer();
        return String.format("redis://%s:%d", container.getHost(), container.getMappedPort(6379));
    }

    /**
     * Gets the Redis host.
     */
    public static String getRedisHost() {
        return getRedisContainer().getHost();
    }

    /**
     * Gets the Redis port.
     */
    public static int getRedisPort() {
        return getRedisContainer().getMappedPort(6379);
    }

    /**
     * Gets the Kafka bootstrap servers.
     */
    public static String getKafkaBootstrapServers() {
        return getKafkaContainer().getBootstrapServers();
    }

    /**
     * Stops all containers.
     */
    public static synchronized void stopAll() {
        if (redisContainer != null && redisContainer.isRunning()) {
            redisContainer.stop();
            redisContainer = null;
        }
        if (kafkaContainer != null && kafkaContainer.isRunning()) {
            kafkaContainer.stop();
            kafkaContainer = null;
        }
    }
}

