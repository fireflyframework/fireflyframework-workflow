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

package org.fireflyframework.workflow.eventsourcing.aggregate;

import org.fireflyframework.eventsourcing.aggregate.AggregateRoot;
import org.fireflyframework.workflow.eventsourcing.event.*;
import org.fireflyframework.workflow.model.StepStatus;
import org.fireflyframework.workflow.model.WorkflowStatus;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event-sourced aggregate representing a workflow instance.
 * <p>
 * This is the heart of the durable execution engine. Each workflow instance
 * becomes an event-sourced aggregate, with its entire lifecycle captured as
 * a sequence of domain events. The aggregate enforces state transitions,
 * validates commands, and maintains consistency through event sourcing.
 * <p>
 * <b>Lifecycle:</b> PENDING -> RUNNING -> COMPLETED/FAILED/CANCELLED
 * <p>
 * <b>Features:</b>
 * <ul>
 *   <li>Step tracking with attempt counting for retries</li>
 *   <li>Signal support with buffered pending signals</li>
 *   <li>Durable timer management</li>
 *   <li>Child workflow orchestration</li>
 *   <li>Side effect recording for deterministic replay</li>
 *   <li>Heartbeat tracking for long-running steps</li>
 *   <li>Search attribute indexing</li>
 *   <li>Saga compensation support</li>
 *   <li>Continue-as-new for unbounded workflows</li>
 * </ul>
 *
 * @see AggregateRoot
 * @see WorkflowStatus
 */
@Slf4j
@Getter
public class WorkflowAggregate extends AggregateRoot {

    // --- Workflow identity and configuration ---

    private String workflowId;
    private String workflowName;
    private String workflowVersion;

    // --- Workflow state ---

    private WorkflowStatus status;
    private String currentStepId;
    private Map<String, Object> context;
    private Map<String, Object> input;
    private Object output;

    // --- Execution metadata ---

    private String correlationId;
    private String triggeredBy;
    private boolean dryRun;
    private Instant startedAt;
    private Instant completedAt;

    // --- Step tracking ---

    private final Map<String, StepState> stepStates = new ConcurrentHashMap<>();
    private final List<String> completedStepOrder = new ArrayList<>();

    // --- Signal support ---

    private final Map<String, SignalData> pendingSignals = new ConcurrentHashMap<>();
    private final Map<String, String> signalWaiters = new ConcurrentHashMap<>();

    // --- Timer support ---

    private final Map<String, TimerData> activeTimers = new ConcurrentHashMap<>();

    // --- Child workflow support ---

    private final Map<String, ChildWorkflowRef> childWorkflows = new ConcurrentHashMap<>();

    // --- Side effects ---

    private final Map<String, Object> sideEffects = new ConcurrentHashMap<>();

    // --- Search attributes ---

    private final Map<String, Object> searchAttributes = new ConcurrentHashMap<>();

    // --- Heartbeats ---

    private final Map<String, Map<String, Object>> lastHeartbeats = new ConcurrentHashMap<>();

    /**
     * Constructs a new WorkflowAggregate with the given ID.
     * <p>
     * Initializes the aggregate in PENDING status with an empty context map.
     *
     * @param id the unique identifier for this workflow instance
     */
    public WorkflowAggregate(UUID id) {
        super(id, "workflow");
        this.status = WorkflowStatus.PENDING;
        this.context = new HashMap<>();
    }

    // ========================================================================
    // Command Methods (validate state + applyChange)
    // ========================================================================

