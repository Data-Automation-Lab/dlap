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

package com.dlap.adminservice.client;

import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Service;

import com.dlap.adminservice.client.dto.FeignTaskListResponses.ArchivalTaskListResponse;
import com.dlap.adminservice.client.dto.FeignTaskListResponses.DeletionTaskListResponse;
import com.dlap.adminservice.client.dto.FeignTaskListResponses.LabelingJobListResponse;
import com.dlap.adminservice.dto.JobStatusDTO;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ResilientJobClients {

    private final IDiscoveryJobApiClient discovery;
    private final IAutolabelJobApiClient autolabel;
    private final IAutoarchivalTaskApiClient autoarchival;
    private final IAutodeleteTaskApiClient autodelete;
    private final IAutocomplianceJobApiClient autocompliance;

    @CircuitBreaker(name = "da-discovery", fallbackMethod = "fallbackJobs")
    public List<JobStatusDTO> discoveryJobs() {
        return discovery.getDiscoveryJobs();
    }

    @CircuitBreaker(name = "da-autolabel", fallbackMethod = "fallbackLabelingJobs")
    public LabelingJobListResponse autolabelJobs(int limit) {
        return autolabel.getAutolabelJobs(limit);
    }

    @CircuitBreaker(name = "da-autoarchival", fallbackMethod = "fallbackArchivalTasks")
    public ArchivalTaskListResponse archivalTasks(int limit) {
        return autoarchival.getArchivalTasks(limit);
    }

    @CircuitBreaker(name = "da-autodelete", fallbackMethod = "fallbackDeletionTasks")
    public DeletionTaskListResponse deletionTasks(int limit) {
        return autodelete.getDeletionTasks(limit);
    }

    @CircuitBreaker(name = "da-autocompliance", fallbackMethod = "fallbackJobs")
    public List<JobStatusDTO> complianceJobs() {
        return autocompliance.getComplianceReportJobs();
    }

    @SuppressWarnings("unused")
    private List<JobStatusDTO> fallbackJobs(Throwable ex) {
        return Collections.emptyList();
    }

    @SuppressWarnings("unused")
    private LabelingJobListResponse fallbackLabelingJobs(int limit, Throwable ex) {
        return new LabelingJobListResponse();
    }

    @SuppressWarnings("unused")
    private ArchivalTaskListResponse fallbackArchivalTasks(int limit, Throwable ex) {
        return new ArchivalTaskListResponse();
    }

    @SuppressWarnings("unused")
    private DeletionTaskListResponse fallbackDeletionTasks(int limit, Throwable ex) {
        return new DeletionTaskListResponse();
    }
}

