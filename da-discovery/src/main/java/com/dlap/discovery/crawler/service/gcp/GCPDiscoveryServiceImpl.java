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

package com.dlap.discovery.crawler.service.gcp;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.catalog.persistence.IResourceCrawlerRegistry;
import com.dlap.discovery.catalog.service.ICatalogService;
import com.dlap.discovery.providers.gcp.auth.GCPAuthenticationServiceImpl;
import com.dlap.discovery.providers.gcp.config.GCPConfigService;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.common.service.CloudResourceDTO;
import com.dlap.discovery.common.service.ResourceNotFoundException;
import com.dlap.discovery.common.util.CloudResourceMapper;
import com.dlap.discovery.common.util.JobStatisticsMapper;
import com.dlap.discovery.common.util.health.HealthStatus;
import com.dlap.discovery.crawler.exception.ExceptionUtils;
import com.dlap.discovery.crawler.model.gcp.GcpResource;
import com.dlap.discovery.crawler.service.IDiscoveryService;
import com.dlap.discovery.crawler.service.IFolderManagementService;
import com.dlap.discovery.crawler.service.IResourceCrawler;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.job.JobType;
import com.dlap.discovery.job.callable.ResourceCrawlerCallable;
import com.dlap.discovery.job.config.JobConfiguration;
import com.dlap.discovery.job.executor.IJobExecutor;
import com.dlap.discovery.job.scheduler.IDiscoveryScheduler;
import com.dlap.discovery.job.service.IDiscoveryJobService;
import com.dlap.discovery.job.service.JobExecutionException;
import com.dlap.discovery.job.service.JobStatisticsDTO;
import com.dlap.discovery.log.service.ILogAnalyzer;
import com.dlap.discovery.common.model.CloudProviderHandler;

/**
 * Implementation of IDiscoveryService for Google Cloud Platform resources.
 * Manages GCP resource discovery jobs and their execution.
 */
@Service("gcpDiscoveryService")
@ConditionalOnProperty(name = "cloud.provider.gcp.enabled", havingValue = "true", matchIfMissing = false)
public class GCPDiscoveryServiceImpl implements IDiscoveryService<GcpResource> {

    private static final Logger log = LoggerFactory.getLogger(GCPDiscoveryServiceImpl.class);
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MS = 1000;

    private final List<IResourceCrawler<GcpResource>> gcpCrawlers;

    @Autowired
    private IJobExecutor jobExecutor;

    private final CloudHierarchyRegistry cloudHierarchyRegistry;
    private final CloudResourceMapper cloudResourceMapper;
    private final JobStatisticsMapper jobStatisticsMapper;
    private final IDiscoveryScheduler discoveryScheduler;
    private final IResourceCrawlerRegistry crawlerRegistry;
    private final List<ILogAnalyzer> logAnalyzers;
    private final ICatalogService catalogService;
    private final IDiscoveryJobService jobService;
    private final GCPConfigService gcpConfigService;
    private final GCPAuthenticationServiceImpl authenticationService;
    private final List<CloudProviderHandler> cloudProviderHandlers;

    @Autowired
    public GCPDiscoveryServiceImpl(
            @Autowired(required = false) List<IResourceCrawler<GcpResource>> gcpCrawlers,
            IResourceCrawlerRegistry crawlerRegistry,
            @Autowired(required = false) List<ILogAnalyzer> logAnalyzers,
            ICatalogService catalogService,
            IDiscoveryJobService jobService,
            GCPConfigService gcpConfigService,
            CloudHierarchyRegistry cloudHierarchyRegistry,
            GCPAuthenticationServiceImpl authenticationService,
            CloudResourceMapper cloudResourceMapper,
            JobStatisticsMapper jobStatisticsMapper,
            IDiscoveryScheduler discoveryScheduler,
            List<CloudProviderHandler> cloudProviderHandlers) {
        this.cloudProviderHandlers = cloudProviderHandlers;
        this.gcpCrawlers = (gcpCrawlers != null) ? gcpCrawlers : Collections.emptyList();
        this.crawlerRegistry = crawlerRegistry;
        this.logAnalyzers = (logAnalyzers != null) ? logAnalyzers : Collections.emptyList();
        this.catalogService = catalogService;
        this.jobService = jobService;
        this.gcpConfigService = gcpConfigService;
        this.cloudHierarchyRegistry = cloudHierarchyRegistry;
        this.authenticationService = authenticationService;
        this.cloudResourceMapper = cloudResourceMapper;
        this.jobStatisticsMapper = jobStatisticsMapper;
        this.discoveryScheduler = discoveryScheduler;


        if (this.gcpCrawlers.isEmpty()) {
            log.warn(
                    "GCPDiscoveryService initialized, but no GCPResourceCrawler beans were found. GCP crawling will be unavailable.");
        }
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.GCP;
    }

