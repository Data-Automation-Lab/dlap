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

package com.dlap.reporting.controller;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dlap.reporting.drift.DriftType;
import com.dlap.reporting.dto.DriftFindingDTO;
import com.dlap.reporting.repository.DriftFindingRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Consumed-stage drift query API (ADR-0040). Lists {@link DriftFindingDTO}s:
 * verdict&#8596;access-log divergences (unenforced reads, enforcement bypasses)
 * detected by the drift join.
 *
 * <p>Read-only audit surface: gated to Executive (ADR-0032) plus the Reader-tier
 * {@code AUDITOR} role - the personas responsible for enforcement assurance.
 */
@RestController
@RequestMapping("/api/v1/reporting/drift")
@Tag(name = "Drift", description = "Consumed-stage decision-vs-access-log drift findings (ADR-0040)")
public class DriftFindingController {

    private static final Logger log = LoggerFactory.getLogger(DriftFindingController.class);

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final DriftFindingRepository driftFindingRepository;

    public DriftFindingController(DriftFindingRepository driftFindingRepository) {
        this.driftFindingRepository = driftFindingRepository;
    }

    /**
     * List drift findings, newest read first, with optional filters.
     *
     * <p>Response: a Spring {@code Page<DriftFindingDTO>} - {@code content} is the
     * findings array, plus {@code totalElements}, {@code totalPages},
     * {@code number}, {@code size}.
     *
     * @param type      filter by {@code UNENFORCED_READ} | {@code ENFORCEMENT_BYPASS}
     * @param assetId   filter by asset uuid
     * @param subjectId filter by principal
     * @param from      inclusive lower bound on read time (ISO-8601 instant)
     * @param to        inclusive upper bound on read time (ISO-8601 instant)
     * @param page      zero-based page index (default 0)
     * @param size      page size (default 50, max 200)
     */
    @GetMapping("/findings")
    @PreAuthorize("hasAnyRole('ADMIN', 'CDO', 'AUDITOR')")
    @Operation(summary = "List drift findings",
               description = "Lists Consumed-stage drift findings (unenforced reads / enforcement bypasses) with optional filters on type, asset, subject, and read-time window.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Findings retrieved"),
        @ApiResponse(responseCode = "400", description = "Invalid filter parameter"),
        @ApiResponse(responseCode = "401", description = "Unauthenticated"),
        @ApiResponse(responseCode = "403", description = "Access denied - insufficient privileges")
    })
    public ResponseEntity<Page<DriftFindingDTO>> listFindings(
            @Parameter(description = "Filter by drift type")
            @RequestParam(required = false) DriftType type,
            @Parameter(description = "Filter by asset id")
            @RequestParam(required = false) String assetId,
            @Parameter(description = "Filter by subject (principal) id")
            @RequestParam(required = false) String subjectId,
            @Parameter(description = "Inclusive lower bound on read time (ISO-8601)")
            @RequestParam(required = false) String from,
            @Parameter(description = "Inclusive upper bound on read time (ISO-8601)")
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Instant fromTs;
        Instant toTs;
        try {
            fromTs = parse(from);
            toTs = parse(to);
        } catch (DateTimeParseException e) {
            log.warn("Invalid drift-finding time filter: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        int pageIndex = Math.max(page, 0);
        Pageable pageable = PageRequest.of(pageIndex, pageSize);

        Page<DriftFindingDTO> result = driftFindingRepository
                .search(type, assetId, subjectId, fromTs, toTs, pageable)
                .map(DriftFindingDTO::from);
        return ResponseEntity.ok(result);
    }

    private static Instant parse(String iso) {
        return (iso == null || iso.isBlank()) ? null : Instant.parse(iso);
    }
}
