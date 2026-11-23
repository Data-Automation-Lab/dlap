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

package com.dlap.logingest.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlap.logingest.config.SecurityConfiguration;
import com.dlap.logingest.dto.IngestRequestDTO;
import com.dlap.logingest.service.ILogIngestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code @WebMvcTest} slice for {@link IngestController}. Verifies the
 * documented happy path (202 ACCEPTED with the assigned event id) and that
 * the {@code @PreAuthorize} on the ingest endpoint is genuinely enforced:
 * anonymous requests are rejected, an unrelated role is forbidden, and an
 * authorized role succeeds.
 */
@WebMvcTest(IngestController.class)
@Import(SecurityConfiguration.class)
class IngestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ILogIngestService logIngestService;

    private IngestRequestDTO request;

    @BeforeEach
    void setUp() {
        request = IngestRequestDTO.builder()
                .sourceProvider("AWS")
                .operation("CreateBucket")
                .changeType("CREATE")
                .eventTime(Instant.parse("2026-06-13T12:00:00Z"))
                .targetResourceId("dlap-demo")
                .build();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void ingest_withAdminRole_returnsAccepted() throws Exception {
        given(logIngestService.ingest(any(IngestRequestDTO.class)))
                .willReturn("abc123eventid");

        mockMvc.perform(post("/api/v1/logingest/events").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value("abc123eventid"))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.topic").value("dlap.log.event.normalized"));
    }

    @Test
    @WithMockUser(roles = "DATA_ENGINEER")
    void ingest_withDataEngineerRole_returnsAccepted() throws Exception {
        // Cloud subscribers run as service accounts mapped to ROLE_DATA_ENGINEER.
        given(logIngestService.ingest(any(IngestRequestDTO.class)))
                .willReturn("svc-event-id");

        mockMvc.perform(post("/api/v1/logingest/events").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value("svc-event-id"));
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void ingest_withUnauthorizedRole_returnsForbidden() throws Exception {
        // DATA_ANALYST is authenticated but not in the endpoint's @PreAuthorize
        // list (ROLE_ADMIN / ROLE_DATA_ENGINEER). The service is stubbed so that
        // if @PreAuthorize were NOT enforced the call would surface as a 202,
        // making any security regression visible rather than masked.
        given(logIngestService.ingest(any(IngestRequestDTO.class))).willReturn("should-not-run");
        mockMvc.perform(post("/api/v1/logingest/events").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void ingest_whenAnonymous_returnsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/logingest/events").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void ingest_withInvalidBody_returnsBadRequest() throws Exception {
        // Missing @NotBlank sourceProvider / operation / changeType and
        // @NotNull eventTime should trip bean validation before the handler.
        IngestRequestDTO invalid = IngestRequestDTO.builder().build();

        mockMvc.perform(post("/api/v1/logingest/events").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }
}
