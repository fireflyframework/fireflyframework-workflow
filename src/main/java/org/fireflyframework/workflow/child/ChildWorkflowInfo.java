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
 * Information about a child workflow spawned by a parent workflow.
 * <p>
 * Used as a query response record to provide status and output information
 * about child workflows without exposing internal aggregate state.
 *
 * @param childInstanceId the child workflow instance identifier
 * @param childWorkflowId the child workflow definition identifier
 * @param parentStepId    the parent step that spawned the child
 * @param completed       whether the child workflow has completed
 * @param success         whether the child workflow completed successfully
 * @param output          the child workflow output, or null if not yet completed
 */
public record ChildWorkflowInfo(
        String childInstanceId,
        String childWorkflowId,
        String parentStepId,
        boolean completed,
        boolean success,
        Object output
) {}
