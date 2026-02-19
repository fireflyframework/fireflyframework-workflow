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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SearchAttributeProjection}.
 * <p>
 * Tests cover attribute updates, removal, querying by single and
 * multiple criteria, and instance count tracking.
 */
class SearchAttributeProjectionTest {

    private SearchAttributeProjection projection;

    @BeforeEach
    void setUp() {
        projection = new SearchAttributeProjection();
    }

    // ========================================================================
    // onSearchAttributeUpdated Tests
    // ========================================================================

    @Nested
    @DisplayName("onSearchAttributeUpdated")
    class OnSearchAttributeUpdatedTests {

        @Test
        @DisplayName("should store attribute for an instance")
        void onSearchAttributeUpdated_shouldStoreAttribute() {
            UUID instanceId = UUID.randomUUID();

            projection.onSearchAttributeUpdated(instanceId, "region", "us-east-1");

            Map<String, Object> attributes = projection.getAttributesForInstance(instanceId);
            assertThat(attributes).containsEntry("region", "us-east-1");
        }

        @Test
        @DisplayName("should overwrite existing attribute value")
        void onSearchAttributeUpdated_shouldOverwriteExisting() {
            UUID instanceId = UUID.randomUUID();

            projection.onSearchAttributeUpdated(instanceId, "status", "pending");
            projection.onSearchAttributeUpdated(instanceId, "status", "active");

            Map<String, Object> attributes = projection.getAttributesForInstance(instanceId);
            assertThat(attributes).containsEntry("status", "active");
            assertThat(attributes).hasSize(1);
        }

        @Test
        @DisplayName("should store multiple attributes for same instance")
        void onSearchAttributeUpdated_multipleAttributes() {
            UUID instanceId = UUID.randomUUID();

            projection.onSearchAttributeUpdated(instanceId, "region", "us-east-1");
            projection.onSearchAttributeUpdated(instanceId, "priority", "high");
            projection.onSearchAttributeUpdated(instanceId, "team", "platform");

            Map<String, Object> attributes = projection.getAttributesForInstance(instanceId);
            assertThat(attributes).hasSize(3);
            assertThat(attributes).containsEntry("region", "us-east-1");
            assertThat(attributes).containsEntry("priority", "high");
            assertThat(attributes).containsEntry("team", "platform");
        }

        @Test
        @DisplayName("should store attributes for different instances independently")
        void onSearchAttributeUpdated_differentInstances() {
            UUID instance1 = UUID.randomUUID();
            UUID instance2 = UUID.randomUUID();

            projection.onSearchAttributeUpdated(instance1, "region", "us-east-1");
            projection.onSearchAttributeUpdated(instance2, "region", "eu-west-1");

            assertThat(projection.getAttributesForInstance(instance1))
                    .containsEntry("region", "us-east-1");
            assertThat(projection.getAttributesForInstance(instance2))
                    .containsEntry("region", "eu-west-1");
        }
    }

    // ========================================================================
    // onWorkflowRemoved Tests
    // ========================================================================

    @Nested
    @DisplayName("onWorkflowRemoved")
    class OnWorkflowRemovedTests {

