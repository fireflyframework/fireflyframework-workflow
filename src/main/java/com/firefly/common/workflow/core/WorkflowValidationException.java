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

package com.firefly.common.workflow.core;

/**
 * Exception thrown when workflow validation fails.
 * <p>
 * This exception is thrown during workflow registration when:
 * <ul>
 *   <li>A step depends on a non-existent step</li>
 *   <li>Circular dependencies are detected</li>
 *   <li>Invalid trigger mode configuration</li>
 *   <li>Other validation errors</li>
 * </ul>
 */
public class WorkflowValidationException extends RuntimeException {

    /**
     * Creates a new validation exception with the given message.
     *
     * @param message the error message
     */
    public WorkflowValidationException(String message) {
        super(message);
    }

    /**
     * Creates a new validation exception with the given message and cause.
     *
     * @param message the error message
     * @param cause the underlying cause
     */
    public WorkflowValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}

