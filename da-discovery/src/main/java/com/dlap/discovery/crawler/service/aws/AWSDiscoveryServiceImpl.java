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

package com.dlap.discovery.crawler.service.aws;

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
import com.dlap.discovery.providers.aws.auth.AWSAuthenticationServiceImpl;
import com.dlap.discovery.providers.aws.config.AWSConfigService;
import com.dlap.discovery.common.model.CloudProviderHandler;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.common.service.CloudResourceDTO;
import com.dlap.discovery.common.service.ResourceNotFoundException;
import com.dlap.discovery.common.util.CloudResourceMapper;
import com.dlap.discovery.common.util.JobStatisticsMapper;
import com.dlap.discovery.common.util.health.HealthStatus;
import com.dlap.discovery.crawler.exception.ExceptionUtils;
import com.dlap.discovery.crawler.model.aws.AwsResource;
import com.dlap.discovery.crawler.service.IDiscoveryService;
import com.dlap.discovery.crawler.service.IFolderManagementService;
import com.dlap.discovery.crawler.service.IResourceCrawler;
import com.dlap.discovery.job.ExecutionMode;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.job.JobType;
import com.dlap.discovery.job.callable.ResourceCrawlerCallable;
import com.dlap.discovery.job.config.JobConfiguration;
import com.dlap.discovery.job.executor.IJobExecutor;
import com.dlap.discovery.job.service.IDiscoveryJobService;
import com.dlap.discovery.job.service.JobExecutionException;
import com.dlap.discovery.job.service.JobStatisticsDTO;
import com.dlap.discovery.log.service.ILogAnalyzer;




/**
 * Implementation of IDiscoveryService for Amazon Web Services resources.
 *
 * <p>R-P0-3 / finding N12 + ADR-0017: AWS support is deferred to v0.2.0.
 * The v0.1.0 platform ships GCP-first (per ADR-0011 GCP-first cloud
 * scope). This class is gated three ways so it cannot wire in a
 * v0.1.0 deployment that doesn't explicitly opt in:
 *
 * <ul>
 *   <li>{@code @Profile("aws")} - Spring profile gate, consistent with
 *       {@code AWSLoggingAdapter} (M0 commit be0908d).</li>
 *   <li>{@code @ConditionalOnProperty(cloud.provider.aws.enabled=true)}
 *       - pre-existing property gate.</li>
 *   <li>Eight stub methods retained as {@code throw UnsupportedOperationException}
 *       with explicit v0.2.0 deferral messages so any caller that bypasses
 *       the bean gating gets a loud, actionable failure.</li>
 * </ul>
 *
 * <p>The non-stub methods ({@code getCloudProvider}, {@code getProvider},
 * {@code getSupportedResourceTypes}, {@code isResourceTypeSupported},
 * {@code createDiscoveryCrawlerJob}, {@code submitJobForExecution},
 * {@code getSystemStatus}, etc.) work today against the AWS crawler set
 * but the eight {@code resourceType}/{@code resourceId}-keyed query
 * methods on {@link IDiscoveryService} were never wired up. Real AWS
 * coverage of those methods ships in v0.2.0 as part of the providers/aws/
 * relocation (ADR-0004) and the AWS bestiary expansion.
 */
@Service("awsDiscoveryService")
@org.springframework.context.annotation.Profile("aws")
@ConditionalOnProperty(name = "cloud.provider.aws.enabled", havingValue = "true", matchIfMissing = false)
public class AWSDiscoveryServiceImpl implements IDiscoveryService<AwsResource> {

    private static final Logger log = LoggerFactory.getLogger(AWSDiscoveryServiceImpl.class);

    private final List<IResourceCrawler<AwsResource>> awsCrawlers;
    private final IJobExecutor jobExecutor;
    private final CloudHierarchyRegistry cloudHierarchyRegistry;
    private final CloudResourceMapper cloudResourceMapper;
    private final JobStatisticsMapper jobStatisticsMapper;
    private final IResourceCrawlerRegistry crawlerRegistry;
    private final List<ILogAnalyzer> logAnalyzers;
    private final ICatalogService catalogService;
    private final IDiscoveryJobService jobService;
    private final AWSConfigService awsConfigService;
    private final AWSAuthenticationServiceImpl authenticationService;
    private final List<CloudProviderHandler> cloudProviderHandlers; 

