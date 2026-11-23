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

package com.dlap.logingest.quarantine;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlap.logingest.config.SecurityConfiguration;
import com.dlap.logingest.quarantine.QuarantineReplayService.ReplayRequest;
import com.dlap.logingest.quarantine.QuarantineReplayService.ReplayResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The quarantine API slice: the roles, the response shapes and the error statuses. The replay itself
 * is covered against a real database in {@code QuarantineReplayIntegrationTest}.
 */
@WebMvcTest(QuarantineController.class)
@Import(SecurityConfiguration.class)
class QuarantineControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private QuarantineRecordRepository repository;

    @MockitoBean
    private QuarantineReplayService replay;

    private static QuarantineRow row(String id) {
        return new QuarantineRow(id, "dlap-rest-aws", "AWS", "DLAP_NORMALIZED_AWS", "ingest-dto", "v1",
                "", "dlap-normalized@1.0.0", "MISSING_REQUIRED_FIELD", "resultStatus is missing",
                Map.of("transport", "REST", "uri", "rest:/x", "recordIndex", -1, "cursor", ""),
                "0".repeat(64), 10, "{}", false, null, "", "", T0, null, 0);
    }

    @Test
    void anonymousIsUnauthorizedOnEveryHandler() throws Exception {
        mvc.perform(get("/api/v1/logingest/quarantine")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/logingest/quarantine/abc")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/logingest/quarantine/replay").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"CDO", "DIRECTOR", "POLICY_MANAGER", "DATA_ENGINEER", "DATA_SCIENTIST", "DATA_ANALYST", "AUDITOR", "VIEWER", "SERVICE_ACCOUNT"})
    void everyRoleButAdminIsForbiddenOnEveryHandler() throws Exception {
        mvc.perform(get("/api/v1/logingest/quarantine")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/logingest/quarantine/abc")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/logingest/quarantine/replay").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isForbidden());
        verify(replay, never()).replay(any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theListIsAPageWithReplayabilityOfEachRow() throws Exception {
        given(repository.find(any(), eq(10), eq(20L))).willReturn(List.of(row("q1")));
        given(repository.count(any())).willReturn(21L);

        mvc.perform(get("/api/v1/logingest/quarantine").param("page", "2").param("size", "10")
                        .param("reason", "MISSING_REQUIRED_FIELD").param("bindingId", "dlap-rest-aws")
                        .param("replayed", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(21))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.items[0].quarantineId").value("q1"))
                .andExpect(jsonPath("$.items[0].reason").value("MISSING_REQUIRED_FIELD"))
                .andExpect(jsonPath("$.items[0].replayable").value(false))
                .andExpect(jsonPath("$.items[0].notReplayableReason").value("ALTERED"));

        ArgumentCaptor<QuarantineRecordRepository.Filter> filter = ArgumentCaptor.forClass(
                QuarantineRecordRepository.Filter.class);
        verify(repository).find(filter.capture(), eq(10), eq(20L));
        org.assertj.core.api.Assertions.assertThat(filter.getValue().reason()).isEqualTo("MISSING_REQUIRED_FIELD");
        org.assertj.core.api.Assertions.assertThat(filter.getValue().bindingId()).isEqualTo("dlap-rest-aws");
        org.assertj.core.api.Assertions.assertThat(filter.getValue().replayed()).isFalse();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aBadReasonOrPageSizeIsABadRequest() throws Exception {
        mvc.perform(get("/api/v1/logingest/quarantine").param("reason", "NOPE")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/logingest/quarantine").param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/logingest/quarantine").param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/logingest/quarantine").param("page", "-1")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void oneRecordByIdOr404() throws Exception {
        given(repository.findById("q1")).willReturn(Optional.of(row("q1")));
        given(repository.findById("missing")).willReturn(Optional.empty());

        mvc.perform(get("/api/v1/logingest/quarantine/q1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rawExcerpt").value("{}"));
        mvc.perform(get("/api/v1/logingest/quarantine/missing")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "ops-admin", roles = "ADMIN")
    void anAdminReplaysWithAFilterAndGetsTheCounts() throws Exception {
        given(replay.replay(any(), eq("ops-admin"))).willReturn(new ReplayResult(false, 3, 2, 1, 1, 1, 0, 0, 0,
                List.of("q1")));

        mvc.perform(post("/api/v1/logingest/quarantine/replay").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bindingId\":\"dlap-rest-aws\",\"reason\":\"UNKNOWN_MAJOR_VERSION\","
                                + "\"detectedVersion\":\"2.0\",\"limit\":50,\"dryRun\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected").value(3))
                .andExpect(jsonPath("$.resolved").value(1))
                .andExpect(jsonPath("$.stillQuarantined").value(1))
                .andExpect(jsonPath("$.notReplayableTruncated").value(1))
                .andExpect(jsonPath("$.resolvedIds[0]").value("q1"));

        ArgumentCaptor<ReplayRequest> request = ArgumentCaptor.forClass(ReplayRequest.class);
        verify(replay).replay(request.capture(), eq("ops-admin"));
        org.assertj.core.api.Assertions.assertThat(request.getValue().bindingId()).isEqualTo("dlap-rest-aws");
        org.assertj.core.api.Assertions.assertThat(request.getValue().reason()).isEqualTo("UNKNOWN_MAJOR_VERSION");
        org.assertj.core.api.Assertions.assertThat(request.getValue().limit()).isEqualTo(50);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aReplayWithNoBodyReplaysEverythingPending() throws Exception {
        given(replay.replay(any(), any())).willReturn(new ReplayResult(false, 0, 0, 0, 0, 0, 0, 0, 0, List.of()));

        mvc.perform(post("/api/v1/logingest/quarantine/replay").with(csrf())).andExpect(status().isOk());

        ArgumentCaptor<ReplayRequest> request = ArgumentCaptor.forClass(ReplayRequest.class);
        verify(replay).replay(request.capture(), any());
        org.assertj.core.api.Assertions.assertThat(request.getValue().bindingId()).isNull();
        org.assertj.core.api.Assertions.assertThat(request.getValue().dryRun()).isFalse();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theLegacyPipelineAndABusyReplayAreConflicts() throws Exception {
        given(replay.replay(any(), any())).willThrow(new QuarantineReplayService.ReplayUnavailableException())
                .willThrow(new QuarantineReplayService.ReplayBusyException());

        mvc.perform(post("/api/v1/logingest/quarantine/replay").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("quarantine replay needs dlap.logingest.pipeline=registry"));
        mvc.perform(post("/api/v1/logingest/quarantine/replay").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("another quarantine replay is running"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void anInvalidReplayRequestIsABadRequest() throws Exception {
        given(replay.replay(any(), any())).willThrow(new IllegalArgumentException("unknown quarantine reason: X"));

        mvc.perform(post("/api/v1/logingest/quarantine/replay").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"X\"}"))
                .andExpect(status().isBadRequest());
    }
}
