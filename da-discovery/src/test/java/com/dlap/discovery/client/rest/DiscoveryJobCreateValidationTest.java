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
package com.dlap.discovery.client.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.catalog.persistence.IResourceCrawlerRegistry;
import com.dlap.discovery.catalog.service.ICatalogService;
import com.dlap.discovery.client.admin.IAdminServiceClient;
import com.dlap.discovery.client.rest.dto.CreateJobRequest;
import com.dlap.discovery.common.model.CloudProviderHandler;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.common.model.repository.CloudResourceRepository;
import com.dlap.discovery.crawler.service.IFolderManagementService;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.job.JobType;
import com.dlap.discovery.job.service.IDiscoveryJobService;
import com.dlap.discovery.mapper.DiscoveryScanApiMapper;

/**
 * SEC-03 (Q-027): the body of the legacy {@code POST /api/v1/discovery/{provider}/jobs} is validated
 * before the job service is reached.
 */
@WebMvcTest(
    controllers = DiscoveryJobController.class,
    excludeAutoConfiguration = { OAuth2ClientAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class }
)
@ActiveProfiles("test")
class DiscoveryJobCreateValidationTest {

    @SpringBootConfiguration
    @Import({ TestWebSecurityConfiguration.class, DiscoveryJobController.class })
    static class TestApp {}

    private static final String PATH = "/api/v1/discovery/gcp/jobs";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IDiscoveryJobService jobService;

    @MockitoBean
    private DiscoveryScanApiMapper scanMapper;

    @MockitoBean
    private IResourceCrawlerRegistry crawlerRegistry;

    @MockitoBean
    private ICatalogService catalogService;

    @MockitoBean
    private CloudHierarchyRegistry cloudHierarchyRegistry;

    @MockitoBean
    private IFolderManagementService folderManagementService;

    @MockitoBean
    private CloudProviderHandler cloudProviderHandler;

    @MockitoBean
    private CloudResourceRepository resourceRepository;

    @MockitoBean
    private IAdminServiceClient adminServiceClient;

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void aWellFormedRequestCreatesTheJob() throws Exception {
        DiscoveryJob job = new DiscoveryJob();
        job.setJobId(UUID.randomUUID());
        job.setStatus(JobStatus.PENDING);
        job.setParameters(new HashMap<>());
        when(jobService.createJob(eq(JobType.RESOURCE_CRAWLER), eq("acct-1"), eq(CloudProvider.GCP), eq("nightly")))
            .thenReturn(job);
        when(jobService.saveJob(any(DiscoveryJob.class))).thenReturn(job);

        mockMvc
            .perform(
                post(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"accountId\":\"acct-1\",\"jobName\":\"nightly\",\"resourceTypeIds\":[\"gcs\"],\"parameters\":{\"k\":\"v\"}}")
            )
            .andExpect(status().isCreated());

        verify(jobService).saveJob(any(DiscoveryJob.class));
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void aMissingOrBlankAccountIdIsABadRequest() throws Exception {
        for (String body : new String[] { "{\"jobName\":\"j\"}", "{\"accountId\":\"\"}", "{\"accountId\":\"  \"}" }) {
            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }

        verifyNoInteractions(jobService);
    }

    @Test
    @WithMockUser(authorities = "ROLE_ADMIN")
    void oversizedFieldsAreABadRequest() throws Exception {
        String longText = "x".repeat(CreateJobRequest.MAX_TEXT_LENGTH + 1);
        String longValue = "v".repeat(CreateJobRequest.MAX_PARAMETER_VALUE_LENGTH + 1);
        String tooMany = "\"t\",".repeat(CreateJobRequest.MAX_ENTRIES) + "\"t\"";
        for (String body : new String[] {
            "{\"accountId\":\"" + longText + "\"}",
            "{\"accountId\":\"a\",\"jobName\":\"" + longText + "\"}",
            "{\"accountId\":\"a\",\"parameters\":{\"k\":\"" + longValue + "\"}}",
            "{\"accountId\":\"a\",\"resourceTypeIds\":[" + tooMany + "]}",
            "{\"accountId\":\"a\",\"resourceTypeIds\":[\"\"]}",
        }) {
            mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }

        verifyNoInteractions(jobService);
    }
}
