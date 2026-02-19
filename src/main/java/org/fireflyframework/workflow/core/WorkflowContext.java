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

package org.fireflyframework.workflow.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.model.StepExecution;
import org.fireflyframework.workflow.model.WorkflowDefinition;
import org.fireflyframework.workflow.model.WorkflowInstance;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Context object passed to workflow steps during execution.
 * <p>
 * The WorkflowContext provides access to:
 * <ul>
 *   <li>Workflow input data</li>
 *   <li>Step-to-step shared data (context)</li>
 *   <li>Previous step outputs</li>
 *   <li>Workflow and step metadata</li>
 * </ul>
 * <p>
 * Steps can add data to the context for use by subsequent steps.
 */
@Slf4j
@Getter
public class WorkflowContext {

    private final WorkflowDefinition workflowDefinition;
    private final WorkflowInstance workflowInstance;
    private final String currentStepId;
    private final Map<String, Object> localContext;
    private final ObjectMapper objectMapper;
    private final boolean dryRun;

    @Nullable
    private final WorkflowAggregate aggregate;

    public WorkflowContext(
            WorkflowDefinition workflowDefinition,
            WorkflowInstance workflowInstance,
            String currentStepId,
            ObjectMapper objectMapper) {
        this(workflowDefinition, workflowInstance, currentStepId, objectMapper, false, null);
    }

    public WorkflowContext(
            WorkflowDefinition workflowDefinition,
            WorkflowInstance workflowInstance,
            String currentStepId,
            ObjectMapper objectMapper,
            boolean dryRun) {
        this(workflowDefinition, workflowInstance, currentStepId, objectMapper, dryRun, null);
    }

    /**
     * Full constructor including optional aggregate for durable execution mode.
     *
     * @param workflowDefinition the workflow definition
     * @param workflowInstance   the workflow instance
     * @param currentStepId      the current step identifier
     * @param objectMapper       the Jackson object mapper
     * @param dryRun             whether this is a dry-run execution
     * @param aggregate          the event-sourced aggregate (null for cache-only mode)
     */
    public WorkflowContext(
            WorkflowDefinition workflowDefinition,
            WorkflowInstance workflowInstance,
            String currentStepId,
            ObjectMapper objectMapper,
            boolean dryRun,
            @Nullable WorkflowAggregate aggregate) {
        this.workflowDefinition = workflowDefinition;
        this.workflowInstance = workflowInstance;
        this.currentStepId = currentStepId;
        this.localContext = new HashMap<>(workflowInstance.context());
        this.objectMapper = objectMapper;
        this.dryRun = dryRun;
        this.aggregate = aggregate;
    }

    /**
     * Checks if the workflow is running in dry-run mode.
     * <p>
     * In dry-run mode, steps should skip actual side effects like:
     * <ul>
     *   <li>External API calls</li>
     *   <li>Database writes</li>
     *   <li>Message publishing</li>
     *   <li>File system modifications</li>
     * </ul>
     * <p>
     * Steps can use this to return mock data for testing and validation.
     *
     * @return true if running in dry-run mode
     */
    public boolean isDryRun() {
        return dryRun;
    }

    /**
     * Gets the workflow instance ID.
     */
    public String getInstanceId() {
        return workflowInstance.instanceId();
    }

    /**
     * Gets the workflow ID.
     */
    public String getWorkflowId() {
        return workflowInstance.workflowId();
    }

    /**
     * Gets the correlation ID.
     */
    public String getCorrelationId() {
        return workflowInstance.correlationId();
    }

    /**
     * Gets a value from the workflow input.
     *
     * @param key the input key
     * @return optional containing the value if present
     */
    public Optional<Object> getInput(String key) {
        return Optional.ofNullable(workflowInstance.input().get(key));
    }

    /**
     * Gets a typed value from the workflow input.
     *
     * @param key the input key
     * @param type the expected type
     * @param <T> the type parameter
     * @return the typed value or null if not present
     */
    public <T> T getInput(String key, Class<T> type) {
        Object value = workflowInstance.input().get(key);
        if (value == null) {
            return null;
        }
        return convertValue(value, type);
    }

    /**
     * Gets all workflow inputs.
     */
    public Map<String, Object> getAllInputs() {
        return Map.copyOf(workflowInstance.input());
    }

    /**
     * Gets all workflow inputs (alias for getAllInputs).
     */
    public Map<String, Object> getAllInput() {
        return getAllInputs();
    }

    /**
     * Gets a value from the shared context.
     *
     * @param key the context key
     * @return optional containing the value if present
     */
    public Optional<Object> get(String key) {
        return Optional.ofNullable(localContext.get(key));
    }

    /**
     * Gets a typed value from the shared context.
     *
     * @param key the context key
     * @param type the expected type
     * @param <T> the type parameter
     * @return the typed value or null if not present
     */
    public <T> T get(String key, Class<T> type) {
        Object value = localContext.get(key);
        if (value == null) {
            return null;
        }
        return convertValue(value, type);
    }

