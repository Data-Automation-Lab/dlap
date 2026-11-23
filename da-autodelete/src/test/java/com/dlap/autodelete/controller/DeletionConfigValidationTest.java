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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dlap.autodelete.dto.DeletionConfigDTO;
import com.dlap.autodelete.service.IDeletionConfigService;

/**
 * SEC-03 (Q-027): the body of {@code PUT /api/v1/deletion/config} is validated before the
 * configuration service is reached. A hard-delete switch that arrives as {@code null} must not be
 * guessed at.
 */
@WebMvcTest(DeletionConfigController.class)
class DeletionConfigValidationTest {

    private static final String PATH = "/api/v1/deletion/config";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IDeletionConfigService deletionConfigService;

    @Test
    @WithMockUser(roles = "ADMIN")
    void aCompleteConfigurationIsAccepted() throws Exception {
        String body = "{\"enabled\":true,\"softDeleteByDefault\":true,\"defaultSoftDeleteRetentionDays\":30,"
            + "\"requireApprovalForHardDelete\":true,\"requireApprovalForSoftDelete\":false,"
            + "\"notificationEmailsOnError\":[\"ops@example.test\"]}";

        mockMvc.perform(put(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());

        verify(deletionConfigService, times(1)).updateDeletionConfig(any(DeletionConfigDTO.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void aBadValueIsABadRequestAndTheServiceIsNotReached() throws Exception {
        String tooMany = "\"a@example.test\",".repeat(DeletionConfigDTO.MAX_NOTIFICATION_EMAILS) + "\"a@example.test\"";
        for (String body : new String[] {
            "{}",
            "{\"enabled\":null}",
            "{\"enabled\":true,\"defaultSoftDeleteRetentionDays\":-1}",
            "{\"enabled\":true,\"defaultSoftDeleteRetentionDays\":" + (DeletionConfigDTO.MAX_RETENTION_DAYS + 1) + "}",
            "{\"enabled\":true,\"notificationEmailsOnError\":[\"not an address\"]}",
            "{\"enabled\":true,\"notificationEmailsOnError\":[\"\"]}",
            "{\"enabled\":true,\"notificationEmailsOnError\":[" + tooMany + "]}",
        }) {
            mockMvc
                .perform(put(PATH).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(deletionConfigService);
    }
}
