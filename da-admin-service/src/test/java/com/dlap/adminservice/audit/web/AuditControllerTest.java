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

package com.dlap.adminservice.audit.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dlap.adminservice.audit.AuditTestKeys;
import com.dlap.adminservice.audit.AuditVerificationService;
import com.dlap.adminservice.audit.CheckpointKeys;
import com.dlap.adminservice.audit.CheckpointService;
import com.dlap.adminservice.audit.CheckpointStore;
import com.dlap.adminservice.audit.StoredCheckpoint;
import com.dlap.adminservice.config.TestSecurityConfiguration;
import com.dlap.common.audit.AuditEntry;
import com.dlap.common.audit.AuditException;
import com.dlap.common.audit.AuditLogReader;
import com.dlap.common.audit.AuditStream;
import com.dlap.common.audit.ChainVerification;
import com.dlap.common.audit.CheckpointSignatures;

/** The audit API: roles per endpoint, response shapes and error statuses. */
@WebMvcTest(AuditController.class)
@Import(TestSecurityConfiguration.class)
class AuditControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuditLogReader reader;

    @MockitoBean
    private CheckpointStore checkpoints;

    @MockitoBean
    private CheckpointKeys keys;

    @MockitoBean
    private AuditVerificationService verification;

    @MockitoBean
    private CheckpointService checkpointService;

    private final KeyPair pair = AuditTestKeys.newPair();

    @BeforeEach
    void streams() {
        given(reader.streams()).willReturn(List.of(new AuditStream("retire.delete", "da_autodelete_user", "Delete path")));
    }

    @Test
    void unauthenticatedCallsAreRefused() throws Exception {
        mvc.perform(get("/api/v1/audit/streams")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void readersOutsideTheAuditRolesAreForbidden() throws Exception {
        mvc.perform(get("/api/v1/audit/streams")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit/streams/retire.delete/verification")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit/keys")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "AUDITOR")
    void anAuditorListsStreamsWithHeadsAndCheckpoints() throws Exception {
        given(reader.head("retire.delete")).willReturn(Optional.of(new AuditEntry("retire.delete", 7, "a".repeat(64),
                "b".repeat(64), "{}")));
        given(checkpoints.latest("retire.delete")).willReturn(Optional.of(new StoredCheckpoint("retire.delete", 6,
                "c".repeat(64), "0123456789abcdef", Instant.parse("2026-10-21T12:00:00Z"), "{}", "sig", "s3", "s3://b/k",
                Instant.parse("2026-10-21T12:00:01Z"), 1, null)));
        mvc.perform(get("/api/v1/audit/streams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].streamId").value("retire.delete"))
                .andExpect(jsonPath("$[0].headSeq").value(7))
                .andExpect(jsonPath("$[0].headHash").value("b".repeat(64)))
                .andExpect(jsonPath("$[0].latestCheckpointSeq").value(6))
                .andExpect(jsonPath("$[0].latestCheckpointExported").value(true));
    }

    @Test
    @WithMockUser(roles = "CDO")
    void entriesArePagedAndBoundsAreChecked() throws Exception {
        given(reader.page("retire.delete", 5, 2)).willReturn(List.of(new AuditEntry("retire.delete", 6, "a".repeat(64),
                "b".repeat(64), "{\"x\":1}")));
        mvc.perform(get("/api/v1/audit/streams/retire.delete/entries").param("afterSeq", "5").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].seq").value(6))
                .andExpect(jsonPath("$[0].record").value("{\"x\":1}"));
        mvc.perform(get("/api/v1/audit/streams/retire.delete/entries").param("limit", "501"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/audit/streams/Not-A-Stream/entries")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/audit/streams/retire.unknown/entries")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/audit/streams/retire.delete/checkpoints").param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "AUDITOR")
    void verificationReportsTheFirstProblem() throws Exception {
        given(verification.verify("retire.delete")).willReturn(Optional.of(new AuditVerificationService.Report(
                "retire.delete", AuditVerificationService.Status.BROKEN, Instant.parse("2026-10-21T12:00:00Z"), 2, 2,
                "d".repeat(64), 3L, ChainVerification.Problem.ENTRY_HASH_MISMATCH, "entry_hash does not match", 1, 0,
                List.of(), null, false, 2)));
        mvc.perform(get("/api/v1/audit/streams/retire.delete/verification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BROKEN"))
                .andExpect(jsonPath("$.firstBadSeq").value(3))
                .andExpect(jsonPath("$.chainProblem").value("ENTRY_HASH_MISMATCH"));
        given(verification.verify("retire.archive")).willReturn(Optional.empty());
        mvc.perform(get("/api/v1/audit/streams/retire.archive/verification")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void keysAndCheckpointsAreListed() throws Exception {
        String keyId = CheckpointSignatures.keyId(pair.getPublic());
        given(keys.trusted()).willReturn(Map.of(keyId, pair.getPublic()));
        given(keys.signing()).willReturn(Optional.of(new CheckpointKeys.SigningKey(pair.getPrivate(), pair.getPublic(), keyId)));
        mvc.perform(get("/api/v1/audit/keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].keyId").value(keyId))
                .andExpect(jsonPath("$[0].signing").value(true))
                .andExpect(jsonPath("$[0].publicKeyPem").value(CheckpointSignatures.toPem(pair.getPublic())));
        given(checkpoints.recent("retire.delete", 50)).willReturn(List.of());
        mvc.perform(get("/api/v1/audit/streams/retire.delete/checkpoints")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "AUDITOR")
    void onlyAnAdminRunsTheCheckpointJob() throws Exception {
        mvc.perform(post("/api/v1/audit/checkpoints")).andExpect(status().isForbidden());
        verify(checkpointService, never()).run();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void anAdminRunsTheJobWhenItIsConfigured() throws Exception {
        given(checkpointService.isConfigured()).willReturn(false);
        mvc.perform(post("/api/v1/audit/checkpoints")).andExpect(status().isConflict());
        given(checkpointService.isConfigured()).willReturn(true);
        given(checkpointService.run()).willReturn(new CheckpointService.CheckpointRun(Instant.parse("2026-10-21T12:00:00Z"),
                null, List.of(new CheckpointService.StreamResult("retire.delete", CheckpointService.StreamStatus.SIGNED, 7L, null)),
                1, 0, 0, null));
        mvc.perform(post("/api/v1/audit/checkpoints"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.streams[0].status").value("SIGNED"))
                .andExpect(jsonPath("$.exported").value(1));
    }

    @Test
    @WithMockUser(roles = "AUDITOR")
    void anUnreachableAuditDatabaseIs503WithoutTheDriverMessage() throws Exception {
        given(reader.streams()).willThrow(new AuditException("reading the audit streams failed: password authentication failed", null));
        mvc.perform(get("/api/v1/audit/streams"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("The audit database (dlap_common) is not reachable"));
    }
}
