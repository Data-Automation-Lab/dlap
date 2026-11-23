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

package com.dlap.discovery.crawler.service.oracle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.catalog.persistence.IResourceCrawlerRegistry;
import com.dlap.discovery.catalog.service.ICatalogService;
import com.dlap.discovery.common.auth.impl.oracle.OracleAuthenticationServiceImpl;
import com.dlap.discovery.common.config.cloud.impl.oracle.OracleConfigService;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.common.service.CloudResourceDTO;
import com.dlap.discovery.common.service.ResourceNotFoundException;
import com.dlap.discovery.common.util.CloudResourceMapper;
import com.dlap.discovery.common.util.JobStatisticsMapper;
import com.dlap.discovery.common.util.health.HealthStatus;
import com.dlap.discovery.crawler.exception.ExceptionUtils;
import com.dlap.discovery.crawler.model.oracle.OracleResource;
import com.dlap.discovery.crawler.service.IDiscoveryService;
import com.dlap.discovery.crawler.service.IResourceCrawler;
import com.dlap.discovery.crawler.service.factory.ResourceCrawlerFactory;
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
 * Implementation of IDiscoveryService for Oracle Cloud Infrastructure
 * resources.
 * Manages Oracle resource discovery jobs and their execution.
 */
@Service("oracleDiscoveryService")
@ConditionalOnProperty(name = "cloud.provider.oci.enabled", havingValue = "true", matchIfMissing = false)
public class OracleDiscoveryServiceImpl implements IDiscoveryService<OracleResource> {

    private static final Logger LOGGER = LoggerFactory.getLogger(OracleDiscoveryServiceImpl.class);

    @Autowired
    private List<IResourceCrawler<OracleResource>> oracleCrawlers;

    @Autowired
    private IJobExecutor jobExecutor;

    @Autowired
    private CloudHierarchyRegistry cloudHierarchyRegistry;

    @Autowired
    private CloudResourceMapper cloudResourceMapper;

    @Autowired
    private JobStatisticsMapper jobStatisticsMapper;

    @Autowired
    private ResourceCrawlerFactory crawlerFactory;

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private IResourceCrawlerRegistry crawlerRegistry;

    @Autowired
    private List<ILogAnalyzer> logAnalyzers;

    @Autowired
    private ICatalogService catalogService;

    @Autowired
    private IDiscoveryJobService jobService;

    @Autowired
    private OracleConfigService oracleConfigService;

    @Autowired
    private OracleAuthenticationServiceImpl authenticationService;

    @Autowired
    private List<CloudProviderHandler> cloudProviderHandler;

    @Override
    public String getCloudProvider() {
        return CloudProvider.OCI.name();
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.OCI;
    }

    @Override
    public List<ResourceType> getSupportedResourceTypes() {
        return crawlerFactory.getSupportedResourceTypes(CloudProvider.OCI);
    }

    @Override
    public boolean isResourceTypeSupported(ResourceType resourceType) {
        return oracleCrawlers.stream()
                .anyMatch(crawler -> crawler.getSupportedResourceTypes().contains(resourceType));
    }

    @Override
    public DiscoveryJob createDiscoveryCrawlerJob(String accountId, List<String> resourceTypeIds,
            Map<String, String> parameters, String jobName) {
        LOGGER.info("Creating ORACLE Resource Crawler job definition for account: {}, types: {}", accountId,
                resourceTypeIds != null ? resourceTypeIds : "All");
        DiscoveryJob job = jobService.createJob(JobType.RESOURCE_CRAWLER, accountId, CloudProvider.OCI, jobName);

        Map<String, Object> jobParams = new HashMap<>();
        if (parameters != null) {
            jobParams.putAll(parameters);
        }
        jobParams.put("resourceTypesToCrawl", resourceTypeIds);
        job.setParameters(jobParams);

        return jobService.saveJob(job);
    }