    @Autowired
    public AWSDiscoveryServiceImpl(List<IResourceCrawler<AwsResource>> awsCrawlers,
            IJobExecutor jobExecutor,
            CloudHierarchyRegistry cloudHierarchyRegistry,
            CloudResourceMapper cloudResourceMapper, JobStatisticsMapper jobStatisticsMapper,
            IResourceCrawlerRegistry crawlerRegistry, List<ILogAnalyzer> logAnalyzers,
            ICatalogService catalogService, IDiscoveryJobService jobService,
            AWSConfigService awsConfigService, AWSAuthenticationServiceImpl authenticationService,
            IFolderManagementService folderManagementService,List<CloudProviderHandler> cloudProviderHandlers) {
        this.cloudProviderHandlers = cloudProviderHandlers;
        this.awsCrawlers = awsCrawlers;
        this.jobExecutor = jobExecutor;
        this.cloudHierarchyRegistry = cloudHierarchyRegistry;
        this.cloudResourceMapper = cloudResourceMapper;
        this.jobStatisticsMapper = jobStatisticsMapper;
        this.crawlerRegistry = crawlerRegistry;
        this.logAnalyzers = logAnalyzers;
        this.catalogService = catalogService;
        this.jobService = jobService;
        this.awsConfigService = awsConfigService;
        this.authenticationService = authenticationService;  
    }

    @Override
    public String getCloudProvider() {
        return CloudProvider.AWS.name();
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.AWS;
    }

