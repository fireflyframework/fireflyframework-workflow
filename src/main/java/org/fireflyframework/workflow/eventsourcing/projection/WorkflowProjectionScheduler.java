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

package org.fireflyframework.workflow.eventsourcing.projection;

import org.fireflyframework.eventsourcing.store.EventStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Polls the {@link EventStore} on a configurable interval and feeds event batches
 * to the {@link WorkflowInstanceProjection} for processing.
 * <p>
 * Each poll cycle:
 * <ol>
 *   <li>Reads the projection's current position</li>
 *   <li>Streams events from that position (up to {@code batchSize})</li>
 *   <li>Delegates to {@link WorkflowInstanceProjection#processBatch}</li>
 * </ol>
 */
@Slf4j
public class WorkflowProjectionScheduler implements DisposableBean {

    private final WorkflowInstanceProjection projection;
    private final EventStore eventStore;
    private final Duration pollInterval;
    private final int batchSize;
    private volatile Disposable subscription;

    public WorkflowProjectionScheduler(WorkflowInstanceProjection projection,
                                       EventStore eventStore,
                                       Duration pollInterval,
                                       int batchSize) {
        this.projection = projection;
        this.eventStore = eventStore;
        this.pollInterval = pollInterval;
        this.batchSize = batchSize;
    }

    /**
     * Starts the polling loop. Safe to call multiple times — subsequent calls are no-ops.
     */
    public void start() {
        if (subscription != null && !subscription.isDisposed()) {
            log.debug("WorkflowProjectionScheduler already running");
            return;
        }

        log.info("Starting WorkflowProjectionScheduler with pollInterval={}, batchSize={}",
                pollInterval, batchSize);

        subscription = Flux.interval(pollInterval)
                .flatMap(tick -> pollAndProcess(), 1)
                .onErrorResume(error -> {
                    log.error("Error in projection scheduler, continuing: {}", error.getMessage());
                    return Mono.empty();
                })
                .subscribe();
    }

    /**
     * Stops the polling loop.
     */
    public void stop() {
        if (subscription != null && !subscription.isDisposed()) {
            log.info("Stopping WorkflowProjectionScheduler");
            subscription.dispose();
        }
    }

    @Override
    public void destroy() {
        stop();
    }

    private Mono<Void> pollAndProcess() {
        return projection.getCurrentPosition()
                .flatMap(position -> {
                    var events = eventStore.streamAllEvents(position + 1).take(batchSize);
                    return projection.processBatch(events);
                })
                .onErrorResume(error -> {
                    log.warn("Failed to poll/process projection batch: {}", error.getMessage());
                    return Mono.empty();
                });
    }
}
