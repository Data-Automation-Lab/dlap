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

package com.dlap.reporting.report.expiry;

import java.io.IOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springdoc.core.annotations.ParameterObject;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The Asset Activity and Expiry report (RPT-01): a paged JSON form, a streamed CSV export, and the
 * manifest of a completed export. The rows come from the catalog; see {@link ExpiryReportService}
 * and the contract in {@code package-info}.
 *
 * <p>Access: all nine human roles of the Access Model may read reports
 * ({@code docs/specs/access-model.md}, "Reports and dashboards": every tier may view). A token
 * with no DLAP role, and the machine principal, are denied: no machine caller reaches reporting.
 * The report is read-only. The service calls the catalog with its own service account, so what a
 * caller sees is decided by these roles and not by the caller's rights in the catalog.
 */
@RestController
@RequestMapping("/api/v1/reporting/reports")
@Tag(name = "Asset Activity and Expiry report", description = "Age, last read, size and retention of every asset, as JSON or CSV")
public class ExpiryReportController {

    /** Who may read the report, its CSV export and the manifest: the nine human roles. */
    static final String READ_ACCESS = "hasAnyAuthority('ROLE_ADMIN', 'ROLE_CDO', 'ROLE_DIRECTOR', "
            + "'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', "
            + "'ROLE_DATA_ANALYST', 'ROLE_AUDITOR', 'ROLE_VIEWER')";

    private static final Logger log = LoggerFactory.getLogger(ExpiryReportController.class);
    private static final String CSV = "text/csv; charset=UTF-8";
    private static final String MANIFEST_PATH = "/api/v1/reporting/reports/expiry/exports/";

    private final ExpiryReportService service;
    private final Clock clock;

    /**
     * Creates the controller.
     *
     * @param service the report service
     * @param clocks  the application clock when one is defined, else the system UTC clock
     */
    public ExpiryReportController(ExpiryReportService service, ObjectProvider<Clock> clocks) {
        this.service = service;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    /**
     * One page of the report as JSON.
     *
     * @param params the filters, the report instant and the paging
     * @return the page
     */
    @GetMapping(value = "/expiry", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(READ_ACCESS)
    @Operation(
        operationId = "getAssetActivityExpiryReport",
        summary = "Asset Activity and Expiry report (JSON, paged)",
        description = "Every asset with its age, size, last read, last write and retention state as of one instant. "
                + "A missing value is null and means unknown. readSignalState says whether a missing last read means "
                + "'not read' (NOT_READ, only with FULL read coverage) or that the read logs did not watch "
                + "(PARTIAL_COVERAGE, NO_COVERAGE, UNKNOWN). readCoverage is shown as the catalog states it.",
        responses = {
            @ApiResponse(responseCode = "200", description = "One page of the report"),
            @ApiResponse(responseCode = "400", description = "A filter, the sort, the page or a time is malformed"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions"),
            @ApiResponse(responseCode = "503", description = "The catalog cannot deliver the rows; the report is never partial")
        })
    public ExpiryReportPage expiry(@ParameterObject ExpiryReportParams params) {
        return service.page(service.parse(params));
    }

    /**
     * The whole report as a streamed CSV. Paging parameters are ignored; the export runs in
     * {@code assetUri} order over every matching row, walking the catalog by keyset (Q-445).
     *
     * @param params         the filters and the report instant
     * @param authentication the caller, for the audit line in the log
     * @param response       the response, written directly so the export is never held in memory
     * @throws IOException when the client connection fails
     */
    @GetMapping(value = "/expiry.csv", produces = "text/csv")
    @PreAuthorize(READ_ACCESS)
    @Operation(
        operationId = "exportAssetActivityExpiryReportCsv",
        summary = "Asset Activity and Expiry report (CSV export, streamed)",
        description = "The same columns as the JSON report for every matching asset, one row per asset in assetUri order. "
                + "The response carries X-Export-Id; GET /api/v1/reporting/reports/expiry/exports/{exportId}/manifest "
                + "returns the SHA-256 of the exact bytes, the row count and the filters. A file with no matching "
                + "manifest is incomplete. Empty cells are unknown values. The export walks the catalog by keyset, so "
                + "an asset written while it runs is never skipped or repeated; one written ahead of the walk is "
                + "included and one written behind it is not.",
        responses = {
            @ApiResponse(responseCode = "200", description = "The CSV export", content = @Content(mediaType = "text/csv",
                    schema = @Schema(type = "string"))),
            @ApiResponse(responseCode = "400", description = "A filter or a time is malformed"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions"),
            @ApiResponse(responseCode = "413", description = "The result is larger than the export limit; narrow the filters"),
            @ApiResponse(responseCode = "503", description = "The catalog cannot deliver the rows")
        })
    public void expiryCsv(@ParameterObject ExpiryReportParams params, Authentication authentication,
            HttpServletResponse response) throws IOException {
        ExpiryReportService.ExportSession session = service.startExport(service.parse(params));
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(CSV);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + session.fileName() + "\"");
        response.setHeader("X-Export-Id", session.exportId().toString());
        response.setHeader(HttpHeaders.LINK,
                "<" + MANIFEST_PATH + session.exportId() + "/manifest>; rel=\"describedby\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        log.info("Expiry report export {} started by {}", session.exportId(),
                authentication == null ? "unknown" : authentication.getName());
        try {
            session.writeTo(response.getOutputStream());
        } catch (ExportAbortedException e) {
            // The status is committed and the session has written its marker line and logged the cause.
            // Letting the exception reach the global handler would append a JSON error to the file.
            response.flushBuffer();
        }
    }

    /**
     * The manifest of a completed export.
     *
     * @param exportId the id from the {@code X-Export-Id} header
     * @return the manifest
     */
    @GetMapping(value = "/expiry/exports/{exportId}/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(READ_ACCESS)
    @Operation(
        operationId = "getAssetActivityExpiryExportManifest",
        summary = "Manifest of a completed CSV export",
        description = "SHA-256 and size of the exported bytes, the row count, the columns, the report instant and the "
                + "filters. Kept in the memory of the instance that served the export for 24 hours by default.",
        responses = {
            @ApiResponse(responseCode = "200", description = "The manifest"),
            @ApiResponse(responseCode = "400", description = "The export id is not a UUID"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions"),
            @ApiResponse(responseCode = "404", description = "No completed export with this id is known here")
        })
    public ResponseEntity<?> manifest(@PathVariable String exportId, HttpServletRequest request) {
        UUID id;
        try {
            id = UUID.fromString(exportId.strip());
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "exportId must be the UUID from the X-Export-Id header", request);
        }
        return service.manifest(id)
                .<ResponseEntity<?>>map(m -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(m))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "No completed export " + id + " is known to this instance. "
                        + "It may have expired, been served by another instance, or not finished", request));
    }

    @ExceptionHandler(ExpiryReportRequestException.class)
    ResponseEntity<Map<String, Object>> badRequest(ExpiryReportRequestException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage(), request);
    }

    @ExceptionHandler(ExportRejectedException.class)
    ResponseEntity<Map<String, Object>> tooLarge(ExportRejectedException e, HttpServletRequest request) {
        return error(HttpStatus.CONTENT_TOO_LARGE, e.getMessage(), request);
    }

    @ExceptionHandler(CatalogUnavailableException.class)
    ResponseEntity<Map<String, Object>> catalogUnavailable(CatalogUnavailableException e, HttpServletRequest request) {
        log.warn("Expiry report cannot be served: {}", e.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), request);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", clock.instant().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
