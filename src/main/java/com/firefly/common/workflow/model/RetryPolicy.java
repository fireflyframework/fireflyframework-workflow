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

import java.time.Duration;

/**
 * Configuration for workflow step retry behavior.
 *
 * @param maxAttempts Maximum number of retry attempts (including initial attempt)
 * @param initialDelay Initial delay before first retry
 * @param maxDelay Maximum delay between retries
 * @param multiplier Multiplier for exponential backoff
 * @param retryableExceptions List of exception class names that should trigger retry
 */
public record RetryPolicy(
        int maxAttempts,
        Duration initialDelay,
        Duration maxDelay,
        double multiplier,
        String[] retryableExceptions
) {

    /**
     * Default retry policy with sensible defaults.
     */
    public static final RetryPolicy DEFAULT = new RetryPolicy(
            3,
            Duration.ofSeconds(1),
            Duration.ofMinutes(5),
            2.0,
            new String[]{}
    );

    /**
     * No retry policy - fail immediately on error.
     */
    public static final RetryPolicy NO_RETRY = new RetryPolicy(
            1,
            Duration.ZERO,
            Duration.ZERO,
            1.0,
            new String[]{}
    );

    /**
     * Creates a retry policy with default values.
     */
    public RetryPolicy() {
        this(3, Duration.ofSeconds(1), Duration.ofMinutes(5), 2.0, new String[]{});
    }

    /**
     * Creates a retry policy with specified max attempts.
     *
     * @param maxAttempts maximum retry attempts
     */
    public RetryPolicy(int maxAttempts) {
        this(maxAttempts, Duration.ofSeconds(1), Duration.ofMinutes(5), 2.0, new String[]{});
    }

    /**
     * Calculates the delay for a given attempt number.
     *
     * @param attempt the current attempt number (1-based)
     * @return the delay duration
     */
    public Duration getDelayForAttempt(int attempt) {
        if (attempt <= 1) {
            return Duration.ZERO;
        }

        long delayMs = (long) (initialDelay.toMillis() * Math.pow(multiplier, attempt - 2));
        return Duration.ofMillis(Math.min(delayMs, maxDelay.toMillis()));
    }

    /**
     * Checks if another retry attempt should be made.
     *
     * @param currentAttempt the current attempt number (1-based)
     * @return true if retry should be attempted
     */
    public boolean shouldRetry(int currentAttempt) {
        return currentAttempt < maxAttempts;
    }
}
