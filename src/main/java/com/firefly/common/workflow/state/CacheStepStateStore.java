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

package com.firefly.common.workflow.state;

import com.firefly.common.cache.core.CacheAdapter;
import com.firefly.common.workflow.model.StepState;
import com.firefly.common.workflow.model.StepStatus;
import com.firefly.common.workflow.model.WorkflowState;
import com.firefly.common.workflow.model.WorkflowStatus;
import com.firefly.common.workflow.properties.WorkflowProperties;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;

/**
 * Cache-based implementation of StepStateStore.
 * <p>
 * Uses lib-common-cache for persisting step and workflow states independently.
 * <p>
 * Key patterns:
 * <ul>
 *   <li>Step state: {@code workflow:step:{workflowId}:{instanceId}:{stepId}}</li>
 *   <li>Workflow state: {@code workflow:state:{workflowId}:{instanceId}}</li>
 *   <li>Waiting index: {@code workflow:waiting:{eventType}:{workflowId}:{instanceId}:{stepId}}</li>
 *   <li>Status index: {@code workflow:step-status:{status}:{workflowId}:{instanceId}:{stepId}}</li>
 * </ul>
 */
@Slf4j
public class CacheStepStateStore implements StepStateStore {

    private static final String STEP_KEY_PREFIX = "workflow:step:";
    private static final String STATE_KEY_PREFIX = "workflow:state:";
    private static final String WAITING_INDEX_PREFIX = "workflow:waiting:";
    private static final String STEP_STATUS_INDEX_PREFIX = "workflow:step-status:";
    private static final String WORKFLOW_STATUS_INDEX_PREFIX = "workflow:wf-status:";

    private final CacheAdapter cacheAdapter;
    private final WorkflowProperties properties;
    private final Duration defaultTtl;

    public CacheStepStateStore(CacheAdapter cacheAdapter, WorkflowProperties properties) {
        this.cacheAdapter = cacheAdapter;
        this.properties = properties;
        this.defaultTtl = properties.getState().getDefaultTtl();
        log.info("CacheStepStateStore initialized with TTL: {}", defaultTtl);
    }

    // ==================== Step State Operations ====================

    @Override
    public Mono<StepState> saveStepState(StepState state) {
        return saveStepState(state, defaultTtl);
    }

    @Override
    public Mono<StepState> saveStepState(StepState state, Duration ttl) {
        String stepKey = stepKey(state.workflowId(), state.instanceId(), state.stepId());
        
        return cacheAdapter.put(stepKey, state, ttl)
                .then(saveStepIndices(state, ttl))
                .thenReturn(state)
                .doOnSuccess(s -> log.debug("Saved step state: {}:{}", s.instanceId(), s.stepId()))
                .doOnError(e -> log.error("Failed to save step state: {}:{}", 
                        state.instanceId(), state.stepId(), e));
    }

    private Mono<Void> saveStepIndices(StepState state, Duration ttl) {
        // Status index
        String statusKey = stepStatusKey(state.status(), state.workflowId(), 
                state.instanceId(), state.stepId());
        Mono<Void> statusIndex = cacheAdapter.put(statusKey, state.stepId(), ttl);

        // Waiting event index (if waiting for event)
        Mono<Void> waitingIndex = Mono.empty();
        if (state.status() == StepStatus.WAITING && state.waitingForEvent() != null) {
            String waitingKey = waitingKey(state.waitingForEvent(), state.workflowId(), 
                    state.instanceId(), state.stepId());
            waitingIndex = cacheAdapter.put(waitingKey, state.stepId(), ttl);
        }

        return Mono.when(statusIndex, waitingIndex);
    }

    @Override
    public Mono<StepState> getStepState(String workflowId, String instanceId, String stepId) {
        return cacheAdapter.<String, StepState>get(stepKey(workflowId, instanceId, stepId))
                .flatMap(opt -> opt.map(Mono::just).orElse(Mono.empty()));
    }

    @Override
    public Flux<StepState> getStepStates(String workflowId, String instanceId) {
        String prefix = STEP_KEY_PREFIX + workflowId + ":" + instanceId + ":";
        return findByKeyPrefix(prefix, StepState.class);
    }

    @Override
    public Flux<StepState> findStepsWaitingForEvent(String eventType) {
        String prefix = WAITING_INDEX_PREFIX + eventType + ":";
        return cacheAdapter.<String>keys()
                .flatMapMany(Flux::fromIterable)
                .filter(key -> key.startsWith(prefix))
                .flatMap(key -> {
                    // Parse workflowId:instanceId:stepId from the key
                    String[] parts = key.substring(prefix.length()).split(":");
                    if (parts.length >= 3) {
                        return getStepState(parts[0], parts[1], parts[2]);
                    }
                    return Mono.empty();
                });
    }

    @Override
    public Flux<StepState> findStepsByStatus(StepStatus status) {
        String prefix = STEP_STATUS_INDEX_PREFIX + status.name() + ":";
        return cacheAdapter.<String>keys()
                .flatMapMany(Flux::fromIterable)
                .filter(key -> key.startsWith(prefix))
                .flatMap(key -> {
                    String[] parts = key.substring(prefix.length()).split(":");
                    if (parts.length >= 3) {
                        return getStepState(parts[0], parts[1], parts[2]);
                    }
                    return Mono.empty();
                });
    }

    @Override
    public Flux<StepState> findStepsByStatus(String workflowId, String instanceId, StepStatus status) {
        return getStepStates(workflowId, instanceId)
                .filter(state -> state.status() == status);
    }

