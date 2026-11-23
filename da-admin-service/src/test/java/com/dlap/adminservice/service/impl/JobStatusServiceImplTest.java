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

package com.dlap.adminservice.service.impl;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.dlap.adminservice.client.IAutoarchivalTaskApiClient;
import com.dlap.adminservice.client.IAutocomplianceJobApiClient;
import com.dlap.adminservice.client.IAutodeleteTaskApiClient;
import com.dlap.adminservice.client.IAutolabelJobApiClient;
import com.dlap.adminservice.client.IDiscoveryJobApiClient;
import com.dlap.adminservice.client.ResilientJobClients;
import com.dlap.adminservice.client.dto.FeignTaskListResponses.ArchivalTaskListResponse;
import com.dlap.adminservice.client.dto.FeignTaskListResponses.DeletionTaskListResponse;
import com.dlap.adminservice.client.dto.FeignTaskListResponses.LabelingJobListResponse;
import com.dlap.adminservice.client.dto.FeignTaskListResponses.LabelingJobStatus;
import com.dlap.adminservice.dto.AggregatedJobStatusDTO;
import com.dlap.adminservice.dto.JobStatusDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class JobStatusServiceImplTest {

    @Mock
    private IDiscoveryJobApiClient discoveryJobApiClient;
    @Mock
    private IAutolabelJobApiClient autolabelJobApiClient;
    @Mock
    private IAutoarchivalTaskApiClient autoarchivalTaskApiClient;
    @Mock
    private IAutodeleteTaskApiClient autodeleteTaskApiClient;
    @Mock
    private IAutocomplianceJobApiClient autocomplianceJobApiClient;

    private JobStatusServiceImpl jobStatusService;

    private JobStatusDTO sampleJob1, sampleJob2;

    @BeforeEach
    void setUp() {
        ResilientJobClients jobClients = new ResilientJobClients(
                discoveryJobApiClient,
                autolabelJobApiClient,
                autoarchivalTaskApiClient,
                autodeleteTaskApiClient,
                autocomplianceJobApiClient);
        jobStatusService = new JobStatusServiceImpl(jobClients);

        sampleJob1 = JobStatusDTO.builder()
                .jobId("job1")
                .serviceName("TestService1")
                .status("COMPLETED_SUCCESS")
                .submittedAt(LocalDateTime.now().minusHours(2))
                .completedAt(LocalDateTime.now().minusHours(1))
                .build();

        sampleJob2 = JobStatusDTO.builder()
                .jobId("job2")
                .serviceName("TestService2")
                .status("PENDING")
                .submittedAt(LocalDateTime.now().minusMinutes(30))
                .build();
    }

    @Test
    void getAggregatedJobStatuses_shouldAggregateFromAllClients() {
        LabelingJobStatus labelingJob2 = new LabelingJobStatus();
        labelingJob2.setJobId(sampleJob2.getJobId());
        labelingJob2.setStatus(sampleJob2.getStatus());
        labelingJob2.setSubmittedAt(sampleJob2.getSubmittedAt());

        when(discoveryJobApiClient.getDiscoveryJobs()).thenReturn(List.of(sampleJob1));
        when(autolabelJobApiClient.getAutolabelJobs(anyInt())).thenReturn(
                LabelingJobListResponse.builder().jobs(List.of(labelingJob2)).build());
        when(autoarchivalTaskApiClient.getArchivalTasks(anyInt())).thenReturn(new ArchivalTaskListResponse());
        when(autodeleteTaskApiClient.getDeletionTasks(anyInt())).thenReturn(new DeletionTaskListResponse());
        when(autocomplianceJobApiClient.getComplianceReportJobs()).thenReturn(Collections.emptyList());

        AggregatedJobStatusDTO result = jobStatusService.getAggregatedJobStatuses();

        assertNotNull(result);
        assertEquals(2, result.getTotalJobs());
        assertEquals(1, result.getCompletedSuccessJobs());
        assertEquals(1, result.getPendingJobs());
        assertEquals(0, result.getRunningJobs());
        assertEquals(0, result.getCompletedFailedJobs());
        assertNotNull(result.getJobs());
        assertEquals(2, result.getJobs().size());
        assertNotNull(result.getServiceErrors());
        assertTrue(result.getServiceErrors().isEmpty());
    }

    @Test
    void getAggregatedJobStatuses_whenClientThrowsException_shouldHandleGracefully() {
        LabelingJobStatus labelingJob2 = new LabelingJobStatus();
        labelingJob2.setJobId(sampleJob2.getJobId());
        labelingJob2.setStatus(sampleJob2.getStatus());
        labelingJob2.setSubmittedAt(sampleJob2.getSubmittedAt());

        when(discoveryJobApiClient.getDiscoveryJobs()).thenThrow(new RuntimeException("Discovery Unreachable"));
        when(autolabelJobApiClient.getAutolabelJobs(anyInt())).thenReturn(
                LabelingJobListResponse.builder().jobs(List.of(labelingJob2)).build());
        when(autoarchivalTaskApiClient.getArchivalTasks(anyInt())).thenReturn(new ArchivalTaskListResponse());
        when(autodeleteTaskApiClient.getDeletionTasks(anyInt())).thenReturn(new DeletionTaskListResponse());
        when(autocomplianceJobApiClient.getComplianceReportJobs()).thenReturn(Collections.emptyList());

        AggregatedJobStatusDTO result = jobStatusService.getAggregatedJobStatuses();

        assertNotNull(result);
        assertEquals(1, result.getTotalJobs()); // Only sampleJob2 from autolabel
        assertEquals(0, result.getCompletedSuccessJobs());
        assertEquals(1, result.getPendingJobs());
        assertNotNull(result.getJobs());
        assertEquals(1, result.getJobs().size());
        assertEquals("job2", result.getJobs().get(0).getJobId());
        assertNotNull(result.getServiceErrors());
        assertEquals(1, result.getServiceErrors().size());
        assertEquals("Discovery", result.getServiceErrors().get(0).getServiceName());
    }

    @Test
    void getAggregatedJobStatuses_whenAllClientsReturnEmpty_shouldReturnEmptyAggregation() {
        when(discoveryJobApiClient.getDiscoveryJobs()).thenReturn(Collections.emptyList());
        when(autolabelJobApiClient.getAutolabelJobs(anyInt())).thenReturn(
                LabelingJobListResponse.builder().jobs(Collections.emptyList()).build());
        when(autoarchivalTaskApiClient.getArchivalTasks(anyInt())).thenReturn(new ArchivalTaskListResponse());
        when(autodeleteTaskApiClient.getDeletionTasks(anyInt())).thenReturn(new DeletionTaskListResponse());
        when(autocomplianceJobApiClient.getComplianceReportJobs()).thenReturn(Collections.emptyList());

        AggregatedJobStatusDTO result = jobStatusService.getAggregatedJobStatuses();

        assertNotNull(result);
        assertEquals(0, result.getTotalJobs());
        assertEquals(0, result.getPendingJobs());
        assertEquals(0, result.getCompletedSuccessJobs());
        assertNotNull(result.getJobs());
        assertEquals(0, result.getJobs().size());
        assertNotNull(result.getServiceErrors());
        assertTrue(result.getServiceErrors().isEmpty());
    }
} 