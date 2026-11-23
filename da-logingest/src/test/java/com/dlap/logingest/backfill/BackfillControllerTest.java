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

package com.dlap.logingest.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlap.logingest.config.SecurityConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The backfill API slice: ADMIN only on every handler, the response shapes and the error statuses. */
@WebMvcTest(BackfillController.class)
@Import(SecurityConfiguration.class)
class BackfillControllerTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");
    private static final String BODY = "{\"bindingId\":\"gcp-p1\",\"streamId\":\"logging-api\","
            + "\"from\":\"2026-10-01T00:00:00Z\",\"to\":\"2026-10-02T00:00:00Z\"}";
    private static final String BASE = "/api/v1/logingest/backfills";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private BackfillService service;

    private static BackfillJob job(BackfillStatus status) {
        return new BackfillJob(UUID.fromString("11111111-2222-3333-4444-555555555555"), "gcp-p1", "logging-api", T0,
                T0.plusSeconds(86400), status, BackfillProgress.EMPTY.afterBatch(3, 1, 0, T0), "ops-admin", T0, null,
                null, "");
    }

    @Test
    void anonymousIsUnauthorizedOnEveryHandler() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/" + UUID.randomUUID() + "/cancel").with(csrf())).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(roles = {"CDO", "DIRECTOR", "POLICY_MANAGER", "DATA_ENGINEER", "DATA_SCIENTIST", "DATA_ANALYST",
        "AUDITOR", "VIEWER", "SERVICE_ACCOUNT"})
    void everyRoleButAdminIsForbiddenOnEveryHandler() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/" + UUID.randomUUID())).andExpect(status().isForbidden());
        mvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/" + UUID.randomUUID() + "/cancel").with(csrf())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    @WithMockUser(username = "ops-admin", roles = "ADMIN")
    void anAdminStartsAJobAndGetsItsLocation() throws Exception {
        given(service.create(any(), eq("ops-admin"))).willReturn(job(BackfillStatus.PENDING));

        mvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", BASE + "/11111111-2222-3333-4444-555555555555"))
                .andExpect(jsonPath("$.id").value("11111111-2222-3333-4444-555555555555"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.bindingId").value("gcp-p1"))
                .andExpect(jsonPath("$.progress.records").value(3))
                .andExpect(jsonPath("$.fractionComplete").value(0.0));

        ArgumentCaptor<BackfillService.CreateRequest> request = ArgumentCaptor.forClass(
                BackfillService.CreateRequest.class);
        verify(service).create(request.capture(), eq("ops-admin"));
        assertThat(request.getValue()).isEqualTo(new BackfillService.CreateRequest("gcp-p1", "logging-api", T0,
                T0.plusSeconds(86400)));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void refusalsMapToTheirStatuses() throws Exception {
        given(service.create(any(), any()))
                .willThrow(new IllegalArgumentException("from must be before to"))
                .willThrow(new BackfillService.BackfillNotFoundException("no binding gcp-p1"))
                .willThrow(new BackfillService.BackfillConflictException("binding gcp-p1 already has an active job"))
                .willThrow(new BackfillService.BackfillUnavailableException());

        mvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("no binding gcp-p1"));
        mvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("binding gcp-p1 already has an active job"));
        mvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("backfill needs dlap.logingest.pipeline=registry"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aBodyThatIsNotJsonOrHoldsABadInstantIsABadRequest() throws Exception {
        mvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{nope"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bindingId\":\"b\",\"from\":\"yesterday\",\"to\":\"today\"}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).create(any(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void theListIsAPageWithFilters() throws Exception {
        given(service.list("gcp-p1", BackfillStatus.RUNNING, 2, 10)).willReturn(List.of(job(BackfillStatus.RUNNING)));
        given(service.count("gcp-p1", BackfillStatus.RUNNING)).willReturn(21L);

        mvc.perform(get(BASE).param("bindingId", "gcp-p1").param("status", "running").param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(21))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.items[0].status").value("RUNNING"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aBadPageSizeOrStatusOrIdIsABadRequest() throws Exception {
        mvc.perform(get(BASE).param("size", "101")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("size", "0")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("page", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("status", "DONE")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/not-a-uuid/cancel").with(csrf())).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void oneJobByIdOr404() throws Exception {
        UUID known = UUID.fromString("11111111-2222-3333-4444-555555555555");
        given(service.get(known)).willReturn(job(BackfillStatus.SUCCEEDED));
        UUID unknown = UUID.fromString("99999999-2222-3333-4444-555555555555");
        given(service.get(unknown)).willThrow(new BackfillService.BackfillNotFoundException("no backfill job"));

        mvc.perform(get(BASE + "/" + known)).andExpect(status().isOk())
                .andExpect(jsonPath("$.fractionComplete").value(1.0));
        mvc.perform(get(BASE + "/" + unknown)).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "ops-admin", roles = "ADMIN")
    void cancelReturnsTheJobAndRefusesAnEndedOne() throws Exception {
        UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
        given(service.cancel(id, "ops-admin")).willReturn(job(BackfillStatus.CANCELLED))
                .willThrow(new BackfillService.BackfillConflictException("job already ended as CANCELLED"));

        mvc.perform(post(BASE + "/" + id + "/cancel").with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post(BASE + "/" + id + "/cancel").with(csrf())).andExpect(status().isConflict());
    }
}
