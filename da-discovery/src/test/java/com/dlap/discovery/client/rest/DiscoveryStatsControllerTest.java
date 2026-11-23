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

package com.dlap.discovery.client.rest;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlap.common.constants.DlapConstants;
import com.dlap.discovery.client.rest.dto.DiscoveryStatsDTO;
import com.dlap.discovery.stats.service.IDiscoveryStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/v1/discovery/statistics} on a web slice with method security on: the response
 * shape, and who may read it. Replaces a full-application test that only ran with a dev stack up,
 * called a path that does not exist ({@code /stats}) and used the roles {@code USER} and
 * {@code GUEST}, which DLAP does not have.
 */
@WebMvcTest(
    controllers = DiscoveryStatsController.class,
    excludeAutoConfiguration = { OAuth2ClientAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class }
)
@ActiveProfiles("test")
class DiscoveryStatsControllerTest {

    @SpringBootConfiguration
    @Import({ TestWebSecurityConfiguration.class, DiscoveryStatsController.class })
    static class TestApp {}

    private static final String ADMIN = "ROLE_" + DlapConstants.UserRole.ADMIN;
    private static final String DATA_ANALYST = "ROLE_" + DlapConstants.UserRole.DATA_ANALYST;
    private static final String VIEWER = "ROLE_" + DlapConstants.UserRole.VIEWER;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IDiscoveryStatsService statsService;

    private DiscoveryStatsDTO statsDTO;

    @BeforeEach
    void setUp() {
        statsDTO =
            DiscoveryStatsDTO
                .builder()
                .totalScansSubmitted(100L)
                .scansSucceeded(90L)
                .scansFailed(5L)
                .scansRunning(3L)
                .scansPending(2L)
                .totalAssetsInCatalog(5000L)
                .assetsDiscoveredLast24h(200L)
                .averageScanDurationSeconds(120.5)
                .build();
    }

    @Test
    @WithMockUser(authorities = ADMIN)
    void getDiscoveryStats_AsAdmin_ShouldReturnStats() throws Exception {
        when(statsService.getDiscoveryStats()).thenReturn(statsDTO);

        mockMvc
            .perform(get("/api/v1/discovery/statistics").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalScansSubmitted").value(100L))
            .andExpect(jsonPath("$.totalAssetsInCatalog").value(5000L))
            .andExpect(jsonPath("$.averageScanDurationSeconds").value(120.5));
    }

    @Test
    @WithMockUser(authorities = DATA_ANALYST)
    void getDiscoveryStats_AsDataAnalyst_ShouldReturnStats() throws Exception {
        when(statsService.getDiscoveryStats()).thenReturn(statsDTO);

        mockMvc
            .perform(get("/api/v1/discovery/statistics").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalScansSubmitted").value(100L));
    }

    @Test
    @WithMockUser(authorities = VIEWER)
    void getDiscoveryStats_AsViewer_ShouldBeForbidden() throws Exception {
        mockMvc
            .perform(get("/api/v1/discovery/statistics").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isForbidden());

        verifyNoInteractions(statsService);
    }

    @Test
    void getDiscoveryStats_WithoutCredentials_ShouldBeUnauthorized() throws Exception {
        mockMvc
            .perform(get("/api/v1/discovery/statistics").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized());
    }
}
