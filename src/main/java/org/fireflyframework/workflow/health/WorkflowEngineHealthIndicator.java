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

package org.fireflyframework.workflow.health;

import org.fireflyframework.workflow.core.WorkflowEngine;
import org.fireflyframework.workflow.state.WorkflowStateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Health indicator for the Workflow Engine.
 * <p>
 * Reports the health status based on:
 * <ul>
 *   <li>Number of registered workflows</li>
 *   <li>State store connectivity</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
public class WorkflowEngineHealthIndicator implements ReactiveHealthIndicator {

    private final WorkflowEngine workflowEngine;
    private final WorkflowStateStore stateStore;

    @Override
    public Mono<Health> health() {
        return checkStateStore()
                .map(storeHealthy -> {
                    int workflowCount = workflowEngine.getAllWorkflows().size();
                    
                    Health.Builder builder = storeHealthy ? Health.up() : Health.down();
                    
                    return builder
                            .withDetail("registeredWorkflows", workflowCount)
                            .withDetail("stateStore", storeHealthy ? "connected" : "disconnected")
                            .build();
                })
                .onErrorResume(e -> {
                    log.warn("Workflow engine health check failed", e);
                    return Mono.just(Health.down()
                            .withDetail("error", e.getMessage())
                            .build());
                });
    }

    private Mono<Boolean> checkStateStore() {
        // Simple health check: try to get a non-existent instance
        return stateStore.findById("__health_check__")
                .map(instance -> true)
                .switchIfEmpty(Mono.just(true))
                .timeout(Duration.ofSeconds(5))
                .onErrorReturn(false);
    }
}
