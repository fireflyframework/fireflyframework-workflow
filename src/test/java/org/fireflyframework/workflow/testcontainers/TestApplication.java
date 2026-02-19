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

import org.fireflyframework.eventsourcing.config.EventSourcingAutoConfiguration;
import org.fireflyframework.eventsourcing.config.EventSourcingHealthAutoConfiguration;
import org.fireflyframework.eventsourcing.config.EventSourcingMetricsAutoConfiguration;
import org.fireflyframework.eventsourcing.config.EventSourcingProjectionAutoConfiguration;
import org.fireflyframework.eventsourcing.config.EventStoreAutoConfiguration;
import org.fireflyframework.eventsourcing.config.R2dbcBeansAutoConfiguration;
import org.fireflyframework.eventsourcing.config.SnapshotAutoConfiguration;
import org.fireflyframework.eventsourcing.multitenancy.MultiTenancyAutoConfiguration;
import org.fireflyframework.eventsourcing.resilience.CircuitBreakerAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;

/**
 * Minimal test application for Testcontainers integration tests.
 * <p>
 * This is a minimal Spring Boot application that excludes auto-configurations
 * that conflict with fireflyframework-cache and fireflyframework-eda.
 * <p>
 * The fireflyframework-eventsourcing auto-configurations are excluded because
 * these integration tests don't provide R2DBC infrastructure required by the
 * event sourcing module.
 * <p>
 * Individual test classes should use @Import to bring in only the
 * auto-configurations they need.
 */
@SpringBootApplication(
        scanBasePackages = "org.fireflyframework.workflow.testcontainers",
        exclude = {
                // Spring Boot auto-configs not needed in these tests
                RedisAutoConfiguration.class,
                RedisReactiveAutoConfiguration.class,
                KafkaAutoConfiguration.class,
                FlywayAutoConfiguration.class,
                R2dbcAutoConfiguration.class,
                // fireflyframework-eventsourcing auto-configs (require R2DBC infrastructure)
                R2dbcBeansAutoConfiguration.class,
                EventStoreAutoConfiguration.class,
                SnapshotAutoConfiguration.class,
                EventSourcingAutoConfiguration.class,
                EventSourcingProjectionAutoConfiguration.class,
                EventSourcingHealthAutoConfiguration.class,
                EventSourcingMetricsAutoConfiguration.class,
                CircuitBreakerAutoConfiguration.class,
                MultiTenancyAutoConfiguration.class
        },
        excludeName = {
                // fireflyframework-r2dbc auto-configs (require R2dbcEntityTemplate)
                "org.fireflyframework.core.config.R2dbcAutoConfiguration",
                "org.fireflyframework.core.config.R2dbcTransactionAutoConfiguration",
                "org.fireflyframework.core.config.SwaggerAutoConfiguration"
        }
)
public class TestApplication {
}

