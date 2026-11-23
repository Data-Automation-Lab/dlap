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

package com.dlap.autoarchival.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dlap.autoarchival.executor.ArchiveRuleEntity;
import com.dlap.autoarchival.executor.ArchiveRuleRepository;
import com.dlap.autoarchival.executor.ArchiveRuleResetService;
import com.dlap.autoarchival.rule.NativeRuleProvider;
import com.dlap.autoarchival.rule.RuleException;
import com.dlap.autoarchival.rule.RulePlan;
import com.dlap.autoarchival.rule.RulePlanner;
import com.dlap.autoarchival.rule.RuleScope;
import com.dlap.autoarchival.service.AssetLocation;
import com.dlap.autoarchival.service.AssetLocationResolver;
import com.dlap.autoarchival.service.IArchivalTaskService;

@WebMvcTest(ArchiveRuleController.class)
class ArchiveRuleControllerTest {

    private static final String ASSET = "11111111-2222-3333-4444-555555555555";
    private static final String PLAN_URL = "/api/v1/archival/rules/plan";
    private static final String ASSET_BODY = "{\"assetId\":\"" + ASSET + "\"}";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private RulePlanner planner;
    @MockitoBean
    private AssetLocationResolver resolver;
    @MockitoBean
    private ArchiveRuleRepository rules;
    @MockitoBean
    private IArchivalTaskService tasks;
    @MockitoBean
    private ArchiveRuleResetService resets;

    private static RulePlan plan() {
        NativeRuleProvider.RuleSpec spec = new NativeRuleProvider.RuleSpec("dlap-archive-0123456789ab",
                new RuleScope("GCP", "", "pilot", "finance/", false), "COLDLINE", 0);
        return new RulePlan(spec, new NativeRuleProvider.Inventory(2, 100, false),
                new NativeRuleProvider.Listing(List.of(new NativeRuleProvider.ListingEntry("finance/a.csv", 10L, "STANDARD")),
                        true, 1, 10L, true, "objects.list, first 1 objects"),
                "{\"rule\":[]}", true, "PREFIX", List.of("The rule covers every object under the prefix."));
    }

    private static ArchiveRuleEntity row(String status) {
        ArchiveRuleEntity r = new ArchiveRuleEntity("dlap-archive-0123456789ab", "appr-1", "fire-1", "t1", ASSET,
                "gs://pilot/finance/", "GCP", ArchiveRuleEntity.MODE_NATIVE_RULE, "pilot", "finance/", "COLDLINE", 0,
                Instant.parse("2026-11-01T10:00:00Z"));
        r.markFailed(status, null, Instant.parse("2026-11-01T10:00:00Z"));
        return r;
    }

