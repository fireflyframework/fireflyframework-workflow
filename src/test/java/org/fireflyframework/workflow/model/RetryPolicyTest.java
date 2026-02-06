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

package org.fireflyframework.workflow.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for RetryPolicy.
 */
class RetryPolicyTest {

    @Test
    void shouldCreateDefaultRetryPolicy() {
        RetryPolicy policy = new RetryPolicy();
        
        assertThat(policy.maxAttempts()).isEqualTo(3);
        assertThat(policy.initialDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.maxDelay()).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.multiplier()).isEqualTo(2.0);
    }

    @Test
    void shouldCreateRetryPolicyWithMaxAttempts() {
        RetryPolicy policy = new RetryPolicy(5);
        
        assertThat(policy.maxAttempts()).isEqualTo(5);
    }

    @Test
    void shouldCalculateDelayForFirstAttempt() {
        RetryPolicy policy = new RetryPolicy();
        
        Duration delay = policy.getDelayForAttempt(1);
        
        assertThat(delay).isEqualTo(Duration.ZERO);
    }

    @Test
    void shouldCalculateDelayForSecondAttempt() {
        RetryPolicy policy = new RetryPolicy(
                3, 
                Duration.ofSeconds(1), 
                Duration.ofMinutes(5), 
                2.0, 
                new String[]{}
        );
        
        Duration delay = policy.getDelayForAttempt(2);
        
        assertThat(delay).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void shouldCalculateExponentialBackoff() {
        RetryPolicy policy = new RetryPolicy(
                5,
                Duration.ofSeconds(1),
                Duration.ofMinutes(5),
                2.0,
                new String[]{}
        );
        
        Duration attempt2 = policy.getDelayForAttempt(2);
        Duration attempt3 = policy.getDelayForAttempt(3);
        Duration attempt4 = policy.getDelayForAttempt(4);
        
        assertThat(attempt2).isEqualTo(Duration.ofSeconds(1));
        assertThat(attempt3).isEqualTo(Duration.ofSeconds(2));
        assertThat(attempt4).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void shouldCapDelayAtMaxDelay() {
        RetryPolicy policy = new RetryPolicy(
                10,
                Duration.ofSeconds(1),
                Duration.ofSeconds(5), // Low max delay
                2.0,
                new String[]{}
        );
        
        Duration delay = policy.getDelayForAttempt(10);
        
        assertThat(delay).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void shouldRetryWhenUnderMaxAttempts() {
        RetryPolicy policy = new RetryPolicy(3);
        
        assertThat(policy.shouldRetry(1)).isTrue();
        assertThat(policy.shouldRetry(2)).isTrue();
    }

    @Test
    void shouldNotRetryWhenAtMaxAttempts() {
        RetryPolicy policy = new RetryPolicy(3);
        
        assertThat(policy.shouldRetry(3)).isFalse();
        assertThat(policy.shouldRetry(4)).isFalse();
    }

    @Test
    void shouldHaveDefaultPolicyConstant() {
        RetryPolicy defaultPolicy = RetryPolicy.DEFAULT;
        
        assertThat(defaultPolicy.maxAttempts()).isEqualTo(3);
        assertThat(defaultPolicy.initialDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(defaultPolicy.maxDelay()).isEqualTo(Duration.ofMinutes(5));
        assertThat(defaultPolicy.multiplier()).isEqualTo(2.0);
    }

    @Test
    void shouldHaveNoRetryPolicyConstant() {
        RetryPolicy noRetry = RetryPolicy.NO_RETRY;
        
        assertThat(noRetry.maxAttempts()).isEqualTo(1);
        assertThat(noRetry.shouldRetry(1)).isFalse();
    }
}
