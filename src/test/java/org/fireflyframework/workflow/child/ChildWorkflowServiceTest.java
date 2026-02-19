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

package org.fireflyframework.workflow.child;

import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.eventsourcing.store.EventSourcedWorkflowStateStore;
import org.fireflyframework.workflow.exception.WorkflowNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ChildWorkflowService}.
 * <p>
 * Tests cover spawning child workflows, completing child workflows,
 * cancelling child workflows, and querying child workflow status.
 */
@ExtendWith(MockitoExtension.class)
class ChildWorkflowServiceTest {

    private static final UUID PARENT_AGGREGATE_ID = UUID.randomUUID();
    private static final String PARENT_INSTANCE_ID = PARENT_AGGREGATE_ID.toString();
    private static final String PARENT_STEP_ID = "step-invoke-child";
    private static final String CHILD_WORKFLOW_ID = "child-processing-workflow";
    private static final Map<String, Object> CHILD_INPUT = Map.of("orderId", "ORD-123", "amount", 99.99);

    @Mock
    private EventSourcedWorkflowStateStore stateStore;

    private ChildWorkflowService childWorkflowService;

    @BeforeEach
    void setUp() {
        childWorkflowService = new ChildWorkflowService(stateStore);
    }

    /**
     * Creates a running WorkflowAggregate (started and committed).
     */
    private WorkflowAggregate createRunningAggregate(UUID aggregateId) {
        WorkflowAggregate aggregate = new WorkflowAggregate(aggregateId);
        aggregate.start("parent-workflow", "Parent Workflow", "1.0.0",
                Map.of("key", "value"), "corr-1", "api", false);
        aggregate.markEventsAsCommitted();
        return aggregate;
    }

    /**
     * Creates a running parent aggregate that has already spawned a child workflow.
     */
    private WorkflowAggregate createParentWithChild(UUID parentId, String childInstanceId) {
        WorkflowAggregate aggregate = createRunningAggregate(parentId);
        aggregate.spawnChildWorkflow(childInstanceId, CHILD_WORKFLOW_ID, CHILD_INPUT, PARENT_STEP_ID);
        aggregate.markEventsAsCommitted();
        return aggregate;
    }

    // ========================================================================
    // spawnChildWorkflow Tests
    // ========================================================================

    @Nested
    @DisplayName("spawnChildWorkflow")
    class SpawnChildWorkflowTests {

        @Test
        @DisplayName("should create child and record on parent")
        void spawnChildWorkflow_shouldCreateChildAndRecordOnParent() {
            WorkflowAggregate parentAggregate = createRunningAggregate(PARENT_AGGREGATE_ID);

            when(stateStore.loadAggregate(PARENT_AGGREGATE_ID)).thenReturn(Mono.just(parentAggregate));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(childWorkflowService.spawnChildWorkflow(
                            PARENT_AGGREGATE_ID, PARENT_STEP_ID, CHILD_WORKFLOW_ID, CHILD_INPUT))
                    .assertNext(result -> {
                        assertThat(result.parentInstanceId()).isEqualTo(PARENT_INSTANCE_ID);
                        assertThat(result.childWorkflowId()).isEqualTo(CHILD_WORKFLOW_ID);
                        assertThat(result.parentStepId()).isEqualTo(PARENT_STEP_ID);
                        assertThat(result.spawned()).isTrue();
                        assertThat(result.childInstanceId()).isNotNull();
                        assertThat(result.childInstanceId()).isNotEmpty();
                    })
                    .verifyComplete();

            // Parent aggregate should have a child workflow recorded
            assertThat(parentAggregate.getChildWorkflows()).hasSize(1);

            // Save should be called twice: once for parent (with spawn event), once for child
            verify(stateStore, times(2)).saveAggregate(any(WorkflowAggregate.class));
        }

        @Test
        @DisplayName("should error when parent ID is invalid")
        void spawnChildWorkflow_withInvalidParentId_shouldError() {
            // Use a null UUID to trigger an error path — the service takes UUID directly,
            // so we test the "parent not found" case instead
            UUID nonExistentId = UUID.randomUUID();
            when(stateStore.loadAggregate(nonExistentId)).thenReturn(Mono.empty());

            StepVerifier.create(childWorkflowService.spawnChildWorkflow(
                            nonExistentId, PARENT_STEP_ID, CHILD_WORKFLOW_ID, CHILD_INPUT))
                    .expectError(WorkflowNotFoundException.class)
                    .verify();

            verify(stateStore).loadAggregate(nonExistentId);
            verify(stateStore, never()).saveAggregate(any());
        }