        @Test
        @DisplayName("should remove all attributes for an instance")
        void onWorkflowRemoved_shouldRemoveAllAttributes() {
            UUID instanceId = UUID.randomUUID();

            projection.onSearchAttributeUpdated(instanceId, "region", "us-east-1");
            projection.onSearchAttributeUpdated(instanceId, "priority", "high");
            assertThat(projection.getInstanceCount()).isEqualTo(1);

            projection.onWorkflowRemoved(instanceId);

            assertThat(projection.getAttributesForInstance(instanceId)).isEmpty();
            assertThat(projection.getInstanceCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("should not affect other instances when removing one")
        void onWorkflowRemoved_shouldNotAffectOtherInstances() {
            UUID instance1 = UUID.randomUUID();
            UUID instance2 = UUID.randomUUID();

            projection.onSearchAttributeUpdated(instance1, "region", "us-east-1");
            projection.onSearchAttributeUpdated(instance2, "region", "eu-west-1");

            projection.onWorkflowRemoved(instance1);

            assertThat(projection.getAttributesForInstance(instance1)).isEmpty();
            assertThat(projection.getAttributesForInstance(instance2))
                    .containsEntry("region", "eu-west-1");
            assertThat(projection.getInstanceCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should be safe to remove non-existent instance")
        void onWorkflowRemoved_nonExistentInstance_shouldNotFail() {
            UUID instanceId = UUID.randomUUID();

            // Should not throw
            projection.onWorkflowRemoved(instanceId);
            assertThat(projection.getInstanceCount()).isEqualTo(0);
        }
    }

    // ========================================================================
    // findByAttribute Tests
    // ========================================================================

    @Nested
    @DisplayName("findByAttribute")
    class FindByAttributeTests {

        @Test
        @DisplayName("should return matching instance IDs")
        void findByAttribute_shouldReturnMatchingInstances() {
            UUID instance1 = UUID.randomUUID();
            UUID instance2 = UUID.randomUUID();
            UUID instance3 = UUID.randomUUID();

            projection.onSearchAttributeUpdated(instance1, "region", "us-east-1");
            projection.onSearchAttributeUpdated(instance2, "region", "us-east-1");
            projection.onSearchAttributeUpdated(instance3, "region", "eu-west-1");

            List<UUID> results = projection.findByAttribute("region", "us-east-1");
            assertThat(results).hasSize(2);
            assertThat(results).containsExactlyInAnyOrder(instance1, instance2);
        }

        @Test
        @DisplayName("should return empty list when no matches")
        void findByAttribute_noMatch_shouldReturnEmpty() {
            UUID instanceId = UUID.randomUUID();
            projection.onSearchAttributeUpdated(instanceId, "region", "us-east-1");

            List<UUID> results = projection.findByAttribute("region", "ap-southeast-1");
            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("should return empty list for non-existent key")
        void findByAttribute_nonExistentKey_shouldReturnEmpty() {
            UUID instanceId = UUID.randomUUID();
            projection.onSearchAttributeUpdated(instanceId, "region", "us-east-1");

            List<UUID> results = projection.findByAttribute("nonexistent", "value");
            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("should return empty list when no instances exist")
        void findByAttribute_noInstances_shouldReturnEmpty() {
            List<UUID> results = projection.findByAttribute("region", "us-east-1");
            assertThat(results).isEmpty();
        }
    }

    // ========================================================================
    // findByAttributes Tests
    // ========================================================================

    @Nested
    @DisplayName("findByAttributes")
    class FindByAttributesTests {

        @Test
        @DisplayName("should return instances matching ALL criteria (AND logic)")
        void findByAttributes_shouldMatchAllCriteria() {
            UUID instance1 = UUID.randomUUID();
            UUID instance2 = UUID.randomUUID();
            UUID instance3 = UUID.randomUUID();

            // instance1: region=us-east-1, priority=high
            projection.onSearchAttributeUpdated(instance1, "region", "us-east-1");
            projection.onSearchAttributeUpdated(instance1, "priority", "high");

            // instance2: region=us-east-1, priority=low
            projection.onSearchAttributeUpdated(instance2, "region", "us-east-1");
            projection.onSearchAttributeUpdated(instance2, "priority", "low");

            // instance3: region=eu-west-1, priority=high
            projection.onSearchAttributeUpdated(instance3, "region", "eu-west-1");
            projection.onSearchAttributeUpdated(instance3, "priority", "high");

            List<UUID> results = projection.findByAttributes(
                    Map.of("region", "us-east-1", "priority", "high"));

            assertThat(results).hasSize(1);
            assertThat(results).containsExactly(instance1);
        }

        @Test
        @DisplayName("should return empty when no instance matches all criteria")
        void findByAttributes_noFullMatch_shouldReturnEmpty() {
            UUID instanceId = UUID.randomUUID();
            projection.onSearchAttributeUpdated(instanceId, "region", "us-east-1");
            projection.onSearchAttributeUpdated(instanceId, "priority", "low");

            List<UUID> results = projection.findByAttributes(
                    Map.of("region", "us-east-1", "priority", "high"));

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("should return all instances when criteria is empty")
        void findByAttributes_emptyCriteria_shouldReturnAll() {
            UUID instance1 = UUID.randomUUID();
            UUID instance2 = UUID.randomUUID();

            projection.onSearchAttributeUpdated(instance1, "region", "us-east-1");
            projection.onSearchAttributeUpdated(instance2, "region", "eu-west-1");

            List<UUID> results = projection.findByAttributes(Map.of());

            assertThat(results).hasSize(2);
            assertThat(results).containsExactlyInAnyOrder(instance1, instance2);
        }
    }

    // ========================================================================
    // getAttributesForInstance Tests
    // ========================================================================

    @Nested
    @DisplayName("getAttributesForInstance")
    class GetAttributesForInstanceTests {

        @Test
        @DisplayName("should return attributes for known instance")
        void getAttributesForInstance_shouldReturnAttributes() {
            UUID instanceId = UUID.randomUUID();
            projection.onSearchAttributeUpdated(instanceId, "region", "us-east-1");
            projection.onSearchAttributeUpdated(instanceId, "priority", "high");

            Map<String, Object> attributes = projection.getAttributesForInstance(instanceId);
            assertThat(attributes).hasSize(2);
            assertThat(attributes).containsEntry("region", "us-east-1");
            assertThat(attributes).containsEntry("priority", "high");
        }

        @Test
        @DisplayName("should return empty map for unknown instance")
        void getAttributesForInstance_unknownInstance_shouldReturnEmptyMap() {
            Map<String, Object> attributes = projection.getAttributesForInstance(UUID.randomUUID());
            assertThat(attributes).isEmpty();
        }
    }

    // ========================================================================
    // getInstanceCount Tests
    // ========================================================================

    @Nested
    @DisplayName("getInstanceCount")
    class GetInstanceCountTests {

        @Test
        @DisplayName("should return zero when no instances tracked")
        void getInstanceCount_shouldReturnZeroInitially() {
            assertThat(projection.getInstanceCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("should track instance count correctly")
        void getInstanceCount_shouldTrackCorrectly() {
            UUID instance1 = UUID.randomUUID();
            UUID instance2 = UUID.randomUUID();

            projection.onSearchAttributeUpdated(instance1, "region", "us-east-1");
            assertThat(projection.getInstanceCount()).isEqualTo(1);

            projection.onSearchAttributeUpdated(instance2, "region", "eu-west-1");
            assertThat(projection.getInstanceCount()).isEqualTo(2);

            // Adding another attribute to same instance should not change count
            projection.onSearchAttributeUpdated(instance1, "priority", "high");
            assertThat(projection.getInstanceCount()).isEqualTo(2);

            projection.onWorkflowRemoved(instance1);
            assertThat(projection.getInstanceCount()).isEqualTo(1);
        }
    }
}
