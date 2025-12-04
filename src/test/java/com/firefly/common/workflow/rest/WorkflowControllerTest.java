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

import com.firefly.common.workflow.exception.WorkflowNotFoundException;
import com.firefly.common.workflow.model.*;
import com.firefly.common.workflow.rest.dto.StartWorkflowRequest;
import com.firefly.common.workflow.rest.dto.WorkflowStatusResponse;
import com.firefly.common.workflow.service.WorkflowService;
import com.firefly.common.workflow.service.WorkflowService.WorkflowResult;
import com.firefly.common.workflow.service.WorkflowService.WorkflowSummary;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for WorkflowController.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowControllerTest {

    @Mock
    private WorkflowService workflowService;

    private WorkflowController controller;

    @BeforeEach
    void setUp() {
        controller = new WorkflowController(workflowService);
    }

    @Test
    void shouldListWorkflows() {
        WorkflowSummary summary1 = new WorkflowSummary("workflow-1", "Workflow 1", "1.0.0", "Description", 1);
        WorkflowSummary summary2 = new WorkflowSummary("workflow-2", "Workflow 2", "1.0.0", "Description", 1);
        when(workflowService.listWorkflows()).thenReturn(List.of(summary1, summary2));

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
        when(workflowService.getWorkflowDefinition("test-workflow"))
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
        when(workflowService.getWorkflowDefinition("unknown")).thenReturn(Optional.empty());

        StepVerifier.create(controller.getWorkflow("unknown"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verifyComplete();
    }

    @Test
    void shouldStartWorkflow() {
        WorkflowStatusResponse statusResponse = createTestStatusResponse(WorkflowStatus.RUNNING);

        when(workflowService.startWorkflow(eq("test-workflow"), any(), any(), eq("api"), anyBoolean(), anyLong()))
                .thenReturn(Mono.just(statusResponse));

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
        WorkflowStatusResponse statusResponse = createTestStatusResponse(WorkflowStatus.RUNNING);

        when(workflowService.startWorkflow(eq("test-workflow"), any(), any(), eq("api"), anyBoolean(), anyLong()))
                .thenReturn(Mono.just(statusResponse));

        StepVerifier.create(controller.startWorkflow("test-workflow", null))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnNotFoundWhenStartingUnknownWorkflow() {
        when(workflowService.startWorkflow(eq("unknown"), any(), any(), eq("api"), anyBoolean(), anyLong()))
                .thenReturn(Mono.error(new WorkflowNotFoundException("unknown")));

        StepVerifier.create(controller.startWorkflow("unknown", new StartWorkflowRequest()))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                })
                .verifyComplete();
    }

    @Test
    void shouldGetStatus() {
        WorkflowStatusResponse statusResponse = createTestStatusResponse(WorkflowStatus.RUNNING);

        when(workflowService.getStatus("test-workflow", "instance-1"))
                .thenReturn(Mono.just(statusResponse));

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
        WorkflowResult result = new WorkflowResult(
                "instance-1", "test-workflow", WorkflowStatus.COMPLETED,
                Map.of("result", "done"), null, null
        );

        when(workflowService.collectResult("test-workflow", "instance-1"))
                .thenReturn(Mono.just(result));

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
        WorkflowResult result = new WorkflowResult(
                "instance-1", "test-workflow", WorkflowStatus.RUNNING,
                null, null, null
        );

        when(workflowService.collectResult("test-workflow", "instance-1"))
                .thenReturn(Mono.just(result));

        StepVerifier.create(controller.collectResult("test-workflow", "instance-1"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
                })
                .verifyComplete();
    }

    @Test
    void shouldCancelWorkflow() {
        WorkflowStatusResponse statusResponse = createTestStatusResponse(WorkflowStatus.CANCELLED);

        when(workflowService.cancelWorkflow("test-workflow", "instance-1"))
                .thenReturn(Mono.just(statusResponse));

        StepVerifier.create(controller.cancelWorkflow("test-workflow", "instance-1"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody().getStatus()).isEqualTo(WorkflowStatus.CANCELLED);
                })
                .verifyComplete();
    }

    @Test
    void shouldRetryWorkflow() {
        WorkflowStatusResponse statusResponse = createTestStatusResponse(WorkflowStatus.RUNNING);

        when(workflowService.retryWorkflow("test-workflow", "instance-1"))
                .thenReturn(Mono.just(statusResponse));

        StepVerifier.create(controller.retryWorkflow("test-workflow", "instance-1"))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                })
                .verifyComplete();
    }

    @Test
    void shouldListInstances() {
        WorkflowStatusResponse response1 = createTestStatusResponse(WorkflowStatus.RUNNING);
        WorkflowStatusResponse response2 = createTestStatusResponse(WorkflowStatus.COMPLETED);

        when(workflowService.findInstances("test-workflow"))
                .thenReturn(Flux.just(response1, response2));

        StepVerifier.create(controller.listInstances("test-workflow", null))
                .expectNextCount(2)
                .verifyComplete();
    }

    @Test
    void shouldListInstancesByStatus() {
        WorkflowStatusResponse statusResponse = createTestStatusResponse(WorkflowStatus.RUNNING);

        when(workflowService.findInstances("test-workflow", WorkflowStatus.RUNNING))
                .thenReturn(Flux.just(statusResponse));

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

    private WorkflowStatusResponse createTestStatusResponse(WorkflowStatus status) {
        return WorkflowStatusResponse.builder()
                .instanceId("instance-1")
                .workflowId("test-workflow")
                .workflowName("Test Workflow")
                .workflowVersion("1.0.0")
                .status(status)
                .currentStepId("step-1")
                .progress(status == WorkflowStatus.COMPLETED ? 100 : 50)
                .correlationId("corr-1")
                .triggeredBy("api")
                .steps(List.of())
                .build();
    }
}
