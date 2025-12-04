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

package com.firefly.common.workflow.model;

/**
 * Defines how a workflow can be triggered.
 */
public enum TriggerMode {

    /**
     * Workflow can only be triggered synchronously via REST API.
     */
    SYNC,

    /**
     * Workflow can only be triggered asynchronously via events.
     */
    ASYNC,

    /**
     * Workflow can be triggered both synchronously and asynchronously.
     */
    BOTH
}