    /**
     * Starts the workflow execution.
     * <p>
     * This command is only valid when the workflow is in PENDING status.
     *
     * @param workflowId      the workflow definition identifier
     * @param workflowName    the human-readable workflow name
     * @param workflowVersion the version of the workflow definition
     * @param input           the input parameters for the workflow
     * @param correlationId   the correlation ID for distributed tracing
     * @param triggeredBy     the entity that triggered this execution
     * @param dryRun          whether this is a dry-run execution
     * @throws IllegalStateException if the workflow is not in PENDING status
     */
    public void start(String workflowId, String workflowName, String workflowVersion,
                      Map<String, Object> input, String correlationId,
                      String triggeredBy, boolean dryRun) {
        if (status != WorkflowStatus.PENDING) {
            throw new IllegalStateException(
                    "Cannot start workflow: current status is " + status + ", expected PENDING");
        }

        applyChange(WorkflowStartedEvent.builder()
                .aggregateId(getId())
                .workflowId(workflowId)
                .workflowName(workflowName)
                .workflowVersion(workflowVersion)
                .input(input)
                .correlationId(correlationId)
                .triggeredBy(triggeredBy)
                .dryRun(dryRun)
                .build());
    }

    /**
     * Completes the workflow execution with the given output.
     *
     * @param output the workflow output
     * @throws IllegalStateException if the workflow is in a terminal state
     */
    public void complete(Object output) {
        requireNonTerminal("complete");

        applyChange(WorkflowCompletedEvent.builder()
                .aggregateId(getId())
                .output(output)
                .durationMs(computeDurationMs())
                .build());
    }

    /**
     * Marks the workflow as failed.
     *
     * @param errorMessage the error message describing the failure
     * @param errorType    the type or class of the error
     * @param failedStepId the step where the failure occurred
     * @throws IllegalStateException if the workflow is in a terminal state
     */
    public void fail(String errorMessage, String errorType, String failedStepId) {
        requireNonTerminal("fail");

        applyChange(WorkflowFailedEvent.builder()
                .aggregateId(getId())
                .errorMessage(errorMessage)
                .errorType(errorType)
                .failedStepId(failedStepId)
                .build());
    }

    /**
     * Cancels the workflow execution.
     *
     * @param reason the reason for cancellation
     * @throws IllegalStateException if the workflow is in a terminal state
     */
    public void cancel(String reason) {
        requireNonTerminal("cancel");

        applyChange(WorkflowCancelledEvent.builder()
                .aggregateId(getId())
                .reason(reason)
                .build());
    }

    /**
     * Suspends the workflow execution.
     *
     * @param reason the reason for suspension
     * @throws IllegalStateException if the workflow cannot be suspended
     */
    public void suspend(String reason) {
        if (!status.canSuspend()) {
            throw new IllegalStateException(
                    "Cannot suspend workflow: current status is " + status);
        }

        applyChange(WorkflowSuspendedEvent.builder()
                .aggregateId(getId())
                .reason(reason)
                .build());
    }

    /**
     * Resumes a suspended workflow execution.
     *
     * @throws IllegalStateException if the workflow cannot be resumed
     */
    public void resume() {
        if (!status.canResume()) {
            throw new IllegalStateException(
                    "Cannot resume workflow: current status is " + status);
        }

        applyChange(WorkflowResumedEvent.builder()
                .aggregateId(getId())
                .build());
    }

    /**
     * Starts execution of a workflow step.
     *
     * @param stepId        the step identifier
     * @param stepName      the human-readable step name
     * @param input         the input parameters for the step
     * @param attemptNumber the current attempt number (1-based)
     */
    public void startStep(String stepId, String stepName, Map<String, Object> input,
                          int attemptNumber) {
        applyChange(StepStartedEvent.builder()
                .aggregateId(getId())
                .stepId(stepId)
                .stepName(stepName)
                .input(input)
                .attemptNumber(attemptNumber)
                .build());
    }

    /**
     * Completes a workflow step with the given output.
     *
     * @param stepId     the step identifier
     * @param output     the step output
     * @param durationMs the step execution duration in milliseconds
     */
    public void completeStep(String stepId, Object output, long durationMs) {
        applyChange(StepCompletedEvent.builder()
                .aggregateId(getId())
                .stepId(stepId)
                .output(output)
                .durationMs(durationMs)
                .build());
    }