        @Test
        @DisplayName("should error when parent not found")
        void spawnChildWorkflow_parentNotFound_shouldError() {
            when(stateStore.loadAggregate(PARENT_AGGREGATE_ID)).thenReturn(Mono.empty());

            StepVerifier.create(childWorkflowService.spawnChildWorkflow(
                            PARENT_AGGREGATE_ID, PARENT_STEP_ID, CHILD_WORKFLOW_ID, CHILD_INPUT))
                    .expectError(WorkflowNotFoundException.class)
                    .verify();

            verify(stateStore).loadAggregate(PARENT_AGGREGATE_ID);
            verify(stateStore, never()).saveAggregate(any());
        }
    }

    // ========================================================================
    // completeChildWorkflow Tests
    // ========================================================================

    @Nested
    @DisplayName("completeChildWorkflow")
    class CompleteChildWorkflowTests {

        @Test
        @DisplayName("should notify parent when child completes")
        void completeChildWorkflow_shouldNotifyParent() {
            UUID childInstanceId = UUID.randomUUID();
            String childInstanceIdStr = childInstanceId.toString();
            Object childOutput = Map.of("result", "processed");

            // Create parent with the child already spawned
            WorkflowAggregate parentAggregate = createParentWithChild(PARENT_AGGREGATE_ID, childInstanceIdStr);

            // Register the child-parent mapping by first spawning through the service
            // Instead, we manually register the mapping
            childWorkflowService.registerChildParentMapping(childInstanceIdStr, PARENT_AGGREGATE_ID);

            when(stateStore.loadAggregate(PARENT_AGGREGATE_ID)).thenReturn(Mono.just(parentAggregate));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(childWorkflowService.completeChildWorkflow(
                            childInstanceId, childOutput, true))
                    .verifyComplete();

            // Verify the parent aggregate was updated
            WorkflowAggregate.ChildWorkflowRef childRef = parentAggregate.getChildWorkflows().get(childInstanceIdStr);
            assertThat(childRef).isNotNull();
            assertThat(childRef.completed()).isTrue();
            assertThat(childRef.output()).isEqualTo(childOutput);

            verify(stateStore).loadAggregate(PARENT_AGGREGATE_ID);
            verify(stateStore).saveAggregate(any(WorkflowAggregate.class));
        }

        @Test
        @DisplayName("should handle gracefully when child has no parent mapping")
        void completeChildWorkflow_withUnknownChild_shouldHandleGracefully() {
            UUID unknownChildId = UUID.randomUUID();
            Object childOutput = Map.of("result", "done");

            // No parent mapping registered for this child
            StepVerifier.create(childWorkflowService.completeChildWorkflow(
                            unknownChildId, childOutput, true))
                    .verifyComplete();

            // Should not attempt to load or save any aggregate
            verify(stateStore, never()).loadAggregate(any());
            verify(stateStore, never()).saveAggregate(any());
        }
    }

    // ========================================================================
    // cancelChildWorkflows Tests
    // ========================================================================

    @Nested
    @DisplayName("cancelChildWorkflows")
    class CancelChildWorkflowsTests {

        @Test
        @DisplayName("should cancel all incomplete children")
        void cancelChildWorkflows_shouldCancelAllIncompleteChildren() {
            UUID childId1 = UUID.randomUUID();
            UUID childId2 = UUID.randomUUID();
            String childIdStr1 = childId1.toString();
            String childIdStr2 = childId2.toString();

            // Create parent with two children spawned
            WorkflowAggregate parentAggregate = createRunningAggregate(PARENT_AGGREGATE_ID);
            parentAggregate.spawnChildWorkflow(childIdStr1, CHILD_WORKFLOW_ID, CHILD_INPUT, "step-1");
            parentAggregate.spawnChildWorkflow(childIdStr2, CHILD_WORKFLOW_ID, CHILD_INPUT, "step-2");
            parentAggregate.markEventsAsCommitted();

            // Create child aggregates that are running
            WorkflowAggregate childAggregate1 = createRunningAggregate(childId1);
            WorkflowAggregate childAggregate2 = createRunningAggregate(childId2);

            when(stateStore.loadAggregate(PARENT_AGGREGATE_ID)).thenReturn(Mono.just(parentAggregate));
            when(stateStore.loadAggregate(childId1)).thenReturn(Mono.just(childAggregate1));
            when(stateStore.loadAggregate(childId2)).thenReturn(Mono.just(childAggregate2));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(childWorkflowService.cancelChildWorkflows(PARENT_AGGREGATE_ID))
                    .verifyComplete();

            // Both children should be cancelled
            assertThat(childAggregate1.getStatus().isTerminal()).isTrue();
            assertThat(childAggregate2.getStatus().isTerminal()).isTrue();

            // Load called for parent + 2 children = 3 total
            verify(stateStore, times(3)).loadAggregate(any());
            // Save called for 2 children (parent is not saved)
            verify(stateStore, times(2)).saveAggregate(any(WorkflowAggregate.class));
        }

