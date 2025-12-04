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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for StepExecution.
 */
class StepExecutionTest {

    @Test
    void shouldCreatePendingStepExecution() {
        StepExecution execution = StepExecution.create("step-1", "Test Step", Map.of("input", "value"));
        
        assertThat(execution.executionId()).isNotNull();
        assertThat(execution.stepId()).isEqualTo("step-1");
        assertThat(execution.stepName()).isEqualTo("Test Step");
        assertThat(execution.status()).isEqualTo(StepStatus.PENDING);
        assertThat(execution.input()).isEqualTo(Map.of("input", "value"));
        assertThat(execution.output()).isNull();
        assertThat(execution.attemptNumber()).isEqualTo(1);
        assertThat(execution.startedAt()).isNull();
        assertThat(execution.completedAt()).isNull();
    }

    @Test
    void shouldTransitionToRunning() {
        StepExecution execution = StepExecution.create("step-1", "Test Step", null);
        
        StepExecution started = execution.start();
        
        assertThat(started.status()).isEqualTo(StepStatus.RUNNING);
        assertThat(started.startedAt()).isNotNull();
        assertThat(started.completedAt()).isNull();
    }

    @Test
    void shouldTransitionToCompleted() {
        StepExecution execution = StepExecution.create("step-1", "Test Step", null).start();
        Object result = Map.of("result", "success");
        
        StepExecution completed = execution.complete(result);
        
        assertThat(completed.status()).isEqualTo(StepStatus.COMPLETED);
        assertThat(completed.output()).isEqualTo(result);
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completed.errorMessage()).isNull();
    }

    @Test
    void shouldTransitionToFailedWithThrowable() {
        StepExecution execution = StepExecution.create("step-1", "Test Step", null).start();
        RuntimeException error = new RuntimeException("Test error");
        
        StepExecution failed = execution.fail(error);
        
        assertThat(failed.status()).isEqualTo(StepStatus.FAILED);
        assertThat(failed.errorMessage()).isEqualTo("Test error");
        assertThat(failed.errorType()).isEqualTo("java.lang.RuntimeException");
        assertThat(failed.completedAt()).isNotNull();
    }

    @Test
    void shouldTransitionToFailedWithMessage() {
        StepExecution execution = StepExecution.create("step-1", "Test Step", null).start();
        
        StepExecution failed = execution.fail("Custom error", "CustomException");
        
        assertThat(failed.status()).isEqualTo(StepStatus.FAILED);
        assertThat(failed.errorMessage()).isEqualTo("Custom error");
        assertThat(failed.errorType()).isEqualTo("CustomException");
    }

    @Test
    void shouldTransitionToRetrying() {
        StepExecution execution = StepExecution.create("step-1", "Test Step", null)
                .start()
                .fail(new RuntimeException("Error"));
        
        StepExecution retrying = execution.retry();
        
        assertThat(retrying.status()).isEqualTo(StepStatus.RETRYING);
        assertThat(retrying.attemptNumber()).isEqualTo(2);
        assertThat(retrying.errorMessage()).isNull();
        assertThat(retrying.startedAt()).isNull(); // Reset for new attempt
    }

    @Test
    void shouldTransitionToSkipped() {
        StepExecution execution = StepExecution.create("step-1", "Test Step", null);
        
        StepExecution skipped = execution.skip();
        
        assertThat(skipped.status()).isEqualTo(StepStatus.SKIPPED);
        assertThat(skipped.completedAt()).isNotNull();
    }

    @Test
    void shouldTransitionToTimedOut() {
        StepExecution execution = StepExecution.create("step-1", "Test Step", null).start();
        
        StepExecution timedOut = execution.timeout();
        
        assertThat(timedOut.status()).isEqualTo(StepStatus.TIMED_OUT);
        assertThat(timedOut.errorMessage()).isEqualTo("Step execution timed out");
        assertThat(timedOut.errorType()).isEqualTo("TimeoutException");
    }

    @Test
    void shouldTransitionToWaiting() {
        StepExecution execution = StepExecution.create("step-1", "Test Step", null).start();
        
        StepExecution waiting = execution.waiting();
        
        assertThat(waiting.status()).isEqualTo(StepStatus.WAITING);
        assertThat(waiting.completedAt()).isNull();
    }

    @Test
    void shouldCalculateDuration() throws InterruptedException {
        StepExecution execution = StepExecution.create("step-1", "Test Step", null).start();
        Thread.sleep(10);
        
        Duration duration = execution.getDuration();
        
        assertThat(duration).isNotNull();
        assertThat(duration.toMillis()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void shouldReturnNullDurationWhenNotStarted() {
        StepExecution execution = StepExecution.create("step-1", "Test Step", null);
        
        assertThat(execution.getDuration()).isNull();
    }

    @Test
    void shouldCheckCanRetry() {
        StepExecution failed = StepExecution.create("step-1", "Test Step", null)
                .start()
                .fail(new RuntimeException("Error"));
        
        RetryPolicy policy = new RetryPolicy(3);
        
        assertThat(failed.canRetry(policy)).isTrue();
    }

    @Test
    void shouldNotRetryWhenMaxAttemptsReached() {
        StepExecution execution = StepExecution.create("step-1", "Test Step", null);
        
        // Simulate multiple retries
        StepExecution attempt1 = execution.start().fail(new RuntimeException("Error"));
        StepExecution attempt2 = attempt1.retry().start().fail(new RuntimeException("Error"));
        StepExecution attempt3 = attempt2.retry().start().fail(new RuntimeException("Error"));
        
        RetryPolicy policy = new RetryPolicy(3);
        
        assertThat(attempt3.canRetry(policy)).isFalse();
    }

    @Test
    void shouldNotRetryWhenNotFailed() {
        StepExecution completed = StepExecution.create("step-1", "Test Step", null)
                .start()
                .complete("result");
        
        RetryPolicy policy = new RetryPolicy(3);
        
        assertThat(completed.canRetry(policy)).isFalse();
    }
}
