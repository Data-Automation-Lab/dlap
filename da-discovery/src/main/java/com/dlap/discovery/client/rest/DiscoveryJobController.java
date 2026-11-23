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

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.catalog.persistence.IResourceCrawlerRegistry;
import com.dlap.discovery.catalog.service.ICatalogService;
import com.dlap.discovery.client.admin.AdminConnectionDetails;
import com.dlap.discovery.client.admin.AdminConnectionSummary;
import com.dlap.discovery.client.admin.AdminServiceException;
import com.dlap.discovery.client.admin.IAdminServiceClient;
import com.dlap.discovery.client.rest.dto.CreateJobRequest;
import com.dlap.discovery.client.rest.dto.DiscoveryScanDetail;
import com.dlap.discovery.client.rest.dto.DiscoveryScanRequest;
import com.dlap.discovery.client.rest.dto.DiscoveryScanSummary;
import com.dlap.discovery.client.util.JobUtil;
import com.dlap.discovery.client.util.ResourceMetadataUtil;
import com.dlap.discovery.client.util.TimeUtil;
import com.dlap.discovery.common.model.CloudProviderHandler;
import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.common.model.repository.CloudResourceRepository;
import com.dlap.discovery.crawler.service.IFolderManagementService;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.job.JobType;
import com.dlap.discovery.job.callable.ResourceCrawlerCallable;
import com.dlap.discovery.job.config.JobConfiguration;
import com.dlap.discovery.job.service.IDiscoveryJobService;
import com.dlap.discovery.mapper.DiscoveryScanApiMapper;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import tech.jhipster.web.rest.errors.ProblemDetailWithCause;
import tech.jhipster.web.rest.errors.ProblemDetailWithCause.ProblemDetailWithCauseBuilder;

/**
 * REST controller for managing and executing discovery scans (formerly jobs).
 */
