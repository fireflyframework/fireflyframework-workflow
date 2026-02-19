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

package org.fireflyframework.workflow.eventsourcing.snapshot;

import org.fireflyframework.eventsourcing.snapshot.AbstractSnapshot;
import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate.ChildWorkflowRef;
import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate.SignalData;
import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate.StepState;
import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate.TimerData;
import org.fireflyframework.workflow.model.StepStatus;
import org.fireflyframework.workflow.model.WorkflowStatus;
import lombok.Builder;
import lombok.Getter;

import java.io.Serializable;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Snapshot of a {@link WorkflowAggregate}'s state for optimized replay.
 * <p>
 * Instead of replaying all events from the beginning, the event store can
 * load a snapshot and only replay events that occurred after the snapshot
 * was taken. This dramatically reduces aggregate reconstruction time for
 * long-running workflows with many events.
 * <p>
 * This class captures the complete state of a WorkflowAggregate at a specific
 * version, including all step states, signals, timers, child workflows,
 * side effects, search attributes, heartbeats, and completed step order.
 * <p>
 * <b>Usage:</b>
 * <pre>
 * {@code
 * // Capture snapshot from a live aggregate
 * WorkflowSnapshot snapshot = WorkflowSnapshot.from(aggregate);
 *
 * // Later, restore the aggregate from the snapshot
 * WorkflowAggregate restored = snapshot.restore();
 *
 * // Then replay only events after the snapshot version
 * restored.loadFromHistory(eventsAfterSnapshot);
 * }
 * </pre>
 *
 * @see AbstractSnapshot
 * @see WorkflowAggregate
 */