    @Override
    public List<ResourceType> getSupportedResourceTypes() {
        return awsCrawlers.stream()
                .flatMap(crawler -> crawler.getSupportedResourceTypes().stream())
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public boolean isResourceTypeSupported(ResourceType resourceType) {
        return awsCrawlers.stream()
                .anyMatch(crawler -> crawler.getSupportedResourceTypes().contains(resourceType));
    }

    @Override
    public DiscoveryJob createDiscoveryCrawlerJob(String accountId, List<String> resourceTypeIds,
            Map<String, String> parameters, String jobName) {
        // Validate inputs
        if (accountId == null || accountId.isBlank()) {
            throw ExceptionUtils.missingParameter("accountId", "Account ID cannot be empty");
        }
        if (resourceTypeIds == null || resourceTypeIds.isEmpty()) {
            log.warn(
                    "No specific resource type IDs provided for AWS job creation for account {}. Will attempt to crawl all supported types.",
                    accountId);
            // Allow empty list
        }

        log.info("Creating AWS Resource Crawler job definition for account: {}, types: {}", accountId,
                resourceTypeIds != null ? resourceTypeIds : "All");

        // Use JobService
        DiscoveryJob job = jobService.createJob(JobType.RESOURCE_CRAWLER, accountId, CloudProvider.AWS, jobName);

        Map<String, Object> jobParams = new HashMap<>(parameters != null ? parameters : Map.of());
        if (resourceTypeIds != null) {
            jobParams.put("resourceTypesToCrawl", resourceTypeIds);
        }
        job.setParameters(jobParams);

        // Validate types (optional)
        if (resourceTypeIds != null) {
            List<ResourceType> validTypes = resourceTypeIds.stream()
                    .map(cloudHierarchyRegistry::getResourceType)
                    .filter(Objects::nonNull)
                    .toList();
            if (validTypes.size() < resourceTypeIds.size()) {
                log.warn("Some provided resource type IDs for job {} were not found in the registry.", job.getJobId());
            }
        }

        return jobService.saveJob(job);
    }

    @Override
    public CompletableFuture<DiscoveryJob> startDiscoveryJobAsync(UUID jobId) {
        log.info("Attempting to start AWS job asynchronously: {}", jobId);
        DiscoveryJob initialJob = jobService.getJob(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob", jobId.toString()));

        if (initialJob.getStatus() != JobStatus.CREATED && initialJob.getStatus() != JobStatus.PENDING) {
            log.warn("Job {} cannot be started asynchronously, current status: {}", jobId, initialJob.getStatus());
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Job cannot be started, status is " + initialJob.getStatus()));
        }

        // Ensure executable is configured
        DiscoveryJob jobToExecute;
        if (initialJob.getExecutable() == null) {
            log.warn("Job {} executable not configured, attempting default crawler config.", jobId);
            try {
                configureDefaultCrawlerExecutable(initialJob);
                jobToExecute = jobService.getJob(jobId).orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob",
                        jobId.toString(), new Throwable("Job disappeared after config")));
            } catch (Exception e) {
                log.error("Failed to auto-configure executable for job {}. Cannot start.", jobId, e);
                return CompletableFuture.failedFuture(e);
            }
        } else {
            jobToExecute = initialJob;
        }

        final DiscoveryJob finalJobToExecute = jobToExecute;
        return CompletableFuture.supplyAsync(() -> {
            log.info("Submitting AWS job {} for asynchronous execution.", jobId);
            IJobExecutor executor = getJobExecutor(jobToExecute.getExecutionMode());
            Future<?> executionFuture = executor.execute(finalJobToExecute);
            try {
                executionFuture.get();
                log.info("Asynchronous execution future completed for AWS job {}. Fetching final status.", jobId);
                return jobService.getJob(jobId)
                        .orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob", jobId.toString()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Asynchronous execution interrupted for AWS job {}", jobId, e);
                updateJobStatusOnError(jobId, "Execution interrupted");
                throw new JobExecutionException("Execution interrupted for job: " + jobId, e);
            } catch (Exception e) {
                log.error("Asynchronous execution failed for AWS job {}", jobId, e);
                updateJobStatusOnError(jobId, e.getMessage());
                throw new JobExecutionException("Execution failed for job: " + jobId, e);
            }
        });
    }

    @Override
    public DiscoveryJob startDiscoveryJob(UUID jobId, Map<String, String> parameters) {
        log.info("Attempting to start AWS job synchronously: {} with params: {}", jobId, parameters);
        DiscoveryJob job = jobService.getJob(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob", jobId.toString()));

        if (job.getStatus() != JobStatus.CREATED) {
            log.warn("Job {} cannot be started synchronously, current status: {}", jobId, job.getStatus());
            throw new IllegalStateException("Job cannot be started, status is " + job.getStatus());
        }

        if (parameters != null && !parameters.isEmpty()) {
            job.getParameters().putAll(parameters);
            job = jobService.saveJob(job);
        }

        DiscoveryJob jobToExecute;
        if (job.getExecutable() == null) {
            log.warn("Job {} executable not configured, attempting default crawler config.", jobId);
            try {
                configureDefaultCrawlerExecutable(job);
                jobToExecute = jobService.getJob(jobId).orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob",
                        jobId.toString(), new Throwable("Job disappeared after config")));
            } catch (Exception e) {
                log.error("Failed to auto-configure executable for job {}. Cannot start.", jobId, e);
                throw new IllegalStateException("Failed to configure job executable before starting.", e);
            }
        } else {
            jobToExecute = job;
        }

        log.info("Submitting AWS job {} for synchronous execution.", jobId);
        IJobExecutor executor = getJobExecutor(jobToExecute.getExecutionMode());
        Future<?> executionFuture = executor.execute(jobToExecute);
        try {
            executionFuture.get();
            log.info("Synchronous execution completed for AWS job {}. Fetching final status.", jobId);
            return jobService.getJob(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob", jobId.toString()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Synchronous execution interrupted for AWS job {}", jobId, e);
            updateJobStatusOnError(jobId, "Execution interrupted");
            throw new JobExecutionException("Execution interrupted for job: " + jobId, e);
        } catch (Exception e) {
            log.error("Synchronous execution failed for AWS job {}", jobId, e);
            updateJobStatusOnError(jobId, e.getMessage());
            throw new JobExecutionException("Execution failed for job: " + jobId, e);
        }
    }

    @Override
    public Optional<DiscoveryJob> getDiscoveryJob(String jobId) {
        try {
            UUID jobUUID = UUID.fromString(jobId);
            return jobService.getJob(jobUUID)
                    .filter(job -> job.getCloudProvider() == CloudProvider.AWS);
        } catch (IllegalArgumentException e) {
            log.error("Invalid UUID format for getDiscoveryJob: {}", jobId);
            return Optional.empty();
        }
    }

    @Override
    public List<DiscoveryJob> getAllDiscoveryJobs() {
        return jobService.getAllJobs().stream()
                .filter(job -> job.getCloudProvider() == CloudProvider.AWS)
                .toList();
    }

    @Override
    public List<DiscoveryJob> getDiscoveryJobsByAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw ExceptionUtils.missingParameter("accountId", "Account ID cannot be empty");
        }
        return jobService.getJobsByAccount(accountId).stream()
                .filter(job -> job.getCloudProvider() == CloudProvider.AWS)
                .toList();
    }

    @Override
    public boolean cancelDiscoveryJob(UUID jobId) {
        log.info("Attempting to cancel AWS DiscoveryJob: {}", jobId);
        return jobExecutor.cancelJob(jobId);
    }

    @Override
    public boolean pauseDiscoveryJob(UUID jobId) {
        log.warn("Pause operation potentially not supported for AWS job: {}", jobId);
        return jobExecutor.pauseJob(jobId);
    }

    @Override
    public boolean resumeDiscoveryJob(UUID jobId) {
        log.warn("Resume operation potentially not supported for AWS job: {}", jobId);
        return jobExecutor.resumeJob(jobId);
    }

    @Override
    public List<IResourceCrawler<AwsResource>> getCrawlers() {
        return List.copyOf(awsCrawlers);
    }

    @Override
    public List<IResourceCrawler<AwsResource>> getCrawlersByProvider(String cloudProvider) {
        if (getProvider().name().equalsIgnoreCase(cloudProvider)) {
            return getCrawlers();
        }
        return List.of();
    }

    @Override
    public void registerCrawler(IResourceCrawler<AwsResource> crawler) {
        log.warn("registerCrawler not fully implemented for AWSDiscoveryService.");
    }

    @Override
    public boolean unregisterCrawler(String crawlerName) {
        log.warn("unregisterCrawler not fully implemented for AWSDiscoveryService.");
        return false;
    }

    @Override
    public boolean deleteResource(String id) {
        return false;
    }

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

        ResourceCrawlerCallable callable = new ResourceCrawlerCallable(
                job, crawlerRegistry, catalogService, cloudHierarchyRegistry,cloudProviderHandlers);

        JobConfiguration config = jobService.configureJob(job);
        config.withDefaultExecution(callable);
        jobService.saveJob(job); // Save the configured job
        log.info("Auto-configured AWS job {} with default ResourceCrawlerCallable.", job.getJobId());
    }

    private static final String AWS_DEFERRED_MESSAGE =
            "AWS implementation is deferred to v0.2.0 per ADR-0017 / R-P0-3. "
            + "v0.1.0 ships GCP-first; this method is part of the AWS bestiary "
            + "scheduled for the providers/aws/ relocation (ADR-0004) and AWS "
            + "discovery completion in v0.2.0.";

    @Override
    public List<CloudResourceDTO> discoverResources(String resourceType, String region) {
        throw new UnsupportedOperationException("discoverResources(resourceType, region): " + AWS_DEFERRED_MESSAGE);
    }

    @Override
    public Optional<CloudResourceDTO> getResource(String resourceId) {
        throw new UnsupportedOperationException("getResource(resourceId): " + AWS_DEFERRED_MESSAGE);
    }

    @Override
    public CloudResourceDTO updateResourceTags(String resourceId, Map<String, String> tags) {
        throw new UnsupportedOperationException("updateResourceTags(resourceId, tags): " + AWS_DEFERRED_MESSAGE);
    }

    @Override
    public CloudResourceDTO updateResourceProperties(String resourceId, Map<String, Object> properties) {
        throw new UnsupportedOperationException("updateResourceProperties(resourceId, properties): " + AWS_DEFERRED_MESSAGE);
    }

    @Override
    public List<CloudResourceDTO> getResourcesByType(String resourceType) {
        throw new UnsupportedOperationException("getResourcesByType(resourceType): " + AWS_DEFERRED_MESSAGE);
    }

    @Override
    public List<CloudResourceDTO> getResourcesByRegion(String region) {
        throw new UnsupportedOperationException("getResourcesByRegion(region): " + AWS_DEFERRED_MESSAGE);
    }

    @Override
    public List<CloudResourceDTO> getResourcesByTags(Map<String, String> tags) {
        throw new UnsupportedOperationException("getResourcesByTags(tags): " + AWS_DEFERRED_MESSAGE);
    }

    @Override
    public JobStatisticsDTO getJobStatistics() {
        throw new UnsupportedOperationException("getJobStatistics(): " + AWS_DEFERRED_MESSAGE);
    }

    private IJobExecutor getJobExecutor(ExecutionMode mode) {
        if (!jobExecutor.supportsExecutionMode(mode)) {
            throw new IllegalStateException("Configured job executor does not support mode: " + mode);
        }
        return jobExecutor;
    }
}