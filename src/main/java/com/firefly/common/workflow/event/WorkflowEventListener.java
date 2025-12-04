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

package com.firefly.common.workflow.event;

import com.firefly.common.eda.event.EventEnvelope;
import com.firefly.common.workflow.core.WorkflowEngine;
import com.firefly.common.workflow.model.*;
import com.firefly.common.workflow.properties.WorkflowProperties;
import com.firefly.common.workflow.state.StepStateStore;
import com.firefly.common.workflow.state.WorkflowStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Listens for events that can trigger workflows and individual steps.
 * <p>
 * This component integrates with lib-common-eda to receive events and:
 * <ul>
 *   <li>Automatically start workflow instances based on trigger configurations</li>
 *   <li>Trigger individual steps based on their inputEventType (step-level choreography)</li>
 *   <li>Resume waiting workflows/steps when matching events are received</li>
 * </ul>
 */
@Slf4j
public class WorkflowEventListener {

    private final WorkflowEngine workflowEngine;
    private final WorkflowProperties properties;
    private final WorkflowStateStore stateStore;
    private final StepStateStore stepStateStore;
    private final Map<String, Pattern> triggerPatterns = new HashMap<>();

    public WorkflowEventListener(
            WorkflowEngine workflowEngine, 
            WorkflowProperties properties,
            WorkflowStateStore stateStore) {
        this(workflowEngine, properties, stateStore, null);
    }

    public WorkflowEventListener(
            WorkflowEngine workflowEngine, 
            WorkflowProperties properties,
            WorkflowStateStore stateStore,
            @Nullable StepStateStore stepStateStore) {
        this.workflowEngine = workflowEngine;
        this.properties = properties;
        this.stateStore = stateStore;
        this.stepStateStore = stepStateStore;
        
        log.info("WorkflowEventListener initialized with step-level choreography: {}", stepStateStore != null);
    }

