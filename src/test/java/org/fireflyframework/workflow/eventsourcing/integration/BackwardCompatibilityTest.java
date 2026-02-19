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

package org.fireflyframework.workflow.eventsourcing.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.fireflyframework.cache.core.CacheAdapter;
import org.fireflyframework.workflow.core.WorkflowContext;
import org.fireflyframework.workflow.model.WorkflowDefinition;
import org.fireflyframework.workflow.model.WorkflowInstance;
import org.fireflyframework.workflow.model.WorkflowStatus;
import org.fireflyframework.workflow.properties.WorkflowProperties;
import org.fireflyframework.workflow.rest.WorkflowController;
import org.fireflyframework.workflow.rest.dto.SendSignalRequest;
import org.fireflyframework.workflow.service.WorkflowService;
import org.fireflyframework.workflow.state.CacheWorkflowStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Backward compatibility tests verifying that existing workflow functionality
 * works correctly when event sourcing is disabled (cache-only mode).
 * <p>
 * These tests ensure that:
 * <ul>
 *   <li>CacheWorkflowStateStore operates normally without event sourcing</li>
 *   <li>WorkflowContext durable execution methods degrade gracefully when no aggregate</li>
 *   <li>WorkflowController returns 501 NOT_IMPLEMENTED for durable-only endpoints</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Backward Compatibility Tests")
class BackwardCompatibilityTest {

    // ========================================================================
    // Test 1: CacheWorkflowStateStore Operations Work Normally
    // ========================================================================

    @Nested
    @DisplayName("Cache-Only WorkflowStateStore")
    class CacheOnlyWorkflowStateStoreTests {

        @Mock
        private CacheAdapter cacheAdapter;

        private CacheWorkflowStateStore cacheStore;
        private WorkflowProperties properties;

        @BeforeEach
        void setUp() {
            properties = new WorkflowProperties();
            cacheStore = new CacheWorkflowStateStore(cacheAdapter, properties);
        }

