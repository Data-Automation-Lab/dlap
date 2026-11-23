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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlap.reporting.config.SecurityConfiguration;
import com.dlap.reporting.service.IDashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * R-P0-6: minimum {@code @WebMvcTest} coverage for {@link DashboardController}.
 *
 * <p>Verifies the controller wires under MockMvc and that {@code @PreAuthorize}
 * gates reject anonymous requests.
 */
@WebMvcTest(controllers = DashboardController.class)
@Import(SecurityConfiguration.class)
class DashboardControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IDashboardService dashboardService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void governanceScore_withoutAuth_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/reporting/dashboards/cdo/governance-score"))
                .andExpect(status().isUnauthorized());
    }
}
