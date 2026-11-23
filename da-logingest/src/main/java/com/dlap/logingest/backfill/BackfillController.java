/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.dlap.logingest.backfill;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Starts, lists and cancels backfill jobs (design B6, work package LOG-04). Every handler is
 * {@code ROLE_ADMIN} only: a job reads a provider's history, which costs API quota or bytes and
 * writes events (plan rules R3 and R9).
 *
 * <p>Errors: an invalid window, stream selection, page or status is {@code 400}; an unknown binding
 * or job is {@code 404}; a binding that does not run, a stream without {@code backfill.allowed},
 * a binding that already has an active job, a job that already ended and the legacy pipeline are
 * {@code 409}.
 */
@RestController
@RequestMapping("/api/v1/logingest/backfills")
@Tag(name = "Log Backfill", description = "Bounded, resumable reads of a binding's history")
public class BackfillController {

    /** Largest page the list endpoint returns. */
    public static final int MAX_PAGE_SIZE = 100;

    private final BackfillService service;

    /**
     * Creates the controller.
     *
     * @param service the job service
     */
    public BackfillController(BackfillService service) {
        this.service = service;
    }

    /**
     * One page of the list.
     *
     * @param items the jobs, newest request first
     * @param page  zero-based page number
     * @param size  page size
     * @param total jobs matching the filter
     */
    @Schema(name = "BackfillPage")
    public record BackfillPage(List<BackfillJob> items, int page, int size, long total) {
    }

    /**
     * Starts a job.
     *
     * @param request the binding, optionally the stream, and the event-time window {@code [from, to)}
     * @param auth    the caller
     * @return 201 with the {@code PENDING} job; 400, 404 or 409 as described on the class
     */
    @PostMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(operationId = "startBackfill",
               summary = "Start a bounded backfill job (ADMIN only, one active job per binding)")
    public ResponseEntity<Object> create(@RequestBody BackfillService.CreateRequest request, Authentication auth) {
        try {
            BackfillJob job = service.create(request, auth == null ? "unknown" : auth.getName());
            return ResponseEntity.created(URI.create("/api/v1/logingest/backfills/" + job.id())).body(job);
        } catch (BackfillService.BackfillNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (BackfillService.BackfillUnavailableException | BackfillService.BackfillConflictException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * Lists jobs, newest request first.
     *
     * @param bindingId only this binding
     * @param status    only this state, for example {@code RUNNING}
     * @param page      zero-based page number
     * @param size      page size, at most {@value #MAX_PAGE_SIZE}
     * @return the page
     */
    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(operationId = "listBackfills", summary = "List backfill jobs")
    public BackfillPage list(@RequestParam(required = false) String bindingId,
                             @RequestParam(required = false) String status,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "25") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is at least 0 and size is between 1 and " + MAX_PAGE_SIZE);
        }
        BackfillStatus s = null;
        if (status != null && !status.isBlank()) {
            try {
                s = BackfillStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("status is one of PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED");
            }
        }
        return new BackfillPage(service.list(bindingId, s, page, size), page, size, service.count(bindingId, s));
    }

    /**
     * Returns one job.
     *
     * @param id the job id
     * @return the job, or 404
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(operationId = "getBackfill", summary = "Get one backfill job with its progress")
    public ResponseEntity<Object> get(@PathVariable String id) {
        try {
            return ResponseEntity.ok(service.get(uuid(id)));
        } catch (BackfillService.BackfillNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Cancels a job. A running job stops after the batch in flight.
     *
     * @param id   the job id
     * @param auth the caller
     * @return the cancelled job, 404 when unknown, 409 when it had ended
     */
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(operationId = "cancelBackfill", summary = "Cancel a backfill job")
    public ResponseEntity<Object> cancel(@PathVariable String id, Authentication auth) {
        try {
            return ResponseEntity.ok(service.cancel(uuid(id), auth == null ? "unknown" : auth.getName()));
        } catch (BackfillService.BackfillNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (BackfillService.BackfillConflictException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * A body that is not JSON or holds an unreadable instant.
     *
     * @param e the parse failure
     * @return 400
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> unreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "the request body is not valid JSON, or from/to are not ISO-8601 instants");
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("a job id is a UUID");
        }
    }

    private static ResponseEntity<Object> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
