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

package com.firefly.common.workflow.config;

import com.firefly.common.cache.core.CacheAdapter;
import com.firefly.common.eda.publisher.EventPublisherFactory;
import com.firefly.common.workflow.aspect.WorkflowAspect;
import com.firefly.common.workflow.core.WorkflowEngine;
import com.firefly.common.workflow.core.WorkflowExecutor;
import com.firefly.common.workflow.core.WorkflowRegistry;
import com.firefly.common.workflow.event.WorkflowEventListener;
import com.firefly.common.workflow.event.WorkflowEventPublisher;
import com.firefly.common.workflow.health.WorkflowEngineHealthIndicator;
import com.firefly.common.workflow.properties.WorkflowProperties;
import com.firefly.common.workflow.rest.WorkflowController;
import com.firefly.common.workflow.metrics.WorkflowMetrics;
import com.firefly.common.workflow.resilience.WorkflowResilience;
import com.firefly.common.workflow.state.CacheStepStateStore;
import com.firefly.common.workflow.state.CacheWorkflowStateStore;
import com.firefly.common.workflow.state.StepStateStore;
import com.firefly.common.workflow.state.WorkflowStateStore;
import com.firefly.common.workflow.tracing.WorkflowTracer;
import org.springframework.lang.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/**
 * Auto-configuration for the Firefly Workflow Engine.
 * <p>
 * This configuration provides all necessary beans for workflow orchestration:
 * <ul>
 *   <li>WorkflowStateStore - persists workflow state using lib-common-cache</li>
 *   <li>WorkflowEventPublisher - publishes workflow events using lib-common-eda</li>
 *   <li>WorkflowRegistry - manages workflow definitions</li>
 *   <li>WorkflowExecutor - executes workflow steps</li>
 *   <li>WorkflowEngine - main orchestration facade</li>
 *   <li>WorkflowEventListener - listens for trigger events</li>
 *   <li>WorkflowAspect - scans and registers @Workflow annotated beans</li>
 *   <li>WorkflowController - REST API endpoints</li>
 *   <li>WorkflowEngineHealthIndicator - health monitoring</li>
 * </ul>
 * <p>
 * Requires lib-common-cache CacheAdapter and lib-common-eda EventPublisherFactory beans.
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(WorkflowProperties.class)
@ConditionalOnProperty(prefix = "firefly.workflow", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkflowEngineAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(CacheAdapter.class)
    public WorkflowStateStore workflowStateStore(CacheAdapter cacheAdapter, WorkflowProperties properties) {
        log.info("Creating CacheWorkflowStateStore with key prefix: {}, TTL: {}",
                properties.getState().getKeyPrefix(),
                properties.getState().getDefaultTtl());
        return new CacheWorkflowStateStore(cacheAdapter, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(CacheAdapter.class)
    @ConditionalOnProperty(prefix = "firefly.workflow.step-state", name = "enabled", havingValue = "true", matchIfMissing = true)
    public StepStateStore stepStateStore(CacheAdapter cacheAdapter, WorkflowProperties properties) {
        log.info("Creating CacheStepStateStore for step-level choreography");
        return new CacheStepStateStore(cacheAdapter, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(EventPublisherFactory.class)
    public WorkflowEventPublisher workflowEventPublisher(
            EventPublisherFactory publisherFactory,
            WorkflowProperties properties) {
        log.info("Creating WorkflowEventPublisher with destination: {}", properties.getEvents().getDefaultDestination());
        return new WorkflowEventPublisher(publisherFactory, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowRegistry workflowRegistry() {
        log.info("Creating WorkflowRegistry");
        return new WorkflowRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowExecutor workflowExecutor(
            WorkflowStateStore stateStore,
            @Nullable StepStateStore stepStateStore,
            WorkflowEventPublisher eventPublisher,
            WorkflowProperties properties,
            org.springframework.context.ApplicationContext applicationContext,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            WorkflowAspect workflowAspect,
            @Nullable WorkflowTracer workflowTracer,
            @Nullable WorkflowMetrics workflowMetrics,
            @Nullable WorkflowResilience workflowResilience) {
        log.info("Creating WorkflowExecutor with step-level choreography: {}, tracing: {}, metrics: {}, resilience: {}",
                stepStateStore != null, workflowTracer != null, workflowMetrics != null, workflowResilience != null);
        return new WorkflowExecutor(stateStore, stepStateStore, eventPublisher, properties,
                applicationContext, objectMapper, workflowAspect, workflowTracer, workflowMetrics, workflowResilience);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowEngine workflowEngine(
            WorkflowRegistry registry,
            WorkflowExecutor executor,
            WorkflowStateStore stateStore,
            WorkflowEventPublisher eventPublisher,
            WorkflowProperties properties,
            @Nullable StepStateStore stepStateStore,
            @Nullable WorkflowMetrics workflowMetrics) {
        log.info("Creating WorkflowEngine with step-level choreography: {}, metrics: {}",
                stepStateStore != null, workflowMetrics != null);
        return new WorkflowEngine(registry, executor, stateStore, eventPublisher, properties,
                stepStateStore, workflowMetrics);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "firefly.workflow.events", name = "listen-enabled", havingValue = "true", matchIfMissing = true)
    public WorkflowEventListener workflowEventListener(
            WorkflowEngine workflowEngine, 
            WorkflowProperties properties,
            WorkflowStateStore stateStore,
            @Nullable StepStateStore stepStateStore) {
        log.info("Creating WorkflowEventListener with step-level event handling: {}", stepStateStore != null);
        return new WorkflowEventListener(workflowEngine, properties, stateStore, stepStateStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowAspect workflowAspect(WorkflowRegistry registry) {
        log.info("Creating WorkflowAspect for annotation scanning");
        return new WorkflowAspect(registry);
    }

    /**
     * REST API Configuration - conditionally enabled.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "firefly.workflow.api", name = "enabled", havingValue = "true", matchIfMissing = true)
    public WorkflowController workflowController(WorkflowEngine workflowEngine) {
        log.info("Creating WorkflowController REST API");
        return new WorkflowController(workflowEngine);
    }

    /**
     * Health indicator for workflow engine monitoring.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "firefly.workflow", name = "health-indicator-enabled", havingValue = "true", matchIfMissing = true)
    public WorkflowEngineHealthIndicator workflowEngineHealthIndicator(
            WorkflowEngine workflowEngine,
            WorkflowStateStore stateStore) {
        log.info("Creating WorkflowEngineHealthIndicator");
        return new WorkflowEngineHealthIndicator(workflowEngine, stateStore);
    }
}