    /**
     * Marks a workflow step as failed.
     *
     * @param stepId        the step identifier
     * @param errorMessage  the error message
     * @param errorType     the error type
     * @param attemptNumber the attempt number when failure occurred
     * @param retryable     whether the step can be retried
     */
    public void failStep(String stepId, String errorMessage, String errorType,
                         int attemptNumber, boolean retryable) {
        applyChange(StepFailedEvent.builder()
                .aggregateId(getId())
                .stepId(stepId)
                .errorMessage(errorMessage)
                .errorType(errorType)
                .attemptNumber(attemptNumber)
                .retryable(retryable)
                .build());
    }

    /**
     * Skips a workflow step.
     *
     * @param stepId the step identifier
     * @param reason the reason for skipping
     */
    public void skipStep(String stepId, String reason) {
        applyChange(StepSkippedEvent.builder()
                .aggregateId(getId())
                .stepId(stepId)
                .reason(reason)
                .build());
    }

    /**
     * Retries a workflow step.
     *
     * @param stepId        the step identifier
     * @param attemptNumber the new attempt number
     * @param delayMs       the delay before retry in milliseconds
     */
    public void retryStep(String stepId, int attemptNumber, long delayMs) {
        applyChange(StepRetriedEvent.builder()
                .aggregateId(getId())
                .stepId(stepId)
                .attemptNumber(attemptNumber)
                .delayMs(delayMs)
                .build());
    }

    /**
     * Receives an external signal.
     *
     * @param signalName the signal name
     * @param payload    the signal payload data
     * @throws IllegalStateException if the workflow is in a terminal state
     */
    public void receiveSignal(String signalName, Map<String, Object> payload) {
        requireNonTerminal("receive signal");

        applyChange(SignalReceivedEvent.builder()
                .aggregateId(getId())
                .signalName(signalName)
                .payload(payload)
                .build());
    }

    /**
     * Registers a durable timer.
     *
     * @param timerId the timer identifier
     * @param fireAt  the instant when the timer should fire
     * @param data    additional data associated with the timer
     * @throws IllegalStateException if the workflow is in a terminal state
     */
    public void registerTimer(String timerId, Instant fireAt, Map<String, Object> data) {
        requireNonTerminal("register timer");

        applyChange(TimerRegisteredEvent.builder()
                .aggregateId(getId())
                .timerId(timerId)
                .fireAt(fireAt)
                .data(data)
                .build());
    }

    /**
     * Fires a registered timer.
     *
     * @param timerId the timer identifier
     * @throws IllegalStateException if the timer does not exist
     */
    public void fireTimer(String timerId) {
        if (!activeTimers.containsKey(timerId)) {
            throw new IllegalStateException(
                    "Cannot fire timer: timer '" + timerId + "' does not exist");
        }

        applyChange(TimerFiredEvent.builder()
                .aggregateId(getId())
                .timerId(timerId)
                .build());
    }

    /**
     * Spawns a child workflow.
     *
     * @param childInstanceId the child workflow instance identifier
     * @param childWorkflowId the child workflow definition identifier
     * @param input           the input for the child workflow
     * @param parentStepId    the parent step that spawned the child
     * @throws IllegalStateException if the workflow is in a terminal state
     */
    public void spawnChildWorkflow(String childInstanceId, String childWorkflowId,
                                   Map<String, Object> input, String parentStepId) {
        requireNonTerminal("spawn child workflow");

        applyChange(ChildWorkflowSpawnedEvent.builder()
                .aggregateId(getId())
                .childInstanceId(childInstanceId)
                .childWorkflowId(childWorkflowId)
                .input(input)
                .parentStepId(parentStepId)
                .build());
    }

    /**
     * Completes a child workflow.
     *
     * @param childInstanceId the child workflow instance identifier
     * @param output          the child workflow output
     * @param success         whether the child completed successfully
     * @throws IllegalStateException if the workflow is in a terminal state
     */
    public void completeChildWorkflow(String childInstanceId, Object output, boolean success) {
        requireNonTerminal("complete child workflow");

        applyChange(ChildWorkflowCompletedEvent.builder()
                .aggregateId(getId())
                .childInstanceId(childInstanceId)
                .output(output)
                .success(success)
                .build());
    }

