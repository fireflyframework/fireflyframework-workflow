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

package org.fireflyframework.workflow.timer;

import org.fireflyframework.eventsourcing.store.ConcurrencyException;
import org.fireflyframework.workflow.eventsourcing.store.EventSourcedWorkflowStateStore;
import org.springframework.beans.factory.DisposableBean;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Service that polls for due durable timers and fires them by loading the aggregate.
 * <p>
 * The timer scheduler runs a periodic polling loop using {@link Flux#interval(Duration)}
 * to discover timers that are due (via the {@link WorkflowTimerProjection}) and fire
 * them by loading the corresponding {@code WorkflowAggregate}, calling
 * {@code fireTimer()}, and saving the aggregate back to the event store.
 * <p>
 * <b>Distributed safety:</b> Optimistic concurrency control in the event store
 * prevents double-firing. If two nodes attempt to fire the same timer concurrently,
 * only one will succeed -- the other will receive a {@link ConcurrencyException}
 * which is handled gracefully (logged and skipped).
 * <p>
 * <b>Error resilience:</b> Errors for individual timers (aggregate not found,
 * timer already fired, save failures) are logged and skipped so that other
 * due timers continue to be processed.
 * <p>
 * <b>Configuration:</b>
 * <ul>
 *   <li>{@code pollInterval} -- how often to check for due timers (default: PT1S)</li>
 *   <li>{@code batchSize} -- max number of timers to process per poll (default: 50)</li>
 * </ul>
 *
 * @see WorkflowTimerProjection
 * @see TimerEntry
 */
@Slf4j
@RequiredArgsConstructor
public class TimerSchedulerService implements DisposableBean {

    private final WorkflowTimerProjection timerProjection;
    private final EventSourcedWorkflowStateStore stateStore;
    private final Duration pollInterval;
    private final int batchSize;

    private volatile Disposable pollingSubscription;

    /**
     * Starts the periodic timer polling loop.
     * <p>
     * Each tick of the interval triggers {@link #pollAndFireTimers()} which
     * queries the projection for due timers and fires them. Errors during
     * polling are logged but do not terminate the loop.
     */
    public synchronized void start() {
        if (pollingSubscription != null && !pollingSubscription.isDisposed()) {
            log.warn("Timer scheduler is already running");
            return;
        }

        log.info("Starting timer scheduler with pollInterval={}, batchSize={}", pollInterval, batchSize);

        pollingSubscription = Flux.interval(pollInterval)
                .flatMap(tick -> pollAndFireTimers()
                        .onErrorResume(error -> {
                            log.error("Error during timer polling cycle", error);
                            return Mono.empty();
                        }))
                .subscribe();
    }

    /**
     * Stops the periodic timer polling loop.
     * <p>
     * Safe to call multiple times or before {@link #start()} has been called.
     */
    public synchronized void stop() {
        if (pollingSubscription != null && !pollingSubscription.isDisposed()) {
            log.info("Stopping timer scheduler");
            pollingSubscription.dispose();
            pollingSubscription = null;
        }
    }

    /**
     * Polls the projection for due timers and fires each one.
     * <p>
     * For each due timer:
     * <ol>
     *   <li>Load the workflow aggregate from the event store</li>
     *   <li>Call {@code fireTimer(timerId)} on the aggregate</li>
     *   <li>Save the aggregate (with optimistic concurrency)</li>
     *   <li>Remove the timer from the projection</li>
     * </ol>
     * <p>
     * Individual timer errors are caught and logged so that failures
     * processing one timer do not prevent processing of others.
     *
     * @return a Mono that completes when all due timers have been processed
     */
    public Mono<Void> pollAndFireTimers() {
        List<TimerEntry> dueTimers = timerProjection.findDueTimers(Instant.now(), batchSize);

        if (dueTimers.isEmpty()) {
            return Mono.empty();
        }

        log.debug("Found {} due timer(s) to fire", dueTimers.size());

        return Flux.fromIterable(dueTimers)
                .concatMap(this::fireTimer)
                .then();
    }

    /**
     * Fires a single timer by loading the aggregate, calling fireTimer, and saving.
     * <p>
     * Error handling:
     * <ul>
     *   <li>{@link ConcurrencyException} -- another node fired the timer; skip</li>
     *   <li>{@link IllegalStateException} -- timer already fired on aggregate; remove from projection</li>
     *   <li>Other errors -- log and continue</li>
     * </ul>
     *
     * @param timerEntry the due timer to fire
     * @return a Mono that completes when the timer has been processed
     */
    private Mono<Void> fireTimer(TimerEntry timerEntry) {
        log.debug("Firing timer: instanceId={}, timerId={}, fireAt={}",
                timerEntry.instanceId(), timerEntry.timerId(), timerEntry.fireAt());

        return stateStore.loadAggregate(timerEntry.instanceId())
                .flatMap(aggregate -> {
                    try {
                        aggregate.fireTimer(timerEntry.timerId());
                    } catch (IllegalStateException e) {
                        // Timer does not exist on the aggregate (already fired or stale projection)
                        log.warn("Timer '{}' not found on aggregate {} (likely already fired): {}",
                                timerEntry.timerId(), timerEntry.instanceId(), e.getMessage());
                        timerProjection.onTimerFired(timerEntry.instanceId(), timerEntry.timerId());
                        return Mono.<Void>empty();
                    }

                    return stateStore.saveAggregate(aggregate)
                            .doOnSuccess(saved -> {
                                timerProjection.onTimerFired(timerEntry.instanceId(), timerEntry.timerId());
                                log.info("Timer fired successfully: instanceId={}, timerId={}",
                                        timerEntry.instanceId(), timerEntry.timerId());
                            })
                            .then();
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Aggregate not found for timer: instanceId={}, timerId={}",
                            timerEntry.instanceId(), timerEntry.timerId());
                    return Mono.empty();
                }))
                .onErrorResume(ConcurrencyException.class, e -> {
                    log.info("Concurrency conflict firing timer '{}' on instance {} " +
                                    "(will retry on next poll cycle): {}",
                            timerEntry.timerId(), timerEntry.instanceId(), e.getMessage());
                    return Mono.empty();
                })
                .onErrorResume(e -> {
                    log.error("Failed to fire timer '{}' on instance {}: {}",
                            timerEntry.timerId(), timerEntry.instanceId(), e.getMessage(), e);
                    return Mono.empty();
                });
    }

    /**
     * Disposes the polling subscription on Spring context shutdown.
     * <p>
     * Implements {@link DisposableBean} to ensure clean shutdown.
     */
    @Override
    public void destroy() {
        stop();
    }
}
