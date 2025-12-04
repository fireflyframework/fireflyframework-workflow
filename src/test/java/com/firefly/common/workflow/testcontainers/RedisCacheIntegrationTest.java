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
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Redis caching using lib-common-cache with Testcontainers.
 * <p>
 * These tests verify that the workflow engine can successfully use the
 * lib-common-cache library with a real Redis instance running in a container.
 */
@Testcontainers
@SpringBootTest(classes = TestApplication.class)
@DisplayName("Redis Cache Integration Tests (lib-common-cache)")
class RedisCacheIntegrationTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("firefly.cache.enabled", () -> "true");
        registry.add("firefly.cache.redis.enabled", () -> "true");
        registry.add("firefly.cache.redis.host", redis::getHost);
        registry.add("firefly.cache.redis.port", () -> redis.getMappedPort(6379));
        registry.add("firefly.cache.default-type", () -> "redis");
    }

    @Autowired
    private FireflyCacheManager cacheManager;

    private CacheAdapter cacheAdapter;

    @BeforeEach
    void setUp() {
        cacheAdapter = cacheManager.getCache("test-cache");
        // Clear cache before each test
        cacheAdapter.clear().block();
    }

    @Test
    @DisplayName("Should connect to Redis container and verify cache is available")
    void shouldConnectToRedisContainer() {
        assertThat(cacheAdapter).isNotNull();
        assertThat(cacheAdapter.isAvailable()).isTrue();
        assertThat(cacheAdapter.getCacheType()).isEqualTo(CacheType.REDIS);
    }

    @Test
    @DisplayName("Should put and get values from Redis cache")
    void shouldPutAndGetValues() {
        String key = "test-key";
        String value = "test-value";

        // Put value
        StepVerifier.create(cacheAdapter.put(key, value))
                .verifyComplete();

        // Get value
        StepVerifier.create(cacheAdapter.get(key, String.class))
                .assertNext(result -> {
                    assertThat(result).isPresent();
                    assertThat(result.get()).isEqualTo(value);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should put and get complex objects from Redis cache")
    void shouldPutAndGetComplexObjects() {
        String key = "workflow-state";
        Map<String, Object> workflowState = Map.of(
                "instanceId", "wf-123",
                "status", "RUNNING",
                "currentStep", "step-2",
                "context", Map.of("orderId", "ORD-456", "amount", 100.0)
        );

        // Put value
        StepVerifier.create(cacheAdapter.put(key, workflowState))
                .verifyComplete();

        // Get value
        StepVerifier.create(cacheAdapter.get(key))
                .assertNext(result -> {
                    assertThat(result).isPresent();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> retrieved = (Map<String, Object>) result.get();
                    assertThat(retrieved.get("instanceId")).isEqualTo("wf-123");
                    assertThat(retrieved.get("status")).isEqualTo("RUNNING");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should support TTL-based expiration")
    void shouldSupportTtlExpiration() {
        String key = "expiring-key";
        String value = "expiring-value";

        // Put value with short TTL
        StepVerifier.create(cacheAdapter.put(key, value, Duration.ofSeconds(1)))
                .verifyComplete();

        // Verify value exists
        StepVerifier.create(cacheAdapter.exists(key))
                .assertNext(exists -> assertThat(exists).isTrue())
                .verifyComplete();

        // Wait for expiration
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify value expired
        StepVerifier.create(cacheAdapter.get(key))
                .assertNext(result -> assertThat(result).isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("Should support putIfAbsent operation")
    void shouldSupportPutIfAbsent() {
        String key = "unique-key";
        String value1 = "first-value";
        String value2 = "second-value";

        // First put should succeed
        StepVerifier.create(cacheAdapter.putIfAbsent(key, value1))
                .assertNext(success -> assertThat(success).isTrue())
                .verifyComplete();

        // Second put should fail (key exists)
        StepVerifier.create(cacheAdapter.putIfAbsent(key, value2))
                .assertNext(success -> assertThat(success).isFalse())
                .verifyComplete();

        // Value should be the first one
        StepVerifier.create(cacheAdapter.get(key, String.class))
                .assertNext(result -> {
                    assertThat(result).isPresent();
                    assertThat(result.get()).isEqualTo(value1);
                })
                .verifyComplete();
    }

