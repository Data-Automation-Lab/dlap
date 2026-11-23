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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.dlap.reporting.config.SecurityConfiguration;
import com.dlap.reporting.dto.GeneratedReportOutputDTO;
import com.dlap.reporting.model.ReportStatus;
import com.dlap.reporting.service.IReportService;

/**
 * {@code @WebMvcTest} coverage for {@link ReportExportController}: the named-role
 * gate (all nine human roles, nothing else) and CSV injection safety of the
 * rendered body.
 */
@WebMvcTest(controllers = ReportExportController.class)
@Import(SecurityConfiguration.class)
class ReportExportControllerWebMvcTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String URL = "/api/v1/reporting/exports/csv/{id}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IReportService reportService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(String authority) {
        return jwt().authorities(new SimpleGrantedAuthority(authority));
    }

    private static GeneratedReportOutputDTO report(Object content) {
        GeneratedReportOutputDTO dto = new GeneratedReportOutputDTO();
        dto.setId(ID);
        dto.setReportKey("gov-summary");
        dto.setStatus(ReportStatus.COMPLETED);
        dto.setCompletedAt(Instant.parse("2026-10-18T00:00:00Z"));
        dto.setReportContent(content);
        return dto;
    }

    @Test
    void export_withoutAuth_isUnauthorized() throws Exception {
        mockMvc.perform(get(URL, ID)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CDO", "DIRECTOR", "POLICY_MANAGER", "DATA_ENGINEER",
            "DATA_SCIENTIST", "DATA_ANALYST", "AUDITOR", "VIEWER"})
    void export_allowsEveryHumanRole(String role) throws Exception {
        when(reportService.getGeneratedReportById(ID)).thenReturn(report(List.of()));

        mockMvc.perform(get(URL, ID).with(as("ROLE_" + role)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"report-" + ID + ".csv\""));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_SERVICE_ACCOUNT", "ROLE_USER", "SCOPE_read"})
    void export_deniesAuthenticatedCallersWithoutADlapRole(String authority) throws Exception {
        when(reportService.getGeneratedReportById(ID)).thenReturn(report(List.of()));

        mockMvc.perform(get(URL, ID).with(as(authority))).andExpect(status().isForbidden());
    }

    @Test
    void export_neutralisesFormulaCellsInValuesAndHeaders() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("asset", "=HYPERLINK(\"http://evil\",\"x\")");
        row.put("owner", "@bob");
        row.put("delta", -5);
        row.put("note", "plain, with comma");
        row.put("=bad", "+1");
        when(reportService.getGeneratedReportById(ID)).thenReturn(report(List.of(row)));

        String body = mockMvc.perform(get(URL, ID).with(as("ROLE_VIEWER")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).isEqualTo(
                "reportId,reportKey,generatedAt,status\n"
                + ID + ",gov-summary,2026-10-18T00:00:00Z,COMPLETED\n"
                + "\n"
                + "asset,owner,delta,note,'=bad\n"
                + "\"'=HYPERLINK(\"\"http://evil\"\",\"\"x\"\")\",'@bob,-5,\"plain, with comma\",'+1\n");
    }

    @Test
    void export_neutralisesTheSummaryRowToo() throws Exception {
        GeneratedReportOutputDTO dto = report(List.of());
        dto.setReportKey("-2+3");
        when(reportService.getGeneratedReportById(ID)).thenReturn(dto);

        String body = mockMvc.perform(get(URL, ID).with(as("ROLE_AUDITOR")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).contains(ID + ",'-2+3,2026-10-18T00:00:00Z,COMPLETED\n");
    }
}
