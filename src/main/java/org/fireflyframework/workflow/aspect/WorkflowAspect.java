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

package org.fireflyframework.workflow.aspect;

import org.fireflyframework.workflow.annotation.OnStepComplete;
import org.fireflyframework.workflow.annotation.OnWorkflowComplete;
import org.fireflyframework.workflow.annotation.OnWorkflowError;
import org.fireflyframework.workflow.annotation.Workflow;
import org.fireflyframework.workflow.annotation.WorkflowStep;
import org.fireflyframework.workflow.core.StepHandler;
import org.fireflyframework.workflow.core.WorkflowContext;
import org.fireflyframework.workflow.core.WorkflowRegistry;
import org.fireflyframework.workflow.model.RetryPolicy;
import org.fireflyframework.workflow.model.WorkflowDefinition;
import org.fireflyframework.workflow.model.WorkflowStepDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aspect that scans for @Workflow and @WorkflowStep annotations
 * and registers workflow definitions automatically.
 * <p>
 * This aspect acts as a BeanPostProcessor to intercept bean creation
 * and scan for workflow annotations. It builds WorkflowDefinition objects
 * from annotated classes and methods and registers them with the WorkflowRegistry.
 * <p>
 * Step handlers created from annotated methods are stored in an internal registry
 * and can be retrieved via {@link #getStepHandler(String, String)}.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class WorkflowAspect implements BeanPostProcessor {

    private final WorkflowRegistry registry;
    private final Map<String, Object> workflowBeans = new ConcurrentHashMap<>();
    private final Map<String, StepHandler<?>> stepHandlers = new ConcurrentHashMap<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> targetClass = bean.getClass();
        
        // Check if the class has @Workflow annotation
        Workflow workflowAnnotation = AnnotationUtils.findAnnotation(targetClass, Workflow.class);
        if (workflowAnnotation == null) {
            return bean;
        }

        log.info("Found @Workflow annotated class: {}", targetClass.getName());
        
        try {
            WorkflowDefinition definition = buildWorkflowDefinition(bean, targetClass, workflowAnnotation);
            registry.register(definition);
            workflowBeans.put(definition.workflowId(), bean);
            log.info("Registered workflow: {} with {} steps", 
                    definition.workflowId(), definition.steps().size());
        } catch (Exception e) {
            log.error("Failed to register workflow from class: {}", targetClass.getName(), e);
        }

        return bean;
    }

    /**
     * Builds a WorkflowDefinition from an annotated class.
     */
    private WorkflowDefinition buildWorkflowDefinition(
            Object bean, Class<?> targetClass, Workflow annotation) {
        
        String workflowId = annotation.id().isEmpty() 
                ? targetClass.getSimpleName() 
                : annotation.id();

        List<WorkflowStepDefinition> steps = new ArrayList<>();
        Method onStepCompleteMethod = null;
        Method onWorkflowCompleteMethod = null;
        Method onWorkflowErrorMethod = null;

        // Scan methods for annotations
        for (Method method : targetClass.getDeclaredMethods()) {
            WorkflowStep stepAnnotation = AnnotationUtils.findAnnotation(method, WorkflowStep.class);
            if (stepAnnotation != null) {
                steps.add(buildStepDefinition(workflowId, bean, method, stepAnnotation));
            }

            if (AnnotationUtils.findAnnotation(method, OnStepComplete.class) != null) {
                onStepCompleteMethod = method;
            }
            if (AnnotationUtils.findAnnotation(method, OnWorkflowComplete.class) != null) {
                onWorkflowCompleteMethod = method;
            }
            if (AnnotationUtils.findAnnotation(method, OnWorkflowError.class) != null) {
                onWorkflowErrorMethod = method;
            }
        }

        // Sort steps by order
        steps.sort(Comparator.comparingInt(WorkflowStepDefinition::order));

        return WorkflowDefinition.builder()
                .workflowId(workflowId)
                .name(annotation.name().isEmpty() ? workflowId : annotation.name())
                .description(annotation.description())
                .version(annotation.version())
                .triggerMode(annotation.triggerMode())
                .triggerEventType(annotation.triggerEventType())
                .timeout(annotation.timeoutMs() > 0 ? Duration.ofMillis(annotation.timeoutMs()) : Duration.ofHours(1))
                .retryPolicy(buildDefaultRetryPolicy(annotation))
                .steps(steps)
                .workflowBean(bean)
                .onStepCompleteMethod(onStepCompleteMethod)
                .onWorkflowCompleteMethod(onWorkflowCompleteMethod)
                .onWorkflowErrorMethod(onWorkflowErrorMethod)
                .build();
    }

    /**
     * Builds a WorkflowStepDefinition from an annotated method.
     */
    private WorkflowStepDefinition buildStepDefinition(
            String workflowId, Object bean, Method method, WorkflowStep annotation) {

        String stepId = annotation.id().isEmpty() ? method.getName() : annotation.id();

        // Create a StepHandler that wraps the annotated method and register it
        StepHandler<?> handler = new MethodStepHandler(bean, method);
        String handlerKey = buildHandlerKey(workflowId, stepId);
        stepHandlers.put(handlerKey, handler);
        log.debug("Registered step handler: {}", handlerKey);

        RetryPolicy retryPolicy = annotation.maxRetries() >= 0
                ? new RetryPolicy(
                        annotation.maxRetries(),
                        Duration.ofMillis(annotation.retryDelayMs() >= 0 ? annotation.retryDelayMs() : 1000),
                        Duration.ofMinutes(5),
                        1.5,
                        new String[]{})
                : null;

        // Convert dependsOn array to list
        List<String> dependsOnList = annotation.dependsOn().length > 0
                ? List.of(annotation.dependsOn())
                : List.of();

        return WorkflowStepDefinition.builder()
                .stepId(stepId)
                .name(annotation.name().isEmpty() ? stepId : annotation.name())
                .description(annotation.description())
                .dependsOn(dependsOnList)
                .order(annotation.order())
                .triggerMode(annotation.triggerMode())
                .async(annotation.async())
                .timeout(annotation.timeoutMs() > 0 ? Duration.ofMillis(annotation.timeoutMs()) : null)
                .retryPolicy(retryPolicy)
                .condition(annotation.condition().isEmpty() ? null : annotation.condition())
                .inputEventType(annotation.inputEventType().isEmpty() ? null : annotation.inputEventType())
                .outputEventType(annotation.outputEventType().isEmpty() ? null : annotation.outputEventType())
                .build();
    }

    /**
     * Builds default retry policy from workflow annotation.
     */
    private RetryPolicy buildDefaultRetryPolicy(Workflow annotation) {
        if (annotation.maxRetries() == 0) {
            return null;
        }
        return new RetryPolicy(
                annotation.maxRetries(),
                Duration.ofMillis(annotation.retryDelayMs()),
                Duration.ofMinutes(5),
                1.0,
                new String[]{}
        );
    }

    /**
     * Gets the bean instance for a workflow.
     */
    public Object getWorkflowBean(String workflowId) {
        return workflowBeans.get(workflowId);
    }

    /**
     * Gets a step handler by workflow ID and step ID.
     *
     * @param workflowId the workflow ID
     * @param stepId the step ID
     * @return the step handler or null if not found
     */
    public StepHandler<?> getStepHandler(String workflowId, String stepId) {
        return stepHandlers.get(buildHandlerKey(workflowId, stepId));
    }

    /**
     * Checks if a step handler exists for the given workflow and step.
     *
     * @param workflowId the workflow ID
     * @param stepId the step ID
     * @return true if the handler exists
     */
    public boolean hasStepHandler(String workflowId, String stepId) {
        return stepHandlers.containsKey(buildHandlerKey(workflowId, stepId));
    }

    /**
     * Builds the handler registry key.
     */
    private String buildHandlerKey(String workflowId, String stepId) {
        return workflowId + ":" + stepId;
    }

    /**
     * StepHandler implementation that wraps an annotated method.
     */
    @RequiredArgsConstructor
    private static class MethodStepHandler implements StepHandler {
        private final Object bean;
        private final Method method;

        @Override
        @SuppressWarnings("unchecked")
        public Mono<Object> execute(WorkflowContext context) {
            try {
                method.setAccessible(true);
                Object result = invokeMethod(context);
                
                if (result instanceof Mono) {
                    return (Mono<Object>) result;
                } else if (result == null) {
                    return Mono.empty();
                } else {
                    return Mono.just(result);
                }
            } catch (Exception e) {
                return Mono.error(e);
            }
        }

        private Object invokeMethod(WorkflowContext context) throws Exception {
            Class<?>[] paramTypes = method.getParameterTypes();
            
            if (paramTypes.length == 0) {
                return method.invoke(bean);
            } else if (paramTypes.length == 1 && paramTypes[0].isAssignableFrom(WorkflowContext.class)) {
                return method.invoke(bean, context);
            } else if (paramTypes.length == 1 && paramTypes[0].isAssignableFrom(Map.class)) {
                return method.invoke(bean, context.getAllInput());
            } else {
                // Try to match parameters by name/type
                Object[] args = new Object[paramTypes.length];
                for (int i = 0; i < paramTypes.length; i++) {
                    if (paramTypes[i].isAssignableFrom(WorkflowContext.class)) {
                        args[i] = context;
                    } else if (paramTypes[i].isAssignableFrom(Map.class)) {
                        args[i] = context.getAllInput();
                    } else {
                        args[i] = null;
                    }
                }
                return method.invoke(bean, args);
            }
        }

        @Override
        public Mono<Void> compensate(WorkflowContext context) {
            // Look for a compensation method with naming convention: methodName_compensate
            try {
                Method compensateMethod = bean.getClass().getDeclaredMethod(
                        method.getName() + "_compensate", WorkflowContext.class);
                compensateMethod.setAccessible(true);
                Object result = compensateMethod.invoke(bean, context);
                if (result instanceof Mono) {
                    return ((Mono<?>) result).then();
                }
                return Mono.empty();
            } catch (NoSuchMethodException e) {
                // No compensation method defined
                return Mono.empty();
            } catch (Exception e) {
                return Mono.error(e);
            }
        }
    }
}
