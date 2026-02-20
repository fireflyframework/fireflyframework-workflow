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

import org.fireflyframework.eventsourcing.config.EventSourcingAutoConfiguration;
import org.fireflyframework.eventsourcing.config.EventSourcingHealthAutoConfiguration;
import org.fireflyframework.eventsourcing.config.EventSourcingMetricsAutoConfiguration;
import org.fireflyframework.eventsourcing.config.EventSourcingProjectionAutoConfiguration;
import org.fireflyframework.eventsourcing.config.SnapshotAutoConfiguration;
import org.fireflyframework.eventsourcing.multitenancy.MultiTenancyAutoConfiguration;
import org.fireflyframework.eventsourcing.resilience.CircuitBreakerAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;

/**
 * Test application for durable execution E2E tests.
 * <p>
 * This class lives in a separate sub-package to avoid component scan
 * conflicts with {@code TestApplication}, which excludes ALL eventsourcing
 * auto-configs. If both were in the same scan scope, Spring Boot would
 * merge the exclusion lists and prevent eventsourcing from loading.
 * <p>
 * We allow these eventsourcing auto-configs to load (needed for real PostgreSQL EventStore):
 * <ul>
 *   <li>{@code R2dbcBeansAutoConfiguration} — creates DatabaseClient, R2dbcEntityTemplate</li>
 *   <li>{@code EventStoreAutoConfiguration} — creates R2dbcEventStore</li>
 *   <li>{@code SnapshotAutoConfiguration} — creates SnapshotStore</li>
 * </ul>
 * <p>
 * We exclude these eventsourcing auto-configs (they require infrastructure we don't need):
 * <ul>
 *   <li>{@code EventSourcingAutoConfiguration} — creates outbox/transactional beans with undeclared deps</li>
 *   <li>{@code EventSourcingProjectionAutoConfiguration} — creates projection processor, outbox repository</li>
 *   <li>{@code EventSourcingHealthAutoConfiguration} — health indicators</li>
 *   <li>{@code EventSourcingMetricsAutoConfiguration} — metrics collectors</li>
 *   <li>{@code CircuitBreakerAutoConfiguration} — circuit breaker for event store</li>
 *   <li>{@code MultiTenancyAutoConfiguration} — multi-tenancy support</li>
 * </ul>
 */
@SpringBootApplication(
        scanBasePackages = "org.fireflyframework.workflow.testcontainers.durableexecution",
        exclude = {
                // Spring Boot auto-configs that conflict with fireflyframework modules
                RedisAutoConfiguration.class,
                RedisReactiveAutoConfiguration.class,
                KafkaAutoConfiguration.class,
                // Eventsourcing auto-configs with problematic unconditional bean dependencies
                EventSourcingAutoConfiguration.class,
                EventSourcingProjectionAutoConfiguration.class,
                SnapshotAutoConfiguration.class,
                EventSourcingHealthAutoConfiguration.class,
                EventSourcingMetricsAutoConfiguration.class,
                CircuitBreakerAutoConfiguration.class,
                MultiTenancyAutoConfiguration.class
        },
        excludeName = {
                "org.fireflyframework.core.config.SwaggerAutoConfiguration"
        }
)
public class DurableExecutionTestApplication {
}
