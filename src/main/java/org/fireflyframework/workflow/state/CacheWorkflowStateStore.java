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

package org.fireflyframework.workflow.state;

import org.fireflyframework.cache.core.CacheAdapter;
import org.fireflyframework.workflow.model.WorkflowInstance;
import org.fireflyframework.workflow.model.WorkflowStatus;
import org.fireflyframework.workflow.properties.WorkflowProperties;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * Cache-based implementation of WorkflowStateStore.
 * <p>
 * Uses fireflyframework-cache for persisting workflow instances. Supports both
 * in-memory (Caffeine) and distributed (Redis) caching backends.
 * <p>
 * Key patterns:
 * <ul>
 *   <li>Instance: {@code workflow:instance:{instanceId}}</li>
 *   <li>Workflow index: {@code workflow:index:{workflowId}:{instanceId}}</li>
 *   <li>Status index: {@code workflow:status:{status}:{instanceId}}</li>
 *   <li>Correlation index: {@code workflow:correlation:{correlationId}:{instanceId}}</li>
 * </ul>
 */
@Slf4j
public class CacheWorkflowStateStore implements WorkflowStateStore {

    private static final String INSTANCE_KEY_PREFIX = "workflow:instance:";
    private static final String WORKFLOW_INDEX_PREFIX = "workflow:index:";
    private static final String STATUS_INDEX_PREFIX = "workflow:status:";
    private static final String CORRELATION_INDEX_PREFIX = "workflow:correlation:";

    private final CacheAdapter cacheAdapter;
    private final WorkflowProperties properties;
    private final Duration defaultTtl;

    public CacheWorkflowStateStore(CacheAdapter cacheAdapter, WorkflowProperties properties) {
        this.cacheAdapter = cacheAdapter;
        this.properties = properties;
        this.defaultTtl = properties.getState().getDefaultTtl();
        log.info("CacheWorkflowStateStore initialized with TTL: {}", defaultTtl);
    }

    @Override
    public Mono<WorkflowInstance> save(WorkflowInstance instance) {
        return save(instance, defaultTtl);
    }

    @Override
    public Mono<WorkflowInstance> save(WorkflowInstance instance, Duration ttl) {
        String instanceKey = instanceKey(instance.instanceId());

        return cacheAdapter.put(instanceKey, instance, ttl)
                .then(saveIndices(instance, ttl))
                .thenReturn(instance)
                .doOnSuccess(i -> log.debug("Saved workflow instance: {}", i.instanceId()))
                .doOnError(e -> log.error("Failed to save workflow instance: {}", instance.instanceId(), e));
    }

    private Mono<Void> saveIndices(WorkflowInstance instance, Duration ttl) {
        String workflowIndexKey = workflowIndexKey(instance.workflowId(), instance.instanceId());
        String statusIndexKey = statusIndexKey(instance.status(), instance.instanceId());

        Mono<Void> workflowIndex = cacheAdapter.put(workflowIndexKey, instance.instanceId(), ttl);
        Mono<Void> statusIndex = cacheAdapter.put(statusIndexKey, instance.instanceId(), ttl);

        Mono<Void> correlationIndex = Mono.empty();
        if (instance.correlationId() != null) {
            String correlationKey = correlationIndexKey(instance.correlationId(), instance.instanceId());
            correlationIndex = cacheAdapter.put(correlationKey, instance.instanceId(), ttl);
        }

        return Mono.when(workflowIndex, statusIndex, correlationIndex);
    }

    @Override
    public Mono<WorkflowInstance> findById(String instanceId) {
        return cacheAdapter.<String, WorkflowInstance>get(instanceKey(instanceId))
                .flatMap(opt -> opt.map(Mono::just).orElse(Mono.empty()));
    }

    @Override
    public Mono<WorkflowInstance> findByWorkflowAndInstanceId(String workflowId, String instanceId) {
        return findById(instanceId)
                .filter(instance -> instance.workflowId().equals(workflowId));
    }

    @Override
    public Flux<WorkflowInstance> findByWorkflowId(String workflowId) {
        String prefix = WORKFLOW_INDEX_PREFIX + workflowId + ":";
        return findInstancesByKeyPrefix(prefix);
    }

    @Override
    public Flux<WorkflowInstance> findByStatus(WorkflowStatus status) {
        String prefix = STATUS_INDEX_PREFIX + status.name() + ":";
        return findInstancesByKeyPrefix(prefix);
    }

    @Override
    public Flux<WorkflowInstance> findByWorkflowIdAndStatus(String workflowId, WorkflowStatus status) {
        return findByWorkflowId(workflowId)
                .filter(instance -> instance.status() == status);
    }

    @Override
    public Flux<WorkflowInstance> findByCorrelationId(String correlationId) {
        String prefix = CORRELATION_INDEX_PREFIX + correlationId + ":";
        return findInstancesByKeyPrefix(prefix);
    }

