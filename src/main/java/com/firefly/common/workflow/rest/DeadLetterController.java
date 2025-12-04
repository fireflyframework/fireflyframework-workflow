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

package com.firefly.common.workflow.rest;

import com.firefly.common.workflow.dlq.DeadLetterEntry;
import com.firefly.common.workflow.dlq.DeadLetterService;
import com.firefly.common.workflow.dlq.DeadLetterService.ReplayResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * REST controller for Dead Letter Queue (DLQ) operations.
 * <p>
 * Provides endpoints for:
 * <ul>
 *   <li>Listing DLQ entries</li>
 *   <li>Getting DLQ entry details</li>
 *   <li>Replaying failed events/workflows</li>
 *   <li>Deleting DLQ entries</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("${firefly.workflow.api.base-path:/api/v1/workflows}/dlq")
@RequiredArgsConstructor
public class DeadLetterController {

    private final DeadLetterService deadLetterService;

    /**
     * Lists all DLQ entries.
     */
    @GetMapping
    public Flux<DeadLetterEntry> listEntries(
            @RequestParam(required = false) String workflowId) {

        if (workflowId != null && !workflowId.isEmpty()) {
            return deadLetterService.getEntriesByWorkflowId(workflowId);
        }
        return deadLetterService.getAllEntries();
    }

    /**
     * Gets the count of DLQ entries.
     */
    @GetMapping("/count")
    public Mono<Map<String, Long>> getCount() {
        return deadLetterService.getCount()
                .map(count -> Map.of("count", count));
    }

    /**
     * Gets a specific DLQ entry.
     */
    @GetMapping("/{entryId}")
    public Mono<ResponseEntity<DeadLetterEntry>> getEntry(@PathVariable String entryId) {
        return deadLetterService.getEntry(entryId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /**
     * Replays a DLQ entry.
     * <p>
     * This will attempt to restart the workflow/step from the failed point.
     */
    @PostMapping("/{entryId}/replay")
    public Mono<ResponseEntity<ReplayResult>> replayEntry(
            @PathVariable String entryId,
            @RequestBody(required = false) Map<String, Object> modifiedInput) {

        log.info("Replaying DLQ entry via API: entryId={}", entryId);

        Mono<ReplayResult> result;
        if (modifiedInput != null && !modifiedInput.isEmpty()) {
            result = deadLetterService.replay(entryId, modifiedInput);
        } else {
            result = deadLetterService.replay(entryId);
        }

        return result
                .map(r -> {
                    if (r.success()) {
                        return ResponseEntity.ok(r);
                    } else {
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(r);
                    }
                })
                .onErrorResume(IllegalArgumentException.class, e ->
                        Mono.just(ResponseEntity.notFound().build()));
    }

    /**
     * Replays all DLQ entries for a workflow.
     */
    @PostMapping("/replay")
    public Flux<ReplayResult> replayByWorkflowId(@RequestParam String workflowId) {
        log.info("Replaying all DLQ entries for workflow via API: workflowId={}", workflowId);
        return deadLetterService.replayByWorkflowId(workflowId);
    }

    /**
     * Deletes a DLQ entry.
     */
    @DeleteMapping("/{entryId}")
    public Mono<ResponseEntity<Void>> deleteEntry(@PathVariable String entryId) {
        log.info("Deleting DLQ entry via API: entryId={}", entryId);

        return deadLetterService.delete(entryId)
                .map(deleted -> {
                    if (deleted) {
                        return ResponseEntity.noContent().<Void>build();
                    } else {
                        return ResponseEntity.notFound().<Void>build();
                    }
                });
    }

    /**
     * Deletes all DLQ entries for a workflow.
     */
    @DeleteMapping
    public Mono<Map<String, Long>> deleteByWorkflowId(@RequestParam String workflowId) {
        log.info("Deleting all DLQ entries for workflow via API: workflowId={}", workflowId);

        return deadLetterService.deleteByWorkflowId(workflowId)
                .map(count -> Map.of("deleted", count));
    }

    /**
     * Deletes all DLQ entries.
     */
    @DeleteMapping("/all")
    public Mono<Map<String, Long>> deleteAll() {
        log.info("Deleting all DLQ entries via API");

        return deadLetterService.deleteAll()
                .map(count -> Map.of("deleted", count));
    }
}
