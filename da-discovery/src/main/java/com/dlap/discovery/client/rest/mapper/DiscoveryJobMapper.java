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

package com.dlap.discovery.client.rest.mapper;

import com.dlap.discovery.client.rest.dto.DiscoveryScanJobResponse;
import com.dlap.discovery.client.rest.dto.DiscoveryScanRequest; // For Scope and ScanType enums
import com.dlap.discovery.common.model.DiscoveryJob;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class DiscoveryJobMapper {

    public DiscoveryScanJobResponse toDiscoveryScanJobResponse(DiscoveryJob job) {
        if (job == null) {
            return null;
        }

        DiscoveryScanJobResponse response = new DiscoveryScanJobResponse();
        response.setScanId(job.getJobId().toString());
        response.setScanName(job.getJobName());
        response.setStatus(job.getStatus());
        response.setSubmittedAt(job.getCreatedAt());

        // Extract from parameters map
        if (job.getParameters() != null) {
            Map<String, Object> params = job.getParameters();
            response.setCloudConnectionId((String) params.get("cloudConnectionId"));
            String scanTypeName = (String) params.get("scanType");
            if (scanTypeName != null) {
                try {
                    response.setScanType(DiscoveryScanRequest.ScanType.valueOf(scanTypeName));
                } catch (IllegalArgumentException e) {
                    // Log error or handle: unknown scan type string in parameters
                }
            }
            // TODO: Reconstruct the full 'scope' object from parameters.
            // This is complex and requires careful mapping back to the DiscoveryScanRequest.Scope structure.
            // For now, leaving it null or providing a simplified representation if possible.
            // response.setScope(reconstructScopeFromParams(params));
        }

        // TODO: Populate startedAt, completedAt, durationMs, progress, summary from DiscoveryJobExecution
        // This would require fetching the latest or relevant job execution(s).
        // For example, if job has a getLatestExecution() method:
        // DiscoveryJobExecution latestExecution = job.getLatestExecution();
        // if (latestExecution != null) {
        //     response.setStartedAt(latestExecution.getStartedAt());
        //     response.setCompletedAt(latestExecution.getCompletedAt());
        //     if (latestExecution.getStartedAt() != null && latestExecution.getCompletedAt() != null) {
        //         response.setDurationMs(latestExecution.getCompletedAt().toEpochMilli() - latestExecution.getStartedAt().toEpochMilli());
        //     }
        //     // Populate progress and summary from execution details...
        // }

        // Placeholder for resultLocation
        response.setResultLocation(String.format("/api/v1/discovery/scans/%s/results", job.getJobId()));

        return response;
    }

    public List<DiscoveryScanJobResponse> toDiscoveryScanJobResponseList(List<DiscoveryJob> jobs) {
        if (jobs == null) {
            return List.of();
        }
        return jobs.stream().map(this::toDiscoveryScanJobResponse).collect(Collectors.toList());
    }

    // Placeholder for scope reconstruction - this would be quite involved.
    private DiscoveryScanRequest.Scope reconstructScopeFromParams(Map<String, Object> params) {
        // This method would need to look for keys like "gcpProjectIds", "awsAccountIds", etc.,
        // and build the nested DiscoveryScanRequest.Scope object.
        // This is a significant piece of logic.
        // Example for GCP part (simplified):
        // DiscoveryScanRequest.Scope scope = new DiscoveryScanRequest.Scope();
        // if (params.containsKey("gcpProjectIds")) {
        //     DiscoveryScanRequest.GcpScope gcpScope = new DiscoveryScanRequest.GcpScope();
        //     gcpScope.setProjectIds((List<String>) params.get("gcpProjectIds"));
        //     gcpScope.setIncludeServices((List<String>) params.get("gcpIncludeServices"));
        //     // ... map other gcp fields ...
        //     scope.setGcp(gcpScope);
        // }
        // ... similarly for AWS, Azure, OCI ...
        return null; // Returning null for now to indicate it's not implemented
    }
}
