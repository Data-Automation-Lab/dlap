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

package com.dlap.discovery.crawler.service.azure;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.springframework.stereotype.Service;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.catalog.persistence.IResourceCrawlerRegistry;
import com.dlap.discovery.catalog.service.ICatalogService;
import com.dlap.discovery.providers.azure.auth.AzureAuthenticationServiceImpl;
import com.dlap.discovery.providers.azure.config.AzureConfigService;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.common.service.CloudResourceDTO;
import com.dlap.discovery.common.service.ResourceNotFoundException;
import com.dlap.discovery.common.util.CloudResourceMapper;
import com.dlap.discovery.common.util.JobStatisticsMapper;
import com.dlap.discovery.common.util.health.HealthStatus;
import com.dlap.discovery.crawler.exception.ExceptionUtils;
import com.dlap.discovery.crawler.model.azure.AzureResource;
import com.dlap.discovery.crawler.service.IDiscoveryService;
import com.dlap.discovery.crawler.service.IResourceCrawler;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.job.JobType;
import com.dlap.discovery.job.callable.ResourceCrawlerCallable;
import com.dlap.discovery.job.config.JobConfiguration;
import com.dlap.discovery.job.executor.IJobExecutor;
import com.dlap.discovery.job.service.IDiscoveryJobService;
import com.dlap.discovery.job.service.JobExecutionException;
import com.dlap.discovery.job.service.JobStatisticsDTO;
import com.dlap.discovery.log.service.ILogAnalyzer;
import com.dlap.discovery.common.model.CloudProviderHandler; 

/**
 * Implementation of IDiscoveryService for Microsoft Azure resources.
 * Manages Azure resource discovery jobs and their execution.
 */
@Service("azureDiscoveryService")
@ConditionalOnProperty(name = "cloud.provider.azure.enabled", havingValue = "true", matchIfMissing = false)
public class AzureDiscoveryServiceImpl implements IDiscoveryService<AzureResource> {

    private static final Logger log = LoggerFactory.getLogger(AzureDiscoveryServiceImpl.class);

    private final List<IResourceCrawler<AzureResource>> azureCrawlers;
    private final IJobExecutor jobExecutor;
    private final CloudHierarchyRegistry cloudHierarchyRegistry;
    private final CloudResourceMapper cloudResourceMapper;
    private final JobStatisticsMapper jobStatisticsMapper;
    private final IResourceCrawlerRegistry crawlerRegistry;
    private final List<ILogAnalyzer> logAnalyzers;
    private final ICatalogService catalogService;
    private final IDiscoveryJobService jobService;
    private final AzureConfigService azureConfigService;
    private final AzureAuthenticationServiceImpl authenticationService;
    private final List<CloudProviderHandler> cloudProviderHandlers; 

    @Autowired
    public AzureDiscoveryServiceImpl(List<IResourceCrawler<AzureResource>> azureCrawlers,
            IJobExecutor jobExecutor, CloudHierarchyRegistry cloudHierarchyRegistry,
            CloudResourceMapper cloudResourceMapper, JobStatisticsMapper jobStatisticsMapper,
            IResourceCrawlerRegistry crawlerRegistry, List<ILogAnalyzer> logAnalyzers,
            ICatalogService catalogService, IDiscoveryJobService jobService,
            AzureConfigService azureConfigService, AzureAuthenticationServiceImpl authenticationService,
            List<CloudProviderHandler> cloudProviderHandlers) {
        this.cloudProviderHandlers = cloudProviderHandlers;
        this.azureCrawlers = azureCrawlers;
        this.jobExecutor = jobExecutor;
        this.cloudHierarchyRegistry = cloudHierarchyRegistry;
        this.cloudResourceMapper = cloudResourceMapper;
        this.jobStatisticsMapper = jobStatisticsMapper;
        this.crawlerRegistry = crawlerRegistry;
        this.logAnalyzers = logAnalyzers;
        this.catalogService = catalogService;
        this.jobService = jobService;
        this.azureConfigService = azureConfigService;
        this.authenticationService = authenticationService;
    }

    @Override
    public String getCloudProvider() {
        return CloudProvider.AZURE.name();
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.AZURE;
    }

