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

package org.fireflyframework.workflow.child;

import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.eventsourcing.store.EventSourcedWorkflowStateStore;
import org.fireflyframework.workflow.exception.WorkflowNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service responsible for managing child workflow lifecycles within the durable
 * execution engine.
 * <p>
 * Child workflows allow a parent workflow to spawn sub-workflows, wait for their
 * completion, and use their results. This service handles:
 * <ul>
 *   <li>Spawning child workflows and recording the relationship on the parent</li>
 *   <li>Notifying parent workflows when child workflows complete</li>
 *   <li>Cascading cancellation from parent to child workflows</li>
 *   <li>Querying the status of child workflows for a given parent</li>
 * </ul>
 * <p>
 * The parent-child mapping is maintained in-memory via a {@link ConcurrentHashMap}
 * for the current phase. Persistent mapping via projections will be added in a
 * future phase.
 *
 * @see ChildWorkflowResult
 * @see ChildWorkflowInfo
 * @see WorkflowAggregate#spawnChildWorkflow(String, String, Map, String)
 * @see WorkflowAggregate#completeChildWorkflow(String, Object, boolean)
 */
@Slf4j
@RequiredArgsConstructor
public class ChildWorkflowService {

    private final EventSourcedWorkflowStateStore stateStore;

    /**
     * In-memory mapping of child instance ID to parent instance ID.
     * <p>
     * This map enables the service to look up which parent owns a given child
     * when the child completes. In a future phase, this will be replaced by
     * a persistent projection.
     */
    private final ConcurrentHashMap<String, UUID> parentChildMap = new ConcurrentHashMap<>();

    /**
     * Spawns a child workflow from a parent workflow.
     * <p>
     * This method performs the following steps:
     * <ol>
     *   <li>Loads the parent aggregate from the event store</li>
     *   <li>Generates a new UUID for the child workflow instance</li>
     *   <li>Records the child spawn on the parent aggregate (emits ChildWorkflowSpawnedEvent)</li>
     *   <li>Saves the parent aggregate</li>
     *   <li>Creates a new child aggregate, starts it with the given input, and saves it</li>
     *   <li>Stores the parent-child mapping for later notification</li>
     * </ol>
     *
     * @param parentInstanceId the parent workflow instance UUID
     * @param parentStepId     the parent step that is spawning the child
     * @param childWorkflowId  the workflow definition identifier for the child
     * @param childInput       the input parameters for the child workflow
     * @return a Mono emitting the {@link ChildWorkflowResult} with child instance details
     * @throws WorkflowNotFoundException if the parent workflow instance is not found
     */
    public Mono<ChildWorkflowResult> spawnChildWorkflow(UUID parentInstanceId, String parentStepId,
                                                         String childWorkflowId,
                                                         Map<String, Object> childInput) {
        log.info("Spawning child workflow '{}' from parent instance: {}, step: {}",
                childWorkflowId, parentInstanceId, parentStepId);

        return stateStore.loadAggregate(parentInstanceId)
                .switchIfEmpty(Mono.error(new WorkflowNotFoundException("unknown", parentInstanceId.toString())))
                .flatMap(parentAggregate -> {
                    // Generate a new UUID for the child instance
                    UUID childInstanceId = UUID.randomUUID();
                    String childInstanceIdStr = childInstanceId.toString();

                    // Record the child spawn on the parent aggregate
                    parentAggregate.spawnChildWorkflow(childInstanceIdStr, childWorkflowId,
                            childInput, parentStepId);

                    log.debug("Recorded child workflow spawn on parent: parentId={}, childId={}, childWorkflowId={}",
                            parentInstanceId, childInstanceIdStr, childWorkflowId);

                    // Save the parent aggregate with the spawn event
                    return stateStore.saveAggregate(parentAggregate)
                            .flatMap(savedParent -> {
                                // Create and start the child aggregate
                                WorkflowAggregate childAggregate = new WorkflowAggregate(childInstanceId);
                                childAggregate.start(
                                        childWorkflowId,
                                        childWorkflowId,  // Use workflow ID as name
                                        "1.0.0",
                                        childInput,
                                        parentInstanceId.toString(),  // Use parent ID as correlation ID
                                        "parent:" + parentInstanceId,
                                        false
                                );

                                return stateStore.saveAggregate(childAggregate)
                                        .onErrorResume(e -> {
                                            log.error("CRITICAL: Parent spawn event persisted but child aggregate creation failed. " +
                                                            "parentId={}, childId={}, childWorkflowId={}: {}",
                                                    parentInstanceId, childInstanceIdStr, childWorkflowId, e.getMessage(), e);
                                            return Mono.error(e);
                                        });
                            })
                            .doOnSuccess(savedChild -> {
                                // Store the parent-child mapping
                                parentChildMap.put(childInstanceIdStr, parentInstanceId);

                                log.info("Child workflow spawned successfully: parentId={}, childId={}, " +
                                                "childWorkflowId={}",
                                        parentInstanceId, childInstanceIdStr, childWorkflowId);
                            })
                            .map(savedChild -> new ChildWorkflowResult(
                                    parentInstanceId.toString(),
                                    childInstanceIdStr,
                                    childWorkflowId,
                                    parentStepId,
                                    true
                            ));
                });
    }