    /**
     * Records a side effect value for deterministic replay.
     *
     * @param sideEffectId the side effect identifier
     * @param value        the recorded value
     */
    public void recordSideEffect(String sideEffectId, Object value) {
        applyChange(SideEffectRecordedEvent.builder()
                .aggregateId(getId())
                .sideEffectId(sideEffectId)
                .value(value)
                .build());
    }

    /**
     * Retrieves a previously recorded side effect value.
     *
     * @param sideEffectId the side effect identifier
     * @return the recorded value, or empty if not found
     */
    public Optional<Object> getSideEffect(String sideEffectId) {
        return Optional.ofNullable(sideEffects.get(sideEffectId));
    }

    /**
     * Records a heartbeat for a long-running step.
     *
     * @param stepId  the step identifier
     * @param details the heartbeat details
     */
    public void heartbeat(String stepId, Map<String, Object> details) {
        applyChange(HeartbeatRecordedEvent.builder()
                .aggregateId(getId())
                .stepId(stepId)
                .details(details)
                .build());
    }

    /**
     * Continues the workflow as a new execution with fresh event history.
     *
     * @param newInput           the input for the new execution
     * @param completedRunOutput the output of the completed run
     * @throws IllegalStateException if the workflow is in a terminal state
     */
    public void continueAsNew(Map<String, Object> newInput, Object completedRunOutput) {
        requireNonTerminal("continue as new");

        applyChange(ContinueAsNewEvent.builder()
                .aggregateId(getId())
                .newInput(newInput)
                .completedRunOutput(completedRunOutput)
                .previousRunId(getId().toString())
                .build());
    }

    /**
     * Starts compensation (saga rollback) for the workflow.
     *
     * @param failedStepId       the step whose failure triggered compensation
     * @param compensationPolicy the compensation policy to apply
     */
    public void startCompensation(String failedStepId, String compensationPolicy) {
        applyChange(CompensationStartedEvent.builder()
                .aggregateId(getId())
                .failedStepId(failedStepId)
                .compensationPolicy(compensationPolicy)
                .build());
    }

    /**
     * Completes a compensation step.
     *
     * @param stepId       the step identifier
     * @param success      whether the compensation was successful
     * @param errorMessage the error message if compensation failed
     */
    public void completeCompensationStep(String stepId, boolean success, String errorMessage) {
        applyChange(CompensationStepCompletedEvent.builder()
                .aggregateId(getId())
                .stepId(stepId)
                .success(success)
                .errorMessage(errorMessage)
                .build());
    }

    /**
     * Updates or inserts a search attribute.
     *
     * @param key   the search attribute key
     * @param value the search attribute value
     */
    public void upsertSearchAttribute(String key, Object value) {
        applyChange(SearchAttributeUpdatedEvent.builder()
                .aggregateId(getId())
                .key(key)
                .value(value)
                .build());
    }

    // ========================================================================
    // Event Handlers (private on() methods — pure state mutations)
    // ========================================================================

    @SuppressWarnings("unused")
    private void on(WorkflowStartedEvent event) {
        this.workflowId = event.getWorkflowId();
        this.workflowName = event.getWorkflowName();
        this.workflowVersion = event.getWorkflowVersion();
        this.input = event.getInput() != null ? new HashMap<>(event.getInput()) : new HashMap<>();
        this.correlationId = event.getCorrelationId();
        this.triggeredBy = event.getTriggeredBy();
        this.dryRun = event.isDryRun();
        this.status = WorkflowStatus.RUNNING;
        this.startedAt = event.getEventTimestamp();
    }

    @SuppressWarnings("unused")
    private void on(WorkflowCompletedEvent event) {
        this.status = WorkflowStatus.COMPLETED;
        this.output = event.getOutput();
        this.completedAt = event.getEventTimestamp();
    }

    @SuppressWarnings("unused")
    private void on(WorkflowFailedEvent event) {
        this.status = WorkflowStatus.FAILED;
        this.completedAt = event.getEventTimestamp();
    }

