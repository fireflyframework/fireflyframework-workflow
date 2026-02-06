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
import org.fireflyframework.workflow.model.StepExecution;
import org.fireflyframework.workflow.model.WorkflowDefinition;
import org.fireflyframework.workflow.model.WorkflowInstance;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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
@Getter
public class WorkflowContext {

    private final WorkflowDefinition workflowDefinition;
    private final WorkflowInstance workflowInstance;
    private final String currentStepId;
    private final Map<String, Object> localContext;
    private final ObjectMapper objectMapper;
    private final boolean dryRun;

    public WorkflowContext(
            WorkflowDefinition workflowDefinition,
            WorkflowInstance workflowInstance,
            String currentStepId,
            ObjectMapper objectMapper) {
        this(workflowDefinition, workflowInstance, currentStepId, objectMapper, false);
    }

    public WorkflowContext(
            WorkflowDefinition workflowDefinition,
            WorkflowInstance workflowInstance,
            String currentStepId,
            ObjectMapper objectMapper,
            boolean dryRun) {
        this.workflowDefinition = workflowDefinition;
        this.workflowInstance = workflowInstance;
        this.currentStepId = currentStepId;
        this.localContext = new HashMap<>(workflowInstance.context());
        this.objectMapper = objectMapper;
        this.dryRun = dryRun;
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
                dryRun
        );
        next.localContext.putAll(this.localContext);
        return next;
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
