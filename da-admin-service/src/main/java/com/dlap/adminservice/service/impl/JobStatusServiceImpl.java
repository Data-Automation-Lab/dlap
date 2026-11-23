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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.dlap.adminservice.client.IAutoarchivalTaskApiClient;
import com.dlap.adminservice.client.IAutocomplianceJobApiClient;
import com.dlap.adminservice.client.IAutodeleteTaskApiClient;
import com.dlap.adminservice.client.IAutolabelJobApiClient;
import com.dlap.adminservice.client.IDiscoveryJobApiClient;
import com.dlap.adminservice.client.ResilientJobClients;
import com.dlap.adminservice.client.dto.FeignTaskListResponses.ArchivalTaskListResponse;
import com.dlap.adminservice.client.dto.FeignTaskListResponses.ArchivalTaskStatus;
import com.dlap.adminservice.client.dto.FeignTaskListResponses.DeletionTaskListResponse;
import com.dlap.adminservice.client.dto.FeignTaskListResponses.DeletionTaskStatus;
import com.dlap.adminservice.client.dto.FeignTaskListResponses.LabelingJobListResponse;
import com.dlap.adminservice.client.dto.FeignTaskListResponses.LabelingJobStatus;
import com.dlap.adminservice.dto.AggregatedJobStatusDTO;
import com.dlap.adminservice.dto.JobStatusDTO;
import com.dlap.adminservice.dto.ServiceAggregationErrorDTO;
import com.dlap.adminservice.service.IJobStatusService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service implementation for aggregating job statuses from all DLAP microservices
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobStatusServiceImpl implements IJobStatusService {

    private final ResilientJobClients jobClients;

    @Override
    public AggregatedJobStatusDTO getAggregatedJobStatuses() {
        log.debug("Aggregating job statuses from all DLAP services");

        List<JobStatusDTO> allJobs = new ArrayList<>();
        List<ServiceAggregationErrorDTO> errors = new ArrayList<>();

        aggregateFromService("Discovery", jobClients::discoveryJobs, allJobs, errors);
        aggregateFromService("Autolabel", () -> mapLabelingJobs(jobClients.autolabelJobs(100)), allJobs, errors);
        aggregateFromService("Autoarchival", () -> mapArchivalTasks(jobClients.archivalTasks(100)), allJobs, errors);
        aggregateFromService("Autodelete", () -> mapDeletionTasks(jobClients.deletionTasks(100)), allJobs, errors);
        aggregateFromService("Autocompliance", jobClients::complianceJobs, allJobs, errors);

        return buildAggregatedResult(allJobs, errors);
    }

    private void aggregateFromService(
            String serviceName,
            ServiceJobProvider provider,
            List<JobStatusDTO> allJobs,
            List<ServiceAggregationErrorDTO> errors) {
        try {
            List<JobStatusDTO> serviceJobs = provider.getJobs();
            if (serviceJobs != null) {
                allJobs.addAll(serviceJobs);
                log.debug("Aggregated {} jobs from {} service", serviceJobs.size(), serviceName);
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve jobs from {} service: {}", serviceName, e.getMessage());
            errors.add(ServiceAggregationErrorDTO.builder()
                    .serviceName(serviceName)
                    .errorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())
                    .build());
        }
    }

    private AggregatedJobStatusDTO buildAggregatedResult(List<JobStatusDTO> allJobs, List<ServiceAggregationErrorDTO> errors) {
        int totalJobs = allJobs.size();
        int pendingJobs = 0;
        int runningJobs = 0;
        int completedSuccessJobs = 0;
        int completedFailedJobs = 0;

        for (JobStatusDTO job : allJobs) {
            String status = job.getStatus();
            if (status == null) {
                continue;
            }

            switch (status.toUpperCase()) {
                case "PENDING":
                    pendingJobs++;
                    break;
                case "RUNNING":
                case "IN_PROGRESS":
                    runningJobs++;
                    break;
                case "COMPLETED_SUCCESS":
                case "COMPLETED":
                case "SUCCESS":
                    completedSuccessJobs++;
                    break;
                case "COMPLETED_FAILED":
                case "FAILED":
                case "ERROR":
                    completedFailedJobs++;
                    break;
                default:
                    break;
            }
        }

        return AggregatedJobStatusDTO.builder()
                .totalJobs(totalJobs)
                .pendingJobs(pendingJobs)
                .runningJobs(runningJobs)
                .completedSuccessJobs(completedSuccessJobs)
                .completedFailedJobs(completedFailedJobs)
                .jobs(allJobs)
                .serviceErrors(errors)
                .build();
    }

    @FunctionalInterface
    private interface ServiceJobProvider {
        List<JobStatusDTO> getJobs();
    }

    private static List<JobStatusDTO> mapArchivalTasks(ArchivalTaskListResponse response) {
        if (response == null || response.getTasks() == null) {
            return Collections.emptyList();
        }
        return response.getTasks().stream().map(JobStatusServiceImpl::toJobStatus).collect(Collectors.toList());
    }

    private static JobStatusDTO toJobStatus(ArchivalTaskStatus t) {
        return JobStatusDTO.builder()
                .jobId(t.getTaskId())
                .serviceName("AUTOARCHIVAL")
                .jobType("ARCHIVAL_TASK")
                .status(t.getStatus())
                .submittedAt(t.getSubmittedAt())
                .startedAt(t.getStartedAt())
                .completedAt(t.getCompletedAt())
                .build();
    }

    private static List<JobStatusDTO> mapDeletionTasks(DeletionTaskListResponse response) {
        if (response == null || response.getTasks() == null) {
            return Collections.emptyList();
        }
        return response.getTasks().stream().map(JobStatusServiceImpl::toJobStatus).collect(Collectors.toList());
    }

    private static JobStatusDTO toJobStatus(DeletionTaskStatus t) {
        return JobStatusDTO.builder()
                .jobId(t.getTaskId())
                .serviceName("AUTODELETE")
                .jobType("DELETION_TASK")
                .status(t.getStatus())
                .submittedAt(t.getSubmittedAt())
                .startedAt(t.getStartedAt())
                .completedAt(t.getCompletedAt())
                .build();
    }

    private static List<JobStatusDTO> mapLabelingJobs(LabelingJobListResponse response) {
        if (response == null || response.getJobs() == null) {
            return Collections.emptyList();
        }
        return response.getJobs().stream().map(JobStatusServiceImpl::toJobStatus).collect(Collectors.toList());
    }

    private static JobStatusDTO toJobStatus(LabelingJobStatus j) {
        return JobStatusDTO.builder()
                .jobId(j.getJobId())
                .serviceName("AUTOLABEL")
                .jobType("LABELING_JOB")
                .status(j.getStatus())
                .submittedAt(j.getSubmittedAt())
                .startedAt(j.getStartedAt())
                .completedAt(j.getCompletedAt())
                .build();
    }
}
