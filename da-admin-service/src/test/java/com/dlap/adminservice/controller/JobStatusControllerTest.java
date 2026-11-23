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

package com.dlap.adminservice.controller;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.dlap.adminservice.config.TestSecurityConfiguration;
import com.dlap.adminservice.dto.AggregatedJobStatusDTO;
import com.dlap.adminservice.service.IJobStatusService;
import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(JobStatusController.class)
@Import(TestSecurityConfiguration.class)
class JobStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IJobStatusService jobStatusService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithMockUser(roles = {"ADMIN", "VIEWER"})
    void getAggregatedJobStatuses_shouldReturnAggregatedStatuses() throws Exception {
        AggregatedJobStatusDTO aggregatedStatus = AggregatedJobStatusDTO.builder()
                .jobs(Collections.emptyList())
                .totalJobs(0)
                .build();

        given(jobStatusService.getAggregatedJobStatuses()).willReturn(aggregatedStatus);

        mockMvc.perform(get("/api/v1/admin/job-statuses")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalJobs").value(0));
    }

    @Test
    @WithMockUser(roles = "USER") // A user without ADMIN or VIEWER role
    void getAggregatedJobStatuses_whenUnauthorized_shouldReturnForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/job-statuses")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
} 