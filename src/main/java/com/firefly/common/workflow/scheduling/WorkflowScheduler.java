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

package com.firefly.common.workflow.scheduling;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.firefly.common.workflow.annotation.ScheduledWorkflow;
import com.firefly.common.workflow.annotation.ScheduledWorkflows;
import com.firefly.common.workflow.annotation.Workflow;
import com.firefly.common.workflow.core.WorkflowEngine;
import com.firefly.common.workflow.properties.WorkflowProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Duration;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ScheduledFuture;

/**
 * Manages scheduled workflow executions.
 * <p>
 * Scans for @ScheduledWorkflow annotations and registers scheduled tasks
 * to trigger workflows based on cron expressions or fixed delays/rates.
 */
@Slf4j
@RequiredArgsConstructor
public class WorkflowScheduler {

    private final WorkflowEngine workflowEngine;
    private final TaskScheduler taskScheduler;
    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper;
    private final WorkflowProperties properties;

    private final Map<String, List<ScheduledFuture<?>>> scheduledTasks = new HashMap<>();
    private final List<ScheduledWorkflowInfo> registeredSchedules = new ArrayList<>();

    /**
     * Initializes the scheduler by scanning for @ScheduledWorkflow annotations.
     */
    @PostConstruct
    public void init() {
        if (!properties.getScheduling().isEnabled()) {
            log.info("Workflow scheduling is disabled");
            return;
        }

        log.info("Initializing WorkflowScheduler...");
        scanAndRegisterScheduledWorkflows();
        log.info("WorkflowScheduler initialized with {} scheduled workflows", registeredSchedules.size());
    }

    /**
     * Shuts down the scheduler and cancels all scheduled tasks.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down WorkflowScheduler...");
        scheduledTasks.values().forEach(futures -> 
                futures.forEach(future -> future.cancel(false)));
        scheduledTasks.clear();
    }

    /**
     * Scans for beans annotated with @Workflow and @ScheduledWorkflow.
     */
    private void scanAndRegisterScheduledWorkflows() {
        Map<String, Object> workflowBeans = applicationContext.getBeansWithAnnotation(Workflow.class);

        for (Map.Entry<String, Object> entry : workflowBeans.entrySet()) {
            Object bean = entry.getValue();
            Class<?> beanClass = bean.getClass();

            Workflow workflowAnnotation = beanClass.getAnnotation(Workflow.class);
            if (workflowAnnotation == null) {
                continue;
            }

            String workflowId = getWorkflowId(workflowAnnotation, beanClass);

            // Check for single @ScheduledWorkflow
            ScheduledWorkflow singleSchedule = beanClass.getAnnotation(ScheduledWorkflow.class);
            if (singleSchedule != null) {
                registerSchedule(workflowId, singleSchedule);
            }

            // Check for multiple @ScheduledWorkflow via container
            ScheduledWorkflows multipleSchedules = beanClass.getAnnotation(ScheduledWorkflows.class);
            if (multipleSchedules != null) {
                for (ScheduledWorkflow schedule : multipleSchedules.value()) {
                    registerSchedule(workflowId, schedule);
                }
            }
        }
    }

    /**
     * Registers a scheduled workflow.
     */
    private void registerSchedule(String workflowId, ScheduledWorkflow schedule) {
        if (!schedule.enabled()) {
            log.debug("Skipping disabled schedule for workflow: {}", workflowId);
            return;
        }

        ScheduledWorkflowInfo info = new ScheduledWorkflowInfo(
                workflowId,
                schedule.cron(),
                schedule.zone(),
                schedule.fixedDelay(),
                schedule.fixedRate(),
                schedule.initialDelay(),
                schedule.input(),
                schedule.description()
        );

        registeredSchedules.add(info);
        scheduleWorkflow(info);
    }

