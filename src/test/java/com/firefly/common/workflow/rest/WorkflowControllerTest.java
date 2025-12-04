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

package com.firefly.common.workflow.rest;

import com.firefly.common.workflow.core.WorkflowEngine;
import com.firefly.common.workflow.exception.WorkflowNotFoundException;
import com.firefly.common.workflow.model.*;
import com.firefly.common.workflow.rest.dto.StartWorkflowRequest;
import com.firefly.common.workflow.rest.dto.WorkflowStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for WorkflowController.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowControllerTest {

    @Mock
    private WorkflowEngine workflowEngine;

    private WorkflowController controller;

    @BeforeEach
    void setUp() {
        controller = new WorkflowController(workflowEngine);
    }

    @Test
    void shouldListWorkflows() {
        WorkflowDefinition workflow1 = createTestDefinition("workflow-1");
        WorkflowDefinition workflow2 = createTestDefinition("workflow-2");
        when(workflowEngine.getAllWorkflows()).thenReturn(List.of(workflow1, workflow2));

        StepVerifier.create(controller.listWorkflows())
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody()).hasSize(2);
                })
                .verifyComplete();
    }

    @Test
    void shouldGetWorkflow() {
        WorkflowDefinition definition = createTestDefinition("test-workflow");
        when(workflowEngine.getWorkflowDefinition("test-workflow"))
                .thenReturn(Optional.of(definition));

        StepVerifier.create(controller.getWorkflow("test-workflow"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody().workflowId()).isEqualTo("test-workflow");
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnNotFoundForUnknownWorkflow() {
        when(workflowEngine.getWorkflowDefinition("unknown")).thenReturn(Optional.empty());

        StepVerifier.create(controller.getWorkflow("unknown"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verifyComplete();
    }

    @Test
    void shouldStartWorkflow() {
        WorkflowInstance instance = createTestInstance(WorkflowStatus.RUNNING);
        WorkflowDefinition definition = createTestDefinition("test-workflow");
        
        when(workflowEngine.startWorkflow(eq("test-workflow"), any(), any(), eq("api")))
                .thenReturn(Mono.just(instance));
        when(workflowEngine.getWorkflowDefinition("test-workflow"))
                .thenReturn(Optional.of(definition));

        StartWorkflowRequest request = new StartWorkflowRequest();
        request.setInput(Map.of("orderId", "123"));

        StepVerifier.create(controller.startWorkflow("test-workflow", request))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                    WorkflowStatusResponse body = response.getBody();
                    assertThat(body.getWorkflowId()).isEqualTo("test-workflow");
                    assertThat(body.getStatus()).isEqualTo(WorkflowStatus.RUNNING);
                })
                .verifyComplete();
    }

    @Test
    void shouldStartWorkflowWithEmptyRequest() {
        WorkflowInstance instance = createTestInstance(WorkflowStatus.RUNNING);
        WorkflowDefinition definition = createTestDefinition("test-workflow");
        
        when(workflowEngine.startWorkflow(eq("test-workflow"), any(), any(), eq("api")))
                .thenReturn(Mono.just(instance));
        when(workflowEngine.getWorkflowDefinition("test-workflow"))
                .thenReturn(Optional.of(definition));

        StepVerifier.create(controller.startWorkflow("test-workflow", null))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnNotFoundWhenStartingUnknownWorkflow() {
        when(workflowEngine.startWorkflow(eq("unknown"), any(), any(), eq("api")))
                .thenReturn(Mono.error(new WorkflowNotFoundException("unknown")));

        StepVerifier.create(controller.startWorkflow("unknown", new StartWorkflowRequest()))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verifyComplete();
    }

    @Test
    void shouldGetStatus() {
        WorkflowInstance instance = createTestInstance(WorkflowStatus.RUNNING);
        WorkflowDefinition definition = createTestDefinition("test-workflow");
        
        when(workflowEngine.getStatus("test-workflow", "instance-1"))
                .thenReturn(Mono.just(instance));
        when(workflowEngine.getWorkflowDefinition("test-workflow"))
                .thenReturn(Optional.of(definition));

        StepVerifier.create(controller.getStatus("test-workflow", "instance-1"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody().getInstanceId()).isEqualTo("instance-1");
                    assertThat(response.getBody().getStatus()).isEqualTo(WorkflowStatus.RUNNING);
                })
                .verifyComplete();
    }

    @Test
    void shouldCollectResultWhenCompleted() {
        WorkflowInstance completed = createTestInstance(WorkflowStatus.COMPLETED);
        
        when(workflowEngine.getStatus("test-workflow", "instance-1"))
                .thenReturn(Mono.just(completed));

        StepVerifier.create(controller.collectResult("test-workflow", "instance-1"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = (Map<String, Object>) response.getBody();
                    assertThat(body.get("status")).isEqualTo("COMPLETED");
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnAcceptedWhenStillRunning() {
        WorkflowInstance running = createTestInstance(WorkflowStatus.RUNNING);
        
        when(workflowEngine.getStatus("test-workflow", "instance-1"))
                .thenReturn(Mono.just(running));

        StepVerifier.create(controller.collectResult("test-workflow", "instance-1"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
                })
                .verifyComplete();
    }

    @Test
    void shouldCancelWorkflow() {
        WorkflowInstance cancelled = createTestInstance(WorkflowStatus.CANCELLED);
        WorkflowDefinition definition = createTestDefinition("test-workflow");
        
        when(workflowEngine.cancelWorkflow("test-workflow", "instance-1"))
                .thenReturn(Mono.just(cancelled));
        when(workflowEngine.getWorkflowDefinition("test-workflow"))
                .thenReturn(Optional.of(definition));

        StepVerifier.create(controller.cancelWorkflow("test-workflow", "instance-1"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody().getStatus()).isEqualTo(WorkflowStatus.CANCELLED);
                })
                .verifyComplete();
    }

    @Test
    void shouldRetryWorkflow() {
        WorkflowInstance retried = createTestInstance(WorkflowStatus.RUNNING);
        WorkflowDefinition definition = createTestDefinition("test-workflow");
        
        when(workflowEngine.retryWorkflow("test-workflow", "instance-1"))
                .thenReturn(Mono.just(retried));
        when(workflowEngine.getWorkflowDefinition("test-workflow"))
                .thenReturn(Optional.of(definition));

        StepVerifier.create(controller.retryWorkflow("test-workflow", "instance-1"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                })
                .verifyComplete();
    }

    @Test
    void shouldListInstances() {
        WorkflowInstance instance1 = createTestInstance(WorkflowStatus.RUNNING);
        WorkflowInstance instance2 = createTestInstance(WorkflowStatus.COMPLETED);
        WorkflowDefinition definition = createTestDefinition("test-workflow");
        
        when(workflowEngine.findInstances("test-workflow"))
                .thenReturn(Flux.just(instance1, instance2));
        when(workflowEngine.getWorkflowDefinition("test-workflow"))
                .thenReturn(Optional.of(definition));

        StepVerifier.create(controller.listInstances("test-workflow", null))
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void shouldListInstancesByStatus() {
        WorkflowInstance running = createTestInstance(WorkflowStatus.RUNNING);
        WorkflowDefinition definition = createTestDefinition("test-workflow");
        
        when(workflowEngine.findInstances("test-workflow", WorkflowStatus.RUNNING))
                .thenReturn(Flux.just(running));
        when(workflowEngine.getWorkflowDefinition("test-workflow"))
                .thenReturn(Optional.of(definition));

        StepVerifier.create(controller.listInstances("test-workflow", WorkflowStatus.RUNNING))
                .expectNextCount(1)
                .verifyComplete();
    }

    private WorkflowDefinition createTestDefinition(String id) {
        return WorkflowDefinition.builder()
                .workflowId(id)
                .name("Test Workflow " + id)
                .triggerMode(TriggerMode.BOTH)
                .steps(List.of(
                        WorkflowStepDefinition.builder()
                                .stepId("step-1")
                                .name("Step 1")
                                .order(1)
                                .build()
                ))
                .build();
    }

    private WorkflowInstance createTestInstance(WorkflowStatus status) {
        return new WorkflowInstance(
                "instance-1", "test-workflow", "Test Workflow", "1.0.0",
                status, "step-1",
                Map.of(), Map.of("input", "value"), 
                status == WorkflowStatus.COMPLETED ? Map.of("result", "done") : null,
                List.of(),
                null, null, "corr-1", "api",
                null, null, status.isTerminal() ? java.time.Instant.now() : null
        );
    }
}
