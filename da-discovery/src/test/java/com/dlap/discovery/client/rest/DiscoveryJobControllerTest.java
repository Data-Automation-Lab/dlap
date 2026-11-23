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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlap.common.constants.DlapConstants;
import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.catalog.persistence.IResourceCrawlerRegistry;
import com.dlap.discovery.catalog.service.ICatalogService;
import com.dlap.discovery.client.admin.IAdminServiceClient;
import com.dlap.discovery.client.rest.dto.DiscoveryScanDetail;
import com.dlap.discovery.client.rest.dto.DiscoveryScanSummary;
import com.dlap.discovery.common.model.CloudProviderHandler;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.common.model.repository.CloudResourceRepository;
import com.dlap.discovery.crawler.service.IFolderManagementService;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.job.JobType;
import com.dlap.discovery.job.service.IDiscoveryJobService;
import com.dlap.discovery.mapper.DiscoveryScanApiMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Query endpoints of {@link DiscoveryJobController} ({@code GET /scans}, {@code GET /scans/{id}})
 * and the role boundaries of the scan and legacy job endpoints, on a web slice with method
 * security switched on. The happy path and the failure mapping of {@code POST /scans} are in
 * {@code DiscoveryJobControllerConnectionsTest}.
 *
 * <p>This replaces a full-application test that only ran when a dev stack was reachable and used
 * the JHipster roles {@code USER} and {@code DATA_STEWARD}, which DLAP does not have.
 */
@WebMvcTest(
    controllers = DiscoveryJobController.class,
    excludeAutoConfiguration = { OAuth2ClientAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class }
)
@ActiveProfiles("test")
class DiscoveryJobControllerTest {

    @SpringBootConfiguration
    @Import({ TestWebSecurityConfiguration.class, DiscoveryJobController.class })
    static class TestApp {}

    private static final String SCAN_BODY = "{\"cloudConnectionId\":\"conn-1\",\"scanType\":\"FULL\",\"scope\":{}}";

    private static final String ADMIN = "ROLE_" + DlapConstants.UserRole.ADMIN;
    private static final String POLICY_MANAGER = "ROLE_" + DlapConstants.UserRole.POLICY_MANAGER;
    private static final String DATA_ANALYST = "ROLE_" + DlapConstants.UserRole.DATA_ANALYST;
    private static final String VIEWER = "ROLE_" + DlapConstants.UserRole.VIEWER;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IDiscoveryJobService jobService;

    @MockitoBean
    private DiscoveryScanApiMapper scanMapper;

    // The controller's other collaborators; none is used by the endpoints under test.
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

    private DiscoveryJob sampleJob;

    @BeforeEach
    void setUp() {
        sampleJob = new DiscoveryJob();
        sampleJob.setJobId(UUID.randomUUID());
        sampleJob.setJobName("Test Scan Job");
        sampleJob.setStatus(JobStatus.PENDING);
        sampleJob.setCloudProvider(CloudProvider.GCP);
        sampleJob.setAccountId("test-account");
        sampleJob.setJobType(JobType.RESOURCE_CRAWLER);
        sampleJob.setParameters(new HashMap<>());
    }