    /**
     * Gets a value with a default if not present.
     *
     * @param key the context key
     * @param defaultValue default value to return
     * @param <T> the type parameter
     * @return the value or default
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String key, T defaultValue) {
        Object value = localContext.get(key);
        return value != null ? (T) value : defaultValue;
    }

    /**
     * Sets a value in the shared context.
     *
     * @param key the context key
     * @param value the value to set
     */
    public void set(String key, Object value) {
        localContext.put(key, value);
    }

    /**
     * Removes a value from the shared context.
     *
     * @param key the context key
     */
    public void remove(String key) {
        localContext.remove(key);
    }

    /**
     * Checks if a key exists in the context.
     *
     * @param key the context key
     * @return true if the key exists
     */
    public boolean has(String key) {
        return localContext.containsKey(key);
    }

    /**
     * Gets the output from a previous step.
     *
     * @param stepId the step ID
     * @return optional containing the output if available
     */
    public Optional<Object> getStepOutput(String stepId) {
        return workflowInstance.getStepExecution(stepId)
                .map(StepExecution::output);
    }

    /**
     * Gets typed output from a previous step.
     *
     * @param stepId the step ID
     * @param type the expected type
     * @param <T> the type parameter
     * @return the typed output or null if not available
     */
    public <T> T getStepOutput(String stepId, Class<T> type) {
        return workflowInstance.getStepExecution(stepId)
                .map(StepExecution::output)
                .map(output -> convertValue(output, type))
                .orElse(null);
    }

    /**
     * Gets all data from the context.
     */
    public Map<String, Object> getAllData() {
        return Map.copyOf(localContext);
    }

    /**
     * Merges additional data into the context.
     *
     * @param data the data to merge
     */
    public void merge(Map<String, Object> data) {
        if (data != null) {
            localContext.putAll(data);
        }
    }

    /**
     * Creates a copy of this context for the next step.
     *
     * @param nextStepId the next step ID
     * @param updatedInstance the updated workflow instance
     * @return new context for the next step
     */
    public WorkflowContext forNextStep(String nextStepId, WorkflowInstance updatedInstance) {
        WorkflowContext next = new WorkflowContext(
                workflowDefinition,
                updatedInstance.withContext(localContext),
                nextStepId,
                objectMapper,
                dryRun,
                aggregate
        );
        next.localContext.putAll(this.localContext);
        return next;
    }

    // ========================================================================
    // Durable Execution Methods
    // ========================================================================

    /**
     * Executes a side effect with deterministic replay support.
     * <p>
     * On first execution, the supplier is called and the result is recorded
     * on the aggregate for future replay. On subsequent replays, the stored
     * value is returned without calling the supplier, ensuring deterministic
     * execution across replays.
     * <p>
     * If no aggregate is available (cache-only mode), the supplier is called
     * directly without recording.
     *
     * @param id       the unique identifier for this side effect
     * @param supplier the supplier that produces the side effect value
     * @param <T>      the type of the side effect value
     * @return the side effect value (either fresh or replayed)
     */
    @SuppressWarnings("unchecked")
    public <T> T sideEffect(String id, Supplier<T> supplier) {
        if (aggregate == null) {
            return supplier.get();
        }

        Optional<Object> stored = aggregate.getSideEffect(id);
        if (stored.isPresent()) {
            return (T) stored.get();
        }

        T value = supplier.get();
        aggregate.recordSideEffect(id, value);
        return value;
    }

    /**
     * Records a heartbeat for the current step.
     * <p>
     * Heartbeats are used to report progress for long-running steps and to
     * detect liveness. If no aggregate is available, the heartbeat is a no-op
     * (logged at debug level).
     *
     * @param details the heartbeat details (e.g., progress percentage, status message)
     */
    public void heartbeat(Map<String, Object> details) {
        if (aggregate != null) {
            aggregate.heartbeat(currentStepId, details);
        } else {
            log.debug("Heartbeat ignored (no aggregate): stepId={}, details={}", currentStepId, details);
        }
    }

    /**
     * Spawns a child workflow from the current step.
     * <p>
     * In durable execution mode, the child workflow is recorded on the aggregate
     * and a unique child instance ID is generated. In cache-only mode (no aggregate),
     * this operation is not supported and returns an error.
     *
     * @param workflowId the child workflow definition identifier
     * @param input      the input for the child workflow
     * @return a Mono that completes when the child workflow is spawned
     */
    public Mono<Void> startChildWorkflow(String workflowId, Map<String, Object> input) {
        if (aggregate == null) {
            return Mono.error(new UnsupportedOperationException(
                    "Child workflows require durable execution mode (event-sourced aggregate)"));
        }

        String childInstanceId = UUID.randomUUID().toString();
        aggregate.spawnChildWorkflow(childInstanceId, workflowId, input, currentStepId);
        return Mono.empty();
    }

    /**
     * Converts a value to the specified type.
     */
    @SuppressWarnings("unchecked")
    private <T> T convertValue(Object value, Class<T> type) {
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return (T) value;
        }
        try {
            return objectMapper.convertValue(value, type);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Cannot convert value of type " + value.getClass() + " to " + type, e);
        }
    }
}
