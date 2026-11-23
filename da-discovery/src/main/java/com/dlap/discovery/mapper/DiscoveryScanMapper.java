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

package com.dlap.discovery.mapper;

import com.dlap.discovery.client.rest.dto.DiscoveryScanDetail;
import com.dlap.discovery.client.rest.dto.DiscoveryScanSummary;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.job.JobType;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Mapper for converting DiscoveryJob entities to API DTOs (DiscoveryScanDetail, DiscoveryScanSummary).
 */
@Component
public class DiscoveryScanMapper {

    public DiscoveryScanDetail toDiscoveryScanDetail(DiscoveryJob job, String triggeredBy) {
        if (job == null) {
            return null;
        }

        DiscoveryScanDetail.ScanScope scope = buildScanScope(job);
        DiscoveryScanDetail.ScanConfiguration configuration = buildScanConfiguration(job);
        DiscoveryScanDetail.ScanSummaryResults summaryResults = buildScanSummaryResults(job);

        Long durationMs = null;
        if (job.getStartedAt().isPresent() && job.getCompletedAt().isPresent()) {
            durationMs = Duration.between(job.getStartedAt().get(), job.getCompletedAt().get()).toMillis();
        }

        return DiscoveryScanDetail
            .builder()
            .scanId(job.getJobId().toString())
            .scanName(job.getJobName())
            .scanType(job.getJobType() != null ? job.getJobType().name() : null) // Example, refine based on DiscoveryScanRequest
            .status(job.getStatus() != null ? job.getStatus().name() : null)
            .submittedAt(job.getCreatedAt())
            .startedAt(job.getStartedAt().orElse(null))
            .completedAt(job.getCompletedAt().orElse(null))
            .durationMs(durationMs)
            .scope(scope)
            .configuration(configuration)
            .summaryResults(summaryResults)
            .triggeredBy(triggeredBy) // Placeholder, actual user/system
            .build();
    }

    public DiscoveryScanSummary toDiscoveryScanSummary(DiscoveryJob job, String triggeredBy) {
        if (job == null) {
            return null;
        }

        DiscoveryJob.JobStatistics stats = job.getStatistics() != null
            ? job.getStatistics()
            : new DiscoveryJob.JobStatistics.Builder().build();
        long addedOrUpdated = stats.getResourcesAdded() + stats.getResourcesUpdated();

        return DiscoveryScanSummary
            .builder()
            .scanId(job.getJobId().toString())
            .scanName(job.getJobName())
            .scanType(job.getJobType() != null ? job.getJobType().name() : null)
            .status(job.getStatus() != null ? job.getStatus().name() : null)
            .submittedAt(job.getCreatedAt())
            .completedAt(job.getCompletedAt().orElse(null))
            .triggeredBy(triggeredBy) // Placeholder
            .assetsScanned((long) stats.getResourcesDiscovered())
            .assetsAddedOrUpdated(addedOrUpdated)
            .build();
    }

    private DiscoveryScanDetail.ScanScope buildScanScope(DiscoveryJob job) {
        // This is a simplified mapping. Actual mapping needs to consider how
        // DiscoveryScanRequest DTO fields were stored in DiscoveryJob.parameters.
        DiscoveryScanDetail.ScanScope.ScanScopeBuilder scopeBuilder = DiscoveryScanDetail.ScanScope.builder();

        Map<String, Object> params = job.getParameters();
        if (params == null) params = Collections.emptyMap();

        // Infer scope type from JobType or specific parameters
        // This part needs to be very robust based on how POST /discovery/scans stores info
        // For example, if DiscoveryScanRequest.scope.awsAccountId was stored as "awsAccountId" in parameters
        scopeBuilder.type(determineScanScopeType(job.getJobType(), job.getCloudProvider(), params));

        if (job.getCloudProvider() != null) {
            switch (job.getCloudProvider()) {
                case AWS:
                    scopeBuilder.awsAccountId(job.getAccountId()); // This is the primary accountId for the job
                    // Extract regions, resourceTypes from params if stored there
                    // e.g., scopeBuilder.awsRegions((List<String>) params.get("awsRegions"));
                    break;
                case GCP:
                    // GCP might have projectId in accountId or in params if multiple projects
                    scopeBuilder.gcpProjectId(job.getAccountId()); // If single project scan
                    // e.g. scopeBuilder.gcpProjectIds((List<String>) params.get("gcpProjectIds"));
                    // e.g. scopeBuilder.gcpOrganizationId((String) params.get("gcpOrganizationId"));
                    break;
                case AZURE:
                    scopeBuilder.azureSubscriptionId(job.getAccountId());
                    // e.g. scopeBuilder.azureResourceGroup((String) params.get("azureResourceGroup"));
                    break;
                case OCI:
                    scopeBuilder.ociTenancyId(job.getAccountId());
                    // e.g. scopeBuilder.ociCompartmentId((String) params.get("ociCompartmentId"));
                    break;
                // Other providers
            }
        }
        // scopeBuilder.includePatterns((List<String>) params.getOrDefault("includePatterns", Collections.emptyList()));
        // scopeBuilder.excludePatterns((List<String>) params.getOrDefault("excludePatterns", Collections.emptyList()));
        // scopeBuilder.tags((Map<String, String>) params.getOrDefault("tags", Collections.emptyMap()));

        return scopeBuilder.build();
    }