        @Test
        @DisplayName("should save and find workflow instances")
        void cacheOnlyWorkflowStateStore_saveAndFind() {
            String instanceId = UUID.randomUUID().toString();
            WorkflowInstance instance = new WorkflowInstance(
                    instanceId, "test-workflow", "Test Workflow", "1.0.0",
                    WorkflowStatus.RUNNING, null, Map.of(), Map.of("key", "value"), null,
                    List.of(), null, null, "corr-123", "test",
                    Instant.now(), Instant.now(), null);

            // Mock cache operations for save
            when(cacheAdapter.put(anyString(), any(), any(Duration.class)))
                    .thenReturn(Mono.empty());

            // Save
            StepVerifier.create(cacheStore.save(instance))
                    .assertNext(saved -> {
                        assertThat(saved.instanceId()).isEqualTo(instanceId);
                        assertThat(saved.workflowId()).isEqualTo("test-workflow");
                        assertThat(saved.status()).isEqualTo(WorkflowStatus.RUNNING);
                    })
                    .verifyComplete();

            // Mock cache operations for find
            when(cacheAdapter.<String, WorkflowInstance>get(anyString()))
                    .thenReturn(Mono.just(Optional.of(instance)));

            // Find
            StepVerifier.create(cacheStore.findById(instanceId))
                    .assertNext(found -> {
                        assertThat(found.instanceId()).isEqualTo(instanceId);
                        assertThat(found.status()).isEqualTo(WorkflowStatus.RUNNING);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should update status correctly")
        void cacheOnlyWorkflowStateStore_updateStatus() {
            String instanceId = UUID.randomUUID().toString();
            WorkflowInstance instance = new WorkflowInstance(
                    instanceId, "test-workflow", "Test Workflow", "1.0.0",
                    WorkflowStatus.RUNNING, null, Map.of(), Map.of(), null,
                    List.of(), null, null, null, "test",
                    Instant.now(), Instant.now(), null);

            // Mock find
            when(cacheAdapter.<String, WorkflowInstance>get(contains(instanceId)))
                    .thenReturn(Mono.just(Optional.of(instance)));

            // Mock evict for old status index
            when(cacheAdapter.evict(anyString()))
                    .thenReturn(Mono.just(true));

            // Mock save for updated instance
            when(cacheAdapter.put(anyString(), any(), any(Duration.class)))
                    .thenReturn(Mono.empty());

            StepVerifier.create(cacheStore.updateStatus(instanceId, WorkflowStatus.RUNNING, WorkflowStatus.COMPLETED))
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("should delete workflow instances")
        void cacheOnlyWorkflowStateStore_delete() {
            String instanceId = UUID.randomUUID().toString();
            WorkflowInstance instance = new WorkflowInstance(
                    instanceId, "test-workflow", "Test Workflow", "1.0.0",
                    WorkflowStatus.COMPLETED, null, Map.of(), Map.of(), null,
                    List.of(), null, null, null, "test",
                    Instant.now(), Instant.now(), Instant.now());

            // Mock find
            when(cacheAdapter.<String, WorkflowInstance>get(contains(instanceId)))
                    .thenReturn(Mono.just(Optional.of(instance)));

            // Mock evict
            when(cacheAdapter.evict(anyString()))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(cacheStore.delete(instanceId))
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("should report health based on cache availability")
        void cacheOnlyWorkflowStateStore_health() {
            when(cacheAdapter.isAvailable()).thenReturn(true);

            StepVerifier.create(cacheStore.isHealthy())
                    .expectNext(true)
                    .verifyComplete();
        }
    }

    // ========================================================================
    // Test 2: WorkflowContext Without Aggregate
    // ========================================================================

    @Nested
    @DisplayName("WorkflowContext Without Aggregate")
    class WorkflowContextWithoutAggregateTests {

        private WorkflowContext context;

        @BeforeEach
        void setUp() {
            String instanceId = UUID.randomUUID().toString();
            WorkflowInstance instance = new WorkflowInstance(
                    instanceId, "test-workflow", "Test Workflow", "1.0.0",
                    WorkflowStatus.RUNNING, "step-1", Map.of(), Map.of("key", "value"), null,
                    List.of(), null, null, "corr-123", "test",
                    Instant.now(), Instant.now(), null);

            WorkflowDefinition definition = WorkflowDefinition.builder()
                    .workflowId("test-workflow")
                    .name("Test Workflow")
                    .version("1.0.0")
                    .build();

            // Create context WITHOUT aggregate (cache-only mode)
            context = new WorkflowContext(definition, instance, "step-1", new ObjectMapper());
        }

        @Test
        @DisplayName("heartbeat should be a no-op when no aggregate is present")
        void heartbeat_shouldBeNoOp() {
            // Should not throw — just logs at debug level
            context.heartbeat(Map.of("progress", 50));

            // Verify aggregate is null
            assertThat(context.getAggregate()).isNull();
        }

        @Test
        @DisplayName("sideEffect should execute supplier directly without recording")
        void sideEffect_shouldExecuteDirectly() {
            // Without an aggregate, sideEffect should execute the supplier directly
            String result = context.sideEffect("test-id", () -> "direct-value");

            assertThat(result).isEqualTo("direct-value");

            // Calling again should execute the supplier again (no replay)
            String result2 = context.sideEffect("test-id", () -> "different-value");
            assertThat(result2).isEqualTo("different-value");
        }

        @Test
        @DisplayName("startChildWorkflow should return error without aggregate")
        void startChildWorkflow_shouldReturnError() {
            StepVerifier.create(context.startChildWorkflow("child-wf", Map.of("input", "data")))
                    .expectErrorMatches(error ->
                            error instanceof UnsupportedOperationException &&
                                    error.getMessage().contains("durable execution mode"))
                    .verify();
        }

        @Test
        @DisplayName("context should provide basic workflow information correctly")
        void context_shouldProvideBasicInfo() {
            assertThat(context.getWorkflowId()).isEqualTo("test-workflow");
            assertThat(context.getCorrelationId()).isEqualTo("corr-123");
            assertThat(context.getCurrentStepId()).isEqualTo("step-1");
            assertThat(context.getInput("key")).isPresent().hasValue("value");
            assertThat(context.isDryRun()).isFalse();
        }
    }

    // ========================================================================
    // Test 3: WorkflowController Without Durable Services
    // ========================================================================

    @Nested
    @DisplayName("WorkflowController Without Durable Services")
    class WorkflowControllerWithoutDurableServicesTests {

        @Mock
        private WorkflowService workflowService;

        private WorkflowController controller;

        @BeforeEach
        void setUp() {
            // Create controller with null durable services (signal, query, search)
            controller = new WorkflowController(workflowService, null, null, null);
        }

        @Test
        @DisplayName("signal endpoint should return 501 NOT_IMPLEMENTED when signalService is null")
        void signalEndpoint_shouldReturn501() {
            SendSignalRequest request = new SendSignalRequest();
            request.setSignalName("test-signal");
            request.setPayload(Map.of("key", "value"));

            StepVerifier.create(controller.sendSignal("wf-1", "inst-1", request))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(501);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("query endpoint should return 501 NOT_IMPLEMENTED when queryService is null")
        void queryEndpoint_shouldReturn501() {
            StepVerifier.create(controller.executeQuery("wf-1", "inst-1", "getStatus"))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(501);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("search endpoint should return 501 NOT_IMPLEMENTED when searchService is null")
        void searchEndpoint_shouldReturn501() {
            StepVerifier.create(controller.searchByAttributes(Map.of("region", "us-east-1")))
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(501);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("standard endpoints should still work without durable services")
        void standardEndpoints_shouldStillWork() {
            // List workflows should work
            when(workflowService.listWorkflows()).thenReturn(List.of());

            StepVerifier.create(controller.listWorkflows())
                    .assertNext(response -> {
                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        assertThat(response.getBody()).isNotNull();
                        assertThat(response.getBody()).isEmpty();
                    })
                    .verifyComplete();
        }
    }
}
