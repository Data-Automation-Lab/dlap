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

package com.dlap.logingest.health;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlap.logingest.config.SecurityConfiguration;
import com.dlap.logingest.health.LogHealthViews.BindingHealth;
import com.dlap.logingest.health.LogHealthViews.DriftPathView;
import com.dlap.logingest.health.LogHealthViews.DriftReport;
import com.dlap.logingest.health.LogHealthViews.FillDropView;
import com.dlap.logingest.health.LogHealthViews.HealthPage;
import com.dlap.logingest.health.LogHealthViews.NewVersionView;
import com.dlap.logingest.health.LogHealthViews.SelfTestView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The health and drift API slice: ADMIN only, the JSON shapes, the status codes. */
@WebMvcTest(LogHealthController.class)
@Import(SecurityConfiguration.class)
class LogHealthControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BindingHealthService health;

    @MockitoBean
    private DriftReportService drift;

    private static BindingHealth binding(String id) {
        return new BindingHealth(id, "src", "GCP_CLOUD_AUDIT", "GCP_PROJECT", "dlap-demo", "ACTIVE", "", "h".repeat(64),
                List.of(), List.of(), List.of(), List.of(), List.of(), 0.25, T0, 120L, "CAPTURED", T0.minusSeconds(3600),
                true);
    }

    @Test
    void anonymousIsUnauthorizedAndOtherRolesAreForbidden() throws Exception {
        mvc.perform(get("/api/v1/logingest/health")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/logingest/drift")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"CDO", "DIRECTOR", "POLICY_MANAGER", "DATA_ENGINEER", "DATA_SCIENTIST", "DATA_ANALYST",
            "AUDITOR", "VIEWER", "SERVICE_ACCOUNT"})
    void everyRoleButAdminIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/logingest/health")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/logingest/health/gcp-main")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/logingest/drift")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void thePageListsBindingsWithTheSelfTest() throws Exception {
        given(health.page()).willReturn(new HealthPage(T0, "registry", new SelfTestView(true, 4, Map.of()),
                List.of(binding("gcp-main"))));

        mvc.perform(get("/api/v1/logingest/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pipeline").value("registry"))
                .andExpect(jsonPath("$.selfTest.ok").value(true))
                .andExpect(jsonPath("$.selfTest.cases").value(4))
                .andExpect(jsonPath("$.bindings[0].bindingId").value("gcp-main"))
                .andExpect(jsonPath("$.bindings[0].readCoverage").value("CAPTURED"))
                .andExpect(jsonPath("$.bindings[0].lagSeconds").value(120))
                .andExpect(jsonPath("$.bindings[0].heuristicRatio").value(0.25));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void oneBindingOr404() throws Exception {
        given(health.binding("gcp-main")).willReturn(Optional.of(binding("gcp-main")));
        given(health.binding("nope")).willReturn(Optional.empty());

        mvc.perform(get("/api/v1/logingest/health/gcp-main")).andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeType").value("GCP_PROJECT"));
        mvc.perform(get("/api/v1/logingest/health/nope")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theDriftReportPassesSinceAndLimitAndNeverCarriesValues() throws Exception {
        given(drift.report(any(), eq(25))).willReturn(new DriftReport(T0, T0.minusSeconds(86400), "note",
                List.of(new NewVersionView("gcp-main", "GCP_CLOUD_AUDIT", "gcp-audit-log", "auditlog-v2", "",
                        T0.minusSeconds(60), 3)),
                List.of(new DriftPathView("GCP_CLOUD_AUDIT", "gcp-audit-log", "auditlog", "/protoPayload/newField", 9)),
                List.of(new FillDropView("GCP_CLOUD_AUDIT", "auditlog", "target.region", 0.5, 1.0, 0.5, 200))));

        mvc.perform(get("/api/v1/logingest/drift").param("since", "2026-10-19T12:00:00Z").param("limit", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newVersions[0].version").value("auditlog-v2"))
                .andExpect(jsonPath("$.unknownPaths[0].path").value("/protoPayload/newField"))
                .andExpect(jsonPath("$.fillDrops[0].field").value("target.region"))
                .andExpect(jsonPath("$.fillDrops[0].drop").value(0.5));

        verify(drift).report(eq(Instant.parse("2026-10-19T12:00:00Z")), eq(25));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aLimitOutsideTheBoundsIsABadRequest() throws Exception {
        mvc.perform(get("/api/v1/logingest/drift").param("limit", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/logingest/drift").param("limit", "201")).andExpect(status().isBadRequest());
    }
}