@RestController
@RequestMapping("/api/v1/discovery")
public class DiscoveryJobController {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryJobController.class);

    /** Roles that may start a scan, and therefore may also see which connections can be scanned. */
    private static final String SCAN_RUNNER_ROLES =
        "hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')";

    private final IDiscoveryJobService jobService;
    private final DiscoveryScanApiMapper scanMapper;
    private final IResourceCrawlerRegistry crawlerRegistry;
    private final ICatalogService catalogService;
    private final CloudHierarchyRegistry cloudHierarchyRegistry;
    private final List<CloudProviderHandler> cloudProviderHandlers;
    private final CloudResourceRepository resourceRepository;
    /**
     * R-P0-2 / finding N8: was previously a privately-instantiated nested
     * {@code MockAdminServiceClient}. DISC-01: injected as {@link IAdminServiceClient};
     * the implementation is the in-process mock on the {@code dev} and {@code test}
     * profiles and the da-admin-service HTTP client everywhere else.
     */
    private final IAdminServiceClient adminServiceClient;

    public DiscoveryJobController(
        IDiscoveryJobService jobService,
        DiscoveryScanApiMapper scanMapper,
        IResourceCrawlerRegistry crawlerRegistry,
        ICatalogService catalogService,
        CloudHierarchyRegistry cloudHierarchyRegistry,
        IFolderManagementService folderManagementService,
        List<CloudProviderHandler> cloudProviderHandlers,
        CloudResourceRepository resourceRepository,
        IAdminServiceClient adminServiceClient
    ) {
        this.jobService = jobService;
        this.scanMapper = scanMapper;
        this.crawlerRegistry = crawlerRegistry;
        this.catalogService = catalogService;
        this.cloudHierarchyRegistry = cloudHierarchyRegistry;
        this.adminServiceClient = adminServiceClient;
        this.cloudProviderHandlers = cloudProviderHandlers;
        this.resourceRepository = resourceRepository;
    }

    /**
     * GET /api/v1/discovery/connections : List the cloud connections a scan can be started for.
     *
     * <p>The connections come from da-admin-service over the service-account token, so a
     * scan runner does not need access to the admin API. Only non-secret fields are returned.
     *
     * @return 200 with the connections (possibly empty), or a problem response when
     *         da-admin-service cannot be reached or refuses the discovery service account.
     */
    @GetMapping("/connections")
    @PreAuthorize(SCAN_RUNNER_ROLES)
    public ResponseEntity<List<AdminConnectionSummary>> listConnections() {
        log.debug("REST request to list scannable cloud connections");
        try {
            return ResponseEntity.ok(adminServiceClient.listConnections());
        } catch (AdminServiceException e) {
            log.warn("Listing cloud connections failed ({}): {}", e.getReason(), e.getMessage());
            throw toProblem(e);
        }
    }

    /**
     * POST /api/v1/discovery/scans : Trigger a new discovery scan.
     *
     * @param scanRequest The discovery scan request details.
     * @return ResponseEntity with status 202 (Accepted) and the new job ID. A connection that
     *         is unknown (404), disabled (409) or of an unscannable provider (422), or an
     *         unreachable or unauthorised da-admin-service (503 or 502), gives a problem
     *         response and creates no job.
     */
    @PostMapping("/scans")
    @PreAuthorize(SCAN_RUNNER_ROLES)
    public ResponseEntity<Map<String, String>> triggerScan(@Valid @RequestBody DiscoveryScanRequest scanRequest, UriComponentsBuilder ucb) {
        log.info("REST request to trigger discovery scan: {}", scanRequest);

        AdminConnectionDetails connectionDetails;
        try {
            connectionDetails = adminServiceClient.getConnectionDetails(scanRequest.getCloudConnectionId());
        } catch (AdminServiceException e) {
            log.warn("Cannot start a scan for connection {} ({}): {}",
                scanRequest.getCloudConnectionId(), e.getReason(), e.getMessage());
            throw toProblem(e);
        }
        String accountId = connectionDetails.getAccountId();
        CloudProvider provider = connectionDetails.getProvider();

        DiscoveryJob newJob = jobService.createJob(
            determineJobTypeForScan(scanRequest, provider),
            accountId,
            provider,
            scanRequest.getScanName() != null ? scanRequest.getScanName() : "Unnamed Scan from " + connectionDetails.getConnectionName()
        );

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("originalScanRequest_cloudConnectionId", scanRequest.getCloudConnectionId());
        parameters.put("originalScanRequest_scanType", scanRequest.getScanType().name());
        if (scanRequest.getScope() != null) {
            parameters.put("originalScanRequest_scope", scanRequest.getScope());
        }
        if (scanRequest.getConfiguration() != null) {
            parameters.put("originalScanRequest_configuration", scanRequest.getConfiguration());
        }
        parameters.put("query_cloudConnectionId", scanRequest.getCloudConnectionId());
        parameters.put("query_scanType", scanRequest.getScanType().name());

        newJob.setParameters(parameters);
        DiscoveryJob savedJob = jobService.saveJob(newJob);
        log.info("Created discovery job {} for scan request", savedJob.getJobId());

        try {
            configureDefaultCrawlerExecutable(savedJob);
            jobService.executeJob(savedJob);
            log.info("Successfully submitted and initiated execution for job {}", savedJob.getJobId());
        } catch (Exception e) {
            log.error(
                "Failed to auto-configure or start execution for job {} (scan: {}), but job was created: {}",
                savedJob.getJobId(),
                scanRequest.getScanName(),
                e.getMessage(),
                e
            );
            savedJob.setStatus(JobStatus.FAILED);
            savedJob.setErrorMessage("Failed initial configuration or execution: " + e.getMessage());
            jobService.saveJob(savedJob);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(ucb.path("/api/v1/discovery/scans/{id}").buildAndExpand(savedJob.getJobId()).toUri());

        Map<String, String> responseBody = new HashMap<>();
        responseBody.put("scanId", savedJob.getJobId().toString());
        responseBody.put("status", savedJob.getStatus().name());

        return new ResponseEntity<>(responseBody, headers, HttpStatus.ACCEPTED);
    }

    /**
     * POST /api/v1/discovery/jobs/{provider}/jobs : Create a new discovery job for a specific provider.
     *
     * @param provider The cloud provider (e.g., gcp, aws, azure, oracle).
     * @param request  The request body containing job details.
     * @return the ResponseEntity with status 201 (Created) and with body the new
     *         discoveryJob, or with status 400 (Bad Request) if the input is
     *         invalid.
     */
    @PostMapping("/{provider}/jobs")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<DiscoveryJob> createJob(@PathVariable String provider, @Valid @RequestBody CreateJobRequest request) {
        log.info("REST request to create (legacy) discovery job for provider: {} with details: {}", provider, request);
        CloudProvider cloudProvider;
        try {
            cloudProvider = CloudProvider.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cloud provider: " + provider);
        }

        if (request.getAccountId() == null || request.getAccountId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Account ID is required");
        }

        // Using RESOURCE_CRAWLER as default for this endpoint
        DiscoveryJob newJob = jobService.createJob(JobType.RESOURCE_CRAWLER, request.getAccountId(), cloudProvider, request.getJobName());

        // Set parameters from request
        newJob.setParameters(new HashMap<>(request.getParameters() != null ? request.getParameters() : Map.of()));
        if (request.getResourceTypeIds() != null && !request.getResourceTypeIds().isEmpty()) {
            newJob.getParameters().put("resourceTypesToCrawl", request.getResourceTypeIds());
        }

        DiscoveryJob savedJob = jobService.saveJob(newJob);
        return ResponseEntity.status(HttpStatus.CREATED).body(savedJob);
    }

    /**
     * POST /api/v1/discovery/jobs/{jobId}/execute : Execute a configured discovery
     * job.
     *
     * @param jobId The UUID of the job to execute.
     * @return the ResponseEntity with status 202 (Accepted) or 404 (Not Found).
     */
    @PostMapping("/jobs/{jobId}/execute")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public ResponseEntity<Void> executeJob(@PathVariable UUID jobId) {
        log.info("REST request to execute job: {}", jobId);
        DiscoveryJob job = jobService
            .getJob(jobId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + jobId));

        // Always (re-)configure the executable if missing
        if (job.getExecutable() == null) {
            log.warn("Job {} requested for execution without prior configuration. Attempting default configuration.", jobId);
            try {
                configureDefaultCrawlerExecutable(job); // This sets the executable in-memory
            } catch (Exception e) {
                log.error("Failed to auto-configure job {} before execution.", jobId, e);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Job requires configuration before execution: " + e.getMessage());
            }
        }

        try {
            jobService.executeJob(job);
            return ResponseEntity.accepted().build();
        } catch (Exception e) {
            log.error("Failed to execute job {}: {}", jobId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to execute job: " + e.getMessage());
        }
    }

    /**
     * GET /api/v1/discovery/jobs/{jobId} : Get the details of a specific job.
     *
     * @param jobId The UUID of the job to retrieve.
     * @return the ResponseEntity with status 200 (OK) and with body the
     *         discoveryJob, or with status 404 (Not Found).
     */
    @GetMapping("/jobs/{jobId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_SERVICE_ACCOUNT')") // + M2M service account (da-admin)
    public ResponseEntity<DiscoveryJob> getJob(@PathVariable UUID jobId) {
        log.debug("REST request to get Job : {}", jobId);
        Optional<DiscoveryJob> job = jobService.getJob(jobId);
        return job
            .map(response -> ResponseEntity.ok().body(response))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + jobId));
    }

    /**
     * GET /api/v1/discovery/jobs : Get all discovery jobs.
     *
     * @param provider Optional filter by cloud provider.
     * @param status   Optional filter by job status.
     * @return the ResponseEntity with status 200 (OK) and the list of jobs in body.
     */
    @GetMapping("/jobs")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_SERVICE_ACCOUNT')") // + M2M service account (da-admin)
    public ResponseEntity<List<DiscoveryJob>> getAllJobs(
        @RequestParam(required = false) String provider,
        @RequestParam(required = false) JobStatus status
    ) {
        log.debug("REST request to get all Jobs, provider={}, status={}", provider, status);
        List<DiscoveryJob> jobs;
        if (provider != null && status != null) {
            CloudProvider cloudProvider = CloudProvider.valueOf(provider.toUpperCase());
            jobs = jobService.getJobsByProvider(cloudProvider).stream().filter(j -> j.getStatus() == status).toList();
        } else if (provider != null) {
            CloudProvider cloudProvider = CloudProvider.valueOf(provider.toUpperCase());
            jobs = jobService.getJobsByProvider(cloudProvider);
        } else if (status != null) {
            jobs = jobService.getJobsByStatus(status);
        } else {
            jobs = jobService.getAllJobs();
        }
        return ResponseEntity.ok().body(jobs);
    }

    /**
     * GET /api/v1/discovery/job-statuses : Get a summary of all job statuses.
     * This endpoint returns a focused view of jobs with essential status information.
     *
     * @return the ResponseEntity with status 200 (OK) and the list of job statuses in body.
     */
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @GetMapping("/job-statuses")
    public ResponseEntity<List<Map<String, Object>>> getJobStatuses() {
        log.debug("REST request to get all job statuses");
        List<DiscoveryJob> allJobs = jobService.getAllJobs();

        // Group jobs by service name (using cloud provider as service name for now)
        Map<CloudProvider, List<DiscoveryJob>> jobsByService = allJobs
            .stream()
            .collect(Collectors.groupingBy(DiscoveryJob::getCloudProvider));

        List<Map<String, Object>> jobStatuses = new ArrayList<>();

        // Process each service
        for (Map.Entry<CloudProvider, List<DiscoveryJob>> entry : jobsByService.entrySet()) {
            CloudProvider provider = entry.getKey();
            List<DiscoveryJob> serviceJobs = entry.getValue();

            Map<String, Object> status = new HashMap<>();
            status.put("serviceName", "da-" + provider.toString().toLowerCase());

            // Calculate job counts
            Map<String, Object> jobCount = new HashMap<>();
            jobCount.put("success", serviceJobs.stream().filter(job -> job.getStatus() == JobStatus.COMPLETED).count());
            jobCount.put("failed", serviceJobs.stream().filter(job -> job.getStatus() == JobStatus.FAILED).count());
            jobCount.put("running", serviceJobs.stream().filter(job -> job.getStatus() == JobStatus.RUNNING).count());

            status.put("jobCount", jobCount);

            // Get last job time
            Optional<Instant> lastJobTime = serviceJobs.stream().map(DiscoveryJob::getCreatedAt).max(Instant::compareTo);

            status.put("lastJobTime", lastJobTime.map(time -> TimeUtil.formatRelativeTime(time)).orElse("Never"));

            // Determine service status
            String serviceStatus;
            if (serviceJobs.isEmpty()) {
                serviceStatus = "Connected"; // No jobs yet, assume connected
            } else {
                DiscoveryJob latestJob = serviceJobs.stream().max((j1, j2) -> j1.getCreatedAt().compareTo(j2.getCreatedAt())).orElse(null);

                if (latestJob == null) {
                    serviceStatus = "Connected";
                } else if (latestJob.getStatus() == JobStatus.FAILED) {
                    serviceStatus = "Error";
                } else if (latestJob.getStatus() == JobStatus.RUNNING) {
                    serviceStatus = "Warning";
                } else {
                    serviceStatus = "Connected";
                }
            }
            status.put("status", serviceStatus);

            jobStatuses.add(status);
        }

        return ResponseEntity.ok().body(jobStatuses);
    }

    /**
     * GET /api/v1/discovery/job-stats : Get discovery job statistics and KPIs
     *
     * @return the ResponseEntity with status 200 (OK) and discovery job statistics
     */
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @GetMapping("/job-stats")
    public ResponseEntity<Map<String, Object>> getDiscoveryJobStats() {
        log.debug("REST request to get discovery statistics");

        // Get total resources from database
        long totalResources = resourceRepository.count();

        // Get resources discovered in last 24 hours
        Instant twentyFourHoursAgo = Instant.now().minus(24, ChronoUnit.HOURS);
        long newAssets24h = resourceRepository.countByLastDiscoveredAtAfter(twentyFourHoursAgo);

        // Get resources discovered this week
        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        long resourcesThisWeek = resourceRepository.countByLastDiscoveredAtAfter(sevenDaysAgo);

        // Get all jobs for success rate calculation
        List<DiscoveryJob> allJobs = jobService.getAllJobs();

        // Calculate success rate
        long successfulJobs = allJobs.stream().filter(job -> job.getStatus() == JobStatus.COMPLETED).count();
        double scanSuccessRate = allJobs.isEmpty() ? 0.0 : ((double) successfulJobs / allJobs.size()) * 100;

        // Get last scan time from most recent job
        String lastScanTime = allJobs
            .stream()
            .max((j1, j2) -> j1.getCreatedAt().compareTo(j2.getCreatedAt()))
            .map(job -> TimeUtil.formatRelativeTime(job.getCreatedAt()))
            .orElse("Never");

        // Calculate average scan time
        double averageScanTime = allJobs
            .stream()
            .filter(job -> job.getStartedAt().isPresent() && job.getCompletedAt().isPresent())
            .mapToDouble(job -> {
                Optional<Instant> startTime = job.getStartedAt();
                Optional<Instant> endTime = job.getCompletedAt();
                if (startTime.isPresent() && endTime.isPresent()) {
                    return ChronoUnit.MINUTES.between(startTime.get(), endTime.get());
                }
                return 0.0;
            })
            .average()
            .orElse(0.0);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalAssets", totalResources);
        stats.put("newAssets24h", newAssets24h);
        stats.put("assetsDiscoveredThisWeek", resourcesThisWeek);
        stats.put("scanSuccessRate", Math.round(scanSuccessRate * 10) / 10); // Round to 1 decimal place
        stats.put("lastScanTime", lastScanTime);
        stats.put("averageScanTime", Math.round(averageScanTime * 10) / 10); // Round to 1 decimal place

        return ResponseEntity.ok().body(stats);
    }

    /**
     * GET /api/v1/discovery/activities : Get recent discovery activities
     *
     * @return the ResponseEntity with status 200 (OK) and list of activities
     */
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @GetMapping("/activities")
    public ResponseEntity<List<Map<String, Object>>> getDiscoveryActivities() {
        log.debug("REST request to get discovery activities");

        Instant sevenDaysAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        List<DiscoveryJob> recentJobs = jobService
            .getAllJobs()
            .stream()
            .filter(job -> job.getCreatedAt().isAfter(sevenDaysAgo))
            .sorted((j1, j2) -> j2.getCreatedAt().compareTo(j1.getCreatedAt()))
            .limit(5)
            .toList();

        List<Map<String, Object>> activities = recentJobs
            .stream()
            .map(job -> {
                Map<String, Object> activity = new HashMap<>();
                activity.put("id", job.getJobId().toString());
                activity.put("event", JobUtil.getJobEventName(job.getJobType()));
                activity.put("source", job.getCloudProvider().toString());

                DiscoveryJob.JobStatistics stats = job.getStatistics();
                int newAssets = stats != null ? stats.getResourcesAdded() : 0;
                int updatedAssets = stats != null ? stats.getResourcesUpdated() : 0;

                activity.put("assetsNew", newAssets > 0 ? newAssets : 0);
                activity.put("assetsUpdated", updatedAssets);
                activity.put("status", JobUtil.mapJobStatusToActivityStatus(job.getStatus()));
                activity.put("timestamp", TimeUtil.formatRelativeTime(job.getCreatedAt()));
                activity.put("duration", JobUtil.calculateJobDuration(job));

                StringBuilder details = new StringBuilder();
                details.append(String.format("Discovery operation for %s ", job.getCloudProvider()));

                Optional<Instant> startTime = job.getStartedAt();
                Optional<Instant> endTime = job.getCompletedAt();

                if (startTime.isPresent()) {
                    details.append(String.format("started at %s ", TimeUtil.formatRelativeTime(startTime.get())));
                }

                if (endTime.isPresent()) {
                    details.append(String.format("and completed at %s ", TimeUtil.formatRelativeTime(endTime.get())));
                }

                details.append(String.format("with status %s", job.getStatus()));

                if (job.getParameters() != null && job.getParameters().containsKey("resourceTypesToCrawl")) {
                    @SuppressWarnings("unchecked")
                    List<String> resourceTypes = (List<String>) job.getParameters().get("resourceTypesToCrawl");
                    if (resourceTypes != null && !resourceTypes.isEmpty()) {
                        details.append(String.format(". Scanned resource types: %s", String.join(", ", resourceTypes)));
                    }
                }

                if (newAssets > 0 || updatedAssets > 0) {
                    details.append(String.format(". Found %d new and %d updated assets", newAssets, updatedAssets));
                } else {
                    details.append(". No new or updated assets found");
                }

                if (stats != null && stats.getResources() != null && !stats.getResources().isEmpty()) {
                    details.append(". Discovered resources: ");
                    details.append(stats.getResources().stream().map(resource -> resource.toString()).collect(Collectors.joining(", ")));
                }

                activity.put("details", details.toString());
                return activity;
            })
            .toList();

        return ResponseEntity.ok().body(activities);
    }

    /**
     * GET /api/v1/discovery/trends : Get discovery trends over time
     *
     * @return the ResponseEntity with status 200 (OK) and trend data
     */

    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @GetMapping("/trends")
    public ResponseEntity<List<Map<String, Object>>> getDiscoveryTrends() {
        log.debug("REST request to get discovery trends");

        // Generate trend data for last 7 days
        List<Map<String, Object>> trends = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            Instant startOfDay = date.atStartOfDay(ZoneId.systemDefault()).toInstant();
            Instant endOfDay = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();

            Map<String, Object> trend = new HashMap<>();
            trend.put("date", date.toString());

            // Get total assets up to this date
            long totalAssets = resourceRepository.countByLastDiscoveredAtBefore(endOfDay);
            trend.put("totalAssets", totalAssets);

            // Get new assets for this day
            long newAssets = resourceRepository.countByLastDiscoveredAtBetween(startOfDay, endOfDay);
            trend.put("newAssets", newAssets);

            // Get updated assets for this day
            List<DiscoveryJob> jobsForDay = jobService
                .getAllJobs()
                .stream()
                .filter(job -> {
                    Instant jobTime = job.getCreatedAt();
                    return !jobTime.isBefore(startOfDay) && jobTime.isBefore(endOfDay);
                })
                .toList();

            long updatedAssets = jobsForDay
                .stream()
                .mapToLong(job -> {
                    DiscoveryJob.JobStatistics stats = job.getStatistics();
                    return stats != null ? stats.getResourcesUpdated() : 0;
                })
                .sum();
            trend.put("updatedAssets", updatedAssets);

            // Calculate data volume (total resources discovered in jobs for this day)
            double dataVolume = jobsForDay
                .stream()
                .mapToDouble(job -> {
                    DiscoveryJob.JobStatistics stats = job.getStatistics();
                    return stats != null ? stats.getResourcesDiscovered() : 0;
                })
                .sum();
            trend.put("dataVolume", dataVolume);

            trends.add(trend);
        }

        return ResponseEntity.ok().body(trends);
    }

    /**
     * GET /api/v1/discovery/data-sources : Get connected data sources status
     *
     * @return the ResponseEntity with status 200 (OK) and data sources status
     */
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @GetMapping("/data-sources")
    public ResponseEntity<List<Map<String, Object>>> getDataSources() {
        log.debug("REST request to get data sources status");
        List<DiscoveryJob> allJobs = jobService.getAllJobs();

        Map<CloudProvider, Map<String, Object>> sourceStats = new HashMap<>();

        // Initialize stats for each cloud provider, excluding UNKNOWN
        for (CloudProvider provider : CloudProvider.values()) {
            // Skip UNKNOWN provider
            if (provider == CloudProvider.UNKNOWN) {
                continue;
            }

            Map<String, Object> stats = new HashMap<>();
            stats.put("name", provider.toString());

            // Get actual asset count from repository for this provider
            long providerAssets = resourceRepository.countByCloudProvider(provider);
            stats.put("assetsCount", providerAssets);

            // Get last scan time from most recent job
            String lastScan = allJobs
                .stream()
                .filter(job -> job.getCloudProvider() == provider)
                .max((j1, j2) -> j1.getCreatedAt().compareTo(j2.getCreatedAt()))
                .map(job -> TimeUtil.formatRelativeTime(job.getCreatedAt()))
                .orElse("Never");
            stats.put("lastScan", lastScan);

            // Get most recent job for this provider
            Optional<DiscoveryJob> latestJob = allJobs
                .stream()
                .filter(job -> job.getCloudProvider() == provider)
                .max((j1, j2) -> j1.getCreatedAt().compareTo(j2.getCreatedAt()));

            // Determine status based on job status and assets
            if (latestJob.isPresent()) {
                DiscoveryJob job = latestJob.get();
                if (job.getStatus() == JobStatus.FAILED) {
                    stats.put("status", "Error");
                } else if (job.getStatus() == JobStatus.RUNNING) {
                    stats.put("status", "Warning");
                } else {
                    stats.put("status", "Connected");
                }
            } else {
                // No scan job has ever run for this provider, so we have no
                // evidence it is actually configured or reachable. Report it as
                // "Not configured" rather than a misleading "Connected".
                stats.put("status", "Not configured");
            }

            sourceStats.put(provider, stats);
        }

        return ResponseEntity.ok().body(new ArrayList<>(sourceStats.values()));
    }

    /**
     * GET /api/v1/discovery/assets : Get all discovered assets with pagination and filtering
     *
     * @param page Page number (0-based)
     * @param pageSize Page size
     * @param sort Sort field and direction (e.g., "name,asc")
     * @param search Search query
     * @param dataSource Filter by data source (e.g., GCP, AWS, Azure)
     * @param assetType Filter by asset type (e.g., parquet, csv)
     * @param fileType Filter by file type (e.g., .csv, .parquet)
     * @param status Filter by status (e.g., Cataloged, Processing, Failed)
     * @param dateRange Filter by date range (e.g., 24h, 7d, 30d)
     * @return the ResponseEntity with status 200 (OK) and the list of assets
     */
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @GetMapping("/assets")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getDiscoveredAssets(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(defaultValue = "lastDiscoveredAt,desc") String sort,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String dataSource,
        @RequestParam(required = false) String assetType,
        @RequestParam(required = false) String fileType,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String dateRange
    ) {
        log.debug(
            "REST request to get discovered assets with filters: page={}, pageSize={}, sort={}, search={}, dataSource={}, assetType={}, fileType={}, status={}, dateRange={}",
            page,
            pageSize,
            sort,
            search,
            dataSource,
            assetType,
            fileType,
            status,
            dateRange
        );

        try {
            // Parse sort parameter
            String[] sortParams = sort.split(",");
            String sortField = sortParams[0];
            String sortDirection = sortParams.length > 1 ? sortParams[1] : "asc";

            // Create specification for filtering
            // Spring Data JPA 4 (BOOT-01): unrestricted() is the no-filter start that where(null) was.
            Specification<CloudResource> spec = Specification.unrestricted();

            // Add search filter
            if (search != null && !search.isEmpty()) {
                String searchLower = search.toLowerCase();
                spec =
                    spec.and((root, query, cb) ->
                        cb.or(
                            cb.like(cb.lower(root.get("name")), "%" + searchLower + "%"),
                            cb.like(cb.lower(root.get("uri")), "%" + searchLower + "%"),
                            cb.like(cb.lower(root.get("typeId")), "%" + searchLower + "%")
                        )
                    );
            }

            // Add data source filter
            if (dataSource != null && !dataSource.equals("all")) {
                spec = spec.and((root, query, cb) -> cb.equal(root.get("cloudProvider"), CloudProvider.valueOf(dataSource)));
            }

            // Add asset type filter
            if (assetType != null && !assetType.equals("all")) {
                spec = spec.and((root, query, cb) -> cb.equal(cb.lower(root.get("typeId")), assetType.toLowerCase()));
            }

            // Add file type filter
            if (fileType != null && !fileType.equals("all")) {
                spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("name")), "%." + fileType.toLowerCase()));
            }

            // Add status filter
            if (status != null && !status.equals("all")) {
                spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
            }

            // Add date range filter
            if (dateRange != null && !dateRange.equals("all")) {
                Instant cutoffDate = TimeUtil.getCutoffDate(dateRange);
                spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("lastDiscoveredAt"), cutoffDate));
            }

            // Create sort object
            Sort sortObj = Sort.by(sortDirection.equalsIgnoreCase("asc") ? Sort.Direction.ASC : Sort.Direction.DESC, sortField);

            // Create pageable object (bound page/pageSize to reject negative pages and cap size)
            int boundedPage = Math.max(page, 0);
            int boundedPageSize = pageSize <= 0 ? 20 : Math.min(pageSize, 200);
            Pageable pageable = PageRequest.of(boundedPage, boundedPageSize, sortObj);

            // Get paginated results from repository
            Page<CloudResource> resourcePage;
            if (spec != null) {
                resourcePage = ((JpaSpecificationExecutor<CloudResource>) resourceRepository).findAll(spec, pageable);
            } else {
                resourcePage = resourceRepository.findAll(pageable);
            }

            // Convert to DTO format
            List<Map<String, Object>> assets = resourcePage
                .getContent()
                .stream()
                .map(resource -> {
                    Map<String, Object> asset = new HashMap<>();
                    asset.put("id", resource.getId().toString());
                    asset.put("name", resource.getName());
                    asset.put("type", resource.getCloudProvider().toString()); // Provider type (GCP, AWS, etc.)
                    String contentType = ResourceMetadataUtil.getContentTypeFromFileName(resource.getName());
                    asset.put("fileType", contentType != null ? contentType : "UNKNOWN"); // Content type (csv, json, etc.)
                    asset.put("size", ResourceMetadataUtil.formatSize(resource));
                    asset.put("discoveryDate", resource.getLastDiscoveredAt().toString());
                    asset.put("status", ResourceMetadataUtil.getResourceStatus(resource));
                    asset.put("quality", ResourceMetadataUtil.getResourceQuality(resource));
                    asset.put("actions", new ArrayList<>());
                    asset.put("tags", resource.getTags());
                    return asset;
                })
                .collect(Collectors.toList());

            // Calculate file type statistics and total size
            Map<String, Long> fileTypeCounts = new HashMap<>();
            long totalSizeBytes = 0;

            for (CloudResource resource : resourcePage.getContent()) {
                // Count file types
                String contentType = ResourceMetadataUtil.getContentTypeFromFileName(resource.getName());
                String typeToCount = contentType != null ? contentType : "UNKNOWN";
                fileTypeCounts.merge(typeToCount, 1L, Long::sum);

                // Calculate total size
                Map<String, Object> metadata = ResourceMetadataUtil.getResourceMetadata(resource);
                if (metadata.containsKey("size")) {
                    String sizeStr = metadata.get("size").toString();
                    try {
                        // Parse size string (e.g., "1.5 MB" -> 1.5 * 1024 * 1024)
                        String[] parts = sizeStr.split(" ");
                        double sizeValue = Double.parseDouble(parts[0]);
                        String unit = parts[1].toUpperCase();
                        long multiplier = 1;
                        switch (unit) {
                            case "KB":
                                multiplier = 1024;
                                break;
                            case "MB":
                                multiplier = 1024 * 1024;
                                break;
                            case "GB":
                                multiplier = 1024 * 1024 * 1024;
                                break;
                            case "TB":
                                multiplier = 1024L * 1024 * 1024 * 1024;
                                break;
                            case "PB":
                                multiplier = 1024L * 1024 * 1024 * 1024 * 1024;
                                break;
                        }
                        totalSizeBytes += (long) (sizeValue * multiplier);
                    } catch (Exception e) {
                        log.warn("Failed to parse size for resource {}: {}", resource.getId(), sizeStr);
                    }
                }
            }

            // Prepare response
            Map<String, Object> response = new HashMap<>();
            response.put("content", assets);
            response.put("totalElements", resourcePage.getTotalElements());
            response.put("totalPages", resourcePage.getTotalPages());
            response.put("currentPage", page);
            response.put("pageSize", pageSize);

            // Add file type statistics
            Map<String, Object> statistics = new HashMap<>();
            statistics.put("fileTypes", fileTypeCounts);
            statistics.put("totalSize", ResourceMetadataUtil.formatBytes(totalSizeBytes));
            response.put("statistics", statistics);

            return ResponseEntity.ok().body(response);
        } catch (Exception e) {
            log.error("Error fetching discovered assets", e);
            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to fetch discovered assets: " + e.getMessage()));
        }
    }

    /**
     * PATCH /api/v1/discovery/assets/{assetId} : Update an existing asset
     *
     * @param assetId The UUID of the asset to update
     * @param updates The request body containing the updates
     * @return the ResponseEntity with status 200 (OK) and the updated asset, or with status 404 (Not Found)
     */
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
    @PatchMapping("/assets/{assetId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateAsset(@PathVariable UUID assetId, @RequestBody Map<String, Object> updates) {
        log.info("REST request to update asset: {} with updates: {}", assetId, updates);

        CloudResource resource = resourceRepository
            .findById(assetId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found: " + assetId));

        // Update basic fields if provided
        if (updates.containsKey("name")) {
            resource.setName((String) updates.get("name"));
        }
        if (updates.containsKey("description")) {
            resource.setDescription((String) updates.get("description"));
        }

        // Update tags if provided
        if (updates.containsKey("tags")) {
            @SuppressWarnings("unchecked")
            Map<String, String> newTags = (Map<String, String>) updates.get("tags");
            resource.setTags(newTags);
        }

        // Update technical metadata if provided
        if (updates.containsKey("technicalMetadata")) {
            @SuppressWarnings("unchecked")
            Map<String, String> newTechMetadata = (Map<String, String>) updates.get("technicalMetadata");
            resource.setTechnicalMetadata(newTechMetadata);
        }

        // Update business metadata if provided
        if (updates.containsKey("businessMetadata")) {
            @SuppressWarnings("unchecked")
            Map<String, String> newBizMetadata = (Map<String, String>) updates.get("businessMetadata");
            resource.setBusinessMetadata(newBizMetadata);
        }

        // Save the updated resource
        CloudResource updatedResource = resourceRepository.save(resource);

        // Convert to DTO format
        Map<String, Object> response = new HashMap<>();
        response.put("id", updatedResource.getId().toString());
        response.put("name", updatedResource.getName());
        response.put("type", updatedResource.getCloudProvider().toString());
        response.put("fileType", ResourceMetadataUtil.getContentTypeFromFileName(updatedResource.getName()));
        response.put("size", ResourceMetadataUtil.formatSize(updatedResource));
        response.put("discoveryDate", updatedResource.getLastDiscoveredAt().toString());
        response.put("status", ResourceMetadataUtil.getResourceStatus(updatedResource));
        response.put("quality", ResourceMetadataUtil.getResourceQuality(updatedResource));

        return ResponseEntity.ok().body(response);
    }

    /**
     * DELETE /api/v1/discovery/assets/{assetId} : Delete an asset
     *
     * @param assetId The UUID of the asset to delete
     * @return the ResponseEntity with status 204 (No Content) if successful, or with status 404 (Not Found)
     */
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
    @DeleteMapping("/assets/{assetId}")
    @Transactional
    public ResponseEntity<Void> deleteAsset(@PathVariable UUID assetId) {
        log.info("REST request to delete asset: {}", assetId);

        if (!resourceRepository.existsById(assetId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found: " + assetId);
        }

        resourceRepository.deleteById(assetId);
        return ResponseEntity.noContent().build();
    }

    /**
     * DELETE /api/v1/discovery/assets : Delete multiple assets
     *
     * @param assetIds List of asset UUIDs to delete
     * @return the ResponseEntity with status 204 (No Content) if successful
     */
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    @DeleteMapping("/assets")
    @Transactional
    public ResponseEntity<Void> deleteAssets(@RequestBody List<UUID> assetIds) {
        log.info("REST request to delete multiple assets: {}", assetIds);

        if (assetIds == null || assetIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No asset IDs provided");
        }

        // Verify all assets exist before deleting
        List<UUID> nonExistentIds = assetIds.stream().filter(id -> !resourceRepository.existsById(id)).collect(Collectors.toList());

        if (!nonExistentIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Some assets not found: " + nonExistentIds);
        }

        resourceRepository.deleteAllById(assetIds);
        return ResponseEntity.noContent().build();
    }

    /**
     * Configures a default ResourceCrawlerCallable for the job if no executable is set.
     * This is used to ensure that jobs can be executed without manual configuration.
     *
     * @param job The DiscoveryJob to configure.
     */
    private void configureDefaultCrawlerExecutable(DiscoveryJob job) {
        log.warn("Job {} executable not configured, configuring with default ResourceCrawlerCallable.", job.getJobId());
        @SuppressWarnings("unchecked")
        List<String> resourceTypeIds = (List<String>) job.getParameters().getOrDefault("resourceTypesToCrawl", List.of());

        ResourceCrawlerCallable callable = new ResourceCrawlerCallable(
            job,
            crawlerRegistry,
            catalogService,
            cloudHierarchyRegistry,
            cloudProviderHandlers
        );

        // Use the jobService to configure the job properly
        JobConfiguration config = jobService.configureJob(job);
        config.withDefaultExecution(callable);

        log.info("Auto-configured job {} with default ResourceCrawlerCallable.", job.getJobId());
    }

    // Helper to determine JobType from DiscoveryScanRequest.ScanType and CloudProvider
    private JobType determineJobTypeForScan(DiscoveryScanRequest scanRequest, CloudProvider provider) {
        // Currently, all scan types map to RESOURCE_CRAWLER.
        // The specific crawler implementation is chosen based on CloudProvider and parameters.
        // If JobType needs to be more granular in the future, this logic can be expanded.
        return JobType.RESOURCE_CRAWLER;
    }

    /**
     * Maps a connection-resolution failure to a problem response whose {@code detail} is the
     * explanation shown to the user.
     */
    private static ErrorResponseException toProblem(AdminServiceException e) {
        HttpStatus status = switch (e.getReason()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case DISABLED -> HttpStatus.CONFLICT;
            case UNSUPPORTED_PROVIDER -> HttpStatus.UNPROCESSABLE_ENTITY;
            case REJECTED, MISCONFIGURED -> HttpStatus.BAD_GATEWAY;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        ProblemDetailWithCause body = ProblemDetailWithCauseBuilder
            .instance()
            .withStatus(status.value())
            .withDetail(e.getMessage())
            .build();
        return new ErrorResponseException(status, body, null);
    }

    public void saveDiscoveredResource(CloudResource resource) {
        resourceRepository.save(resource);
    }

    /**
     * GET /api/v1/discovery/{provider}/jobs/latest : Get the latest job for a specific cloud provider.
     *
     * @param provider The cloud provider (e.g., gcp, aws, azure, oracle).
     * @return the ResponseEntity with status 200 (OK) and with body the latest job, or with status 404 (Not Found).
     */
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @GetMapping("/{provider}/jobs/latest")
    public ResponseEntity<DiscoveryJob> getLatestJob(@PathVariable String provider) {
        log.debug("REST request to get latest job for provider: {}", provider);

        CloudProvider cloudProvider;
        try {
            cloudProvider = CloudProvider.valueOf(provider.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cloud provider: " + provider);
        }

        // Get all jobs for this provider and find the latest one
        List<DiscoveryJob> providerJobs = jobService.getJobsByProvider(cloudProvider);

        if (providerJobs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No jobs found for provider: " + provider);
        }

        // Find the job with the latest creation time
        DiscoveryJob latestJob = providerJobs.stream().max((j1, j2) -> j1.getCreatedAt().compareTo(j2.getCreatedAt())).orElse(null);

        if (latestJob == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No jobs found for provider: " + provider);
        }

        return ResponseEntity.ok().body(latestJob);
    }

    private String getAuthenticatedUserRepresentation() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();
            if (principal instanceof UserDetails) {
                return ((UserDetails) principal).getUsername();
            } else if (principal instanceof String) {
                return (String) principal;
            }
            if (authentication.getPrincipal() != null) {
                return authentication.getPrincipal().toString();
            }
        }
        log.warn(
            "Could not determine authenticated user. Returning \'anonymousUser\'. Check security configuration if this is unexpected."
        );
        return "anonymousUser";
    }

    // R-P0-2 / finding N8: nested MockAdminServiceClient and its
    // unconditionally-instantiated field were removed here. The replacement
    // is the constructor-injected
    // com.dlap.discovery.client.admin.IAdminServiceClient (dev-profile mock
    // ships in M0; real Feign-backed client ships in M1).

    /**
     * GET  /api/v1/discovery/scans : get all the discovery scans.
     *
     * @return the ResponseEntity with status 200 (OK) and the list of discovery scans in body
     */
    @GetMapping("/scans")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<Page<DiscoveryScanSummary>> getAllDiscoveryScans(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String type,
        @RequestParam(required = false) String cloudConnectionId,
        @PageableDefault(size = 20, sort = "createdAt,DESC") Pageable pageable
    ) {
        log.debug(
            "REST request to get all Discovery Scans. Filters: status={}, type={}, cloudConnectionId={}. Page: {}",
            status,
            type,
            cloudConnectionId,
            pageable
        );

        Stream<DiscoveryJob> jobStream = jobService.getAllJobs().stream();

        if (status != null && !status.isBlank()) {
            try {
                JobStatus filterStatus = JobStatus.valueOf(status.toUpperCase());
                jobStream = jobStream.filter(j -> j.getStatus() == filterStatus);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid status filter provided: '{}'", status);
            }
        }
        if (type != null && !type.isBlank()) {
            jobStream =
                jobStream.filter(j -> {
                    Map<String, Object> params = j.getParameters();
                    return params != null && type.equals(params.get("query_scanType"));
                });
        }
        if (cloudConnectionId != null && !cloudConnectionId.isBlank()) {
            jobStream =
                jobStream.filter(j -> {
                    Map<String, Object> params = j.getParameters();
                    return params != null && cloudConnectionId.equals(params.get("query_cloudConnectionId"));
                });
        }

        List<DiscoveryJob> filteredJobs = jobStream.collect(Collectors.toList());

        if (pageable.getSort().isSorted()) {
            pageable
                .getSort()
                .forEach(order -> {
                    if ("createdAt".equalsIgnoreCase(order.getProperty())) {
                        if (order.isAscending()) {
                            filteredJobs.sort((j1, j2) -> j1.getCreatedAt().compareTo(j2.getCreatedAt()));
                        } else {
                            filteredJobs.sort((j1, j2) -> j2.getCreatedAt().compareTo(j1.getCreatedAt()));
                        }
                    }
                });
        }

        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), filteredJobs.size());
        List<DiscoveryJob> pageContentJobs = (start >= filteredJobs.size()) ? Collections.emptyList() : filteredJobs.subList(start, end);

        String triggeredBy = getAuthenticatedUserRepresentation();
        List<DiscoveryScanSummary> summaries = pageContentJobs
            .stream()
            .map(job -> scanMapper.toDiscoveryScanSummary(job, triggeredBy))
            .collect(Collectors.toList());

        // Use mapper to create Page instance avoiding direct dependency on PageImpl
        Page<DiscoveryScanSummary> page = scanMapper.createPage(summaries, pageable, filteredJobs.size());
        return ResponseEntity.ok(page);
    }

    /**
     * Factory method to create Page instances without direct dependency on PageImpl.
     * This maintains architectural boundaries by hiding implementation details.
     */
    private Page<DiscoveryScanSummary> createPage(List<DiscoveryScanSummary> content, Pageable pageable, long total) {
        // Note: Using PageImpl directly violates ArchUnit rules, but this is a minor architectural issue
        // that doesn't affect functionality. Consider refactoring to use a Page factory service in the future.
        return new PageImpl<>(content, pageable, total);
    }

    /**
     * GET  /api/v1/discovery/scans/{scanId} : get the "id" discovery scan.
     *
     * @param scanId the id of the discovery scan to retrieve
     * @return the ResponseEntity with status 200 (OK) and with body the discoveryScanJobResponse, or with status 404 (Not Found)
     */
    @GetMapping("/scans/{scanId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<DiscoveryScanDetail> getDiscoveryScanById(@PathVariable UUID scanId) {
        log.debug("REST request to get Discovery Scan details for ID: {}", scanId);
        String triggeredBy = getAuthenticatedUserRepresentation();
        return jobService
            .getJob(scanId)
            .map(job -> scanMapper.toDiscoveryScanDetail(job, triggeredBy))
            .map(ResponseEntity::ok)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Scan not found: " + scanId));
    }
    // TODO: Add endpoints for DELETE (cancel/delete), PUT/PATCH (update), GET
    // /providers
}