    /**
     * Handles incoming events from the EDA system.
     * <p>
     * This method is called by the EDA listener aspect when events are received.
     * It checks:
     * <ol>
     *   <li>If any waiting workflows/steps should be resumed</li>
     *   <li>If any step should be triggered by this event (step-level choreography)</li>
     *   <li>If any workflow should be started by this event</li>
     * </ol>
     *
     * @param envelope the event envelope
     * @return a Mono that completes when processing is done
     */
    @EventListener
    public Mono<Void> handleEvent(EventEnvelope envelope) {
        if (envelope == null || envelope.eventType() == null) {
            return Mono.empty();
        }

        String eventType = envelope.eventType();
        log.debug("Received event: type={}, destination={}", eventType, envelope.destination());

        // 1. Resume any waiting workflows or steps
        Mono<Void> resumeWaiting = resumeWaitingWorkflows(envelope);
        Mono<Void> resumeWaitingSteps = resumeWaitingSteps(envelope);
        
        // 2. Trigger individual steps by inputEventType (step-level choreography)
        Mono<Void> triggerSteps = triggerStepsByEvent(envelope);
        
        // 3. Find workflows that can be triggered by this event
        Mono<Void> triggerNew = workflowEngine.findWorkflowsByTriggerEvent(eventType)
                .flatMap(workflow -> triggerWorkflow(workflow, envelope))
                .then();

        return resumeWaiting
                .then(resumeWaitingSteps)
                .then(triggerSteps)
                .then(triggerNew)
                .doOnError(e -> log.error("Error processing event: type={}", eventType, e))
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * Triggers individual steps based on their inputEventType.
     * <p>
     * This enables step-level choreography where steps can be independently
     * triggered by events, not just entire workflows.
     */
    private Mono<Void> triggerStepsByEvent(EventEnvelope envelope) {
        String eventType = envelope.eventType();
        List<WorkflowEngine.WorkflowStepMatch> matches = workflowEngine.findStepsByInputEvent(eventType);
        
        if (matches.isEmpty()) {
            return Mono.empty();
        }
        
        Map<String, Object> input = buildInputFromEvent(envelope);
        String correlationId = extractCorrelationId(envelope);
        String triggeredBy = "event:" + eventType;
        
        return Flux.fromIterable(matches)
                .flatMap(match -> triggerStepFromEvent(match, input, correlationId, triggeredBy))
                .then();
    }

    /**
     * Triggers a specific step from an event match.
     * <p>
     * For step-level choreography, we need to either:
     * - Find an existing workflow instance and trigger the step
     * - Create a new workflow instance if none exists and the workflow supports async trigger
     */
    private Mono<Void> triggerStepFromEvent(
            WorkflowEngine.WorkflowStepMatch match,
            Map<String, Object> input,
            String correlationId,
            String triggeredBy) {
        
        WorkflowDefinition workflow = match.workflow();
        WorkflowStepDefinition step = match.step();
        
        log.info("Triggering step from event: workflowId={}, stepId={}, triggeredBy={}",
                workflow.workflowId(), step.stepId(), triggeredBy);
        
        // First, try to find an existing instance by correlation ID
        if (correlationId != null && !correlationId.isEmpty()) {
            return stateStore.findByCorrelationId(correlationId)
                    .filter(instance -> instance.workflowId().equals(workflow.workflowId()))
                    .filter(instance -> !instance.status().isTerminal())
                    .next()
                    .flatMap(instance -> workflowEngine.triggerStep(
                            workflow.workflowId(),
                            instance.instanceId(),
                            step.stepId(),
                            input,
                            triggeredBy))
                    .switchIfEmpty(Mono.defer(() -> createInstanceAndTriggerStep(
                            workflow, step, input, correlationId, triggeredBy)))
                    .then();
        }
        
        // No correlation ID - create a new instance if workflow supports async trigger
        return createInstanceAndTriggerStep(workflow, step, input, correlationId, triggeredBy)
                .then();
    }

    /**
     * Creates a new workflow instance and triggers the specified step.
     */
    private Mono<WorkflowInstance> createInstanceAndTriggerStep(
            WorkflowDefinition workflow,
            WorkflowStepDefinition step,
            Map<String, Object> input,
            String correlationId,
            String triggeredBy) {
        
        if (!workflow.supportsAsyncTrigger()) {
            log.debug("Workflow {} does not support async trigger for step {}", 
                    workflow.workflowId(), step.stepId());
            return Mono.empty();
        }
        
        log.info("Creating new workflow instance and triggering step: workflowId={}, stepId={}",
                workflow.workflowId(), step.stepId());
        
        // Start workflow and then trigger the specific step
        return workflowEngine.startWorkflow(
                workflow.workflowId(),
                input,
                correlationId,
                triggeredBy);
    }

    /**
     * Resumes steps that are waiting for a specific event.
     */
    private Mono<Void> resumeWaitingSteps(EventEnvelope envelope) {
        if (stepStateStore == null) {
            return Mono.empty();
        }
        
        String eventType = envelope.eventType();
        Map<String, Object> eventData = buildInputFromEvent(envelope);
        String triggeredBy = "event:" + eventType;
        
        return stepStateStore.findStepsWaitingForEvent(eventType)
                .flatMap(stepState -> resumeWaitingStep(stepState, eventData, triggeredBy))
                .then();
    }

    /**
     * Resumes a waiting step with event data.
     */
    private Mono<Void> resumeWaitingStep(
            StepState stepState, 
            Map<String, Object> eventData, 
            String triggeredBy) {
        
        log.info("Resuming waiting step: workflowId={}, instanceId={}, stepId={}",
                stepState.workflowId(), stepState.instanceId(), stepState.stepId());
        
        return workflowEngine.triggerStep(
                        stepState.workflowId(),
                        stepState.instanceId(),
                        stepState.stepId(),
                        eventData,
                        triggeredBy)
                .then();
    }
    
    /**
     * Resumes workflows that are waiting for a specific event.
     */
    private Mono<Void> resumeWaitingWorkflows(EventEnvelope envelope) {
        String eventType = envelope.eventType();
        String correlationId = extractCorrelationId(envelope);
        
        // Find waiting workflows by correlation ID or event type
        return stateStore.findByStatus(WorkflowStatus.WAITING)
                .filter(instance -> shouldResumeWorkflow(instance, envelope))
                .flatMap(instance -> resumeWorkflow(instance, envelope))
                .then();
    }
    
    /**
     * Checks if a waiting workflow should be resumed by this event.
     */
    private boolean shouldResumeWorkflow(WorkflowInstance instance, EventEnvelope envelope) {
        // Check correlation ID match
        String correlationId = extractCorrelationId(envelope);
        if (correlationId != null && correlationId.equals(instance.correlationId())) {
            return true;
        }
        
        // Check if instance is waiting for this specific event type
        Object waitingFor = instance.context().get("_waitingForEvent");
        if (waitingFor != null && matchesTrigger(envelope.eventType(), waitingFor.toString())) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Resumes a waiting workflow with event data.
     */
    private Mono<Void> resumeWorkflow(WorkflowInstance instance, EventEnvelope envelope) {
        log.info("Resuming waiting workflow {} with event {}", 
                instance.instanceId(), envelope.eventType());
        
        // Build input from event
        Map<String, Object> eventData = buildInputFromEvent(envelope);
        
        // Update instance context with event data and resume
        WorkflowInstance updated = instance
                .withContext("_resumeEvent", envelope.eventType())
                .withContext("_resumeEventData", eventData);
        
        // The workflow engine will need to handle resumption
        // For now, we update the instance to RUNNING and let the executor pick it up
        WorkflowInstance running = new WorkflowInstance(
                updated.instanceId(),
                updated.workflowId(),
                updated.workflowName(),
                updated.workflowVersion(),
                WorkflowStatus.RUNNING,
                updated.currentStepId(),
                updated.context(),
                updated.input(),
                updated.output(),
                updated.stepExecutions(),
                null, null,
                updated.correlationId(),
                updated.triggeredBy(),
                updated.createdAt(),
                updated.startedAt(),
                null
        );
        
        return stateStore.save(running)
                .doOnSuccess(saved -> log.info("Resumed workflow {}", saved.instanceId()))
                .then();
    }

    /**
     * Triggers a workflow from an event.
     */
    private Mono<Void> triggerWorkflow(WorkflowDefinition workflow, EventEnvelope envelope) {
        if (!workflow.supportsAsyncTrigger()) {
            log.debug("Workflow {} does not support async trigger, skipping", workflow.workflowId());
            return Mono.empty();
        }

        // Build input from event
        Map<String, Object> input = buildInputFromEvent(envelope);
        
        // Extract correlation ID from event headers
        String correlationId = extractCorrelationId(envelope);

        log.info("Triggering workflow {} from event: type={}", 
                workflow.workflowId(), envelope.eventType());

        return workflowEngine.startWorkflow(
                workflow.workflowId(),
                input,
                correlationId,
                "event:" + envelope.eventType()
        )
        .doOnSuccess(instance -> 
                log.info("Started workflow instance {} from event", instance.instanceId()))
        .doOnError(e -> 
                log.error("Failed to start workflow {} from event", workflow.workflowId(), e))
        .then();
    }

    /**
     * Builds workflow input from an event envelope.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildInputFromEvent(EventEnvelope envelope) {
        Map<String, Object> input = new HashMap<>();
        
        // Add event metadata
        input.put("_eventType", envelope.eventType());
        input.put("_eventDestination", envelope.destination());
        input.put("_eventTimestamp", envelope.timestamp());
        
        // Add payload
        Object payload = envelope.payload();
        if (payload instanceof Map) {
            input.putAll((Map<String, Object>) payload);
        } else if (payload != null) {
            input.put("payload", payload);
        }
        
        // Add relevant headers
        if (envelope.headers() != null) {
            envelope.headers().forEach((key, value) -> {
                if (!key.startsWith("_") && value != null) {
                    input.put("header_" + key, value);
                }
            });
        }
        
        return input;
    }

    /**
     * Extracts correlation ID from event.
     */
    private String extractCorrelationId(EventEnvelope envelope) {
        if (envelope.transactionId() != null) {
            return envelope.transactionId();
        }
        
        if (envelope.headers() != null) {
            Object correlationId = envelope.headers().get("correlationId");
            if (correlationId != null) {
                return correlationId.toString();
            }
        }
        
        if (envelope.metadata() != null && envelope.metadata().correlationId() != null) {
            return envelope.metadata().correlationId();
        }
        
        return null;
    }

    /**
     * Checks if an event type matches a trigger pattern.
     * <p>
     * Supports glob patterns (*, ?) for flexible matching.
     */
    public boolean matchesTrigger(String eventType, String triggerPattern) {
        if (triggerPattern == null || triggerPattern.isEmpty()) {
            return false;
        }
        
        // Exact match
        if (triggerPattern.equals(eventType)) {
            return true;
        }
        
        // Pattern matching
        Pattern pattern = triggerPatterns.computeIfAbsent(triggerPattern, this::compilePattern);
        return pattern.matcher(eventType).matches();
    }

    /**
     * Compiles a trigger pattern (with glob support) to a regex.
     */
    private Pattern compilePattern(String triggerPattern) {
        String regex = triggerPattern
                .replace(".", "\\.")
                .replace("*", ".*")
                .replace("?", ".");
        return Pattern.compile("^" + regex + "$");
    }
}
