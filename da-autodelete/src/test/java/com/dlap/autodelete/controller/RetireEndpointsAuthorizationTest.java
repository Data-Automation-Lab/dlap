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

package com.dlap.autodelete.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlap.autodelete.config.TestSecurityConfiguration;
import com.dlap.autodelete.dto.WouldDeleteRowDTO;
import com.dlap.autodelete.retire.WouldDeleteService;
import com.dlap.autodelete.dto.DeletionTaskListResponse;
import com.dlap.autodelete.service.IDeletionService;
import com.dlap.autodelete.service.IDeletionTaskService;
import com.dlap.autodelete.service.RestoreOutcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * RET-02 endpoints with method security on: the would-delete list answers only the roles named in
 * its {@code @PreAuthorize}, and a restore is recorded under the caller's JWT subject, never a body
 * value.
 */
@WebMvcTest(controllers = {WouldDeleteController.class, RestoreController.class, DeletionTaskController.class})
@Import(TestSecurityConfiguration.class)
class RetireEndpointsAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WouldDeleteService wouldDelete;

    @MockitoBean
    private IDeletionService deletionService;

    @MockitoBean
    private IDeletionTaskService deletionTaskService;

    private static WouldDeleteRowDTO row() {
        return new WouldDeleteRowDTO(UUID.randomUUID(), UUID.randomUUID(), "gs://dlap-gcp-e2e", "GCP", "GCS_BUCKET",
                "SOFT_DELETED", "POLICY", "p1", Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-12-01T00:00:00Z"), 30, UUID.randomUUID(), null, "FULL", "NATIVE_RULE",
                "GCS_LIFECYCLE", "dlap-retire-x", "{}", Instant.parse("2026-12-02T00:00:00Z"), null, true, null);
    }

    @Test
    void anAuditorReadsTheWouldDeleteList() throws Exception {
        when(wouldDelete.list(anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of(row()), PageRequest.of(0, 50), 1));

        mockMvc.perform(get("/api/v1/autodelete/would-delete")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_AUDITOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].plannedPlatform").value("GCS_LIFECYCLE"))
                .andExpect(jsonPath("$.content[0].dryRun").value(true))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void aDataEngineerOrAServiceAccountMayNotReadIt() throws Exception {
        mockMvc.perform(get("/api/v1/autodelete/would-delete")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DATA_ENGINEER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/autodelete/would-delete")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SERVICE_ACCOUNT"))))
                .andExpect(status().isForbidden());
        verify(wouldDelete, never()).list(anyInt(), anyInt());
    }

    /**
     * Q-624: everyone who can restore (ADMIN, POLICY_MANAGER, DIRECTOR) can also list the soft-delete records to
     * find what to restore; the other roles of the list stay as they were.
     */
    @Test
    void everyRoleThatCanRestoreCanAlsoListWhatToRestore() throws Exception {
        when(deletionTaskService.listTasks(any())).thenReturn(DeletionTaskListResponse.fromPage(
                new PageImpl<>(List.of(), PageRequest.of(0, 20), 0)));
        for (String role : List.of("ROLE_ADMIN", "ROLE_POLICY_MANAGER", "ROLE_DIRECTOR", "ROLE_CDO",
                "ROLE_DATA_ENGINEER", "ROLE_SERVICE_ACCOUNT")) {
            mockMvc.perform(get("/api/v1/deletion/tasks").with(jwt().authorities(new SimpleGrantedAuthority(role))))
                    .andExpect(status().isOk());
        }
        for (String role : List.of("ROLE_AUDITOR", "ROLE_DATA_SCIENTIST", "ROLE_DATA_ANALYST", "ROLE_VIEWER")) {
            mockMvc.perform(get("/api/v1/deletion/tasks").with(jwt().authorities(new SimpleGrantedAuthority(role))))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void aRestoreIsRecordedUnderTheCallersSubjectNotTheBodysUser() throws Exception {
        UUID record = UUID.randomUUID();
        when(deletionService.restore(eq(record), eq("sub-of-caller"), any()))
                .thenReturn(RestoreOutcome.done(false, "NOT_ATTEMPTED"));

        mockMvc.perform(post("/api/v1/autodelete/restores/{id}", record)
                        .with(jwt().jwt(j -> j.subject("sub-of-caller"))
                                .authorities(new SimpleGrantedAuthority("ROLE_POLICY_MANAGER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + UUID.randomUUID() + "\",\"notes\":\"wrong asset\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESTORED"));

        verify(deletionService).restore(eq(record), eq("sub-of-caller"), eq("wrong asset"));
    }

    /**
     * Q-676 (RET-06): the caller's token is not passed on; the service withdraws the pending approval with its own
     * service-account token on behalf of the caller's subject.
     */
    @Test
    void theCallersTokenIsNotPassedOnOnlyTheSubject() throws Exception {
        UUID record = UUID.randomUUID();
        when(deletionService.restore(any(), any(), any()))
                .thenReturn(RestoreOutcome.done(false, "WITHDRAWN"));

        mockMvc.perform(post("/api/v1/autodelete/restores/{id}", record)
                        .with(jwt().jwt(j -> j.subject("sub").tokenValue("the-bearer"))
                                .authorities(new SimpleGrantedAuthority("ROLE_DIRECTOR"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalWithdrawal").value("WITHDRAWN"))
                .andExpect(jsonPath("$.ruleRemoved").value(false));

        verify(deletionService).restore(record, "sub", null);
    }

    /** Q-682 (RET-06): a restore whose rule was not found answers 409, RESTORE_FAILED and ruleRemoved false. */
    @Test
    void aRestoreWhoseRuleWasNotFoundIsRestoreFailedWithRuleRemovedFalse() throws Exception {
        UUID record = UUID.randomUUID();
        when(deletionService.restore(any(), any(), any())).thenReturn(RestoreOutcome.ruleNotFound("rule-not-found"));

        mockMvc.perform(post("/api/v1/autodelete/restores/{id}", record)
                        .with(jwt().jwt(j -> j.subject("sub")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("RESTORE_FAILED"))
                .andExpect(jsonPath("$.ruleRemoved").value(false))
                .andExpect(jsonPath("$.reason", org.hamcrest.Matchers.startsWith("rule-not-found")));
    }

    /** Q-631: the answer to a restore of a rule in force says the rule was removed and expired objects are gone. */
    @Test
    void restoringARuleInForceSaysTheRuleWasRemovedAndExpiredObjectsAreGone() throws Exception {
        UUID record = UUID.randomUUID();
        when(deletionService.restore(any(), any(), any())).thenReturn(RestoreOutcome.done(true, "NOT_ATTEMPTED"));

        mockMvc.perform(post("/api/v1/autodelete/restores/{id}", record)
                        .with(jwt().jwt(j -> j.subject("sub")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESTORED"))
                .andExpect(jsonPath("$.ruleRemoved").value(true))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("already expired are gone")));
    }

    @Test
    void aRestoreOfAnAppliedDeleteIsAConflictAndAnAuditorMayNotRestore() throws Exception {
        UUID record = UUID.randomUUID();
        when(deletionService.restore(any(), any(), any()))
                .thenReturn(RestoreOutcome.notRestored("record-is-HARD_DELETED", "Already applied."));

        mockMvc.perform(post("/api/v1/autodelete/restores/{id}", record)
                        .with(jwt().jwt(j -> j.subject("sub")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("NOT_RESTORABLE"))
                .andExpect(jsonPath("$.reason", org.hamcrest.Matchers.startsWith("record-is-HARD_DELETED")));
        mockMvc.perform(post("/api/v1/autodelete/restores/{id}", record)
                        .with(jwt().jwt(j -> j.subject("sub")).authorities(new SimpleGrantedAuthority("ROLE_AUDITOR"))))
                .andExpect(status().isForbidden());
    }
}
