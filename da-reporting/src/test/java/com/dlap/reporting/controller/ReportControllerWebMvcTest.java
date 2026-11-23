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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.dlap.reporting.config.SecurityConfiguration;
import com.dlap.reporting.service.IReportService;

/**
 * {@code @WebMvcTest} role matrix for {@link ReportController} against
 * docs/specs/access-model.md ("Reports and dashboards"): every human role may
 * read, only the Executive tier (ADMIN, CDO) may define or generate reports,
 * and nothing else is authorised, including the machine principal and the
 * non-canonical {@code SCOPE_manage:reports} authority the controller used to accept.
 */
@WebMvcTest(controllers = ReportController.class)
@Import(SecurityConfiguration.class)
class ReportControllerWebMvcTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String DEFINITION_JSON = "{\"reportKey\":\"gov-summary\",\"displayName\":\"Governance summary\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IReportService reportService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(String authority) {
        return jwt().authorities(new SimpleGrantedAuthority(authority));
    }

    // ----------------------------------------------------------------- anonymous

    @Test
    void everyEndpoint_withoutAuth_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/reporting/definitions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reporting/definitions/{id}", ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reporting/definitions/key/k")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reporting/reports")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reporting/reports/key/k")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/reporting/reports/jobs/{id}", ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/reporting/definitions").contentType(MediaType.APPLICATION_JSON)
                .content(DEFINITION_JSON)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/reporting/key/k/generate")).andExpect(status().isUnauthorized());
    }

    // ----------------------------------------------------------------- read

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CDO", "DIRECTOR", "POLICY_MANAGER", "DATA_ENGINEER",
            "DATA_SCIENTIST", "DATA_ANALYST", "AUDITOR", "VIEWER"})
    void readEndpoints_allowEveryHumanRole(String role) throws Exception {
        RequestPostProcessor caller = as("ROLE_" + role);

        mockMvc.perform(get("/api/v1/reporting/definitions").with(caller)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/reporting/definitions/{id}", ID).with(caller)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/reporting/definitions/key/k").with(caller)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/reporting/reports").with(caller)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/reporting/reports/key/k").with(caller)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/reporting/reports/jobs/{id}", ID).with(caller)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_SERVICE_ACCOUNT", "ROLE_USER", "ROLE_offline_access",
            "SCOPE_manage:reports", "SCOPE_read"})
    void readEndpoints_denyAuthenticatedCallersWithoutADlapRole(String authority) throws Exception {
        RequestPostProcessor caller = as(authority);

        mockMvc.perform(get("/api/v1/reporting/definitions").with(caller)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/reporting/definitions/{id}", ID).with(caller)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/reporting/definitions/key/k").with(caller)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/reporting/reports").with(caller)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/reporting/reports/key/k").with(caller)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/reporting/reports/jobs/{id}", ID).with(caller)).andExpect(status().isForbidden());
    }

    @Test
    void readEndpoints_denyATokenWithNoAuthoritiesAtAll() throws Exception {
        mockMvc.perform(get("/api/v1/reporting/reports").with(jwt().authorities()))
                .andExpect(status().isForbidden());
    }

    // ----------------------------------------------------------------- define and generate

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CDO"})
    void manageEndpoints_allowTheExecutiveTier(String role) throws Exception {
        RequestPostProcessor caller = as("ROLE_" + role);

        mockMvc.perform(post("/api/v1/reporting/definitions").with(caller)
                .contentType(MediaType.APPLICATION_JSON).content(DEFINITION_JSON))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/v1/reporting/definitions/{id}", ID).with(caller)
                .contentType(MediaType.APPLICATION_JSON).content(DEFINITION_JSON))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/reporting/definitions/{id}", ID).with(caller))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/reporting/key/gov-summary/generate").with(caller))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/v1/reporting/id/{id}/generate", ID).with(caller))
                .andExpect(status().isAccepted());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_DIRECTOR", "ROLE_POLICY_MANAGER", "ROLE_DATA_ENGINEER",
            "ROLE_DATA_SCIENTIST", "ROLE_DATA_ANALYST", "ROLE_AUDITOR", "ROLE_VIEWER",
            "ROLE_SERVICE_ACCOUNT", "SCOPE_manage:reports"})
    void manageEndpoints_denyEverythingBelowTheExecutiveTier(String authority) throws Exception {
        RequestPostProcessor caller = as(authority);

        mockMvc.perform(post("/api/v1/reporting/definitions").with(caller)
                .contentType(MediaType.APPLICATION_JSON).content(DEFINITION_JSON))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/reporting/definitions/{id}", ID).with(caller)
                .contentType(MediaType.APPLICATION_JSON).content(DEFINITION_JSON))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/reporting/definitions/{id}", ID).with(caller))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/reporting/key/gov-summary/generate").with(caller))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/reporting/id/{id}/generate", ID).with(caller))
                .andExpect(status().isForbidden());
    }
}