    @Override
    public Mono<Boolean> deleteStepState(String workflowId, String instanceId, String stepId) {
        return getStepState(workflowId, instanceId, stepId)
                .flatMap(state -> {
                    String stepKey = stepKey(workflowId, instanceId, stepId);
                    String statusKey = stepStatusKey(state.status(), workflowId, instanceId, stepId);
                    
                    Mono<Boolean> deleteStep = cacheAdapter.evict(stepKey);
                    Mono<Boolean> deleteStatus = cacheAdapter.evict(statusKey);
                    
                    Mono<Boolean> deleteWaiting = Mono.just(true);
                    if (state.waitingForEvent() != null) {
                        String waitingKey = waitingKey(state.waitingForEvent(), 
                                workflowId, instanceId, stepId);
                        deleteWaiting = cacheAdapter.evict(waitingKey);
                    }
                    
                    return Mono.when(deleteStep, deleteStatus, deleteWaiting)
                            .thenReturn(true);
                })
                .defaultIfEmpty(false);
    }

    @Override
    public Mono<Long> deleteStepStates(String workflowId, String instanceId) {
        return getStepStates(workflowId, instanceId)
                .flatMap(state -> deleteStepState(workflowId, instanceId, state.stepId()))
                .filter(deleted -> deleted)
                .count();
    }

    // ==================== Workflow State Operations ====================

    @Override
    public Mono<WorkflowState> saveWorkflowState(WorkflowState state) {
        return saveWorkflowState(state, defaultTtl);
    }

    @Override
    public Mono<WorkflowState> saveWorkflowState(WorkflowState state, Duration ttl) {
        String stateKey = stateKey(state.workflowId(), state.instanceId());
        
        return cacheAdapter.put(stateKey, state, ttl)
                .then(saveWorkflowIndices(state, ttl))
                .thenReturn(state)
                .doOnSuccess(s -> log.debug("Saved workflow state: {} status={}", 
                        s.instanceId(), s.status()))
                .doOnError(e -> log.error("Failed to save workflow state: {}", 
                        state.instanceId(), e));
    }

    private Mono<Void> saveWorkflowIndices(WorkflowState state, Duration ttl) {
        // Status index
        String statusKey = workflowStatusKey(state.status(), state.workflowId(), state.instanceId());
        Mono<Void> statusIndex = cacheAdapter.put(statusKey, state.instanceId(), ttl);

        // Waiting event index
        Mono<Void> waitingIndex = Mono.empty();
        if (state.status() == WorkflowStatus.WAITING && state.waitingForEvent() != null) {
            String waitingKey = WAITING_INDEX_PREFIX + "wf:" + state.waitingForEvent() + 
                    ":" + state.workflowId() + ":" + state.instanceId();
            waitingIndex = cacheAdapter.put(waitingKey, state.instanceId(), ttl);
        }

        return Mono.when(statusIndex, waitingIndex);
    }

    @Override
    public Mono<WorkflowState> getWorkflowState(String workflowId, String instanceId) {
        return cacheAdapter.<String, WorkflowState>get(stateKey(workflowId, instanceId))
                .flatMap(opt -> opt.map(Mono::just).orElse(Mono.empty()));
    }

    @Override
    public Flux<WorkflowState> findWorkflowStatesByStatus(WorkflowStatus status) {
        String prefix = WORKFLOW_STATUS_INDEX_PREFIX + status.name() + ":";
        return cacheAdapter.<String>keys()
                .flatMapMany(Flux::fromIterable)
                .filter(key -> key.startsWith(prefix))
                .flatMap(key -> {
                    String[] parts = key.substring(prefix.length()).split(":");
                    if (parts.length >= 2) {
                        return getWorkflowState(parts[0], parts[1]);
                    }
                    return Mono.empty();
                });
    }

    @Override
    public Flux<WorkflowState> findWorkflowStatesWaitingForEvent(String eventType) {
        String prefix = WAITING_INDEX_PREFIX + "wf:" + eventType + ":";
        return cacheAdapter.<String>keys()
                .flatMapMany(Flux::fromIterable)
                .filter(key -> key.startsWith(prefix))
                .flatMap(key -> {
                    String[] parts = key.substring(prefix.length()).split(":");
                    if (parts.length >= 2) {
                        return getWorkflowState(parts[0], parts[1]);
                    }
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Boolean> isHealthy() {
        return Mono.fromSupplier(cacheAdapter::isAvailable);
    }

    // ==================== Helper Methods ====================

    private <T> Flux<T> findByKeyPrefix(String prefix, Class<T> type) {
        return cacheAdapter.<String>keys()
                .flatMapMany(Flux::fromIterable)
                .filter(key -> key.startsWith(prefix))
                .flatMap(key -> cacheAdapter.<String, T>get(key))
                .filter(Optional::isPresent)
                .map(Optional::get);
    }

    private String stepKey(String workflowId, String instanceId, String stepId) {
        return STEP_KEY_PREFIX + workflowId + ":" + instanceId + ":" + stepId;
    }

    private String stateKey(String workflowId, String instanceId) {
        return STATE_KEY_PREFIX + workflowId + ":" + instanceId;
    }

    private String stepStatusKey(StepStatus status, String workflowId, String instanceId, String stepId) {
        return STEP_STATUS_INDEX_PREFIX + status.name() + ":" + workflowId + ":" + instanceId + ":" + stepId;
    }

    private String workflowStatusKey(WorkflowStatus status, String workflowId, String instanceId) {
        return WORKFLOW_STATUS_INDEX_PREFIX + status.name() + ":" + workflowId + ":" + instanceId;
    }

    private String waitingKey(String eventType, String workflowId, String instanceId, String stepId) {
        return WAITING_INDEX_PREFIX + eventType + ":" + workflowId + ":" + instanceId + ":" + stepId;
    }
}