    @Override
    public String getCloudProvider() {
        return getProvider().name();
    }

    @Override
    public List<ResourceType> getSupportedResourceTypes() {
        return gcpCrawlers.stream()
                .flatMap(crawler -> crawler.getSupportedResourceTypes().stream())
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public boolean isResourceTypeSupported(ResourceType resourceType) {
        return gcpCrawlers.stream()
                .anyMatch(crawler -> crawler.getSupportedResourceTypes().contains(resourceType));
    }

    @Override
    public DiscoveryJob createDiscoveryCrawlerJob(String accountId, List<String> resourceTypeIds,
            Map<String, String> parameters, String jobName) {
        log.info("Creating GCP Resource Crawler job definition for account: {}, types: {}", accountId,
                resourceTypeIds != null ? resourceTypeIds : "All");

        DiscoveryJob job = jobService.createJob(JobType.RESOURCE_CRAWLER, accountId, CloudProvider.GCP, jobName);

        Map<String, Object> jobParams = new HashMap<>();
        if (parameters != null) {
            jobParams.putAll(parameters);
        }
        if (resourceTypeIds != null && !resourceTypeIds.isEmpty()) {
            jobParams.put("resourceTypesToCrawl", resourceTypeIds);
        }
        job.setParameters(jobParams);
        configureDefaultCrawlerExecutable(job);

        return jobService.saveJob(job);
    }

    @Override
    @Retryable(value = {
            JobExecutionException.class }, maxAttempts = MAX_RETRY_ATTEMPTS, backoff = @Backoff(delay = RETRY_DELAY_MS))
    public CompletableFuture<DiscoveryJob> startDiscoveryJobAsync(UUID jobId) {
        log.info("Attempting to start job asynchronously: {}", jobId);
        DiscoveryJob job = jobService.getJob(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob", jobId.toString()));

        if (job.getStatus() != JobStatus.CREATED && job.getStatus() != JobStatus.PENDING) {
            log.warn("Job {} cannot be started asynchronously, current status: {}", jobId, job.getStatus());
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Job cannot be started, status is " + job.getStatus()));
        }

        if (job.getExecutable() == null) {
            log.error("Job {} cannot be started, it has not been configured with an executable.", jobId);
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Job must be configured via JobConfiguration before starting."));
        }

        return CompletableFuture.supplyAsync(() -> {
            log.info("Submitting job {} for asynchronous execution.", jobId);
            Future<?> executionFuture = jobService.executeJob(job);
            try {
                executionFuture.get();
                log.info("Asynchronous execution future completed for job {}. Fetching final status.", jobId);
                return jobService.getJob(jobId)
                        .orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob", jobId.toString()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Asynchronous execution interrupted for job {}", jobId, e);
                updateJobStatusOnError(jobId, "Execution interrupted");
                throw new JobExecutionException("Execution interrupted for job: " + jobId, e);
            } catch (Exception e) {
                log.error("Asynchronous execution failed for job {}", jobId, e);
                updateJobStatusOnError(jobId, e.getMessage());
                throw new JobExecutionException("Execution failed for job: " + jobId, e);
            }
        });
    }

    @Override
    @Retryable(value = {
            JobExecutionException.class }, maxAttempts = MAX_RETRY_ATTEMPTS, backoff = @Backoff(delay = RETRY_DELAY_MS))
    public DiscoveryJob startDiscoveryJob(UUID jobId, Map<String, String> parameters) {
        log.info("Attempting to start job synchronously: {} with params: {}", jobId, parameters);
        DiscoveryJob job = jobService.getJob(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob", jobId.toString()));

        if (job == null) {
            log.error("Job {} not found", jobId);
            throw new ResourceNotFoundException("DiscoveryJob not found, jobId:{}", jobId.toString());
        }

        if (job.getStatus() != JobStatus.CREATED) {
            log.warn("Job {} cannot be started synchronously, current status: {}", jobId, job.getStatus());
            throw new IllegalStateException("Job cannot be started, status is " + job.getStatus());
        }

        if (job.getExecutable() == null) {
            log.error("Job {} cannot be started, it has not been configured with an executable.", jobId);
            throw new IllegalStateException("Job must be configured via JobConfiguration before starting.");
        }

        if (parameters != null && !parameters.isEmpty()) {
            job.getParameters().putAll(parameters);
            job = jobService.saveJob(job);
        }

        log.info("Submitting job {} for synchronous execution.", jobId);
        Future<?> executionFuture = jobService.executeJob(job);
        try {
            executionFuture.get();
            log.info("Synchronous execution completed for job {}. Fetching final status.", jobId);
            return jobService.getJob(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob", jobId.toString()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Synchronous execution interrupted for job {}", jobId, e);
            updateJobStatusOnError(jobId, "Execution interrupted");
            throw new JobExecutionException("Execution interrupted for job: " + jobId, e);
        } catch (Exception e) {
            log.error("Synchronous execution failed for job {}", jobId, e);
            updateJobStatusOnError(jobId, e.getMessage());
            throw new JobExecutionException("Execution failed for job: " + jobId, e);
        }
    }

    @Override
    public Optional<DiscoveryJob> getDiscoveryJob(String jobId) {
        try {
            UUID jobUUID = UUID.fromString(jobId);
            return jobService.getJob(jobUUID);
        } catch (IllegalArgumentException e) {
            log.error("Invalid UUID format for getDiscoveryJob: {}", jobId);
            return Optional.empty();
        }
    }

    @Override
    public List<DiscoveryJob> getAllDiscoveryJobs() {
        return jobService.getAllJobs().stream()
                .filter(job -> job.getCloudProvider() == CloudProvider.GCP)
                .toList();
    }

    @Override
    public List<DiscoveryJob> getDiscoveryJobsByAccount(String accountId) {
        return jobService.getJobsByAccount(accountId).stream()
                .filter(job -> job.getCloudProvider() == CloudProvider.GCP)
                .toList();
    }

    private void updateJobStatusOnError(UUID jobId, String errorMessage) {
        try {
            jobService.getJob(jobId).ifPresent(jobToUpdate -> {
                jobToUpdate.setStatus(JobStatus.FAILED);
                jobToUpdate.setErrorMessage(errorMessage != null ? errorMessage : "Execution failed");
                jobService.saveJob(jobToUpdate);
            });
        } catch (Exception e) {
            log.error("Failed to update job {} status to FAILED after execution error", jobId, e);
        }
    }

    @Override
    public boolean cancelDiscoveryJob(UUID jobId) {
        log.info("Attempting to cancel GCP DiscoveryJob: {}", jobId);
        return jobExecutor.cancelJob(jobId);
    }

    @Override
    public boolean pauseDiscoveryJob(UUID jobId) {
        log.warn("Pause operation potentially not supported for job: {}", jobId);
        return jobExecutor.pauseJob(jobId);
    }

    @Override
    public boolean resumeDiscoveryJob(UUID jobId) {
        log.warn("Resume operation potentially not supported for job: {}", jobId);
        return jobExecutor.resumeJob(jobId);
    }

    @Override
    public List<IResourceCrawler<GcpResource>> getCrawlers() {
        return List.copyOf(gcpCrawlers);
    }

    @Override
    public List<IResourceCrawler<GcpResource>> getCrawlersByProvider(String cloudProvider) {
        if (!getProvider().name().equals(cloudProvider)) {
            return List.of();
        }
        return getCrawlers();
    }

    @Override
    public void registerCrawler(IResourceCrawler<GcpResource> crawler) {
        if (crawler == null) {
            throw ExceptionUtils.missingParameter("crawler", "Crawler cannot be null");
        }

        if (!(crawler instanceof IResourceCrawler<?>)) {
            throw ExceptionUtils.invalidParameter("crawler", crawler.getClass().getName(),
                    "Crawler must implement IResourceCrawler interface");
        }

        if (!GcpResource.class.isAssignableFrom(crawler.getClass().getTypeParameters()[0].getClass())) {
            throw ExceptionUtils.invalidParameter("crawler", crawler.getClass().getName(),
                    "Crawler must be compatible with GCP resources");
        }

        String crawlerName = crawler.getClass().getSimpleName();
        if (gcpCrawlers.stream().anyMatch(c -> c.getClass().getSimpleName().equals(crawlerName))) {
            throw ExceptionUtils.invalidConfiguration("crawler", crawlerName,
                    "A crawler with this name is already registered");
        }

        @SuppressWarnings("unchecked")
        IResourceCrawler<GcpResource> gcpCrawler = (IResourceCrawler<GcpResource>) crawler;
        gcpCrawlers.add(gcpCrawler);
        log.info("Registered GCP crawler: {}", crawlerName);
    }

    @Override
    public boolean unregisterCrawler(String crawlerName) {
        if (crawlerName == null || crawlerName.isBlank()) {
            throw ExceptionUtils.missingParameter("crawlerName", "Crawler name cannot be empty");
        }

        boolean removed = gcpCrawlers.removeIf(crawler -> crawler.getClass().getSimpleName().equals(crawlerName));
        if (removed) {
            log.info("Unregistered GCP crawler: {}", crawlerName);
        }
        return removed;
    }

    @Override
    public List<CloudResourceDTO> discoverResources(String resourceType, String region) {
        // Implementation will be provided by the service layer
        return List.of();
    }

    @Override
    public Optional<CloudResourceDTO> getResource(String resourceId) {
        // Implementation will be provided by the service layer
        return Optional.empty();
    }

    @Override
    public List<CloudResourceDTO> getResourcesByType(String resourceType) {
        // Implementation will be provided by the service layer
        return List.of();
    }

    @Override
    public List<CloudResourceDTO> getResourcesByRegion(String region) {
        // Implementation will be provided by the service layer
        return List.of();
    }

    @Override
    public List<CloudResourceDTO> getResourcesByTags(Map<String, String> tags) {
        // Implementation will be provided by the service layer
        return List.of();
    }

    @Override
    public CloudResourceDTO updateResourceProperties(String resourceId, Map<String, Object> properties) {
        // Implementation will be provided by the service layer
        return null;
    }

    @Override
    public CloudResourceDTO updateResourceTags(String resourceId, Map<String, String> tags) {
        // Implementation will be provided by the service layer
        return null;
    }

    @Override
    public boolean deleteResource(String resourceId) {
        // Implementation will be provided by the service layer
        return false;
    }

    @Override
    public JobStatisticsDTO getJobStatistics() {
        // Implementation will be provided by the service layer
        return null;
    }

    private HealthStatus checkHealth() {
        // Implementation will be provided by the service layer
        return null;
    }

    private String getServiceName() {
        return "GCP Discovery Service";
    }

    @Override
    public List<HealthStatus> getSystemStatus() {
        // Implementation will be provided by the service layer
        return List.of();
    }

    @Override
    public HealthStatus checkServiceHealth(String serviceName) {
        // Implementation will be provided by the service layer
        return null;
    }

    @Override
    public boolean isServiceHealthy(String serviceName) {
        // Implementation will be provided by the service layer
        return false;
    }

    @Override
    public void registerHealthCheck(String serviceName, String displayName, Supplier<HealthStatus> checkSupplier) {
        // Implementation will be provided by the service layer
    }

    private void configureDefaultCrawlerExecutable(DiscoveryJob job) {
        log.warn("Job {} was not configured before start. Configuring with default ResourceCrawlerCallable.",
                job.getJobId());

        ResourceCrawlerCallable callable = new ResourceCrawlerCallable(
                job, crawlerRegistry, catalogService, cloudHierarchyRegistry,cloudProviderHandlers);       

        JobConfiguration config = jobService.configureJob(job);
        config.withDefaultExecution(callable);

        log.info("Auto-configured job {} with default ResourceCrawlerCallable.", job.getJobId());
    }

}   