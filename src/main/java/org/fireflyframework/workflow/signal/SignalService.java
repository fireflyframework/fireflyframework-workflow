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

package org.fireflyframework.workflow.signal;

import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.eventsourcing.store.EventSourcedWorkflowStateStore;
import org.fireflyframework.workflow.exception.WorkflowNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Service responsible for delivering external signals to running workflow instances.
 * <p>
 * Signals are a core durable execution primitive that allow external systems to
 * communicate with running workflows. When a signal is sent, it is persisted as
 * an event in the aggregate's event stream, making it durable and replay-safe.
 * <p>
 * If a workflow step is waiting for the signal (registered in {@code signalWaiters}),
 * the service detects this and reports that the step was resumed.
 *
 * @see SignalResult
 * @see WorkflowAggregate#receiveSignal(String, Map)
 */
@Slf4j
@RequiredArgsConstructor
public class SignalService {

    private final EventSourcedWorkflowStateStore stateStore;

    /**
     * Sends an external signal to a workflow instance.
     * <p>
     * The signal is delivered by:
     * <ol>
     *   <li>Parsing the instance ID to a UUID</li>
     *   <li>Loading the aggregate from the event store</li>
     *   <li>Calling {@code receiveSignal} on the aggregate (which applies a {@code SignalReceivedEvent})</li>
     *   <li>Checking if any step was waiting for this signal</li>
     *   <li>Saving the aggregate back to the event store</li>
     * </ol>
     *
     * @param instanceId the workflow instance identifier
     * @param signalName the name of the signal to deliver
     * @param payload    the signal payload data
     * @return a Mono emitting the {@link SignalResult} describing the delivery outcome
     * @throws WorkflowNotFoundException if the instance does not exist
     */
    public Mono<SignalResult> sendSignal(String instanceId, String signalName, Map<String, Object> payload) {
        log.info("Sending signal '{}' to workflow instance: {}", signalName, instanceId);

        UUID aggregateId;
        try {
            aggregateId = UUID.fromString(instanceId);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid instance ID format: {}", instanceId);
            return Mono.error(new WorkflowNotFoundException("unknown", instanceId));
        }

        return stateStore.loadAggregate(aggregateId)
                .switchIfEmpty(Mono.error(new WorkflowNotFoundException("unknown", instanceId)))
                .flatMap(aggregate -> {
                    // Check if a step is waiting for this signal before delivery
                    String waitingStepId = aggregate.getSignalWaiters().get(signalName);

                    // Deliver the signal to the aggregate (applies SignalReceivedEvent)
                    aggregate.receiveSignal(signalName, payload);

                    boolean stepResumed = waitingStepId != null;

                    log.debug("Signal '{}' applied to aggregate: {}, stepResumed={}, resumedStepId={}",
                            signalName, instanceId, stepResumed, waitingStepId);

                    return stateStore.saveAggregate(aggregate)
                            .map(saved -> new SignalResult(
                                    instanceId,
                                    signalName,
                                    true,
                                    stepResumed,
                                    waitingStepId
                            ));
                });
    }

    /**
     * Consumes a pending signal from a workflow instance.
     * <p>
     * If the aggregate has a buffered signal with the given name in its
     * {@code pendingSignals} map, this method returns its payload and persists
     * a {@code SignalConsumedEvent} to remove it from the pending map. This is
     * used internally by steps that are waiting for signals.
     *
     * @param instanceId the workflow instance identifier
     * @param signalName the name of the signal to consume
     * @return a Mono emitting the signal payload map, or empty if no such signal is pending
     */
    public Mono<Map<String, Object>> consumeSignal(String instanceId, String signalName) {
        log.debug("Consuming signal '{}' from workflow instance: {}", signalName, instanceId);

        UUID aggregateId;
        try {
            aggregateId = UUID.fromString(instanceId);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid instance ID format: {}", instanceId);
            return Mono.empty();
        }

        return stateStore.loadAggregate(aggregateId)
                .flatMap(aggregate -> {
                    WorkflowAggregate.SignalData signalData = aggregate.getPendingSignals().get(signalName);
                    if (signalData != null) {
                        Map<String, Object> payload = signalData.payload();
                        log.debug("Consuming pending signal '{}' for instance: {}, receivedAt={}",
                                signalName, instanceId, signalData.receivedAt());

                        aggregate.consumeSignal(signalName, null);

                        return stateStore.saveAggregate(aggregate)
                                .thenReturn(payload);
                    }
                    log.debug("No pending signal '{}' found for instance: {}", signalName, instanceId);
                    return Mono.<Map<String, Object>>empty();
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("Aggregate not found or no signal for instance: {}", instanceId);
                    return Mono.empty();
                }));
    }
}