    @SuppressWarnings("unused")
    private void on(WorkflowCancelledEvent event) {
        this.status = WorkflowStatus.CANCELLED;
        this.completedAt = event.getEventTimestamp();
    }

    @SuppressWarnings("unused")
    private void on(WorkflowSuspendedEvent event) {
        this.status = WorkflowStatus.SUSPENDED;
    }

    @SuppressWarnings("unused")
    private void on(WorkflowResumedEvent event) {
        this.status = WorkflowStatus.RUNNING;
    }

    @SuppressWarnings("unused")
    private void on(StepStartedEvent event) {
        this.currentStepId = event.getStepId();
        this.stepStates.put(event.getStepId(), new StepState(
                event.getStepId(),
                event.getStepName(),
                StepStatus.RUNNING,
                event.getAttemptNumber(),
                event.getInput(),
                null,
                null,
                event.getEventTimestamp(),
                null));
    }

    @SuppressWarnings("unused")
    private void on(StepCompletedEvent event) {
        StepState existing = stepStates.get(event.getStepId());
        if (existing != null) {
            stepStates.put(event.getStepId(), existing.complete(event.getOutput(), event.getEventTimestamp()));
        }
        completedStepOrder.add(event.getStepId());

        // Merge output map into context
        if (event.getOutput() instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> outputMap = (Map<String, Object>) event.getOutput();
            context.putAll(outputMap);
        }

        // Store step output as stepId.output in context
        context.put(event.getStepId() + ".output", event.getOutput());
    }

    @SuppressWarnings("unused")
    private void on(StepFailedEvent event) {
        StepState existing = stepStates.get(event.getStepId());
        if (existing != null) {
            stepStates.put(event.getStepId(), existing.fail(event.getErrorMessage(), event.getEventTimestamp()));
        }
    }

    @SuppressWarnings("unused")
    private void on(StepSkippedEvent event) {
        stepStates.put(event.getStepId(), new StepState(
                event.getStepId(),
                null,
                StepStatus.SKIPPED,
                0,
                null,
                null,
                event.getReason(),
                null,
                event.getEventTimestamp()));
    }

    @SuppressWarnings("unused")
    private void on(StepRetriedEvent event) {
        StepState existing = stepStates.get(event.getStepId());
        if (existing != null) {
            stepStates.put(event.getStepId(), existing.retry(event.getAttemptNumber()));
        }
    }

    @SuppressWarnings("unused")
    private void on(SignalReceivedEvent event) {
        pendingSignals.put(event.getSignalName(), new SignalData(
                event.getSignalName(),
                event.getPayload(),
                event.getEventTimestamp()));
    }

    @SuppressWarnings("unused")
    private void on(TimerRegisteredEvent event) {
        activeTimers.put(event.getTimerId(), new TimerData(
                event.getTimerId(),
                event.getFireAt(),
                event.getData()));
    }

    @SuppressWarnings("unused")
    private void on(TimerFiredEvent event) {
        activeTimers.remove(event.getTimerId());
    }

    @SuppressWarnings("unused")
    private void on(ChildWorkflowSpawnedEvent event) {
        childWorkflows.put(event.getChildInstanceId(), new ChildWorkflowRef(
                event.getChildInstanceId(),
                event.getChildWorkflowId(),
                event.getParentStepId(),
                false,
                null));
    }

    @SuppressWarnings("unused")
    private void on(ChildWorkflowCompletedEvent event) {
        ChildWorkflowRef existing = childWorkflows.get(event.getChildInstanceId());
        if (existing != null) {
            childWorkflows.put(event.getChildInstanceId(),
                    existing.complete(event.getOutput(), event.isSuccess()));
        }
    }

    @SuppressWarnings("unused")
    private void on(SideEffectRecordedEvent event) {
        sideEffects.put(event.getSideEffectId(), event.getValue());
    }

    @SuppressWarnings("unused")
    private void on(HeartbeatRecordedEvent event) {
        lastHeartbeats.put(event.getStepId(),
                event.getDetails() != null ? new HashMap<>(event.getDetails()) : new HashMap<>());
    }

