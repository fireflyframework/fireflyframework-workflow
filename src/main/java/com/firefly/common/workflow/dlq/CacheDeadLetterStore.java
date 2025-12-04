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

package com.firefly.common.workflow.dlq;

import com.firefly.common.cache.core.CacheAdapter;
import com.firefly.common.workflow.properties.WorkflowProperties;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache-based implementation of DeadLetterStore.
 * <p>
 * Uses lib-common-cache for storage, with an in-memory index
 * for efficient querying by workflow/instance/step.
 */
@Slf4j
public class CacheDeadLetterStore implements DeadLetterStore {

    private static final String DLQ_KEY_PREFIX = "dlq:entry:";
    private static final String DLQ_INDEX_KEY = "dlq:index";

    private final CacheAdapter cacheAdapter;
    private final Duration ttl;

    // In-memory index for fast lookups (synchronized with cache)
    private final Set<String> entryIds = ConcurrentHashMap.newKeySet();

    public CacheDeadLetterStore(CacheAdapter cacheAdapter, WorkflowProperties properties) {
        this.cacheAdapter = cacheAdapter;
        this.ttl = properties.getDlq().getRetentionPeriod();
        log.info("CacheDeadLetterStore initialized with TTL: {}", ttl);
    }

    @Override
    public Mono<DeadLetterEntry> save(DeadLetterEntry entry) {
        String key = DLQ_KEY_PREFIX + entry.id();
        return cacheAdapter.put(key, entry, ttl)
                .doOnSuccess(v -> {
                    entryIds.add(entry.id());
                    log.debug("Saved DLQ entry: id={}, workflowId={}, stepId={}",
                            entry.id(), entry.workflowId(), entry.stepId());
                })
                .thenReturn(entry);
    }

    @Override
    public Mono<DeadLetterEntry> findById(String id) {
        String key = DLQ_KEY_PREFIX + id;
        return cacheAdapter.get(key, DeadLetterEntry.class)
                .flatMap(opt -> opt.map(Mono::just).orElse(Mono.empty()));
    }

    @Override
    public Flux<DeadLetterEntry> findAll() {
        return Flux.fromIterable(entryIds)
                .flatMap(this::findById)
                .filter(entry -> entry != null);
    }

    @Override
    public Flux<DeadLetterEntry> findByWorkflowId(String workflowId) {
        return findAll()
                .filter(entry -> workflowId.equals(entry.workflowId()));
    }

    @Override
    public Flux<DeadLetterEntry> findByInstanceId(String instanceId) {
        return findAll()
                .filter(entry -> instanceId.equals(entry.instanceId()));
    }

    @Override
    public Flux<DeadLetterEntry> findByStep(String workflowId, String stepId) {
        return findAll()
                .filter(entry -> workflowId.equals(entry.workflowId())
                        && stepId.equals(entry.stepId()));
    }

    @Override
    public Mono<Long> count() {
        return Mono.just((long) entryIds.size());
    }

    @Override
    public Mono<Long> countByWorkflowId(String workflowId) {
        return findByWorkflowId(workflowId).count();
    }

    @Override
    public Mono<Boolean> delete(String id) {
        String key = DLQ_KEY_PREFIX + id;
        return cacheAdapter.evict(key)
                .doOnSuccess(deleted -> {
                    if (deleted) {
                        entryIds.remove(id);
                        log.debug("Deleted DLQ entry: id={}", id);
                    }
                });
    }

    @Override
    public Mono<Long> deleteByWorkflowId(String workflowId) {
        return findByWorkflowId(workflowId)
                .flatMap(entry -> delete(entry.id()).thenReturn(1L))
                .reduce(0L, Long::sum);
    }

    @Override
    public Mono<Long> deleteAll() {
        return Flux.fromIterable(entryIds)
                .flatMap(id -> delete(id).thenReturn(1L))
                .reduce(0L, Long::sum)
                .doOnSuccess(count -> {
                    entryIds.clear();
                    log.info("Deleted {} DLQ entries", count);
                });
    }

    @Override
    public Mono<Boolean> isHealthy() {
        return cacheAdapter.get("health-check-dlq", String.class)
                .map(opt -> true)
                .onErrorReturn(false)
                .defaultIfEmpty(true);
    }
}
