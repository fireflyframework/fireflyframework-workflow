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

package org.fireflyframework.workflow.recovery;

import lombok.extern.slf4j.Slf4j;
import org.fireflyframework.workflow.core.WorkflowEngine;
import org.fireflyframework.workflow.model.WorkflowInstance;
import org.fireflyframework.workflow.model.WorkflowStatus;
import org.fireflyframework.workflow.state.WorkflowStateStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Recovers stale workflow instances after application restart.
 *
 * <p>On {@link ApplicationReadyEvent}, scans the state store for workflows in
 * RUNNING or WAITING state that haven't been updated within the stale threshold.
 * RUNNING instances are resumed from their last completed step. SUSPENDED instances
 * are left as-is (user explicitly suspended).</p>
 *
 * <p>Configuration:
 * <pre>
 * firefly.workflow.recovery.enabled=true
 * firefly.workflow.recovery.stale-threshold=PT5M
 * </pre>
 */
@Slf4j
public class WorkflowRecoveryService {

    private final WorkflowStateStore stateStore;
    private final WorkflowEngine workflowEngine;
    private final Duration staleThreshold;
    private final boolean enabled;

    public WorkflowRecoveryService(WorkflowStateStore stateStore,
                                    WorkflowEngine workflowEngine,
                                    boolean enabled,
                                    Duration staleThreshold) {
        this.stateStore = stateStore;
        this.workflowEngine = workflowEngine;
        this.enabled = enabled;
        this.staleThreshold = staleThreshold;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        if (!enabled) {
            log.info("Workflow recovery is disabled");
            return;
        }

        log.info("Starting workflow recovery scan (staleThreshold={})", staleThreshold);

        AtomicLong recovered = new AtomicLong();
        AtomicLong failed = new AtomicLong();

        stateStore.findStaleInstances(staleThreshold)
                .filter(instance -> instance.status() == WorkflowStatus.RUNNING)
                .flatMap(instance -> attemptRecovery(instance)
                        .doOnSuccess(v -> recovered.incrementAndGet())
                        .doOnError(e -> {
                            failed.incrementAndGet();
                            log.error("Failed to recover workflow instance {}: {}",
                                    instance.instanceId(), e.getMessage());
                        })
                        .onErrorResume(e -> reactor.core.publisher.Mono.empty()))
                .doOnComplete(() -> log.info("Workflow recovery complete: recovered={}, failed={}",
                        recovered.get(), failed.get()))
                .subscribe();
    }

    private reactor.core.publisher.Mono<WorkflowInstance> attemptRecovery(WorkflowInstance instance) {
        log.info("Recovering stale workflow: instanceId={}, workflowId={}, currentStep={}, startedAt={}",
                instance.instanceId(), instance.workflowId(),
                instance.currentStepId(), instance.startedAt());

        return workflowEngine.resumeWorkflow(instance.workflowId(), instance.instanceId())
                .doOnSuccess(resumed -> log.info("Successfully recovered workflow instance {}",
                        resumed.instanceId()));
    }
}
