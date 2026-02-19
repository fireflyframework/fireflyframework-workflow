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

package org.fireflyframework.workflow.search;

import org.fireflyframework.workflow.eventsourcing.aggregate.WorkflowAggregate;
import org.fireflyframework.workflow.eventsourcing.store.EventSourcedWorkflowStateStore;
import org.fireflyframework.workflow.model.WorkflowInstance;
import org.fireflyframework.workflow.model.WorkflowStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WorkflowSearchService}.
 * <p>
 * Tests cover searching by attributes, updating attributes,
 * and retrieving attributes for a specific instance.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowSearchServiceTest {

    @Mock
    private EventSourcedWorkflowStateStore stateStore;

    private SearchAttributeProjection projection;
    private WorkflowSearchService searchService;

    @BeforeEach
    void setUp() {
        projection = new SearchAttributeProjection();
        searchService = new WorkflowSearchService(projection, stateStore);
    }

    // ========================================================================
    // searchByAttribute Tests
    // ========================================================================

    @Nested
    @DisplayName("searchByAttribute")
    class SearchByAttributeTests {

        @Test
        @DisplayName("should load and return matching workflow instances")
        void searchByAttribute_shouldReturnMatchingInstances() {
            UUID instanceId = UUID.randomUUID();
            projection.onSearchAttributeUpdated(instanceId, "region", "us-east-1");

            WorkflowAggregate aggregate = createMockAggregate(instanceId);
            WorkflowInstance instance = createWorkflowInstance(instanceId);

            when(stateStore.loadAggregate(instanceId)).thenReturn(Mono.just(aggregate));
            when(stateStore.toWorkflowInstance(aggregate)).thenReturn(instance);

            StepVerifier.create(searchService.searchByAttribute("region", "us-east-1"))
                    .assertNext(result -> {
                        assertThat(result.instanceId()).isEqualTo(instanceId.toString());
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty when no matches found")
        void searchByAttribute_noMatch_shouldReturnEmpty() {
            StepVerifier.create(searchService.searchByAttribute("region", "us-east-1"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should skip instances that fail to load")
        void searchByAttribute_shouldSkipFailedLoads() {
            UUID instance1 = UUID.randomUUID();
            UUID instance2 = UUID.randomUUID();
            projection.onSearchAttributeUpdated(instance1, "region", "us-east-1");
            projection.onSearchAttributeUpdated(instance2, "region", "us-east-1");

            WorkflowAggregate aggregate2 = createMockAggregate(instance2);
            WorkflowInstance workflowInstance2 = createWorkflowInstance(instance2);

            when(stateStore.loadAggregate(instance1)).thenReturn(Mono.empty());
            when(stateStore.loadAggregate(instance2)).thenReturn(Mono.just(aggregate2));
            when(stateStore.toWorkflowInstance(aggregate2)).thenReturn(workflowInstance2);

            StepVerifier.create(searchService.searchByAttribute("region", "us-east-1"))
                    .assertNext(result -> {
                        assertThat(result.instanceId()).isEqualTo(instance2.toString());
                    })
                    .verifyComplete();
        }
    }

    // ========================================================================
    // searchByAttributes Tests
    // ========================================================================

    @Nested
    @DisplayName("searchByAttributes")
    class SearchByAttributesTests {

        @Test
        @DisplayName("should return instances matching all criteria")
        void searchByAttributes_shouldReturnMatchingInstances() {
            UUID instanceId = UUID.randomUUID();
            projection.onSearchAttributeUpdated(instanceId, "region", "us-east-1");
            projection.onSearchAttributeUpdated(instanceId, "priority", "high");

            WorkflowAggregate aggregate = createMockAggregate(instanceId);
            WorkflowInstance instance = createWorkflowInstance(instanceId);

            when(stateStore.loadAggregate(instanceId)).thenReturn(Mono.just(aggregate));
            when(stateStore.toWorkflowInstance(aggregate)).thenReturn(instance);

            StepVerifier.create(searchService.searchByAttributes(
                            Map.of("region", "us-east-1", "priority", "high")))
                    .assertNext(result -> {
                        assertThat(result.instanceId()).isEqualTo(instanceId.toString());
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty when no instance matches all criteria")
        void searchByAttributes_noFullMatch_shouldReturnEmpty() {
            UUID instanceId = UUID.randomUUID();
            projection.onSearchAttributeUpdated(instanceId, "region", "us-east-1");
            projection.onSearchAttributeUpdated(instanceId, "priority", "low");

            StepVerifier.create(searchService.searchByAttributes(
                            Map.of("region", "us-east-1", "priority", "high")))
                    .verifyComplete();
        }
    }

    // ========================================================================
    // updateSearchAttribute Tests
    // ========================================================================

    @Nested
    @DisplayName("updateSearchAttribute")
    class UpdateSearchAttributeTests {

        @Test
        @DisplayName("should update attribute on aggregate and projection")
        void updateSearchAttribute_shouldUpdateBoth() {
            UUID instanceId = UUID.randomUUID();

            WorkflowAggregate aggregate = new WorkflowAggregate(instanceId);
            // Start the aggregate so it is in RUNNING state
            aggregate.start("test-workflow", "Test Workflow", "1.0", Map.of(), null, "test", false);

            when(stateStore.loadAggregate(instanceId)).thenReturn(Mono.just(aggregate));
            when(stateStore.saveAggregate(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(searchService.updateSearchAttribute(instanceId, "region", "us-east-1"))
                    .verifyComplete();

            // Verify projection was updated
            Map<String, Object> attributes = projection.getAttributesForInstance(instanceId);
            assertThat(attributes).containsEntry("region", "us-east-1");

            // Verify aggregate was saved
            verify(stateStore).saveAggregate(aggregate);
        }
    }

    // ========================================================================
    // getSearchAttributes Tests
    // ========================================================================

    @Nested
    @DisplayName("getSearchAttributes")
    class GetSearchAttributesTests {

        @Test
        @DisplayName("should return search attributes from aggregate")
        void getSearchAttributes_shouldReturnFromAggregate() {
            UUID instanceId = UUID.randomUUID();

            WorkflowAggregate aggregate = new WorkflowAggregate(instanceId);
            aggregate.start("test-workflow", "Test Workflow", "1.0", Map.of(), null, "test", false);
            aggregate.upsertSearchAttribute("region", "us-east-1");
            aggregate.upsertSearchAttribute("priority", "high");

            when(stateStore.loadAggregate(instanceId)).thenReturn(Mono.just(aggregate));

            StepVerifier.create(searchService.getSearchAttributes(instanceId))
                    .assertNext(attributes -> {
                        assertThat(attributes).hasSize(2);
                        assertThat(attributes).containsEntry("region", "us-east-1");
                        assertThat(attributes).containsEntry("priority", "high");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should return empty map when aggregate not found")
        void getSearchAttributes_aggregateNotFound_shouldReturnEmpty() {
            UUID instanceId = UUID.randomUUID();
            when(stateStore.loadAggregate(instanceId)).thenReturn(Mono.empty());

            StepVerifier.create(searchService.getSearchAttributes(instanceId))
                    .assertNext(attributes -> {
                        assertThat(attributes).isEmpty();
                    })
                    .verifyComplete();
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private WorkflowAggregate createMockAggregate(UUID instanceId) {
        WorkflowAggregate aggregate = new WorkflowAggregate(instanceId);
        aggregate.start("test-workflow", "Test Workflow", "1.0", Map.of(), null, "test", false);
        return aggregate;
    }

    private WorkflowInstance createWorkflowInstance(UUID instanceId) {
        return new WorkflowInstance(
                instanceId.toString(),
                "test-workflow",
                "Test Workflow",
                "1.0",
                WorkflowStatus.RUNNING,
                null,
                Map.of(),
                Map.of(),
                null,
                List.of(),
                null,
                null,
                null,
                "test",
                null,
                null,
                null
        );
    }
}
