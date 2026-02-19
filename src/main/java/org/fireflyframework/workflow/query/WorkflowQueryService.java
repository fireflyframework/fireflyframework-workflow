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

package org.fireflyframework.workflow.query;

import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.eventsourcing.store.EventSourcedWorkflowStateStore;
import org.fireflyframework.workflow.exception.WorkflowNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for querying the internal state of running workflow instances.
 * <p>
 * Provides a read-only inspection mechanism that allows external systems
 * to examine workflow state without affecting execution. State is loaded
 * by replaying the aggregate's event stream, ensuring consistency with
 * the durable execution model.
 * <p>
 * <b>Built-in queries:</b>
 * <ul>
 *   <li>{@code getStatus} -- current workflow status</li>
 *   <li>{@code getCurrentStep} -- current step ID</li>
 *   <li>{@code getStepHistory} -- map of all step states</li>
 *   <li>{@code getContext} -- workflow context map</li>
 *   <li>{@code getSearchAttributes} -- search attribute map</li>
 *   <li>{@code getInput} -- workflow input</li>
 *   <li>{@code getOutput} -- workflow output (null if not completed)</li>
 *   <li>{@code getPendingSignals} -- pending signal names</li>
 *   <li>{@code getActiveTimers} -- active timer IDs</li>
 *   <li>{@code getChildWorkflows} -- child workflow status map</li>
 * </ul>
 *
 * @see WorkflowAggregate
 * @see EventSourcedWorkflowStateStore
 */
@Slf4j
@RequiredArgsConstructor
public class WorkflowQueryService {

    private final EventSourcedWorkflowStateStore stateStore;

    /**
     * The set of supported built-in query names.
     */
    public static final Set<String> SUPPORTED_QUERIES = Set.of(
            "getStatus",
            "getCurrentStep",
            "getStepHistory",
            "getContext",
            "getSearchAttributes",
            "getInput",
            "getOutput",
            "getPendingSignals",
            "getActiveTimers",
            "getChildWorkflows"
    );

    /**
     * Executes a built-in query against a workflow instance.
     * <p>
     * Loads the workflow aggregate from the event store, executes the
     * named query against it, and returns the result. This is a read-only
     * operation that does not modify the aggregate.
     *
     * @param instanceId the workflow instance identifier
     * @param queryName  the name of the built-in query to execute
     * @return a Mono emitting the query result
     * @throws WorkflowNotFoundException if the instance does not exist
     * @throws IllegalArgumentException  if the query name is not recognized
     */
    public Mono<Object> executeQuery(String instanceId, String queryName) {
        log.info("Executing query '{}' on workflow instance: {}", queryName, instanceId);

        UUID aggregateId;
        try {
            aggregateId = UUID.fromString(instanceId);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid instance ID format: {}", instanceId);
            return Mono.error(new WorkflowNotFoundException("unknown", instanceId));
        }

        return stateStore.loadAggregate(aggregateId)
                .map(aggregate -> executeBuiltInQuery(aggregate, queryName))
                .switchIfEmpty(Mono.error(new WorkflowNotFoundException("unknown", instanceId)));
    }

    /**
     * Executes a built-in query against the given aggregate.
     *
     * @param aggregate the workflow aggregate to query
     * @param queryName the name of the built-in query
     * @return the query result
     * @throws IllegalArgumentException if the query name is not recognized
     */
    private Object executeBuiltInQuery(WorkflowAggregate aggregate, String queryName) {
        return switch (queryName) {
            case "getStatus" -> aggregate.getStatus().name();
            case "getCurrentStep" -> aggregate.getCurrentStepId();
            case "getStepHistory" -> buildStepHistory(aggregate);
            case "getContext" -> aggregate.getContext();
            case "getSearchAttributes" -> aggregate.getSearchAttributes();
            case "getInput" -> aggregate.getInput();
            case "getOutput" -> aggregate.getOutput();
            case "getPendingSignals" -> aggregate.getPendingSignals().keySet();
            case "getActiveTimers" -> aggregate.getActiveTimers().keySet();
            case "getChildWorkflows" -> buildChildWorkflowSummary(aggregate);
            default -> throw new IllegalArgumentException("Unknown query: " + queryName);
        };
    }

    /**
     * Builds a step history map from the aggregate's step states.
     * <p>
     * Each entry maps a step ID to a summary map containing the step's
     * status, attempt number, and error/reason (if any).
     *
     * @param aggregate the workflow aggregate
     * @return a map of step ID to step state summary
     */
    private Map<String, Map<String, Object>> buildStepHistory(WorkflowAggregate aggregate) {
        return aggregate.getStepStates().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            WorkflowAggregate.StepState state = entry.getValue();
                            Map<String, Object> summary = new LinkedHashMap<>();
                            summary.put("status", state.status().name());
                            summary.put("attemptNumber", state.attemptNumber());
                            if (state.errorOrReason() != null) {
                                summary.put("errorOrReason", state.errorOrReason());
                            }
                            if (state.startedAt() != null) {
                                summary.put("startedAt", state.startedAt().toString());
                            }
                            if (state.completedAt() != null) {
                                summary.put("completedAt", state.completedAt().toString());
                            }
                            return summary;
                        }
                ));
    }

    /**
     * Builds a child workflow summary map from the aggregate.
     * <p>
     * Each entry maps a child instance ID to a summary map containing
     * the child workflow ID, parent step ID, and completion status.
     *
     * @param aggregate the workflow aggregate
     * @return a map of child instance ID to child workflow summary
     */
    private Map<String, Map<String, Object>> buildChildWorkflowSummary(WorkflowAggregate aggregate) {
        return aggregate.getChildWorkflows().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            WorkflowAggregate.ChildWorkflowRef ref = entry.getValue();
                            Map<String, Object> summary = new LinkedHashMap<>();
                            summary.put("childWorkflowId", ref.childWorkflowId());
                            summary.put("parentStepId", ref.parentStepId());
                            summary.put("completed", ref.completed());
                            if (ref.output() != null) {
                                summary.put("output", ref.output());
                            }
                            return summary;
                        }
                ));
    }
}
