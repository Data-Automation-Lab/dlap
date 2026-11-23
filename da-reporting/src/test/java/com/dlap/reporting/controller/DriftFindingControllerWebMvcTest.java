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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import com.dlap.reporting.config.SecurityConfiguration;
import com.dlap.reporting.drift.DriftType;
import com.dlap.reporting.model.DriftFindingEntity;
import com.dlap.reporting.repository.DriftFindingRepository;

/**
 * {@code @WebMvcTest} coverage for {@link DriftFindingController}: verifies the
 * {@code @PreAuthorize} gate (anonymous rejected, AUDITOR allowed) and the
 * documented {@code Page<DriftFindingDTO>} response shape.
 */
@WebMvcTest(controllers = DriftFindingController.class)
@Import(SecurityConfiguration.class)
class DriftFindingControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DriftFindingRepository driftFindingRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private DriftFindingEntity sampleFinding() {
        DriftFindingEntity e = new DriftFindingEntity();
        e.setDriftType(DriftType.UNENFORCED_READ);
        e.setAssetId("asset-1");
        e.setSubjectId("svc-agent");
        e.setReadEventId("evt-1");
        e.setReadAt(Instant.parse("2026-07-06T12:00:00Z"));
        e.setDetail("unenforced read");
        return e;
    }

    @Test
    void listFindings_withoutAuth_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/reporting/drift/findings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listFindings_withWrongRole_isForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/reporting/drift/findings")
                        .with(jwt().authorities(new org.springframework.security.core.authority
                                .SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listFindings_asAuditor_returnsPagedFindings() throws Exception {
        Page<DriftFindingEntity> page =
                new PageImpl<>(List.of(sampleFinding()), Pageable.ofSize(50), 1);
        when(driftFindingRepository.search(any(), any(), any(), any(), any(), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/reporting/drift/findings")
                        .with(jwt().authorities(new org.springframework.security.core.authority
                                .SimpleGrantedAuthority("ROLE_AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].type").value("UNENFORCED_READ"))
                .andExpect(jsonPath("$.content[0].assetId").value("asset-1"))
                .andExpect(jsonPath("$.content[0].subjectId").value("svc-agent"));
    }

    @Test
    void listFindings_withBadTimeFilter_isBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/reporting/drift/findings")
                        .param("from", "not-a-timestamp")
                        .with(jwt().authorities(new org.springframework.security.core.authority
                                .SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest());
    }
}
