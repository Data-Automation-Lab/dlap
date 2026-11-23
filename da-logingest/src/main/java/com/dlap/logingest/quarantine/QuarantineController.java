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

package com.dlap.logingest.quarantine;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads and replays the quarantine (design B3). Every handler is {@code ROLE_ADMIN} only: a
 * quarantined record is provider data, masked and bounded but still a sample of someone's audit
 * log, and a replay writes events (plan rules R3 and R9).
 *
 * <p>Errors: an unknown reason, a limit below 1 or a page size above {@value #MAX_PAGE_SIZE} is
 * {@code 400} (the service-wide handler maps {@link IllegalArgumentException}); a replay on the
 * legacy pipeline or while another replay runs is {@code 409}.
 */
@RestController
@RequestMapping("/api/v1/logingest/quarantine")
@Tag(name = "Log Quarantine", description = "Inspect and replay records the parser registry quarantined")
public class QuarantineController {

    /** Largest page the list endpoint returns. */
    public static final int MAX_PAGE_SIZE = 100;

    private final QuarantineRecordRepository repository;
    private final QuarantineReplayService replayService;

    /**
     * Creates the controller.
     *
     * @param repository    the quarantine table
     * @param replayService the replay
     */
    public QuarantineController(QuarantineRecordRepository repository, QuarantineReplayService replayService) {
        this.repository = repository;
        this.replayService = replayService;
    }

    /**
     * One page of the list.
     *
     * @param items the rows, newest first
     * @param page  zero-based page number
     * @param size  page size
     * @param total rows matching the filter
     */
    public record QuarantinePage(List<QuarantineRow> items, int page, int size, long total) {
    }

    /**
     * Lists quarantined records, newest first.
     *
     * @param bindingId       only this binding
     * @param reason          only this reason, for example {@code UNKNOWN_MAJOR_VERSION}
     * @param version         only records detected as this version
     * @param replayed        true for resolved rows, false for pending ones, absent for both
     * @param from            quarantined at or after this instant (ISO-8601)
     * @param to              quarantined before this instant (ISO-8601)
     * @param page            zero-based page number
     * @param size            page size, at most {@value #MAX_PAGE_SIZE}
     * @return the page
     */
    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "List quarantined log records (masked, bounded excerpts)")
    public QuarantinePage list(@RequestParam(required = false) String bindingId,
                               @RequestParam(required = false) String reason,
                               @RequestParam(required = false) String version,
                               @RequestParam(required = false) Boolean replayed,
                               @RequestParam(required = false) Instant from,
                               @RequestParam(required = false) Instant to,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "25") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is at least 0 and size is between 1 and " + MAX_PAGE_SIZE);
        }
        String code = reason == null ? null : QuarantineBounds.reasonCode(QuarantineBounds.parseReason(reason));
        QuarantineRecordRepository.Filter filter = new QuarantineRecordRepository.Filter(bindingId, code, version,
                replayed, from, to, null);
        return new QuarantinePage(repository.find(filter, size, (long) page * size), page, size,
                repository.count(filter));
    }

    /**
     * Returns one quarantined record.
     *
     * @param quarantineId the record's id
     * @return the row, or 404
     */
    @GetMapping("/{quarantineId}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Get one quarantined log record")
    public ResponseEntity<QuarantineRow> get(@PathVariable String quarantineId) {
        return repository.findById(quarantineId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Re-runs quarantined records through the current parser registry. Idempotent: a record that
     * parses is marked replayed and not selected again; its events keep their ids.
     *
     * @param request the selection (all fields optional) and whether to only count
     * @param auth    the caller
     * @return what the replay did; 409 when the registry pipeline is off or another replay runs
     */
    @PostMapping("/replay")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @Operation(summary = "Replay quarantined records (ADMIN only, idempotent)")
    public ResponseEntity<Object> replay(@RequestBody(required = false) QuarantineReplayService.ReplayRequest request,
                                    Authentication auth) {
        QuarantineReplayService.ReplayRequest r = request == null
                ? new QuarantineReplayService.ReplayRequest(null, null, null, null, null, false) : request;
        try {
            return ResponseEntity.ok(replayService.replay(r, auth == null ? "unknown" : auth.getName()));
        } catch (QuarantineReplayService.ReplayUnavailableException | QuarantineReplayService.ReplayBusyException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", HttpStatus.CONFLICT.value());
            body.put("error", HttpStatus.CONFLICT.getReasonPhrase());
            body.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
    }
}