    /**
     * Schedules a workflow based on its configuration.
     */
    private void scheduleWorkflow(ScheduledWorkflowInfo info) {
        Runnable task = () -> executeScheduledWorkflow(info);

        ScheduledFuture<?> future;

        if (info.fixedRate() > 0) {
            // Fixed rate scheduling
            if (info.initialDelay() > 0) {
                future = taskScheduler.scheduleAtFixedRate(
                        task,
                        java.time.Instant.now().plusMillis(info.initialDelay()),
                        Duration.ofMillis(info.fixedRate())
                );
            } else {
                future = taskScheduler.scheduleAtFixedRate(
                        task,
                        Duration.ofMillis(info.fixedRate())
                );
            }
            log.info("Scheduled workflow {} with fixed rate: {}ms", info.workflowId(), info.fixedRate());
        } else if (info.fixedDelay() > 0) {
            // Fixed delay scheduling
            if (info.initialDelay() > 0) {
                future = taskScheduler.scheduleWithFixedDelay(
                        task,
                        java.time.Instant.now().plusMillis(info.initialDelay()),
                        Duration.ofMillis(info.fixedDelay())
                );
            } else {
                future = taskScheduler.scheduleWithFixedDelay(
                        task,
                        Duration.ofMillis(info.fixedDelay())
                );
            }
            log.info("Scheduled workflow {} with fixed delay: {}ms", info.workflowId(), info.fixedDelay());
        } else {
            // Cron scheduling
            ZoneId zoneId = info.zone() != null && !info.zone().isEmpty()
                    ? ZoneId.of(info.zone())
                    : ZoneId.systemDefault();
            
            CronTrigger trigger = new CronTrigger(info.cron(), zoneId);
            future = taskScheduler.schedule(task, trigger);
            log.info("Scheduled workflow {} with cron: {} (zone: {})", 
                    info.workflowId(), info.cron(), zoneId);
        }

        scheduledTasks.computeIfAbsent(info.workflowId(), k -> new ArrayList<>()).add(future);
    }

    /**
     * Executes a scheduled workflow.
     */
    private void executeScheduledWorkflow(ScheduledWorkflowInfo info) {
        try {
            log.info("Executing scheduled workflow: {}", info.workflowId());

            Map<String, Object> input = parseInput(info.input());
            input.put("_scheduledExecution", true);
            input.put("_scheduledAt", java.time.Instant.now().toString());

            workflowEngine.startWorkflow(
                    info.workflowId(),
                    input,
                    UUID.randomUUID().toString(),
                    "scheduler:" + info.cron()
            ).subscribe(
                    instance -> log.info("Started scheduled workflow instance: workflowId={}, instanceId={}",
                            info.workflowId(), instance.instanceId()),
                    error -> log.error("Failed to start scheduled workflow: workflowId={}, error={}",
                            info.workflowId(), error.getMessage())
            );
        } catch (Exception e) {
            log.error("Error executing scheduled workflow: {}", info.workflowId(), e);
        }
    }

    /**
     * Parses input JSON string to a map.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseInput(String inputJson) {
        if (inputJson == null || inputJson.isEmpty() || "{}".equals(inputJson)) {
            return new HashMap<>();
        }

        try {
            return objectMapper.readValue(inputJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse schedule input JSON: {}", inputJson, e);
            return new HashMap<>();
        }
    }

    /**
     * Gets the workflow ID from annotation or class name.
     */
    private String getWorkflowId(Workflow annotation, Class<?> beanClass) {
        if (annotation.id() != null && !annotation.id().isEmpty()) {
            return annotation.id();
        }
        // Convert class name to kebab-case
        String simpleName = beanClass.getSimpleName();
        return simpleName.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
    }

    /**
     * Gets the list of registered scheduled workflows.
     *
     * @return list of scheduled workflow info
     */
    public List<ScheduledWorkflowInfo> getRegisteredSchedules() {
        return Collections.unmodifiableList(registeredSchedules);
    }

    /**
     * Cancels all schedules for a specific workflow.
     *
     * @param workflowId the workflow ID
     * @return true if any schedules were cancelled
     */
    public boolean cancelSchedules(String workflowId) {
        List<ScheduledFuture<?>> futures = scheduledTasks.remove(workflowId);
        if (futures != null && !futures.isEmpty()) {
            futures.forEach(future -> future.cancel(false));
            registeredSchedules.removeIf(info -> info.workflowId().equals(workflowId));
            log.info("Cancelled {} schedules for workflow: {}", futures.size(), workflowId);
            return true;
        }
        return false;
    }

    /**
     * Triggers immediate execution of a scheduled workflow.
     *
     * @param workflowId the workflow ID
     */
    public void triggerNow(String workflowId) {
        registeredSchedules.stream()
                .filter(info -> info.workflowId().equals(workflowId))
                .findFirst()
                .ifPresent(this::executeScheduledWorkflow);
    }

    /**
     * Information about a scheduled workflow.
     */
    public record ScheduledWorkflowInfo(
            String workflowId,
            String cron,
            String zone,
            long fixedDelay,
            long fixedRate,
            long initialDelay,
            String input,
            String description
    ) {}
}