    private Flux<WorkflowInstance> findInstancesByKeyPrefix(String prefix) {
        return cacheAdapter.<String>keys()
                .flatMapMany(Flux::fromIterable)
                .filter(key -> key.startsWith(prefix))
                .flatMap(key -> cacheAdapter.<String, String>get(key))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .flatMap(this::findById);
    }

    @Override
    public Mono<Boolean> delete(String instanceId) {
        return findById(instanceId)
                .flatMap(instance -> {
                    String instanceKey = instanceKey(instanceId);
                    String workflowIndexKey = workflowIndexKey(instance.workflowId(), instanceId);
                    String statusIndexKey = statusIndexKey(instance.status(), instanceId);

                    Mono<Boolean> deleteInstance = cacheAdapter.evict(instanceKey);
                    Mono<Boolean> deleteWorkflowIndex = cacheAdapter.evict(workflowIndexKey);
                    Mono<Boolean> deleteStatusIndex = cacheAdapter.evict(statusIndexKey);

                    Mono<Boolean> deleteCorrelation = Mono.just(true);
                    if (instance.correlationId() != null) {
                        String correlationKey = correlationIndexKey(instance.correlationId(), instanceId);
                        deleteCorrelation = cacheAdapter.evict(correlationKey);
                    }

                    return Mono.when(deleteInstance, deleteWorkflowIndex, deleteStatusIndex, deleteCorrelation)
                            .thenReturn(true);
                })
                .defaultIfEmpty(false)
                .doOnSuccess(deleted -> {
                    if (deleted) {
                        log.debug("Deleted workflow instance: {}", instanceId);
                    }
                });
    }

    @Override
    public Mono<Long> deleteByWorkflowId(String workflowId) {
        return findByWorkflowId(workflowId)
                .flatMap(instance -> delete(instance.instanceId()))
                .filter(deleted -> deleted)
                .count();
    }

    @Override
    public Mono<Boolean> exists(String instanceId) {
        return cacheAdapter.exists(instanceKey(instanceId));
    }

    @Override
    public Mono<Long> countByWorkflowId(String workflowId) {
        return findByWorkflowId(workflowId).count();
    }

    @Override
    public Mono<Long> countByWorkflowIdAndStatus(String workflowId, WorkflowStatus status) {
        return findByWorkflowIdAndStatus(workflowId, status).count();
    }

    @Override
    public Flux<WorkflowInstance> findActiveInstances() {
        return Flux.concat(
                findByStatus(WorkflowStatus.PENDING),
                findByStatus(WorkflowStatus.RUNNING),
                findByStatus(WorkflowStatus.WAITING)
        );
    }

    @Override
    public Flux<WorkflowInstance> findStaleInstances(Duration maxAge) {
        Instant cutoff = Instant.now().minus(maxAge);
        return findActiveInstances()
                .filter(instance -> {
                    Instant startTime = instance.startedAt() != null ? instance.startedAt() : instance.createdAt();
                    return startTime != null && startTime.isBefore(cutoff);
                });
    }

    @Override
    public Mono<Boolean> updateStatus(String instanceId, WorkflowStatus expectedStatus, WorkflowStatus newStatus) {
        return findById(instanceId)
                .flatMap(instance -> {
                    if (instance.status() != expectedStatus) {
                        log.warn("Status mismatch for instance {}: expected {}, found {}",
                                instanceId, expectedStatus, instance.status());
                        return Mono.just(false);
                    }

                    // Remove old status index
                    String oldStatusKey = statusIndexKey(expectedStatus, instanceId);
                    
                    // Create updated instance
                    WorkflowInstance updated = new WorkflowInstance(
                            instance.instanceId(),
                            instance.workflowId(),
                            instance.workflowName(),
                            instance.workflowVersion(),
                            newStatus,
                            instance.currentStepId(),
                            instance.context(),
                            instance.input(),
                            instance.output(),
                            instance.stepExecutions(),
                            instance.errorMessage(),
                            instance.errorType(),
                            instance.correlationId(),
                            instance.triggeredBy(),
                            instance.createdAt(),
                            instance.startedAt(),
                            newStatus.isTerminal() ? Instant.now() : instance.completedAt()
                    );

                    return cacheAdapter.evict(oldStatusKey)
                            .then(save(updated))
                            .thenReturn(true);
                })
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<Boolean> isHealthy() {
        return Mono.fromSupplier(cacheAdapter::isAvailable);
    }

    private String instanceKey(String instanceId) {
        return INSTANCE_KEY_PREFIX + instanceId;
    }

    private String workflowIndexKey(String workflowId, String instanceId) {
        return WORKFLOW_INDEX_PREFIX + workflowId + ":" + instanceId;
    }

    private String statusIndexKey(WorkflowStatus status, String instanceId) {
        return STATUS_INDEX_PREFIX + status.name() + ":" + instanceId;
    }

    private String correlationIndexKey(String correlationId, String instanceId) {
        return CORRELATION_INDEX_PREFIX + correlationId + ":" + instanceId;
    }
}