        @Test
        @DisplayName("should skip already-completed children")
        void cancelChildWorkflows_shouldSkipCompletedChildren() {
            UUID childId1 = UUID.randomUUID();
            UUID childId2 = UUID.randomUUID();
            String childIdStr1 = childId1.toString();
            String childIdStr2 = childId2.toString();

            // Create parent with two children, one already completed
            WorkflowAggregate parentAggregate = createRunningAggregate(PARENT_AGGREGATE_ID);
            parentAggregate.spawnChildWorkflow(childIdStr1, CHILD_WORKFLOW_ID, CHILD_INPUT, "step-1");
            parentAggregate.spawnChildWorkflow(childIdStr2, CHILD_WORKFLOW_ID, CHILD_INPUT, "step-2");
            parentAggregate.completeChildWorkflow(childIdStr1, Map.of("done", true), true);
            parentAggregate.markEventsAsCommitted();

            // Only child2 is still running
            WorkflowAggregate childAggregate2 = createRunningAggregate(childId2);

            when(stateStore.loadAggregate(PARENT_AGGREGATE_ID)).thenReturn(Mono.just(parentAggregate));
            when(stateStore.loadAggregate(childId2)).thenReturn(Mono.just(childAggregate2));
            when(stateStore.saveAggregate(any(WorkflowAggregate.class)))
                    .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(childWorkflowService.cancelChildWorkflows(PARENT_AGGREGATE_ID))
                    .verifyComplete();

            // Only child2 should be cancelled
            assertThat(childAggregate2.getStatus().isTerminal()).isTrue();

            // Load called for parent + 1 incomplete child = 2 total
            verify(stateStore, times(2)).loadAggregate(any());
            // Save called for 1 child only
            verify(stateStore, times(1)).saveAggregate(any(WorkflowAggregate.class));
        }
    }

    // ========================================================================
    // getChildWorkflowStatus Tests
    // ========================================================================

    @Nested
    @DisplayName("getChildWorkflowStatus")
    class GetChildWorkflowStatusTests {

        @Test
        @DisplayName("should return all child info")
        void getChildWorkflowStatus_shouldReturnAllChildInfo() {
            UUID childId1 = UUID.randomUUID();
            UUID childId2 = UUID.randomUUID();
            String childIdStr1 = childId1.toString();
            String childIdStr2 = childId2.toString();

            // Create parent with two children, one completed
            WorkflowAggregate parentAggregate = createRunningAggregate(PARENT_AGGREGATE_ID);
            parentAggregate.spawnChildWorkflow(childIdStr1, CHILD_WORKFLOW_ID, CHILD_INPUT, "step-1");
            parentAggregate.spawnChildWorkflow(childIdStr2, "other-child-workflow", Map.of(), "step-2");
            parentAggregate.completeChildWorkflow(childIdStr1, Map.of("result", "ok"), true);
            parentAggregate.markEventsAsCommitted();

            when(stateStore.loadAggregate(PARENT_AGGREGATE_ID)).thenReturn(Mono.just(parentAggregate));

            StepVerifier.create(childWorkflowService.getChildWorkflowStatus(PARENT_AGGREGATE_ID))
                    .assertNext(statusMap -> {
                        assertThat(statusMap).hasSize(2);

                        // Check completed child
                        ChildWorkflowInfo child1Info = statusMap.get(childIdStr1);
                        assertThat(child1Info).isNotNull();
                        assertThat(child1Info.childInstanceId()).isEqualTo(childIdStr1);
                        assertThat(child1Info.childWorkflowId()).isEqualTo(CHILD_WORKFLOW_ID);
                        assertThat(child1Info.parentStepId()).isEqualTo("step-1");
                        assertThat(child1Info.completed()).isTrue();
                        assertThat(child1Info.output()).isEqualTo(Map.of("result", "ok"));

                        // Check incomplete child
                        ChildWorkflowInfo child2Info = statusMap.get(childIdStr2);
                        assertThat(child2Info).isNotNull();
                        assertThat(child2Info.childInstanceId()).isEqualTo(childIdStr2);
                        assertThat(child2Info.childWorkflowId()).isEqualTo("other-child-workflow");
                        assertThat(child2Info.parentStepId()).isEqualTo("step-2");
                        assertThat(child2Info.completed()).isFalse();
                        assertThat(child2Info.output()).isNull();
                    })
                    .verifyComplete();

            verify(stateStore).loadAggregate(PARENT_AGGREGATE_ID);
        }
    }
}
