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

import com.dlap.reporting.dto.GeneratedReportOutputDTO;
import com.dlap.reporting.export.CsvWriter;
import com.dlap.reporting.service.IReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M6: CSV export endpoint for generated reports. Closes the
 * "CSV/Excel export" acceptance criterion in OPERATING_PLAN. Excel
 * (XLSX) export lands in 0.2.0 with Apache POI; v0.1.0 ships CSV
 * because every consumer (spreadsheets, BI tools, scripts) reads it
 * out of the box.
 */
@RestController
@RequestMapping("/api/v1/reporting/exports")
@RequiredArgsConstructor
@Tag(name = "Report Exports", description = "Download report findings in portable formats")
@Slf4j
public class ReportExportController {

    private final IReportService reportService;

    @GetMapping(value = "/csv/{generatedReportId}", produces = "text/csv; charset=UTF-8")
    @PreAuthorize(ReportController.READ_ACCESS)
    @Operation(summary = "Download a generated report as CSV")
    public ResponseEntity<String> exportAsCsv(@PathVariable UUID generatedReportId) {
        GeneratedReportOutputDTO report = reportService.getGeneratedReportById(generatedReportId);
        String csv = renderAsCsv(report);
        String filename = "report-" + generatedReportId + ".csv";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    /**
     * Render the report's findings list as RFC 4180 CSV. First row is
     * headers from the union of all keys across findings; subsequent
     * rows are values. NULL cells emit empty fields.
     *
     * <p>Every text cell, header names included, goes through
     * {@link CsvWriter#cell(Object)}: a cell that starts with {@code = + - @}
     * TAB or CR is prefixed with a single quote so a spreadsheet cannot run it
     * as a formula, and values containing comma / quote / newline are
     * double-quoted with embedded quotes doubled.
     */
    private String renderAsCsv(GeneratedReportOutputDTO report) {
        StringBuilder sb = new StringBuilder();
        sb.append("reportId,reportKey,generatedAt,status\n");
        sb.append(CsvWriter.row(Arrays.asList(
                String.valueOf(report.getId()),
                report.getReportKey(),
                report.getCompletedAt() != null ? report.getCompletedAt().toString() : "",
                report.getStatus() != null ? report.getStatus().toString() : "")));
        sb.append('\n');

        // Findings table follows the report-summary table.
        Object findings = report.getReportContent();
        if (findings instanceof List<?> list && !list.isEmpty()) {
            // Collect column names across all rows (preserve order of first appearance).
            LinkedHashSet<String> columns = new LinkedHashSet<>();
            for (Object row : list) {
                if (row instanceof Map<?, ?> m) {
                    for (Object k : m.keySet()) {
                        columns.add(String.valueOf(k));
                    }
                }
            }
            if (!columns.isEmpty()) {
                sb.append(CsvWriter.row(columns));
                for (Object row : list) {
                    if (row instanceof Map<?, ?> m) {
                        List<Object> cells = new ArrayList<>(columns.size());
                        for (String col : columns) {
                            cells.add(m.get(col));
                        }
                        sb.append(CsvWriter.row(cells));
                    }
                }
            }
        }

        return sb.toString();
    }
}