    @SuppressWarnings("unused")
    private void on(ContinueAsNewEvent event) {
        this.status = WorkflowStatus.COMPLETED;
        this.output = event.getCompletedRunOutput();
        this.completedAt = event.getEventTimestamp();
    }

    @SuppressWarnings("unused")
    private void on(CompensationStartedEvent event) {
        // Compensation started — state tracked externally by orchestrator
        log.debug("Compensation started for workflow {}, failed step: {}, policy: {}",
                getId(), event.getFailedStepId(), event.getCompensationPolicy());
    }

    @SuppressWarnings("unused")
    private void on(CompensationStepCompletedEvent event) {
        log.debug("Compensation step completed: stepId={}, success={}", event.getStepId(), event.isSuccess());
    }

    @SuppressWarnings("unused")
    private void on(SearchAttributeUpdatedEvent event) {
        searchAttributes.put(event.getKey(), event.getValue());
    }

    // ========================================================================
    // Guard / Helper Methods
    // ========================================================================

    /**
     * Ensures the workflow is not in a terminal state before executing a command.
     *
     * @param action the action being attempted
     * @throws IllegalStateException if the workflow is in a terminal state
     */
    private void requireNonTerminal(String action) {
        if (status.isTerminal()) {
            throw new IllegalStateException(
                    "Cannot " + action + " workflow: current status is " + status + " (terminal)");
        }
    }

    /**
     * Computes the duration since the workflow started.
     *
     * @return duration in milliseconds, or 0 if not started
     */
    private long computeDurationMs() {
        if (startedAt == null) {
            return 0L;
        }
        return Instant.now().toEpochMilli() - startedAt.toEpochMilli();
    }

    // ========================================================================
    // Inner Types
    // ========================================================================

    /**
     * Represents the state of a workflow step.
     */
    public record StepState(
            String stepId,
            String stepName,
            StepStatus status,
            int attemptNumber,
            Map<String, Object> input,
            Object output,
            String errorOrReason,
            Instant startedAt,
            Instant completedAt) {

        /**
         * Creates a new StepState representing successful completion.
         *
         * @param output      the step output
         * @param completedAt the completion timestamp
         * @return a new StepState with COMPLETED status
         */
        public StepState complete(Object output, Instant completedAt) {
            return new StepState(stepId, stepName, StepStatus.COMPLETED,
                    attemptNumber, input, output, null, startedAt, completedAt);
        }

        /**
         * Creates a new StepState representing a failure.
         *
         * @param error       the error message
         * @param completedAt the failure timestamp
         * @return a new StepState with FAILED status
         */
        public StepState fail(String error, Instant completedAt) {
            return new StepState(stepId, stepName, StepStatus.FAILED,
                    attemptNumber, input, null, error, startedAt, completedAt);
        }

        /**
         * Creates a new StepState representing a retry.
         *
         * @param newAttemptNumber the new attempt number
         * @return a new StepState with RETRYING status
         */
        public StepState retry(int newAttemptNumber) {
            return new StepState(stepId, stepName, StepStatus.RETRYING,
                    newAttemptNumber, input, null, null, startedAt, null);
        }
    }

    /**
     * Represents a buffered signal with its payload.
     */
    public record SignalData(String signalName, Map<String, Object> payload, Instant receivedAt) {
    }

    /**
     * Represents a registered durable timer.
     */
    public record TimerData(String timerId, Instant fireAt, Map<String, Object> data) {
    }

    /**
     * Represents a reference to a child workflow.
     */
    public record ChildWorkflowRef(
            String childInstanceId,
            String childWorkflowId,
            String parentStepId,
            boolean completed,
            Object output) {

        /**
         * Creates a new ChildWorkflowRef representing completion.
         *
         * @param output  the child workflow output
         * @param success whether the child completed successfully
         * @return a new ChildWorkflowRef marked as completed
         */
        public ChildWorkflowRef complete(Object output, boolean success) {
            return new ChildWorkflowRef(childInstanceId, childWorkflowId,
                    parentStepId, true, output);
        }
    }
}