    @Override
    public CompletableFuture<DiscoveryJob> startDiscoveryJobAsync(UUID jobId) {
        LOGGER.info("Attempting to start ORACLE job asynchronously: {}", jobId);
        DiscoveryJob job = jobService.getJob(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob", jobId.toString()));

        if (job.getStatus() != JobStatus.CREATED && job.getStatus() != JobStatus.PENDING) {
            LOGGER.warn("Job {} cannot be started asynchronously, current status: {}", jobId, job.getStatus());
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Job cannot be started, status is " + job.getStatus()));
        }

        if (job.getExecutable() == null) {
            LOGGER.warn("Job {} executable not configured, attempting default crawler config.", jobId);
            try {
                configureDefaultCrawlerExecutable(job);
            } catch (Exception e) {
                LOGGER.error("Failed to auto-configure executable for job {}. Cannot start.", jobId, e);
                return CompletableFuture.failedFuture(e);
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            LOGGER.info("Submitting ORACLE job {} for asynchronous execution.", jobId);
            Future<?> executionFuture = jobService.executeJob(job);
            try {
                executionFuture.get();
                LOGGER.info("Asynchronous execution future completed for ORACLE job {}. Fetching final status.", jobId);
                return jobService.getJob(jobId)
                        .orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob", jobId.toString()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.error("Asynchronous execution interrupted for ORACLE job {}", jobId, e);
                updateJobStatusOnError(jobId, "Execution interrupted");
                throw new JobExecutionException("Execution interrupted for job: " + jobId, e);
            } catch (Exception e) {
                LOGGER.error("Asynchronous execution failed for ORACLE job {}", jobId, e);
                updateJobStatusOnError(jobId, e.getMessage());
                throw new JobExecutionException("Execution failed for job: " + jobId, e);
            }
        });
    }

    @Override
    public DiscoveryJob startDiscoveryJob(UUID jobId, Map<String, String> parameters) {
        LOGGER.info("Attempting to start ORACLE job synchronously: {} with params: {}", jobId, parameters);
        DiscoveryJob job = jobService.getJob(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob", jobId.toString()));

        if (job.getStatus() != JobStatus.CREATED) {
            LOGGER.warn("Job {} cannot be started synchronously, current status: {}", jobId, job.getStatus());
            throw new IllegalStateException("Job cannot be started, status is " + job.getStatus());
        }

        if (job.getExecutable() == null) {
            LOGGER.warn("Job {} executable not configured, attempting default crawler config.", jobId);
            try {
                configureDefaultCrawlerExecutable(job);
            } catch (Exception e) {
                LOGGER.error("Failed to auto-configure executable for job {}. Cannot start.", jobId, e);
                throw new IllegalStateException("Failed to configure job executable before starting.", e);
            }
        }

        if (parameters != null && !parameters.isEmpty()) {
            job.getParameters().putAll(parameters);
            job = jobService.saveJob(job);
        }

        LOGGER.info("Submitting ORACLE job {} for synchronous execution.", jobId);
        Future<?> executionFuture = jobService.executeJob(job);
        try {
            executionFuture.get();
            LOGGER.info("Synchronous execution completed for ORACLE job {}. Fetching final status.", jobId);
            return jobService.getJob(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("DiscoveryJob", jobId.toString()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("Synchronous execution interrupted for ORACLE job {}", jobId, e);
            updateJobStatusOnError(jobId, "Execution interrupted");
            throw new JobExecutionException("Execution interrupted for job: " + jobId, e);
        } catch (Exception e) {
            LOGGER.error("Synchronous execution failed for ORACLE job {}", jobId, e);
            updateJobStatusOnError(jobId, e.getMessage());
            throw new JobExecutionException("Execution failed for job: " + jobId, e);
        }
    }

    @Override
    public Optional<DiscoveryJob> getDiscoveryJob(String jobId) {
        try {
            UUID jobUUID = UUID.fromString(jobId);
            return jobService.getJob(jobUUID)
                    .filter(job -> job.getCloudProvider() == CloudProvider.OCI);
        } catch (IllegalArgumentException e) {
            LOGGER.error("Invalid UUID format for getDiscoveryJob: {}", jobId);
            return Optional.empty();
        }
    }

    @Override
    public List<DiscoveryJob> getAllDiscoveryJobs() {
        return jobService.getAllJobs().stream()
                .filter(job -> job.getCloudProvider() == CloudProvider.OCI)
                .toList();
    }

    @Override
    public List<DiscoveryJob> getDiscoveryJobsByAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw ExceptionUtils.missingParameter("accountId", "Account ID cannot be empty");
        }

        return jobService.getJobsByAccount(accountId).stream()
                .filter(job -> job.getCloudProvider() == CloudProvider.OCI)
                .toList();
    }

    @Override
    public boolean cancelDiscoveryJob(UUID jobId) {
        LOGGER.info("Attempting to cancel Oracle DiscoveryJob: {}", jobId);
        return jobExecutor.cancelJob(jobId);
    }

    @Override
    public boolean pauseDiscoveryJob(UUID jobId) {
        LOGGER.warn("Pause operation potentially not supported for job: {}", jobId);
        return jobExecutor.pauseJob(jobId);
    }

    @Override
    public boolean resumeDiscoveryJob(UUID jobId) {
        LOGGER.warn("Resume operation potentially not supported for job: {}", jobId);
        return jobExecutor.resumeJob(jobId);
    }

    @Override
    public List<IResourceCrawler<OracleResource>> getCrawlers() {
        return List.copyOf(oracleCrawlers);
    }

    @Override
    public List<IResourceCrawler<OracleResource>> getCrawlersByProvider(String cloudProvider) {
        if (getProvider().name().equalsIgnoreCase(cloudProvider)) {
            return getCrawlers();
        }
        return List.of();
    }

    @Override
    public void registerCrawler(IResourceCrawler<OracleResource> crawler) {
        if (crawler == null) {
            throw ExceptionUtils.missingParameter("crawler", "Crawler cannot be null");
        }
        try {
            if (!oracleCrawlers.contains(crawler)) {
                oracleCrawlers.add(crawler);
                LOGGER.info("Registered Oracle crawler: {}", crawler.getClass().getSimpleName());
            }
        } catch (UnsupportedOperationException e) {
            LOGGER.error("Cannot register crawler, the injected list might be immutable.", e);
            throw new IllegalStateException("Crawler list modification not supported.", e);
        }
    }

    @Override
    public boolean unregisterCrawler(String crawlerName) {
        if (crawlerName == null || crawlerName.isBlank()) {
            throw ExceptionUtils.missingParameter("crawlerName", "Crawler name cannot be empty");
        }
        try {
            boolean removed = oracleCrawlers
                    .removeIf(crawler -> crawler.getClass().getSimpleName().equals(crawlerName));
            if (removed) {
                LOGGER.info("Unregistered Oracle crawler: {}", crawlerName);
            }
            return removed;
        } catch (UnsupportedOperationException e) {
            LOGGER.error("Cannot unregister crawler, the injected list might be immutable.", e);
            throw new IllegalStateException("Crawler list modification not supported.", e);
        }
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
        return "Oracle Discovery Service";
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

    public Set<String> getRegisteredServiceNames() {
        // Implementation will be provided by the service layer
        return Set.of();
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
            LOGGER.error("Failed to update job {} status to FAILED after execution error", jobId, e);
        }
    }

    private void configureDefaultCrawlerExecutable(DiscoveryJob job) {
        LOGGER.warn("Job {} executable not configured, configuring with default ResourceCrawlerCallable.",
                job.getJobId());
        String accountId = job.getAccountId();
        @SuppressWarnings("unchecked")
        List<String> resourceTypeIds = (List<String>) job.getParameters().getOrDefault("resourceTypesToCrawl",
                List.of());

        ResourceCrawlerCallable callable = new ResourceCrawlerCallable(
                job, crawlerRegistry, catalogService, cloudHierarchyRegistry, cloudProviderHandler);

        JobConfiguration config = jobService.configureJob(job);
        config.withDefaultExecution(callable);
        jobService.saveJob(job);
        LOGGER.info("Auto-configured ORACLE job {} with default ResourceCrawlerCallable.", job.getJobId());
    }
}