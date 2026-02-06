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

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;

/**
 * Minimal test application for Testcontainers integration tests.
 * <p>
 * This is a minimal Spring Boot application that excludes auto-configurations
 * that conflict with fireflyframework-cache and fireflyframework-eda.
 * <p>
 * Individual test classes should use @Import to bring in only the
 * auto-configurations they need.
 */
@SpringBootApplication(
        scanBasePackages = "org.fireflyframework.workflow.testcontainers",
        exclude = {
                RedisAutoConfiguration.class,
                RedisReactiveAutoConfiguration.class,
                KafkaAutoConfiguration.class
        }
)
public class TestApplication {
}

