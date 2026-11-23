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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dlap.discovery.client.admin.AdminConnectionDetails;
import com.dlap.discovery.client.admin.AdminConnectionSummary;
import com.dlap.discovery.client.admin.AdminServiceException;
import com.dlap.discovery.client.admin.AdminServiceException.Reason;
import com.dlap.discovery.client.admin.IAdminServiceClient;
import com.dlap.discovery.client.rest.errors.ExceptionTranslator;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.mapper.DiscoveryScanApiMapper;
import com.dlap.discovery.test.util.FakeJobService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * DISC-01: the scan API resolves connections through {@link IAdminServiceClient} and turns every
 * resolution failure into a problem response with an explanation the UI can show, creating no job.
 * Also pins who may list connections and start scans.
 *
 * <p>A standalone MockMvc with the real {@link ExceptionTranslator}, so the JSON the UI parses
 * ({@code status}, {@code detail}) is the production shape. No Spring context, no Docker.
 */
class DiscoveryJobControllerConnectionsTest {

    private static final String BODY = "{\"cloudConnectionId\":\"conn-1\",\"scanType\":\"FULL\",\"scope\":{}}";

    private FakeJobService jobs;
    private IAdminServiceClient admin;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jobs = new FakeJobService();
        admin = new IAdminServiceClient() {
            @Override
            public AdminConnectionDetails getConnectionDetails(String id) {
                return new AdminConnectionDetails("proj-1", CloudProvider.GCP, "Prod GCP");
            }

            @Override
            public List<AdminConnectionSummary> listConnections() {
                return List.of(new AdminConnectionSummary("conn-1", "Prod GCP", "GCP", true, true));
            }
        };
        mvc = mvcFor(admin);
    }

    private MockMvc mvcFor(IAdminServiceClient client) {
        DiscoveryJobController controller = new DiscoveryJobController(jobs, new DiscoveryScanApiMapper(),
                null, null, null, null, List.of(), null, client);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ExceptionTranslator(new MockEnvironment()))
                .build();
    }

    @Test
    void listConnections_returnsTheNonSecretSummaries() throws Exception {
        mvc.perform(get("/api/v1/discovery/connections").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("conn-1"))
                .andExpect(jsonPath("$[0].name").value("Prod GCP"))
                .andExpect(jsonPath("$[0].providerType").value("GCP"))
                .andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[0].scannable").value(true))
                .andExpect(jsonPath("$[0].connectionParameters").doesNotExist());
    }

    @Test
    void listConnections_whenThereAreNone_isAnEmptyArray() throws Exception {
        MockMvc empty = mvcFor(new IAdminServiceClient() {
            @Override
            public AdminConnectionDetails getConnectionDetails(String id) {
                throw new AssertionError("not used");
            }

            @Override
            public List<AdminConnectionSummary> listConnections() {
                return List.of();
            }
        });

        empty.perform(get("/api/v1/discovery/connections").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listConnections_whenTheAdminServiceIsDown_is503WithAnExplanation() throws Exception {
        MockMvc down = mvcFor(new IAdminServiceClient() {
            @Override
            public AdminConnectionDetails getConnectionDetails(String id) {
                throw new AssertionError("not used");
            }

            @Override
            public List<AdminConnectionSummary> listConnections() {
                throw new AdminServiceException(Reason.UNAVAILABLE, "da-admin-service is unreachable");
            }
        });

        down.perform(get("/api/v1/discovery/connections").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("da-admin-service is unreachable"));
    }

    @Test
    void triggerScan_resolvesTheConnectionAndCreatesAJobForItsAccount() throws Exception {
        mvc.perform(post("/api/v1/discovery/scans").accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.scanId").isNotEmpty())
                .andExpect(jsonPath("$.status").isNotEmpty());

        assertThat(jobs.createCount.get()).isEqualTo(1);
        var job = jobs.created.get(0);
        assertThat(job.getAccountId()).isEqualTo("proj-1");
        assertThat(job.getCloudProvider()).isEqualTo(CloudProvider.GCP);
        assertThat(job.getJobName()).isEqualTo("Unnamed Scan from Prod GCP");
        assertThat(job.getParameters()).containsEntry("originalScanRequest_cloudConnectionId", "conn-1");
        assertThat(jobs.executed).as("the default crawler executable was attached and the job submitted")
                .isNotNull();
        assertThat(jobs.executed.getExecutable()).isNotNull();
    }

    @ParameterizedTest
    @CsvSource({
        "NOT_FOUND, 404",
        "DISABLED, 409",
        "UNSUPPORTED_PROVIDER, 422",
        "REJECTED, 502",
        "MISCONFIGURED, 502",
        "UNAVAILABLE, 503"
    })
    void triggerScan_mapsEveryResolutionFailureToAProblem_andCreatesNoJob(Reason reason, int expected)
            throws Exception {
        MockMvc failing = mvcFor(id -> {
            throw new AdminServiceException(reason, "explanation for " + reason);
        });

        failing.perform(post("/api/v1/discovery/scans").accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is(expected))
                .andExpect(jsonPath("$.status").value(expected))
                .andExpect(jsonPath("$.detail").value("explanation for " + reason));

        assertThat(jobs.createCount.get()).isZero();
    }

    @Test
    void triggerScan_whenTheJobCannotBeStarted_isAcceptedButReportedFailed() throws Exception {
        jobs.executeFailure = new IllegalStateException("No executor registered for mode: DEFAULT");

        mvc.perform(post("/api/v1/discovery/scans").accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value(JobStatus.FAILED.name()));

        assertThat(jobs.created.get(0).getStatus()).isEqualTo(JobStatus.FAILED);
    }

    @Test
    void triggerScan_withoutAConnectionId_is400_andDoesNotCallTheAdminService() throws Exception {
        mvc.perform(post("/api/v1/discovery/scans").accept(MediaType.APPLICATION_JSON).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scanType\":\"FULL\",\"scope\":{}}"))
                .andExpect(status().isBadRequest());

        assertThat(jobs.createCount.get()).isZero();
    }

    @Test
    void connectionListingAndScanStart_areRestrictedToTheSameNamedRoles() throws Exception {
        String expected = "hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')";
        Method list = DiscoveryJobController.class.getMethod("listConnections");
        Method trigger = DiscoveryJobController.class.getMethod("triggerScan",
                com.dlap.discovery.client.rest.dto.DiscoveryScanRequest.class,
                org.springframework.web.util.UriComponentsBuilder.class);

        assertThat(list.getAnnotation(PreAuthorize.class)).isNotNull();
        assertThat(list.getAnnotation(PreAuthorize.class).value()).isEqualTo(expected);
        assertThat(trigger.getAnnotation(PreAuthorize.class).value()).isEqualTo(expected);
    }
}