    @Override
    public List<ResourceType> getSupportedResourceTypes() {
        return azureCrawlers.stream()
                .flatMap(crawler -> crawler.getSupportedResourceTypes().stream())
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public boolean isResourceTypeSupported(ResourceType resourceType) {
        return azureCrawlers.stream()
                .anyMatch(crawler -> crawler.getSupportedResourceTypes().contains(resourceType));
    }

    @Override
    public DiscoveryJob createDiscoveryCrawlerJob(String accountId, List<String> resourceTypeIds,
            Map<String, String> parameters, String jobName) {
        // Validate inputs
        if (accountId == null || accountId.isBlank()) {
            throw ExceptionUtils.missingParameter("accountId", "Account ID (Subscription ID) cannot be empty");
        }
        if (resourceTypeIds == null || resourceTypeIds.isEmpty()) {
            log.warn(
                    "No specific resource type IDs provided for Azure job creation for account {}. Will attempt to crawl all supported types.",
                    accountId);
            // Allow empty list, might mean crawl all supported types for the provider
        }

        log.info("Creating AZURE Resource Crawler job definition for account: {}, types: {}", accountId,
                resourceTypeIds != null ? resourceTypeIds : "All");

        // Use JobService to create the job
        DiscoveryJob job = jobService.createJob(JobType.RESOURCE_CRAWLER, accountId, CloudProvider.AZURE, jobName);

        // Convert parameters and add resourceTypeIds
        Map<String, Object> jobParams = new HashMap<>(parameters != null ? parameters : Map.of());
        // Use a consistent key for parameters map
        if (resourceTypeIds != null) {
            jobParams.put("resourceTypesToCrawl", resourceTypeIds);
        }
        job.setParameters(jobParams);
        // Default execution mode is handled by DiscoveryJob entity

        // Validate resource types against registry (optional, but good practice)
        if (resourceTypeIds != null) {
            List<ResourceType> validTypes = resourceTypeIds.stream()
                    .map(cloudHierarchyRegistry::getResourceType)
                    .filter(Objects::nonNull)
                    .toList();
            if (validTypes.size() < resourceTypeIds.size()) {
                log.warn("Some provided resource type IDs for job {} were not found in the registry.", job.getJobId());
                // Optionally add warning to job context if needed
            }
        }
        configureDefaultCrawlerExecutable(job);

        // Save and return the created job entity
        return jobService.saveJob(job);
    }

    @Override
    public CompletableFuture<DiscoveryJob> startDiscoveryJobAsync(UUID jobId) {
        log.info("Attempting to start AZURE job asynchronously: {}", jobId);
        DiscoveryJob initialJob = jobService.getJob(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob", jobId.toString()));

        if (initialJob.getStatus() != JobStatus.CREATED && initialJob.getStatus() != JobStatus.PENDING) {
            log.warn("Job {} cannot be started asynchronously, current status: {}", jobId, initialJob.getStatus());
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Job cannot be started, status is " + initialJob.getStatus()));
        }

        // Ensure executable is configured
        DiscoveryJob jobToExecute; // Variable to hold the potentially reconfigured job
        if (initialJob.getExecutable() == null) {
            log.warn("Job {} executable not configured, attempting default crawler config.", jobId);
            try {
                configureDefaultCrawlerExecutable(initialJob);
                // Re-fetch the job as configureDefaultCrawlerExecutable saves it
                jobToExecute = jobService.getJob(jobId).orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob",
                        jobId.toString(), new Throwable("Job not found after config")));
            } catch (Exception e) {
                log.error("Failed to auto-configure executable for job {}. Cannot start.", jobId, e);
                return CompletableFuture.failedFuture(e);
            }
        } else {
            jobToExecute = initialJob;
        }

        // Make it final for the lambda
        final DiscoveryJob finalJobToExecute = jobToExecute;

        // Use the jobService to execute
        return CompletableFuture.supplyAsync(() -> {
            log.info("Submitting AZURE job {} for asynchronous execution.", jobId);
            Future<?> executionFuture = jobService.executeJob(finalJobToExecute);
            try {
                executionFuture.get(); // Wait for the execution result
                log.info("Asynchronous execution future completed for AZURE job {}. Fetching final status.", jobId);
                return jobService.getJob(jobId)
                        .orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob", jobId.toString()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Asynchronous execution interrupted for AZURE job {}", jobId, e);
                updateJobStatusOnError(jobId, "Execution interrupted");
                throw new JobExecutionException("Execution interrupted for job: " + jobId, e);
            } catch (Exception e) {
                log.error("Asynchronous execution failed for AZURE job {}", jobId, e);
                updateJobStatusOnError(jobId, e.getMessage());
                throw new JobExecutionException("Execution failed for job: " + jobId, e);
            }
        });
    }

    @Override
    public DiscoveryJob startDiscoveryJob(UUID jobId, Map<String, String> parameters) {
        log.info("Attempting to start AZURE job synchronously: {} with params: {}", jobId, parameters);
        DiscoveryJob job = jobService.getJob(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob", jobId.toString()));

        if (job.getStatus() != JobStatus.CREATED) {
            log.warn("Job {} cannot be started synchronously, current status: {}", jobId, job.getStatus());
            throw new IllegalStateException("Job cannot be started, status is " + job.getStatus());
        }

        // Update parameters before configuration check
        if (parameters != null && !parameters.isEmpty()) {
            job.getParameters().putAll(parameters);
            job = jobService.saveJob(job);
        }

        // Ensure executable is configured
        DiscoveryJob jobToExecute; // Variable to hold the potentially reconfigured job
        if (job.getExecutable() == null) {
            log.warn("Job {} executable not configured, attempting default crawler config.", jobId);
            try {
                configureDefaultCrawlerExecutable(job);
                // Re-fetch the job as configureDefaultCrawlerExecutable saves it
                jobToExecute = jobService.getJob(jobId).orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob",
                        jobId.toString(), new Throwable("Job disappeared after config")));
            } catch (Exception e) {
                log.error("Failed to auto-configure executable for job {}. Cannot start.", jobId, e);
                throw new IllegalStateException("Failed to configure job executable before starting.", e);
            }
        } else {
            jobToExecute = job;
        }

        log.info("Submitting AZURE job {} for synchronous execution.", jobId);
        Future<?> executionFuture = jobService.executeJob(jobToExecute);
        try {
            executionFuture.get(); // Wait for completion
            log.info("Synchronous execution completed for AZURE job {}. Fetching final status.", jobId);
            return jobService.getJob(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob", jobId.toString()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Synchronous execution interrupted for AZURE job {}", jobId, e);
            updateJobStatusOnError(jobId, "Execution interrupted");
            throw new JobExecutionException("Execution interrupted for job: " + jobId, e);
        } catch (Exception e) {
            log.error("Synchronous execution failed for AZURE job {}", jobId, e);
            updateJobStatusOnError(jobId, e.getMessage());
            throw new JobExecutionException("Execution failed for job: " + jobId, e);
        }
    }

    @Override
    public Optional<DiscoveryJob> getDiscoveryJob(String jobId) {
        try {
            UUID jobUUID = UUID.fromString(jobId);
            return jobService.getJob(jobUUID)
                    .filter(job -> job.getCloudProvider() == CloudProvider.AZURE);
        } catch (IllegalArgumentException e) {
            log.error("Invalid UUID format for getDiscoveryJob: {}", jobId);
            return Optional.empty();
        }
    }

    @Override
    public List<DiscoveryJob> getAllDiscoveryJobs() {
        return jobService.getAllJobs().stream()
                .filter(job -> job.getCloudProvider() == CloudProvider.AZURE)
                .toList();
    }

    @Override
    public List<DiscoveryJob> getDiscoveryJobsByAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw ExceptionUtils.missingParameter("accountId", "Account ID (Subscription ID) cannot be empty");
        }
        return jobService.getJobsByAccount(accountId).stream()
                .filter(job -> job.getCloudProvider() == CloudProvider.AZURE)
                .toList();
    }

    @Override
    public boolean cancelDiscoveryJob(UUID jobId) {
        log.info("Attempting to cancel Azure DiscoveryJob: {}", jobId);
        // Delegate cancellation to the appropriate executor via jobExecutor
        // Need logic to determine which executor instance handles this job if not
        // DEFAULT
        return jobExecutor.cancelJob(jobId); // Assuming jobExecutor holds state or routes correctly
    }

    @Override
    public boolean pauseDiscoveryJob(UUID jobId) {
        log.warn("Pause operation potentially not supported for Azure job: {}", jobId);
        return jobExecutor.pauseJob(jobId);
    }

    @Override
    public boolean resumeDiscoveryJob(UUID jobId) {
        log.warn("Resume operation potentially not supported for Azure job: {}", jobId);
        return jobExecutor.resumeJob(jobId);
    }

    @Override
    public List<IResourceCrawler<AzureResource>> getCrawlers() {
        return List.copyOf(azureCrawlers);
    }

    @Override
    public List<IResourceCrawler<AzureResource>> getCrawlersByProvider(String cloudProvider) {
        if (getProvider().name().equalsIgnoreCase(cloudProvider)) {
            return getCrawlers();
        }
        return List.of();
    }

    @Override
    public void registerCrawler(IResourceCrawler<AzureResource> crawler) {
        // Implementation needed - consider if list is mutable or delegate to registry
        log.warn("registerCrawler not fully implemented for AzureDiscoveryService.");
        // Example (if list is mutable):
        // if (crawler != null && !azureCrawlers.contains(crawler)) {
        // azureCrawlers.add(crawler);
        // }
    }

    @Override
    public boolean unregisterCrawler(String crawlerName) {
        // Implementation needed
        log.warn("unregisterCrawler not fully implemented for AzureDiscoveryService.");
        return false;
        // Example (if list is mutable):
        // return azureCrawlers.removeIf(c ->
        // c.getClass().getSimpleName().equals(crawlerName));
    }

    // --- Resource methods delegate to CatalogService or other services ---
    @Override
    public List<CloudResourceDTO> discoverResources(String rt, String r) {
        return List.of();
    }

    @Override
    public Optional<CloudResourceDTO> getResource(String id) {
        return Optional.empty();
    }

    @Override
    public List<CloudResourceDTO> getResourcesByType(String rt) {
        return List.of();
    }

    @Override
    public List<CloudResourceDTO> getResourcesByRegion(String r) {
        return List.of();
    }

    @Override
    public List<CloudResourceDTO> getResourcesByTags(Map<String, String> t) {
        return List.of();
    }

    @Override
    public CloudResourceDTO updateResourceProperties(String id, Map<String, Object> p) {
        return null;
    }

    @Override
    public CloudResourceDTO updateResourceTags(String id, Map<String, String> t) {
        return null;
    }

    @Override
    public boolean deleteResource(String id) {
        return false;
    }

    @Override
    public JobStatisticsDTO getJobStatistics() {
        return null;
    }
    
    // --- Health check methods ---
    @Override
    public List<HealthStatus> getSystemStatus() {
        return List.of();
    }

    @Override
    public HealthStatus checkServiceHealth(String sn) {
        return null;
    }

    @Override
    public boolean isServiceHealthy(String sn) {
        return false;
    }

    @Override
    public void registerHealthCheck(String sn, String dn, Supplier<HealthStatus> cs) {
    }

    // --- Helper Methods ---
    private void updateJobStatusOnError(UUID jobId, String errorMessage) {
        try {
            jobService.getJob(jobId).ifPresent(jobToUpdate -> {
                if (jobToUpdate.getStatus() != JobStatus.COMPLETED && jobToUpdate.getStatus() != JobStatus.FAILED) {
                    jobToUpdate.setStatus(JobStatus.FAILED);
                    jobToUpdate.setErrorMessage(errorMessage != null ? errorMessage : "Execution failed");
                    jobService.saveJob(jobToUpdate);
                }
            });
        } catch (Exception e) {
            log.error("Failed to update job {} status to FAILED after execution error", jobId, e);
        }
    }

    private void configureDefaultCrawlerExecutable(DiscoveryJob job) {
        log.warn("Job {} executable not configured, configuring with default ResourceCrawlerCallable.", job.getJobId());
        String accountId = job.getAccountId();
        @SuppressWarnings("unchecked")
        List<String> resourceTypeIds = (List<String>) job.getParameters().getOrDefault("resourceTypesToCrawl",
                List.of());       

        // Instantiate ResourceCrawlerCallable
        ResourceCrawlerCallable callable = new ResourceCrawlerCallable(
                job, crawlerRegistry, catalogService, cloudHierarchyRegistry, cloudProviderHandlers);

        // Use JobConfiguration bean (obtained via jobService) to configure
        JobConfiguration config = jobService.configureJob(job);
        config.withDefaultExecution(callable);
        // Save the configured job (build() is called implicitly or explicitly depending
        // on design)
        jobService.saveJob(job); // Ensure the job with the executable is saved
        log.info("Auto-configured AZURE job {} with default ResourceCrawlerCallable.", job.getJobId());
    }
}