    @Test
    @WithMockUser(authorities = DATA_ANALYST)
    void getAllDiscoveryScans_AsDataAnalyst_ShouldReturnAPageOfSummaries() throws Exception {
        DiscoveryScanSummary summary = DiscoveryScanSummary
            .builder()
            .scanId(sampleJob.getJobId().toString())
            .scanName(sampleJob.getJobName())
            .scanType(JobType.RESOURCE_CRAWLER.name())
            .status(JobStatus.PENDING.name())
            .build();
        when(jobService.getAllJobs()).thenReturn(List.of(sampleJob));
        when(scanMapper.toDiscoveryScanSummary(eq(sampleJob), anyString())).thenReturn(summary);
        // A page with a real Pageable: an unpaged PageImpl throws when Jackson reads its paging fields.
        when(scanMapper.createPage(anyList(), any(Pageable.class), anyLong()))
            .thenAnswer(invocation ->
                new PageImpl<>(
                    invocation.<List<DiscoveryScanSummary>>getArgument(0),
                    invocation.<Pageable>getArgument(1),
                    invocation.<Long>getArgument(2)
                )
            );

        mockMvc
            .perform(get("/api/v1/discovery/scans").param("page", "0").param("size", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].scanId").value(sampleJob.getJobId().toString()))
            .andExpect(jsonPath("$.content[0].status").value("PENDING"));
    }

    @Test
    @WithMockUser(authorities = DATA_ANALYST)
    void getDiscoveryScanById_AsDataAnalyst_ShouldReturnTheDetail() throws Exception {
        DiscoveryScanDetail detail = DiscoveryScanDetail
            .builder()
            .scanId(sampleJob.getJobId().toString())
            .scanName(sampleJob.getJobName())
            .status(sampleJob.getStatus().name())
            .build();
        when(jobService.getJob(sampleJob.getJobId())).thenReturn(Optional.of(sampleJob));
        when(scanMapper.toDiscoveryScanDetail(eq(sampleJob), anyString())).thenReturn(detail);

        mockMvc
            .perform(get("/api/v1/discovery/scans/{scanId}", sampleJob.getJobId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scanId").value(sampleJob.getJobId().toString()));
    }

    @Test
    @WithMockUser(authorities = DATA_ANALYST)
    void getDiscoveryScanById_WhenUnknown_ShouldBeNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(jobService.getJob(unknown)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/discovery/scans/{scanId}", unknown)).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(authorities = VIEWER)
    void getAllDiscoveryScans_AsViewer_ShouldBeForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/discovery/scans")).andExpect(status().isForbidden());

        verifyNoInteractions(jobService);
    }

    @Test
    @WithMockUser(authorities = DATA_ANALYST)
    void triggerScan_AsDataAnalyst_ShouldBeForbidden() throws Exception {
        // A reader may look at scans but not start one: that needs ADMIN, POLICY_MANAGER or DIRECTOR.
        mockMvc
            .perform(post("/api/v1/discovery/scans").contentType(MediaType.APPLICATION_JSON).content(SCAN_BODY))
            .andExpect(status().isForbidden());

        verifyNoInteractions(jobService, adminServiceClient);
    }

    @Test
    @WithMockUser(authorities = "ROLE_DATA_STEWARD")
    void triggerScan_WithTheRetiredDataStewardRole_ShouldBeForbidden() throws Exception {
        // ROLE_DATA_STEWARD came from the JHipster scaffold and is not a DLAP role. Pin that it
        // grants nothing, so a client that still sends it fails visibly.
        mockMvc
            .perform(post("/api/v1/discovery/scans").contentType(MediaType.APPLICATION_JSON).content(SCAN_BODY))
            .andExpect(status().isForbidden());

        verifyNoInteractions(jobService, adminServiceClient);
    }

    @Test
    @WithMockUser(authorities = POLICY_MANAGER)
    void createLegacyJob_AsPolicyManager_ShouldBeForbidden() throws Exception {
        // The legacy per-provider job endpoint is ADMIN only.
        mockMvc
            .perform(
                post("/api/v1/discovery/gcp/jobs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{ \"accountId\": \"test\", \"jobName\": \"legacy\" }")
            )
            .andExpect(status().isForbidden());

        verifyNoInteractions(jobService);
    }

    @Test
    @WithMockUser(authorities = ADMIN)
    void getJobStatuses_AsAdmin_ShouldPassTheRoleRule() throws Exception {
        // Not an authorization failure: the role rule lets an ADMIN through to the handler.
        mockMvc.perform(get("/api/v1/discovery/job-statuses")).andExpect(status().is2xxSuccessful());
    }
}
