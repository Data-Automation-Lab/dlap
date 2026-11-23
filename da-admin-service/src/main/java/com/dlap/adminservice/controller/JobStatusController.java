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

import com.dlap.adminservice.dto.AggregatedJobStatusDTO;
import com.dlap.adminservice.service.IJobStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/job-statuses")
@RequiredArgsConstructor
@Tag(name = "Job Status Management", description = "APIs for viewing aggregated job statuses across services.")
public class JobStatusController {

    private final IJobStatusService jobStatusService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'VIEWER')") // Or more specific roles as needed
    @Operation(summary = "Get Aggregated Job Statuses",
               description = "Retrieves a list of all jobs from various microservices and their statuses.",
               responses = {
                   @ApiResponse(responseCode = "200", description = "Successfully retrieved job statuses"),
                   @ApiResponse(responseCode = "500", description = "Internal server error while fetching statuses")
               })
    public ResponseEntity<AggregatedJobStatusDTO> getAggregatedJobStatuses() {
        AggregatedJobStatusDTO aggregatedStatus = jobStatusService.getAggregatedJobStatuses();
        return ResponseEntity.ok(aggregatedStatus);
    }
} 