    private String determineScanScopeType(JobType jobType, CloudProvider cloudProvider, Map<String, Object> params) {
        // Logic to determine the high-level scope type string based on jobType, provider, and params
        // Example:
        if (cloudProvider == CloudProvider.AWS) return "AWS_ACCOUNT";
        if (cloudProvider == CloudProvider.GCP) {
            if (params.containsKey("gcpOrganizationId")) return "GCP_ORGANIZATION";
            if (params.containsKey("gcpProjectIds")) return "GCP_PROJECTS";
            return "GCP_PROJECT";
        }
        // ... and so on for Azure, OCI
        return jobType != null ? jobType.name() : "UNKNOWN"; // Fallback
    }

    private DiscoveryScanDetail.ScanConfiguration buildScanConfiguration(DiscoveryJob job) {
        DiscoveryScanDetail.ScanConfiguration.ScanConfigurationBuilder configBuilder = DiscoveryScanDetail.ScanConfiguration.builder();
        configBuilder.mode(job.isPeriodicJob() ? "SCHEDULED" : "MANUAL");
        if (job.isPeriodicJob()) {
            job.getScheduleInfo().ifPresent(configBuilder::schedule);
        }
        // Extract overrides, maxDepth, incremental from job.getParameters() if they were stored there
        // Map<String, Object> params = job.getParameters();
        // configBuilder.overrides((Map<String, Object>) params.getOrDefault("configOverrides", Collections.emptyMap()));
        // configBuilder.maxDepth((Integer) params.get("maxDepth"));
        // configBuilder.incremental((Boolean) params.get("incrementalScan"));
        return configBuilder.build();
    }

    private DiscoveryScanDetail.ScanSummaryResults buildScanSummaryResults(DiscoveryJob job) {
        DiscoveryJob.JobStatistics stats = job.getStatistics() != null
            ? job.getStatistics()
            : new DiscoveryJob.JobStatistics.Builder().build();
        DiscoveryScanDetail.ScanSummaryResults.ScanSummaryResultsBuilder summaryBuilder = DiscoveryScanDetail.ScanSummaryResults.builder();

        summaryBuilder.assetsScanned((long) stats.getResourcesDiscovered());
        summaryBuilder.assetsAdded((long) stats.getResourcesAdded());
        summaryBuilder.assetsUpdated((long) stats.getResourcesUpdated());
        summaryBuilder.errorsEncountered((long) stats.getResourcesFailed());
        // assetsMatched and assetsUnchanged are not directly available in JobStatistics, may need to be derived or added to JobStatistics.
        summaryBuilder.assetsMatched(0L); // Placeholder
        summaryBuilder.assetsUnchanged(0L); // Placeholder

        if (job.getErrorMessage().isPresent()) {
            summaryBuilder.errorMessages(Collections.singletonList(job.getErrorMessage().get()));
        } else {
            summaryBuilder.errorMessages(Collections.emptyList());
        }
        return summaryBuilder.build();
    }

    public List<DiscoveryScanSummary> toDiscoveryScanSummaryList(List<DiscoveryJob> jobs, String triggeredBy) {
        if (jobs == null) {
            return Collections.emptyList();
        }
        return jobs.stream().map(job -> toDiscoveryScanSummary(job, triggeredBy)).collect(Collectors.toList());
    }
}