    /**
     * Notifies the parent workflow that a child workflow has completed.
     * <p>
     * Looks up the parent instance from the in-memory parent-child mapping,
     * loads the parent aggregate, applies the child completion event, and
     * saves the parent aggregate.
     * <p>
     * If no parent mapping exists for the given child instance ID, the method
     * completes gracefully with a warning log.
     *
     * @param childInstanceId the child workflow instance UUID that completed
     * @param output          the output produced by the child workflow
     * @param success         whether the child workflow completed successfully
     * @return a Mono that completes when the parent has been notified
     */
    public Mono<Void> completeChildWorkflow(UUID childInstanceId, Object output, boolean success) {
        String childInstanceIdStr = childInstanceId.toString();

        log.info("Completing child workflow: childId={}, success={}", childInstanceIdStr, success);

        UUID parentInstanceId = parentChildMap.get(childInstanceIdStr);

        if (parentInstanceId == null) {
            log.warn("No parent mapping found for child workflow: {}. " +
                    "Child may have been spawned before this service started or mapping was lost.",
                    childInstanceIdStr);
            return Mono.empty();
        }

        return stateStore.loadAggregate(parentInstanceId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Parent aggregate not found for child completion: parentId={}, childId={}",
                            parentInstanceId, childInstanceIdStr);
                    return Mono.empty();
                }))
                .flatMap(parentAggregate -> {
                    // Idempotency guard: skip if child is already completed
                    WorkflowAggregate.ChildWorkflowRef existingRef = parentAggregate.getChildWorkflows().get(childInstanceIdStr);
                    if (existingRef != null && existingRef.completed()) {
                        log.warn("Child workflow already completed, skipping duplicate notification: childId={}", childInstanceIdStr);
                        parentChildMap.remove(childInstanceIdStr);
                        return Mono.empty();
                    }

                    parentAggregate.completeChildWorkflow(childInstanceIdStr, output, success);

                    log.debug("Recorded child completion on parent: parentId={}, childId={}, success={}",
                            parentInstanceId, childInstanceIdStr, success);

                    return stateStore.saveAggregate(parentAggregate)
                            .doOnSuccess(saved -> {
                                // Remove the mapping since the child is done
                                parentChildMap.remove(childInstanceIdStr);

                                log.info("Parent notified of child completion: parentId={}, childId={}",
                                        parentInstanceId, childInstanceIdStr);
                            })
                            .then();
                });
    }

    /**
     * Cancels all incomplete child workflows for a given parent.
     * <p>
     * Loads the parent aggregate, identifies all child workflows that have not
     * yet completed, and cancels each one by loading the child aggregate,
     * calling cancel(), and saving it.
     * <p>
     * Already-completed children are skipped. Errors cancelling individual
     * children are logged and do not prevent cancellation of other children.
     *
     * @param parentInstanceId the parent workflow instance UUID
     * @return a Mono that completes when all incomplete children have been cancelled
     */
    public Mono<Void> cancelChildWorkflows(UUID parentInstanceId) {
        log.info("Cancelling child workflows for parent: {}", parentInstanceId);

        return stateStore.loadAggregate(parentInstanceId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Parent aggregate not found for child cancellation: {}", parentInstanceId);
                    return Mono.empty();
                }))
                .flatMap(parentAggregate -> {
                    Map<String, WorkflowAggregate.ChildWorkflowRef> children = parentAggregate.getChildWorkflows();

                    if (children.isEmpty()) {
                        log.debug("No child workflows to cancel for parent: {}", parentInstanceId);
                        return Mono.<Void>empty();
                    }

                    // Filter to only incomplete children
                    return Flux.fromIterable(children.values())
                            .filter(childRef -> !childRef.completed())
                            .concatMap(childRef -> cancelSingleChild(childRef, parentInstanceId))
                            .then();
                });
    }

    /**
     * Returns the status of all child workflows for a given parent.
     * <p>
     * Loads the parent aggregate and converts its child workflow references
     * into {@link ChildWorkflowInfo} records.
     *
     * @param parentInstanceId the parent workflow instance UUID
     * @return a Mono emitting a map of child instance ID to {@link ChildWorkflowInfo}
     */
    public Mono<Map<String, ChildWorkflowInfo>> getChildWorkflowStatus(UUID parentInstanceId) {
        log.debug("Getting child workflow status for parent: {}", parentInstanceId);

        return stateStore.loadAggregate(parentInstanceId)
                .map(parentAggregate -> {
                    Map<String, ChildWorkflowInfo> result = new HashMap<>();

                    parentAggregate.getChildWorkflows().forEach((childId, childRef) ->
                            result.put(childId, new ChildWorkflowInfo(
                                    childRef.childInstanceId(),
                                    childRef.childWorkflowId(),
                                    childRef.parentStepId(),
                                    childRef.completed(),
                                    childRef.success(),
                                    childRef.output()
                            ))
                    );

                    log.debug("Found {} child workflow(s) for parent: {}", result.size(), parentInstanceId);
                    return result;
                })
                .switchIfEmpty(Mono.error(new WorkflowNotFoundException("unknown", parentInstanceId.toString())));
    }

    /**
     * Registers a child-to-parent mapping.
     * <p>
     * This method is exposed for cases where the mapping needs to be established
     * outside the normal spawn flow (e.g., during recovery or testing).
     *
     * @param childInstanceId  the child workflow instance ID
     * @param parentInstanceId the parent workflow instance UUID
     */
    public void registerChildParentMapping(String childInstanceId, UUID parentInstanceId) {
        parentChildMap.put(childInstanceId, parentInstanceId);
        log.debug("Registered child-parent mapping: childId={}, parentId={}",
                childInstanceId, parentInstanceId);
    }

    // ========================================================================
    // Private Helpers
    // ========================================================================

    /**
     * Cancels a single child workflow aggregate.
     * <p>
     * Loads the child aggregate, calls cancel(), and saves it. Errors are
     * logged and suppressed so that cancellation of other children can continue.
     *
     * @param childRef         the child workflow reference from the parent
     * @param parentInstanceId the parent instance ID (for logging)
     * @return a Mono that completes when the child has been cancelled
     */
    private Mono<Void> cancelSingleChild(WorkflowAggregate.ChildWorkflowRef childRef,
                                          UUID parentInstanceId) {
        UUID childAggregateId;
        try {
            childAggregateId = UUID.fromString(childRef.childInstanceId());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid child instance ID format: {}", childRef.childInstanceId());
            return Mono.empty();
        }

        return stateStore.loadAggregate(childAggregateId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Child aggregate not found for cancellation: childId={}, parentId={}",
                            childRef.childInstanceId(), parentInstanceId);
                    return Mono.empty();
                }))
                .flatMap(childAggregate -> {
                    if (childAggregate.getStatus().isTerminal()) {
                        log.debug("Child workflow already in terminal state: childId={}, status={}",
                                childRef.childInstanceId(), childAggregate.getStatus());
                        return Mono.<Void>empty();
                    }

                    childAggregate.cancel("Parent workflow cancelled: " + parentInstanceId);

                    return stateStore.saveAggregate(childAggregate)
                            .doOnSuccess(saved -> log.info("Cancelled child workflow: childId={}, parentId={}",
                                    childRef.childInstanceId(), parentInstanceId))
                            .then();
                })
                .onErrorResume(e -> {
                    log.error("Failed to cancel child workflow: childId={}, parentId={}: {}",
                            childRef.childInstanceId(), parentInstanceId, e.getMessage(), e);
                    return Mono.empty();
                });
    }
}
