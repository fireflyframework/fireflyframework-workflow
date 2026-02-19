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

package org.fireflyframework.workflow.continueasnew;

import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.eventsourcing.store.EventSourcedWorkflowStateStore;
import org.fireflyframework.workflow.exception.WorkflowNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Service responsible for implementing the continue-as-new pattern for long-running workflows.
 * <p>
 * Continue-as-new resets the event history of a workflow by completing the current aggregate
 * and starting a fresh one with the same workflow identity (workflowId, name, version,
 * correlationId). This prevents unbounded event history growth in workflows that run
 * indefinitely (e.g., polling loops, subscription handlers).
 * <p>
 * The operation performs the following steps:
 * <ol>
 *   <li>Loads the current aggregate from the event store</li>
 *   <li>Marks the current aggregate as COMPLETED with a {@code ContinueAsNewEvent}</li>
 *   <li>Saves the current aggregate</li>
 *   <li>Creates a new aggregate with a fresh UUID and starts it with the same identity</li>
 *   <li>Migrates pending signals from the old aggregate to the new one</li>
 *   <li>Migrates active timers from the old aggregate to the new one</li>
 *   <li>Saves the new aggregate</li>
 * </ol>
 * <p>
 * This follows the Temporal.io continue-as-new pattern.
 *
 * @see ContinueAsNewResult
 * @see WorkflowAggregate#continueAsNew(Map, Object)
 */
@Slf4j
@RequiredArgsConstructor
public class ContinueAsNewService {

    private final EventSourcedWorkflowStateStore stateStore;

    /**
     * Continues a workflow as a new execution with fresh event history.
     * <p>
     * Completes the current aggregate, creates a new one with the same workflow identity,
     * and migrates pending signals and active timers to the new aggregate.
     *
     * @param instanceId the UUID of the current workflow aggregate instance
     * @param newInput   the input parameters for the new execution
     * @return a Mono emitting the {@link ContinueAsNewResult} with migration details
     * @throws WorkflowNotFoundException if the instance does not exist
     * @throws IllegalStateException     if the instance is already in a terminal state
     */
    public Mono<ContinueAsNewResult> continueAsNew(UUID instanceId, Map<String, Object> newInput) {
        log.info("Continue-as-new requested for workflow instance: {}", instanceId);

        return stateStore.loadAggregate(instanceId)
                .switchIfEmpty(Mono.error(new WorkflowNotFoundException("unknown", instanceId.toString())))
                .flatMap(currentAggregate -> {
                    // Capture state from the current aggregate before marking it completed
                    Object currentOutput = currentAggregate.getOutput();
                    String workflowId = currentAggregate.getWorkflowId();
                    String workflowName = currentAggregate.getWorkflowName();
                    String workflowVersion = currentAggregate.getWorkflowVersion();
                    String correlationId = currentAggregate.getCorrelationId();
                    String triggeredBy = currentAggregate.getTriggeredBy();

                    // Capture signals and timers before the aggregate becomes terminal
                    Map<String, WorkflowAggregate.SignalData> pendingSignals =
                            Map.copyOf(currentAggregate.getPendingSignals());
                    Map<String, WorkflowAggregate.TimerData> activeTimers =
                            Map.copyOf(currentAggregate.getActiveTimers());

                    // Mark the current aggregate as completed via ContinueAsNewEvent
                    currentAggregate.continueAsNew(newInput, currentOutput);

                    log.debug("Marked current aggregate as completed: instanceId={}, workflowId={}",
                            instanceId, workflowId);

                    // Save the completed current aggregate
                    return stateStore.saveAggregate(currentAggregate)
                            .flatMap(savedOld -> {
                                // Create a new aggregate with a fresh UUID
                                UUID newInstanceId = UUID.randomUUID();
                                WorkflowAggregate newAggregate = new WorkflowAggregate(newInstanceId);

                                // Start the new aggregate with the same workflow identity
                                newAggregate.start(
                                        workflowId,
                                        workflowName,
                                        workflowVersion,
                                        newInput,
                                        correlationId,
                                        triggeredBy,
                                        false
                                );

                                // Migrate pending signals to the new aggregate
                                int migratedSignals = 0;
                                for (WorkflowAggregate.SignalData signal : pendingSignals.values()) {
                                    newAggregate.receiveSignal(signal.signalName(), signal.payload());
                                    migratedSignals++;
                                }

                                // Migrate active timers to the new aggregate
                                int migratedTimers = 0;
                                for (WorkflowAggregate.TimerData timer : activeTimers.values()) {
                                    newAggregate.registerTimer(timer.timerId(), timer.fireAt(), timer.data());
                                    migratedTimers++;
                                }

                                log.debug("Created new aggregate: newInstanceId={}, migratedSignals={}, migratedTimers={}",
                                        newInstanceId, migratedSignals, migratedTimers);

                                int finalMigratedSignals = migratedSignals;
                                int finalMigratedTimers = migratedTimers;

                                // Save the new aggregate
                                return stateStore.saveAggregate(newAggregate)
                                        .map(savedNew -> {
                                            log.info("Continue-as-new completed: previousInstanceId={}, " +
                                                            "newInstanceId={}, workflowId={}, migratedSignals={}, " +
                                                            "migratedTimers={}",
                                                    instanceId, newInstanceId, workflowId,
                                                    finalMigratedSignals, finalMigratedTimers);

                                            return new ContinueAsNewResult(
                                                    instanceId.toString(),
                                                    newInstanceId.toString(),
                                                    workflowId,
                                                    finalMigratedTimers,
                                                    finalMigratedSignals
                                            );
                                        });
                            });
                });
    }
}
