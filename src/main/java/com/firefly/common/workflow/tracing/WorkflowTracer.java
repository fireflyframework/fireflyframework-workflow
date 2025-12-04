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

package com.firefly.common.workflow.tracing;

import com.firefly.common.workflow.model.StepExecution;
import com.firefly.common.workflow.model.WorkflowInstance;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Mono;

import java.util.function.Supplier;

/**
 * Provides OpenTelemetry tracing support for workflow execution.
 * <p>
 * This class wraps workflow and step executions with Micrometer Observations,
 * which are automatically bridged to OpenTelemetry spans when the OTEL bridge
 * is configured.
 * <p>
 * Key features:
 * <ul>
 *   <li>Creates parent spans for workflow execution</li>
 *   <li>Creates child spans for each step execution</li>
 *   <li>Adds workflow/step metadata as span attributes</li>
 *   <li>Propagates trace context through reactive chains</li>
 *   <li>Records errors and timing information</li>
 * </ul>
 */
@Slf4j
public class WorkflowTracer {

    private static final String WORKFLOW_SPAN_NAME = "workflow.execute";
    private static final String STEP_SPAN_NAME = "workflow.step.execute";

    private final ObservationRegistry observationRegistry;

    public WorkflowTracer(@Nullable ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry != null 
                ? observationRegistry 
                : ObservationRegistry.NOOP;
    }

    /**
     * Wraps a workflow execution with tracing.
     *
     * @param instance the workflow instance
     * @param execution the workflow execution mono
     * @param <T> the result type
     * @return the traced mono
     */
    public <T> Mono<T> traceWorkflow(WorkflowInstance instance, Mono<T> execution) {
        if (observationRegistry == ObservationRegistry.NOOP) {
            return execution;
        }

        return Mono.deferContextual(ctx -> {
            Observation observation = Observation.createNotStarted(WORKFLOW_SPAN_NAME, observationRegistry)
                    .lowCardinalityKeyValue("workflow.id", instance.workflowId())
                    .lowCardinalityKeyValue("workflow.status", instance.status().name())
                    .highCardinalityKeyValue("workflow.instance.id", instance.instanceId())
                    .highCardinalityKeyValue("workflow.correlation.id", 
                            instance.correlationId() != null ? instance.correlationId() : "")
                    .highCardinalityKeyValue("workflow.triggered.by", 
                            instance.triggeredBy() != null ? instance.triggeredBy() : "");

            log.debug("Starting workflow trace: workflowId={}, instanceId={}", 
                    instance.workflowId(), instance.instanceId());

            return execution
                    .doOnSubscribe(s -> observation.start())
                    .doOnSuccess(result -> {
                        observation.lowCardinalityKeyValue("workflow.outcome", "success");
                        observation.stop();
                        log.debug("Workflow trace completed: workflowId={}, instanceId={}", 
                                instance.workflowId(), instance.instanceId());
                    })
                    .doOnError(error -> {
                        observation.lowCardinalityKeyValue("workflow.outcome", "error");
                        observation.error(error);
                        observation.stop();
                        log.debug("Workflow trace failed: workflowId={}, instanceId={}, error={}", 
                                instance.workflowId(), instance.instanceId(), error.getMessage());
                    });
        });
    }

    /**
     * Wraps a step execution with tracing.
     *
     * @param instance the workflow instance
     * @param stepId the step ID
     * @param stepName the step name
     * @param triggeredBy what triggered this step
     * @param execution the step execution mono
     * @param <T> the result type
     * @return the traced mono
     */
    public <T> Mono<T> traceStep(
            WorkflowInstance instance,
            String stepId,
            String stepName,
            String triggeredBy,
            Mono<T> execution) {
        
        if (observationRegistry == ObservationRegistry.NOOP) {
            return execution;
        }

        return Mono.deferContextual(ctx -> {
            Observation observation = Observation.createNotStarted(STEP_SPAN_NAME, observationRegistry)
                    .lowCardinalityKeyValue("workflow.id", instance.workflowId())
                    .lowCardinalityKeyValue("step.id", stepId)
                    .lowCardinalityKeyValue("step.name", stepName)
                    .highCardinalityKeyValue("workflow.instance.id", instance.instanceId())
                    .highCardinalityKeyValue("step.triggered.by", triggeredBy != null ? triggeredBy : "");

            log.debug("Starting step trace: workflowId={}, instanceId={}, stepId={}, triggeredBy={}", 
                    instance.workflowId(), instance.instanceId(), stepId, triggeredBy);

            return execution
                    .doOnSubscribe(s -> observation.start())
                    .doOnSuccess(result -> {
                        observation.lowCardinalityKeyValue("step.outcome", "success");
                        observation.stop();
                        log.debug("Step trace completed: workflowId={}, instanceId={}, stepId={}", 
                                instance.workflowId(), instance.instanceId(), stepId);
                    })
                    .doOnError(error -> {
                        observation.lowCardinalityKeyValue("step.outcome", "error");
                        observation.error(error);
                        observation.stop();
                        log.debug("Step trace failed: workflowId={}, instanceId={}, stepId={}, error={}", 
                                instance.workflowId(), instance.instanceId(), stepId, error.getMessage());
                    });
        });
    }
}

