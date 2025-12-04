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

import com.firefly.common.eda.publisher.EventPublisher;
import com.firefly.common.eda.publisher.EventPublisherFactory;
import com.firefly.common.workflow.model.StepExecution;
import com.firefly.common.workflow.model.WorkflowInstance;
import com.firefly.common.workflow.properties.WorkflowProperties;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Publishes workflow lifecycle events using lib-common-eda.
 * <p>
 * This component integrates with the EDA library to publish events
 * when workflows and steps change state. Events can be consumed by
 * external systems or used to trigger other workflows.
 */
@Slf4j
public class WorkflowEventPublisher {

    private final EventPublisherFactory publisherFactory;
    private final WorkflowProperties properties;
    private final boolean enabled;
    private final String destination;

    public WorkflowEventPublisher(EventPublisherFactory publisherFactory, WorkflowProperties properties) {
        this.publisherFactory = publisherFactory;
        this.properties = properties;
        this.enabled = properties.getEvents().isEnabled();
        this.destination = properties.getEvents().getDefaultDestination();
        
        log.info("WorkflowEventPublisher initialized: enabled={}, destination={}", enabled, destination);
    }

    /**
     * Publishes a workflow started event.
     */
    public Mono<Void> publishWorkflowStarted(WorkflowInstance instance) {
        return publishEvent(WorkflowEvent.workflowStarted(instance));
    }

    /**
     * Publishes a workflow completed event.
     */
    public Mono<Void> publishWorkflowCompleted(WorkflowInstance instance) {
        return publishEvent(WorkflowEvent.workflowCompleted(instance));
    }

    /**
     * Publishes a workflow failed event.
     */
    public Mono<Void> publishWorkflowFailed(WorkflowInstance instance) {
        return publishEvent(WorkflowEvent.workflowFailed(instance));
    }

    /**
     * Publishes a workflow cancelled event.
     */
    public Mono<Void> publishWorkflowCancelled(WorkflowInstance instance) {
        return publishEvent(WorkflowEvent.workflowCancelled(instance));
    }

    /**
     * Publishes a step started event.
     */
    public Mono<Void> publishStepStarted(WorkflowInstance instance, StepExecution step) {
        if (!properties.getEvents().isPublishStepEvents()) {
            return Mono.empty();
        }
        return publishEvent(WorkflowEvent.stepStarted(instance, step));
    }

    /**
     * Publishes a step completed event.
     */
    public Mono<Void> publishStepCompleted(WorkflowInstance instance, StepExecution step) {
        if (!properties.getEvents().isPublishStepEvents()) {
            return Mono.empty();
        }
        return publishEvent(WorkflowEvent.stepCompleted(instance, step));
    }

    /**
     * Publishes a step failed event.
     */
    public Mono<Void> publishStepFailed(WorkflowInstance instance, StepExecution step) {
        if (!properties.getEvents().isPublishStepEvents()) {
            return Mono.empty();
        }
        return publishEvent(WorkflowEvent.stepFailed(instance, step));
    }

    /**
     * Publishes a step retrying event.
     */
    public Mono<Void> publishStepRetrying(WorkflowInstance instance, StepExecution step) {
        if (!properties.getEvents().isPublishStepEvents()) {
            return Mono.empty();
        }
        return publishEvent(WorkflowEvent.stepRetrying(instance, step));
    }

    /**
     * Publishes a custom workflow event.
     */
    public Mono<Void> publishEvent(WorkflowEvent event) {
        if (!enabled) {
            log.debug("Event publishing disabled, skipping: {}", event.eventType());
            return Mono.empty();
        }

        return Mono.defer(() -> {
            try {
                EventPublisher publisher = getPublisher();
                if (publisher == null || !publisher.isAvailable()) {
                    log.warn("No available publisher for workflow events");
                    return Mono.empty();
                }

                Map<String, Object> headers = buildHeaders(event);

                log.debug("Publishing workflow event: type={}, workflowId={}, instanceId={}",
                        event.eventType(), event.workflowId(), event.instanceId());

                return publisher.publish(event, destination, headers)
                        .doOnSuccess(v -> log.debug("Published workflow event: {}", event.eventType()))
                        .doOnError(e -> log.error("Failed to publish workflow event: {}", event.eventType(), e))
                        .onErrorResume(e -> Mono.empty()); // Don't fail workflow on event publish error

            } catch (Exception e) {
                log.error("Error preparing workflow event publication", e);
                return Mono.empty();
            }
        });
    }

    /**
     * Publishes a custom event to a specific destination.
     */
    public Mono<Void> publishCustomEvent(String eventType, Object payload, String customDestination, 
                                         Map<String, Object> additionalHeaders) {
        if (!enabled) {
            return Mono.empty();
        }

        return Mono.defer(() -> {
            try {
                EventPublisher publisher = getPublisher();
                if (publisher == null || !publisher.isAvailable()) {
                    log.warn("No available publisher for custom workflow events");
                    return Mono.empty();
                }

                Map<String, Object> headers = new HashMap<>();
                headers.put("eventType", eventType);
                headers.put("source", "workflow-engine");
                if (additionalHeaders != null) {
                    headers.putAll(additionalHeaders);
                }

                String dest = customDestination != null ? customDestination : destination;

                return publisher.publish(payload, dest, headers)
                        .doOnSuccess(v -> log.debug("Published custom event: {} to {}", eventType, dest))
                        .doOnError(e -> log.error("Failed to publish custom event: {}", eventType, e))
                        .onErrorResume(e -> Mono.empty());

            } catch (Exception e) {
                log.error("Error preparing custom event publication", e);
                return Mono.empty();
            }
        });
    }

    private EventPublisher getPublisher() {
        return publisherFactory.getPublisher(
                properties.getEvents().getPublisherType(),
                properties.getEvents().getConnectionId()
        );
    }

    private Map<String, Object> buildHeaders(WorkflowEvent event) {
        Map<String, Object> headers = new HashMap<>();
        headers.put("eventType", event.getEventTypeString());
        headers.put("workflowId", event.workflowId());
        headers.put("instanceId", event.instanceId());
        headers.put("source", "workflow-engine");
        
        if (event.correlationId() != null) {
            headers.put("correlationId", event.correlationId());
        }
        if (event.stepId() != null) {
            headers.put("stepId", event.stepId());
        }
        
        return headers;
    }

    /**
     * Checks if event publishing is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }
}
