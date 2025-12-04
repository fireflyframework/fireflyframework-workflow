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

import com.firefly.common.cache.config.CacheAutoConfiguration;
import com.firefly.common.cache.config.RedisCacheAutoConfiguration;
import com.firefly.common.cache.core.CacheAdapter;
import com.firefly.common.cache.core.CacheType;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Redis caching using lib-common-cache with Testcontainers.
 * <p>
 * These tests verify that the workflow engine can successfully use the
 * lib-common-cache library with a real Redis instance running in a container.
 */
@Testcontainers
@SpringBootTest(classes = TestApplication.class)
@Import({CacheAutoConfiguration.class, RedisCacheAutoConfiguration.class, RedisCacheIntegrationTest.ResilienceTestConfig.class})
@ContextConfiguration(initializers = RedisCacheIntegrationTest.Initializer.class)
@DisplayName("Redis Cache Integration Tests (lib-common-cache)")
class RedisCacheIntegrationTest {

    /**
     * Test configuration that provides Resilience4j beans required by lib-common-eda.
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

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    /**
     * Initializer that sets Redis properties before Spring context loads.
     * This ensures the RedisCacheAutoConfiguration conditions are evaluated correctly.
     */
    static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            // Start container if not already started
            if (!redis.isRunning()) {
                redis.start();
            }

            Map<String, Object> props = new HashMap<>();
            props.put("firefly.cache.enabled", "true");
            props.put("firefly.cache.redis.enabled", "true");
            props.put("firefly.cache.redis.host", redis.getHost());
            props.put("firefly.cache.redis.port", redis.getMappedPort(6379));
            props.put("firefly.cache.default-cache-type", "REDIS");

            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("testcontainers", props));
        }
    }

    @Autowired
    private CacheAdapter cacheAdapter;

    @BeforeEach
    void setUp() {
        // Clear cache before each test
        cacheAdapter.clear().block();
    }

    @Test
    @DisplayName("Should connect to Redis container and verify cache is available")
    void shouldConnectToRedisContainer() {
        assertThat(cacheAdapter).isNotNull();
        assertThat(cacheAdapter.isAvailable()).isTrue();
        // Cache type can be REDIS or AUTO (when using SmartCacheAdapter with L1+L2)
        assertThat(cacheAdapter.getCacheType()).isIn(CacheType.REDIS, CacheType.AUTO);
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

    @Test
    @DisplayName("Should evict values from cache")
    void shouldEvictValues() {
        String key = "evict-key";
        String value = "evict-value";

        // Put value
        StepVerifier.create(cacheAdapter.put(key, value))
                .verifyComplete();

        // Verify exists
        StepVerifier.create(cacheAdapter.exists(key))
                .assertNext(exists -> assertThat(exists).isTrue())
                .verifyComplete();

        // Evict
        StepVerifier.create(cacheAdapter.evict(key))
                .assertNext(evicted -> assertThat(evicted).isTrue())
                .verifyComplete();

        // Verify gone
        StepVerifier.create(cacheAdapter.exists(key))
                .assertNext(exists -> assertThat(exists).isFalse())
                .verifyComplete();
    }

    @Test
    @DisplayName("Should get cache statistics")
    void shouldGetCacheStatistics() {
        // Put some values
        cacheAdapter.put("stat-key-1", "value-1").block();
        cacheAdapter.put("stat-key-2", "value-2").block();
        cacheAdapter.get("stat-key-1").block(); // hit
        cacheAdapter.get("stat-key-missing").block(); // miss

        StepVerifier.create(cacheAdapter.getStats())
                .assertNext(stats -> {
                    // Cache type can be REDIS or AUTO (when using SmartCacheAdapter)
                    assertThat(stats.getCacheType()).isIn(CacheType.REDIS, CacheType.AUTO);
                    assertThat(stats.getRequestCount()).isGreaterThanOrEqualTo(2);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should get cache health")
    void shouldGetCacheHealth() {
        StepVerifier.create(cacheAdapter.getHealth())
                .assertNext(health -> {
                    assertThat(health.isHealthy()).isTrue();
                    // Cache type can be REDIS or AUTO (when using SmartCacheAdapter)
                    assertThat(health.getCacheType()).isIn(CacheType.REDIS, CacheType.AUTO);
                    assertThat(health.isAvailable()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should clear all cache entries")
    void shouldClearCache() {
        // Put multiple values
        cacheAdapter.put("clear-key-1", "value-1").block();
        cacheAdapter.put("clear-key-2", "value-2").block();
        cacheAdapter.put("clear-key-3", "value-3").block();

        // Verify size
        StepVerifier.create(cacheAdapter.size())
                .assertNext(size -> assertThat(size).isGreaterThanOrEqualTo(3))
                .verifyComplete();

        // Clear
        StepVerifier.create(cacheAdapter.clear())
                .verifyComplete();

        // Verify empty
        StepVerifier.create(cacheAdapter.size())
                .assertNext(size -> assertThat(size).isEqualTo(0L))
                .verifyComplete();
    }
}