@Getter
public class WorkflowSnapshot extends AbstractSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    // --- Workflow identity and configuration ---

    private final String workflowId;
    private final String workflowName;
    private final String workflowVersion;

    // --- Workflow state ---

    private final WorkflowStatus status;
    private final String currentStepId;
    private final Map<String, Object> context;
    private final Map<String, Object> input;
    private final Object output;

    // --- Execution metadata ---

    private final String correlationId;
    private final String triggeredBy;
    private final boolean dryRun;
    private final Instant startedAt;
    private final Instant completedAt;

    // --- Serializable copies of complex state ---

    private final Map<String, StepStateData> stepStatesSnapshot;
    private final Map<String, SignalData> pendingSignals;
    private final Map<String, TimerData> activeTimers;
    private final Map<String, ChildWorkflowRef> childWorkflows;
    private final Map<String, Object> sideEffects;
    private final Map<String, Object> searchAttributes;
    private final Map<String, Map<String, Object>> lastHeartbeats;
    private final List<String> completedStepOrder;

    /**
     * Constructs a WorkflowSnapshot with all captured state.
     * <p>
     * Prefer using the {@link #from(WorkflowAggregate)} factory method
     * instead of calling this constructor directly.
     */
    @Builder
    @SuppressWarnings("java:S107") // Large constructor is acceptable for snapshots
    WorkflowSnapshot(UUID aggregateId, long version, Instant createdAt,
                     String workflowId, String workflowName, String workflowVersion,
                     WorkflowStatus status, String currentStepId,
                     Map<String, Object> context, Map<String, Object> input, Object output,
                     String correlationId, String triggeredBy, boolean dryRun,
                     Instant startedAt, Instant completedAt,
                     Map<String, StepStateData> stepStatesSnapshot,
                     Map<String, SignalData> pendingSignals,
                     Map<String, TimerData> activeTimers,
                     Map<String, ChildWorkflowRef> childWorkflows,
                     Map<String, Object> sideEffects,
                     Map<String, Object> searchAttributes,
                     Map<String, Map<String, Object>> lastHeartbeats,
                     List<String> completedStepOrder) {
        super(aggregateId, version, createdAt);
        this.workflowId = workflowId;
        this.workflowName = workflowName;
        this.workflowVersion = workflowVersion;
        this.status = status;
        this.currentStepId = currentStepId;
        this.context = context != null ? new HashMap<>(context) : new HashMap<>();
        this.input = input != null ? new HashMap<>(input) : new HashMap<>();
        this.output = output;
        this.correlationId = correlationId;
        this.triggeredBy = triggeredBy;
        this.dryRun = dryRun;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.stepStatesSnapshot = stepStatesSnapshot != null
                ? new HashMap<>(stepStatesSnapshot) : new HashMap<>();
        this.pendingSignals = pendingSignals != null
                ? new HashMap<>(pendingSignals) : new HashMap<>();
        this.activeTimers = activeTimers != null
                ? new HashMap<>(activeTimers) : new HashMap<>();
        this.childWorkflows = childWorkflows != null
                ? new HashMap<>(childWorkflows) : new HashMap<>();
        this.sideEffects = sideEffects != null
                ? new HashMap<>(sideEffects) : new HashMap<>();
        this.searchAttributes = searchAttributes != null
                ? new HashMap<>(searchAttributes) : new HashMap<>();
        this.lastHeartbeats = lastHeartbeats != null
                ? deepCopyHeartbeats(lastHeartbeats) : new HashMap<>();
        this.completedStepOrder = completedStepOrder != null
                ? new ArrayList<>(completedStepOrder) : new ArrayList<>();
    }

    @Override
    public String getSnapshotType() {
        return "workflow";
    }

    // ========================================================================
    // Factory Method
    // ========================================================================

    /**
     * Creates a snapshot from the current state of a {@link WorkflowAggregate}.
     * <p>
     * This method captures a deep copy of all aggregate state, ensuring that
     * subsequent modifications to the aggregate do not affect the snapshot.
     *
     * @param aggregate the workflow aggregate to snapshot
     * @return a new WorkflowSnapshot capturing the aggregate's current state
     * @throws IllegalArgumentException if aggregate is null
     */
    public static WorkflowSnapshot from(WorkflowAggregate aggregate) {
        if (aggregate == null) {
            throw new IllegalArgumentException("Aggregate cannot be null");
        }

        return WorkflowSnapshot.builder()
                .aggregateId(aggregate.getId())
                .version(aggregate.getCurrentVersion())
                .createdAt(Instant.now())
                .workflowId(aggregate.getWorkflowId())
                .workflowName(aggregate.getWorkflowName())
                .workflowVersion(aggregate.getWorkflowVersion())
                .status(aggregate.getStatus())
                .currentStepId(aggregate.getCurrentStepId())
                .context(aggregate.getContext() != null
                        ? new HashMap<>(aggregate.getContext()) : new HashMap<>())
                .input(aggregate.getInput() != null
                        ? new HashMap<>(aggregate.getInput()) : new HashMap<>())
                .output(aggregate.getOutput())
                .correlationId(aggregate.getCorrelationId())
                .triggeredBy(aggregate.getTriggeredBy())
                .dryRun(aggregate.isDryRun())
                .startedAt(aggregate.getStartedAt())
                .completedAt(aggregate.getCompletedAt())
                .stepStatesSnapshot(convertStepStates(aggregate.getStepStates()))
                .pendingSignals(new HashMap<>(aggregate.getPendingSignals()))
                .activeTimers(new HashMap<>(aggregate.getActiveTimers()))
                .childWorkflows(new HashMap<>(aggregate.getChildWorkflows()))
                .sideEffects(new HashMap<>(aggregate.getSideEffects()))
                .searchAttributes(new HashMap<>(aggregate.getSearchAttributes()))
                .lastHeartbeats(deepCopyHeartbeats(aggregate.getLastHeartbeats()))
                .completedStepOrder(new ArrayList<>(aggregate.getCompletedStepOrder()))
                .build();
    }

    // ========================================================================
    // Restore Method
    // ========================================================================

    /**
     * Restores a {@link WorkflowAggregate} from this snapshot.
     * <p>
     * Creates a new WorkflowAggregate instance and directly sets all internal
     * state from the snapshot data. The restored aggregate will have the same
     * version and state as the original at the time the snapshot was taken.
     * <p>
     * After restoration, additional events can be replayed on the aggregate
     * to bring it up to date.
     *
     * @return a new WorkflowAggregate with state restored from this snapshot
     */
    public WorkflowAggregate restore() {
        WorkflowAggregate aggregate = new WorkflowAggregate(getAggregateId());
        aggregate.restoreFromSnapshot(this);
        return aggregate;
    }

    // ========================================================================
    // Inner Types
    // ========================================================================

    /**
     * Serializable representation of a workflow step's state.
     * <p>
     * This record mirrors {@link StepState} but implements {@link Serializable}
     * to ensure reliable snapshot serialization.
     */
    public record StepStateData(
            String stepId,
            String stepName,
            StepStatus status,
            int attemptNumber,
            Map<String, Object> input,
            Object output,
            String errorOrReason,
            Instant startedAt,
            Instant completedAt) implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * Creates a StepStateData from a {@link StepState}.
         *
         * @param stepState the step state to convert
         * @return a new StepStateData instance
         */
        public static StepStateData from(StepState stepState) {
            return new StepStateData(
                    stepState.stepId(),
                    stepState.stepName(),
                    stepState.status(),
                    stepState.attemptNumber(),
                    stepState.input() != null ? new HashMap<>(stepState.input()) : null,
                    stepState.output(),
                    stepState.errorOrReason(),
                    stepState.startedAt(),
                    stepState.completedAt());
        }

        /**
         * Converts this StepStateData back to a {@link StepState}.
         *
         * @return a new StepState instance
         */
        public StepState toStepState() {
            return new StepState(
                    stepId, stepName, status, attemptNumber,
                    input != null ? new HashMap<>(input) : null,
                    output, errorOrReason, startedAt, completedAt);
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Converts a map of StepState to a map of StepStateData for serialization.
     */
    private static Map<String, StepStateData> convertStepStates(Map<String, StepState> stepStates) {
        if (stepStates == null || stepStates.isEmpty()) {
            return new HashMap<>();
        }
        return stepStates.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> StepStateData.from(entry.getValue())));
    }

    /**
     * Creates a deep copy of the heartbeats map.
     */
    private static Map<String, Map<String, Object>> deepCopyHeartbeats(
            Map<String, Map<String, Object>> original) {
        Map<String, Map<String, Object>> copy = new HashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : original.entrySet()) {
            copy.put(entry.getKey(), entry.getValue() != null
                    ? new HashMap<>(entry.getValue()) : new HashMap<>());
        }
        return copy;
    }
}
