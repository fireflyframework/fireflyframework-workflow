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

/**
 * Result of spawning a child workflow from a parent workflow.
 * <p>
 * Contains the identifiers for the parent and child workflow instances,
 * the child workflow definition ID, and the parent step that initiated
 * the spawn.
 *
 * @param parentInstanceId the parent workflow instance identifier
 * @param childInstanceId  the newly created child workflow instance identifier
 * @param childWorkflowId  the child workflow definition identifier
 * @param parentStepId     the parent step that spawned the child
 * @param spawned          true if the child was successfully spawned
 */
public record ChildWorkflowResult(
        String parentInstanceId,
        String childInstanceId,
        String childWorkflowId,
        String parentStepId,
        boolean spawned
) {}