    @Test
    @WithMockUser(authorities = "ROLE_POLICY_MANAGER")
    void theDryRunReturnsTheRuleTheDocumentAndABoundedListing() throws Exception {
        when(resolver.resolve(ASSET)).thenReturn(Optional.of(new AssetLocation("GCP", "gs://pilot/")));
        when(planner.plan(eq("GCP"), eq("gs://pilot/"), any(), isNull(), isNull(), eq(5))).thenReturn(plan());

        mvc.perform(post(PLAN_URL).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assetId\":\"" + ASSET + "\",\"tier\":\"COLD\",\"prefix\":\"finance/\",\"listingKeys\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spec.ruleId").value("dlap-archive-0123456789ab"))
                .andExpect(jsonPath("$.spec.nativeClass").value("COLDLINE"))
                .andExpect(jsonPath("$.scopeKind").value("PREFIX"))
                .andExpect(jsonPath("$.listing.entries[0].key").value("finance/a.csv"))
                .andExpect(jsonPath("$.listing.truncated").value(true))
                .andExpect(jsonPath("$.inventory.totalRules").value(2));
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void theDryRunNeverTakesABucketNameOnlyACatalogAsset() throws Exception {
        when(resolver.resolve(ASSET)).thenReturn(Optional.empty());

        mvc.perform(post(PLAN_URL).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(ASSET_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("UNRESOLVED_LOCATION"));
        mvc.perform(post(PLAN_URL).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bucket\":\"someone-elses-bucket\"}"))
                .andExpect(status().isBadRequest());
        verify(planner, never()).plan(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void aRuleRefusedAtPlanningIsReportedWithItsReason() throws Exception {
        when(resolver.resolve(ASSET)).thenReturn(Optional.of(new AssetLocation("AWS", "s3://b/")));
        when(planner.plan(anyString(), anyString(), any(), any(), any(), any()))
                .thenThrow(new RuleException(RuleException.Reason.INVALID_TIER, "'STANDARD_ARCHIVE' is not an archive tier"))
                .thenThrow(new RuleException(RuleException.Reason.RULE_LIMIT, "S3 allows 1000 lifecycle rules"))
                .thenThrow(new RuleException(RuleException.Reason.PROVIDER_ERROR, "S3 listing failed"));

        for (int expected : new int[] {400, 409, 502}) {
            mvc.perform(post(PLAN_URL).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(ASSET_BODY))
                    .andExpect(status().is(expected));
        }
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void theDryRunValidatesItsInput() throws Exception {
        for (String body : List.of("{\"assetId\":\"" + ASSET + "\",\"listingKeys\":5000}",
                "{\"assetId\":\"" + ASSET + "\",\"afterDays\":-1}", "{\"assetId\":\"\"}")) {
            mvc.perform(post(PLAN_URL).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    @WithMockUser(authorities = "ROLE_DATA_ANALYST")
    void anAnalystCannotPlanOrRetry() throws Exception {
        mvc.perform(post(PLAN_URL).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(ASSET_BODY))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/archival/rules/r1/retry").with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void anAnonymousCallerIsRefused() throws Exception {
        mvc.perform(get("/api/v1/archival/rules")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(authorities = "ROLE_AUDITOR")
    void anAuditorReadsTheRecordsWithTheirProof() throws Exception {
        ArchiveRuleEntity executed = row(ArchiveRuleEntity.STATUS_PLANNED);
        executed.markApplied(ArchiveRuleEntity.STATUS_APPLIED, "{}", false, Instant.parse("2026-11-01T10:00:00Z"));
        executed.markExecuted("ev-9", "LifecycleTransition", Instant.parse("2026-11-02T00:00:07Z"), "PROVIDER_LOG",
                Instant.parse("2026-11-02T01:00:00Z"));
        when(rules.findByStatusInOrderByCreatedAtDesc(anyCollection(), any()))
                .thenReturn(new PageImpl<>(List.of(executed), PageRequest.of(0, 50), 1));
        when(rules.findById("dlap-archive-0123456789ab")).thenReturn(Optional.of(executed));

        mvc.perform(get("/api/v1/archival/rules?status=executed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("EXECUTED"))
                .andExpect(jsonPath("$.content[0].evidenceEventId").value("ev-9"))
                .andExpect(jsonPath("$.content[0].evidenceKind").value("PROVIDER_LOG"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/archival/rules/dlap-archive-0123456789ab"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalId").value("appr-1"))
                .andExpect(jsonPath("$.executedAt").exists());
        mvc.perform(get("/api/v1/archival/rules/nope")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/archival/rules?status=DONE")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void retryQueuesOnlyAWaitingRule() throws Exception {
        when(rules.findById("w")).thenReturn(Optional.of(row(ArchiveRuleEntity.STATUS_PLANNED)));
        when(rules.findById("d")).thenReturn(Optional.of(row(ArchiveRuleEntity.STATUS_APPLIED)));

        mvc.perform(post("/api/v1/archival/rules/w/retry").with(csrf())).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"));
        verify(tasks).executeApprovalAsync("appr-1");
        mvc.perform(post("/api/v1/archival/rules/d/retry").with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/archival/rules/none/retry").with(csrf())).andExpect(status().isNotFound());
    }

    // ---- Q-669: POST /rules/{id}/reset, ADMIN only ----------------------------------------------------

    private static ArchiveRuleResetService.Result resetResult(ArchiveRuleResetService.Outcome outcome, String status) {
        ArchiveRuleEntity r = row(status);
        return new ArchiveRuleResetService.Result(outcome, r, "message for " + outcome);
    }

    @Test
    @WithMockUser(username = "admin-sub", authorities = "ROLE_ADMIN")
    void q669_resetReturnsTheNewStatusAndAuditsTheAdministrator() throws Exception {
        when(resets.reset(eq("r1"), any())).thenReturn(
                resetResult(ArchiveRuleResetService.Outcome.RESET_TO_APPLIED, ArchiveRuleEntity.STATUS_APPLIED));

        mvc.perform(post("/api/v1/archival/rules/r1/reset").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RESET_TO_APPLIED"))
                .andExpect(jsonPath("$.status").value("APPLIED"));

        org.mockito.ArgumentCaptor<com.dlap.common.audit.AuditActor> actor =
                org.mockito.ArgumentCaptor.forClass(com.dlap.common.audit.AuditActor.class);
        verify(resets).reset(eq("r1"), actor.capture());
        org.assertj.core.api.Assertions.assertThat(actor.getValue())
                .isEqualTo(com.dlap.common.audit.AuditActor.user("admin-sub"));
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void q669_resetToFailedIsAlsoA200() throws Exception {
        when(resets.reset(eq("r2"), any())).thenReturn(
                resetResult(ArchiveRuleResetService.Outcome.RESET_TO_FAILED, ArchiveRuleEntity.STATUS_FAILED));

        mvc.perform(post("/api/v1/archival/rules/r2/reset").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RESET_TO_FAILED"))
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void q669_resetMapsEveryRefusalToItsStatus() throws Exception {
        for (Object[] c : new Object[][] {
                {ArchiveRuleResetService.Outcome.NOT_EXECUTING, 409},
                {ArchiveRuleResetService.Outcome.UNREADABLE, 409},
                {ArchiveRuleResetService.Outcome.ROW_CHANGED, 409},
                {ArchiveRuleResetService.Outcome.PROVIDER_ERROR, 502},
                {ArchiveRuleResetService.Outcome.AUDIT_UNAVAILABLE, 503}}) {
            when(resets.reset(eq("rx"), any())).thenReturn(
                    resetResult((ArchiveRuleResetService.Outcome) c[0], ArchiveRuleEntity.STATUS_EXECUTING));
            mvc.perform(post("/api/v1/archival/rules/rx/reset").with(csrf())).andExpect(status().is((Integer) c[1]))
                    .andExpect(jsonPath("$.outcome").value(((ArchiveRuleResetService.Outcome) c[0]).name()));
        }
        when(resets.reset(eq("none"), any())).thenReturn(
                new ArchiveRuleResetService.Result(ArchiveRuleResetService.Outcome.NOT_FOUND, null, "no archive rule"));
        mvc.perform(post("/api/v1/archival/rules/none/reset").with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void q669_resetIsAdminOnly() throws Exception {
        for (String role : List.of("ROLE_POLICY_MANAGER", "ROLE_DIRECTOR", "ROLE_DATA_ENGINEER", "ROLE_AUDITOR",
                "ROLE_SERVICE_ACCOUNT", "ROLE_DATA_ANALYST")) {
            mvc.perform(post("/api/v1/archival/rules/r1/reset").with(csrf())
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                    .user("u").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(role))))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v1/archival/rules/r1/reset").with(csrf())).andExpect(status().isUnauthorized());
        verify(resets, never()).reset(any(), any());
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void q673_aDuplicateScopeDryRunIsAConflict() throws Exception {
        when(resolver.resolve(ASSET)).thenReturn(Optional.of(new AssetLocation("GCP", "gs://pilot/finance/")));
        when(planner.plan(anyString(), anyString(), any(), any(), any(), any()))
                .thenThrow(new RuleException(RuleException.Reason.DUPLICATE_SCOPE, "archive rule x already holds it"));

        mvc.perform(post(PLAN_URL).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(ASSET_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("DUPLICATE_SCOPE"));
    }
}
