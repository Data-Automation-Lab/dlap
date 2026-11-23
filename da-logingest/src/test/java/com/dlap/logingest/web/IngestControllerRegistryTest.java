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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlap.logingest.config.SecurityConfiguration;
import com.dlap.logingest.config.sources.BuiltinBindings;
import com.dlap.logingest.dto.IngestRequestDTO;
import com.dlap.logingest.service.ILogIngestService;
import com.dlap.logingest.service.registry.Disposition;
import com.dlap.logingest.service.registry.RecordReport;
import com.dlap.logingest.service.registry.RegistryIngestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /api/v1/logingest/events} on the registry pipeline: the request goes through the
 * registry on the built-in REST channel (never the legacy service), a first delivery answers
 * {@code 202 PUBLISHED}, a duplicate {@code 202 DUPLICATE}, a quarantined record {@code 422} with
 * its quarantine id. The handler's {@code @PreAuthorize} is the same as on the legacy path.
 */
@WebMvcTest(IngestController.class)
@Import(SecurityConfiguration.class)
class IngestControllerRegistryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ILogIngestService legacy;

    @MockitoBean
    private RegistryIngestService registry;

    private String body() throws Exception {
        return objectMapper.writeValueAsString(IngestRequestDTO.builder()
                .sourceProvider("AWS").operation("CreateBucket").changeType("CREATE").resultStatus("SUCCESS")
                .eventTime(Instant.parse("2026-06-13T12:00:00Z")).targetResourceId("s3://dlap-demo").build());
    }

    private void answer(RecordReport r) {
        given(registry.ingestDto(any(IngestRequestDTO.class), eq(BuiltinBindings.Channel.REST), anyString(), anyLong(),
                eq(false))).willReturn(r);
    }

    @Test
    @WithMockUser(roles = "DATA_ENGINEER")
    void aFirstDeliveryIsPublished() throws Exception {
        answer(new RecordReport("PARSED_WITH_WARNINGS",
                List.of(new RecordReport.EventReport("id-1", Disposition.PUBLISHED)), Optional.empty()));

        mockMvc.perform(post("/api/v1/logingest/events").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value("id-1"))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.outcome").value("PARSED_WITH_WARNINGS"))
                .andExpect(jsonPath("$.topic").value("dlap.log.event.normalized"));
        verify(legacy, never()).ingest(any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aDuplicateIsReportedAndNotRepublished() throws Exception {
        answer(new RecordReport("PARSED_WITH_WARNINGS",
                List.of(new RecordReport.EventReport("id-1", Disposition.DUPLICATE)), Optional.empty()));

        mockMvc.perform(post("/api/v1/logingest/events").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aQuarantinedRecordIsUnprocessable() throws Exception {
        answer(new RecordReport("QUARANTINED:MISSING_REQUIRED_FIELD",
                List.of(new RecordReport.EventReport("pass-1", Disposition.PUBLISHED)), Optional.of("q-1")));

        mockMvc.perform(post("/api/v1/logingest/events").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("QUARANTINED"))
                .andExpect(jsonPath("$.quarantineId").value("q-1"))
                .andExpect(jsonPath("$.outcome").value("QUARANTINED:MISSING_REQUIRED_FIELD"));
    }

    @Test
    @WithMockUser(roles = "DATA_ANALYST")
    void theSameRolesAreRequired() throws Exception {
        mockMvc.perform(post("/api/v1/logingest/events").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isForbidden());
        verify(registry, never()).ingestDto(any(), any(), anyString(), anyLong(), eq(false));
    }
}
