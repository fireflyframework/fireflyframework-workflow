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

import org.fireflyframework.workflow.event.WorkflowEventPublisher;
import org.fireflyframework.workflow.exception.WorkflowNotFoundException;
import org.fireflyframework.workflow.model.*;
import org.fireflyframework.workflow.properties.WorkflowProperties;
import org.fireflyframework.workflow.state.WorkflowStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for WorkflowEngine.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowEngineTest {

    @Mock
    private WorkflowRegistry registry;
    
    @Mock
    private WorkflowExecutor executor;
    
    @Mock
    private WorkflowStateStore stateStore;
    
    @Mock
    private WorkflowEventPublisher eventPublisher;

    private WorkflowProperties properties;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        properties = createTestProperties();
        engine = new WorkflowEngine(registry, executor, stateStore, eventPublisher, properties);
    }

    @Test
    void shouldStartWorkflow() {
        WorkflowDefinition definition = createTestDefinition();
        when(registry.get("test-workflow")).thenReturn(Optional.of(definition));
        when(stateStore.save(any(WorkflowInstance.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(eventPublisher.publishWorkflowStarted(any())).thenReturn(Mono.empty());
        when(executor.executeWorkflow(any(), any())).thenAnswer(inv -> {
            WorkflowInstance instance = inv.getArgument(1);
            return Mono.just(instance.complete(Map.of("result", "done")));
        });

        StepVerifier.create(engine.startWorkflow("test-workflow", Map.of("input", "value")))
                .assertNext(instance -> {
                    assertThat(instance.workflowId()).isEqualTo("test-workflow");
                    assertThat(instance.status()).isEqualTo(WorkflowStatus.COMPLETED);
                })
                .verifyComplete();

        verify(stateStore).save(any(WorkflowInstance.class));
        verify(eventPublisher).publishWorkflowStarted(any());
        verify(executor).executeWorkflow(eq(definition), any());
    }

    @Test
    void shouldFailWhenWorkflowNotFound() {
        when(registry.get("unknown")).thenReturn(Optional.empty());

        StepVerifier.create(engine.startWorkflow("unknown", Map.of()))
                .expectError(WorkflowNotFoundException.class)
                .verify();
    }

    @Test
    void shouldGetWorkflowStatus() {
        WorkflowInstance instance = createTestInstance(WorkflowStatus.RUNNING);
        when(stateStore.findByWorkflowAndInstanceId("test-workflow", "instance-1"))
                .thenReturn(Mono.just(instance));

        StepVerifier.create(engine.getStatus("test-workflow", "instance-1"))
                .assertNext(result -> {
                    assertThat(result.instanceId()).isEqualTo("instance-1");
                    assertThat(result.status()).isEqualTo(WorkflowStatus.RUNNING);
                })
                .verifyComplete();
    }

    @Test
    void shouldFailGetStatusWhenNotFound() {
        when(stateStore.findByWorkflowAndInstanceId("test-workflow", "unknown"))
                .thenReturn(Mono.empty());

        StepVerifier.create(engine.getStatus("test-workflow", "unknown"))
                .expectError(WorkflowNotFoundException.class)
                .verify();
    }

    @Test
    void shouldCollectResult() {
        WorkflowInstance completed = createTestInstance(WorkflowStatus.COMPLETED)
                .complete(Map.of("orderId", "123"));
        
        when(stateStore.findByWorkflowAndInstanceId("test-workflow", "instance-1"))
                .thenReturn(Mono.just(completed));

        StepVerifier.create(engine.collectResult("test-workflow", "instance-1", Map.class))
                .assertNext(result -> assertThat(result).containsEntry("orderId", "123"))
                .verifyComplete();
    }

    @Test
    void shouldFailCollectResultWhenNotComplete() {
        WorkflowInstance running = createTestInstance(WorkflowStatus.RUNNING);
        when(stateStore.findByWorkflowAndInstanceId("test-workflow", "instance-1"))
                .thenReturn(Mono.just(running));

        StepVerifier.create(engine.collectResult("test-workflow", "instance-1", Map.class))
                .expectErrorMatches(e -> e instanceof IllegalStateException &&
                        e.getMessage().contains("not completed"))
                .verify();
    }

    @Test
    void shouldCancelWorkflow() {
        WorkflowInstance running = createTestInstance(WorkflowStatus.RUNNING);
        when(stateStore.findByWorkflowAndInstanceId("test-workflow", "instance-1"))
                .thenReturn(Mono.just(running));
        when(stateStore.save(any(WorkflowInstance.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(eventPublisher.publishWorkflowCancelled(any())).thenReturn(Mono.empty());

        StepVerifier.create(engine.cancelWorkflow("test-workflow", "instance-1"))
                .assertNext(instance -> {
                    assertThat(instance.status()).isEqualTo(WorkflowStatus.CANCELLED);
                })
                .verifyComplete();

        verify(eventPublisher).publishWorkflowCancelled(any());
    }

    @Test
    void shouldFailCancelWhenAlreadyTerminal() {
        WorkflowInstance completed = createTestInstance(WorkflowStatus.COMPLETED);
        when(stateStore.findByWorkflowAndInstanceId("test-workflow", "instance-1"))
                .thenReturn(Mono.just(completed));

        StepVerifier.create(engine.cancelWorkflow("test-workflow", "instance-1"))
                .expectErrorMatches(e -> e instanceof IllegalStateException &&
                        e.getMessage().contains("Cannot cancel"))
                .verify();
    }

    @Test
    void shouldRetryFailedWorkflow() {
        WorkflowDefinition definition = createTestDefinition();
        WorkflowInstance failed = new WorkflowInstance(
                "instance-1", "test-workflow", "Test", "1.0.0",
                WorkflowStatus.FAILED, "step-1",
                Map.of(), Map.of("input", "value"), null, List.of(),
                "Error", "RuntimeException", "corr-1", "api",
                null, null, null
        );
        
        when(registry.get("test-workflow", "1.0.0")).thenReturn(Optional.of(definition));
        when(stateStore.findByWorkflowAndInstanceId("test-workflow", "instance-1"))
                .thenReturn(Mono.just(failed));
        when(stateStore.save(any(WorkflowInstance.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(executor.executeStep(any(), any(), any(), eq("retry"))).thenAnswer(inv -> {
            WorkflowInstance instance = inv.getArgument(1);
            return Mono.just(instance.complete(Map.of("retried", true)));
        });

        StepVerifier.create(engine.retryWorkflow("test-workflow", "instance-1"))
                .assertNext(instance -> {
                    assertThat(instance.status()).isEqualTo(WorkflowStatus.COMPLETED);
                })
                .verifyComplete();
    }

    @Test
    void shouldFailRetryWhenNotFailed() {
        WorkflowInstance running = createTestInstance(WorkflowStatus.RUNNING);
        when(stateStore.findByWorkflowAndInstanceId("test-workflow", "instance-1"))
                .thenReturn(Mono.just(running));

        StepVerifier.create(engine.retryWorkflow("test-workflow", "instance-1"))
                .expectErrorMatches(e -> e instanceof IllegalStateException &&
                        e.getMessage().contains("only retry failed"))
                .verify();
    }

    @Test
    void shouldFindInstances() {
        WorkflowInstance instance1 = createTestInstance(WorkflowStatus.RUNNING);
        WorkflowInstance instance2 = createTestInstance(WorkflowStatus.COMPLETED);
        
        when(stateStore.findByWorkflowId("test-workflow"))
                .thenReturn(Flux.just(instance1, instance2));

        StepVerifier.create(engine.findInstances("test-workflow"))
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void shouldFindInstancesByStatus() {
        WorkflowInstance running = createTestInstance(WorkflowStatus.RUNNING);
        when(stateStore.findByWorkflowIdAndStatus("test-workflow", WorkflowStatus.RUNNING))
                .thenReturn(Flux.just(running));

        StepVerifier.create(engine.findInstances("test-workflow", WorkflowStatus.RUNNING))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void shouldRegisterAndUnregisterWorkflow() {
        WorkflowDefinition definition = createTestDefinition();
        
        engine.registerWorkflow(definition);
        verify(registry).register(definition);
        
        when(registry.unregister("test-workflow")).thenReturn(true);
        assertThat(engine.unregisterWorkflow("test-workflow")).isTrue();
    }

    @Test
    void shouldCheckHealth() {
        when(stateStore.isHealthy()).thenReturn(Mono.just(true));

        StepVerifier.create(engine.isHealthy())
                .assertNext(healthy -> assertThat(healthy).isTrue())
                .verifyComplete();
    }

    private WorkflowProperties createTestProperties() {
        WorkflowProperties props = new WorkflowProperties();
        props.setDefaultTimeout(Duration.ofMinutes(5));
        props.setDefaultStepTimeout(Duration.ofSeconds(30));
        return props;
    }

    private WorkflowDefinition createTestDefinition() {
        return WorkflowDefinition.builder()
                .workflowId("test-workflow")
                .name("Test Workflow")
                .steps(List.of(
                        WorkflowStepDefinition.builder()
                                .stepId("step-1")
                                .name("Step 1")
                                .order(1)
                                .handlerBeanName("testStepHandler")
                                .build()
                ))
                .build();
    }

    private WorkflowInstance createTestInstance(WorkflowStatus status) {
        return new WorkflowInstance(
                "instance-1", "test-workflow", "Test Workflow", "1.0.0",
                status, "step-1",
                Map.of(), Map.of("input", "value"), null, List.of(),
                null, null, "corr-1", "api",
                null, null, null
        );
    }
}
