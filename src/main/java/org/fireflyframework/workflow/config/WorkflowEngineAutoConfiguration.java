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

package org.fireflyframework.workflow.config;

import org.fireflyframework.cache.core.CacheAdapter;
import org.fireflyframework.eda.publisher.EventPublisherFactory;
import org.fireflyframework.eventsourcing.store.EventStore;
import org.fireflyframework.workflow.aspect.WorkflowAspect;
import org.fireflyframework.workflow.child.ChildWorkflowService;
import org.fireflyframework.workflow.compensation.CompensationOrchestrator;
import org.fireflyframework.workflow.continueasnew.ContinueAsNewService;
import org.fireflyframework.workflow.core.StepHandler;
import org.fireflyframework.workflow.core.WorkflowEngine;
import org.fireflyframework.workflow.core.WorkflowExecutor;
import org.fireflyframework.workflow.core.WorkflowRegistry;
import org.fireflyframework.workflow.event.WorkflowEventListener;
import org.fireflyframework.workflow.event.WorkflowEventPublisher;
import org.fireflyframework.workflow.eventsourcing.projection.WorkflowInstanceProjection;
import org.fireflyframework.workflow.eventsourcing.projection.WorkflowProjectionRepository;
import org.fireflyframework.workflow.eventsourcing.projection.WorkflowProjectionScheduler;
import org.fireflyframework.workflow.eventsourcing.store.EventSourcedWorkflowStateStore;
import org.fireflyframework.workflow.health.WorkflowEngineHealthIndicator;
import org.fireflyframework.workflow.properties.WorkflowProperties;
import org.fireflyframework.workflow.dlq.CacheDeadLetterStore;
import org.fireflyframework.workflow.dlq.DeadLetterService;
import org.fireflyframework.workflow.dlq.DeadLetterStore;
import org.fireflyframework.workflow.rest.DeadLetterController;
import org.fireflyframework.workflow.query.WorkflowQueryService;
import org.fireflyframework.workflow.rest.WorkflowController;
import org.fireflyframework.workflow.scheduling.WorkflowScheduler;
import org.fireflyframework.workflow.search.SearchAttributeProjection;
import org.fireflyframework.workflow.search.WorkflowSearchService;
import org.fireflyframework.workflow.service.WorkflowService;
import org.fireflyframework.workflow.signal.SignalService;
import org.fireflyframework.workflow.metrics.WorkflowMetrics;
import org.fireflyframework.workflow.resilience.WorkflowResilience;
import org.fireflyframework.workflow.state.CacheStepStateStore;
import org.fireflyframework.workflow.state.CacheWorkflowStateStore;
import org.fireflyframework.workflow.state.StepStateStore;
import org.fireflyframework.workflow.state.WorkflowStateStore;
import org.fireflyframework.workflow.timer.TimerSchedulerService;
import org.fireflyframework.workflow.timer.WorkflowTimerProjection;
import org.fireflyframework.workflow.tracing.WorkflowTracer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.lang.Nullable;
import org.springframework.r2dbc.core.DatabaseClient;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Auto-configuration for the Firefly Workflow Engine.
 * <p>
 * This configuration provides all necessary beans for workflow orchestration:
 * <ul>
 *   <li>WorkflowStateStore - persists workflow state using fireflyframework-cache</li>
 *   <li>WorkflowEventPublisher - publishes workflow events using fireflyframework-eda</li>
 *   <li>WorkflowRegistry - manages workflow definitions</li>
 *   <li>WorkflowExecutor - executes workflow steps</li>
 *   <li>WorkflowEngine - main orchestration facade</li>
 *   <li>WorkflowEventListener - listens for trigger events</li>
 *   <li>WorkflowAspect - scans and registers @Workflow annotated beans</li>
 *   <li>WorkflowController - REST API endpoints</li>
 *   <li>WorkflowEngineHealthIndicator - health monitoring</li>
 * </ul>
 * <p>
 * Requires fireflyframework-cache CacheAdapter and fireflyframework-eda EventPublisherFactory beans.
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
            @Nullable WorkflowResilience workflowResilience,
            @Nullable DeadLetterStore deadLetterStore) {
        log.info("Creating WorkflowExecutor with step-level choreography: {}, tracing: {}, metrics: {}, resilience: {}, dlq: {}",
                stepStateStore != null, workflowTracer != null, workflowMetrics != null, workflowResilience != null, deadLetterStore != null);
        return new WorkflowExecutor(stateStore, stepStateStore, eventPublisher, properties,
                applicationContext, objectMapper, workflowAspect, workflowTracer, workflowMetrics, workflowResilience, deadLetterStore);
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
     * Service layer for workflow operations.
     */
    @Bean
    @ConditionalOnMissingBean
    public WorkflowService workflowService(WorkflowEngine workflowEngine) {
        log.info("Creating WorkflowService");
        return new WorkflowService(workflowEngine);
    }

    // ==================== Durable Execution Beans ====================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({EventStore.class, DatabaseClient.class})
    @ConditionalOnProperty(prefix = "firefly.workflow.eventsourcing", name = "enabled", havingValue = "true")
    public WorkflowProjectionRepository workflowProjectionRepository(DatabaseClient databaseClient) {
        log.info("Creating WorkflowProjectionRepository for read-side projection queries");
        return new WorkflowProjectionRepository(databaseClient);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({EventStore.class, DatabaseClient.class})
    @ConditionalOnProperty(prefix = "firefly.workflow.eventsourcing", name = "enabled", havingValue = "true")
    public WorkflowInstanceProjection workflowInstanceProjection(
            DatabaseClient databaseClient,
            EventStore eventStore,
            MeterRegistry meterRegistry) {
        log.info("Creating WorkflowInstanceProjection for read-side event processing");
        return new WorkflowInstanceProjection(databaseClient, eventStore, meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(WorkflowInstanceProjection.class)
    public WorkflowProjectionScheduler workflowProjectionScheduler(
            WorkflowInstanceProjection projection,
            EventStore eventStore,
            WorkflowProperties properties) {
        WorkflowProjectionScheduler scheduler = new WorkflowProjectionScheduler(
                projection,
                eventStore,
                properties.getEventsourcing().getProjectionPollInterval(),
                properties.getEventsourcing().getProjectionBatchSize());
        scheduler.start();
        log.info("Created and started WorkflowProjectionScheduler with pollInterval={}, batchSize={}",
                properties.getEventsourcing().getProjectionPollInterval(),
                properties.getEventsourcing().getProjectionBatchSize());
        return scheduler;
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(EventSourcedWorkflowStateStore.class)
    @ConditionalOnBean(EventStore.class)
    @ConditionalOnProperty(prefix = "firefly.workflow.eventsourcing", name = "enabled", havingValue = "true")
    public EventSourcedWorkflowStateStore eventSourcedWorkflowStateStore(
            EventStore eventStore,
            @Nullable WorkflowProjectionRepository projectionRepository) {
        log.info("Creating EventSourcedWorkflowStateStore for durable execution (projection: {})",
                projectionRepository != null);
        return new EventSourcedWorkflowStateStore(eventStore, projectionRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(EventSourcedWorkflowStateStore.class)
    @ConditionalOnProperty(prefix = "firefly.workflow.signals", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SignalService signalService(EventSourcedWorkflowStateStore eventSourcedStateStore) {
        log.info("Creating SignalService for durable signal delivery");
        return new SignalService(eventSourcedStateStore);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(EventSourcedWorkflowStateStore.class)
    public WorkflowQueryService workflowQueryService(EventSourcedWorkflowStateStore eventSourcedStateStore) {
        log.info("Creating WorkflowQueryService for workflow state inspection");
        return new WorkflowQueryService(eventSourcedStateStore);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "firefly.workflow.timers", name = "enabled", havingValue = "true", matchIfMissing = true)
    public WorkflowTimerProjection workflowTimerProjection() {
        log.info("Creating WorkflowTimerProjection for durable timer tracking");
        return new WorkflowTimerProjection();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({WorkflowTimerProjection.class, EventSourcedWorkflowStateStore.class})
    @ConditionalOnProperty(prefix = "firefly.workflow.timers", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TimerSchedulerService timerSchedulerService(
            WorkflowTimerProjection timerProjection,
            EventSourcedWorkflowStateStore eventSourcedStateStore,
            WorkflowProperties properties) {
        TimerSchedulerService scheduler = new TimerSchedulerService(
                timerProjection,
                eventSourcedStateStore,
                properties.getTimers().getPollInterval(),
                properties.getTimers().getBatchSize());
        scheduler.start();
        log.info("Created and started TimerSchedulerService with pollInterval={}, batchSize={}",
                properties.getTimers().getPollInterval(), properties.getTimers().getBatchSize());
        return scheduler;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(EventSourcedWorkflowStateStore.class)
    @ConditionalOnProperty(prefix = "firefly.workflow.child-workflows", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ChildWorkflowService childWorkflowService(EventSourcedWorkflowStateStore eventSourcedStateStore) {
        log.info("Creating ChildWorkflowService for parent-child workflow management");
        return new ChildWorkflowService(eventSourcedStateStore);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(EventSourcedWorkflowStateStore.class)
    @ConditionalOnProperty(prefix = "firefly.workflow.compensation", name = "enabled", havingValue = "true", matchIfMissing = true)
    public CompensationOrchestrator compensationOrchestrator(
            EventSourcedWorkflowStateStore eventSourcedStateStore,
            Map<String, StepHandler<?>> stepHandlers) {
        log.info("Creating CompensationOrchestrator for saga-style compensation");
        return new CompensationOrchestrator(eventSourcedStateStore, stepHandlers);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "firefly.workflow.search-attributes", name = "enabled", havingValue = "true", matchIfMissing = true)
    public SearchAttributeProjection searchAttributeProjection() {
        log.info("Creating SearchAttributeProjection for workflow discovery");
        return new SearchAttributeProjection();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({SearchAttributeProjection.class, EventSourcedWorkflowStateStore.class})
    @ConditionalOnProperty(prefix = "firefly.workflow.search-attributes", name = "enabled", havingValue = "true", matchIfMissing = true)
    public WorkflowSearchService workflowSearchService(
            SearchAttributeProjection searchAttributeProjection,
            EventSourcedWorkflowStateStore eventSourcedStateStore) {
        log.info("Creating WorkflowSearchService for search by attributes");
        return new WorkflowSearchService(searchAttributeProjection, eventSourcedStateStore);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(EventSourcedWorkflowStateStore.class)
    public ContinueAsNewService continueAsNewService(EventSourcedWorkflowStateStore eventSourcedStateStore) {
        log.info("Creating ContinueAsNewService for long-running workflow history reset");
        return new ContinueAsNewService(eventSourcedStateStore);
    }

    /**
     * REST API Configuration - conditionally enabled.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "firefly.workflow.api", name = "enabled", havingValue = "true", matchIfMissing = true)
    public WorkflowController workflowController(
            WorkflowService workflowService,
            @Nullable SignalService signalService,
            @Nullable WorkflowQueryService queryService,
            @Nullable WorkflowSearchService searchService) {
        log.info("Creating WorkflowController REST API (signalService: {}, queryService: {}, searchService: {})",
                signalService != null, queryService != null, searchService != null);
        return new WorkflowController(workflowService, signalService, queryService, searchService);
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

    /**
     * Task scheduler for scheduled workflow execution.
     */
    @Bean
    @ConditionalOnMissingBean(name = "workflowTaskScheduler")
    @ConditionalOnProperty(prefix = "firefly.workflow.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
    public TaskScheduler workflowTaskScheduler(WorkflowProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.getScheduling().getPoolSize());
        scheduler.setThreadNamePrefix(properties.getScheduling().getThreadNamePrefix());
        scheduler.setWaitForTasksToCompleteOnShutdown(properties.getScheduling().isWaitForTasksToCompleteOnShutdown());
        scheduler.setAwaitTerminationSeconds(properties.getScheduling().getAwaitTerminationSeconds());
        scheduler.initialize();
        log.info("Creating workflow TaskScheduler with pool size: {}", properties.getScheduling().getPoolSize());
        return scheduler;
    }

    /**
     * Workflow scheduler for cron-based workflow execution.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "firefly.workflow.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
    public WorkflowScheduler workflowScheduler(
            WorkflowEngine workflowEngine,
            TaskScheduler workflowTaskScheduler,
            org.springframework.context.ApplicationContext applicationContext,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            WorkflowProperties properties) {
        log.info("Creating WorkflowScheduler for cron-based workflow execution");
        return new WorkflowScheduler(workflowEngine, workflowTaskScheduler, applicationContext, objectMapper, properties);
    }

    // ==================== Dead Letter Queue (DLQ) Beans ====================

    /**
     * Dead Letter Store for persisting failed workflow/step entries.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(CacheAdapter.class)
    @ConditionalOnProperty(prefix = "firefly.workflow.dlq", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DeadLetterStore deadLetterStore(CacheAdapter cacheAdapter, WorkflowProperties properties) {
        log.info("Creating CacheDeadLetterStore with retention: {}", properties.getDlq().getRetentionPeriod());
        return new CacheDeadLetterStore(cacheAdapter, properties);
    }

    /**
     * Dead Letter Service for DLQ operations.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DeadLetterStore.class)
    @ConditionalOnProperty(prefix = "firefly.workflow.dlq", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DeadLetterService deadLetterService(
            DeadLetterStore deadLetterStore,
            WorkflowEngine workflowEngine,
            WorkflowProperties properties) {
        log.info("Creating DeadLetterService");
        return new DeadLetterService(deadLetterStore, workflowEngine, properties);
    }

    /**
     * Dead Letter Controller for DLQ REST API.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DeadLetterService.class)
    @ConditionalOnProperty(prefix = "firefly.workflow.api", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DeadLetterController deadLetterController(DeadLetterService deadLetterService) {
        log.info("Creating DeadLetterController REST API");
        return new DeadLetterController(deadLetterService);
    }

    /**
     * Workflow Recovery Service — recovers stale workflows after application restart.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "firefly.workflow.recovery", name = "enabled", havingValue = "true", matchIfMissing = true)
    public org.fireflyframework.workflow.recovery.WorkflowRecoveryService workflowRecoveryService(
            WorkflowStateStore stateStore,
            WorkflowEngine workflowEngine,
            WorkflowProperties properties) {
        log.info("Creating WorkflowRecoveryService with staleThreshold: {}",
                properties.getRecovery().getStaleThreshold());
        return new org.fireflyframework.workflow.recovery.WorkflowRecoveryService(
                stateStore, workflowEngine,
                properties.getRecovery().isEnabled(),
                properties.getRecovery().getStaleThreshold());
    }
}
