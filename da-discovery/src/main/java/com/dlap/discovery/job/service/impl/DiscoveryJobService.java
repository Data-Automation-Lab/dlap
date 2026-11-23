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

package com.dlap.discovery.job.service.impl;

import com.dlap.discovery.common.exception.DiscoveryException;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.common.model.repository.DiscoveryJobRepository;
import com.dlap.discovery.crawler.exception.ErrorCode;
import com.dlap.discovery.job.ExecutionMode;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.job.JobType;
import com.dlap.discovery.job.config.JobConfiguration;
import com.dlap.discovery.job.scheduler.IDiscoveryScheduler;
import com.dlap.discovery.job.service.IDiscoveryJobService;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
// Added imports for Page, Pageable, Specification, Predicate, etc.
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementation for managing DiscoveryJob entities and their
 * execution.
 */
@Service
@Transactional
public class DiscoveryJobService implements IDiscoveryJobService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscoveryJobService.class);

    private final DiscoveryJobRepository jobRepository;
    private final IDiscoveryScheduler scheduler;
    private final ApplicationContext applicationContext; // To get JobConfiguration bean

    public DiscoveryJobService(DiscoveryJobRepository jobRepository, IDiscoveryScheduler scheduler, ApplicationContext applicationContext) {
        this.jobRepository = jobRepository;
        this.scheduler = scheduler;
        this.applicationContext = applicationContext;
    }

    @Override
    public DiscoveryJob createJob(JobType type, String accountId, CloudProvider provider, String jobName) {
        DiscoveryJob job = new DiscoveryJob();
        job.setJobName(jobName != null ? jobName : "Discovery Job - " + type + " - " + accountId);
        job.setJobType(type);
        job.setAccountId(accountId);
        job.setCloudProvider(provider);
        job.setStatus(JobStatus.CREATED); // Initial status
        // Default execution mode, can be overridden via configureJob
        //job.setExecutionMode(ExecutionMode.DEFAULT);
        return jobRepository.save(job);
    }

    @Override
    public JobConfiguration configureJob(DiscoveryJob job) {
        // Return a new prototype-scoped bean instance
        return applicationContext.getBean(JobConfiguration.class, job);
    }

    @Override
    public Future<?> executeJob(DiscoveryJob job) {
        if (job.getExecutable() == null) {
            throw new IllegalStateException("Job cannot be executed without configuration. Use configureJob() first.");
        }
        // Optionally update status before submitting
        // job.setStatus(JobStatus.PENDING);
        // saveJob(job); // Ensure state is saved before execution
        return scheduler.executeJob(job);
    }

    @Override
    public DiscoveryJob saveJob(DiscoveryJob job) {
        // Ensure updatedAt is handled by JPA or set here if needed
        return jobRepository.save(job);
    }

    @Override
    public void deleteJob(UUID jobId) {
        jobRepository.deleteById(jobId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DiscoveryJob> getJob(UUID jobId) {
        return jobRepository.findById(jobId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiscoveryJob> getAllJobs() {
        return jobRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiscoveryJob> getJobsByStatus(JobStatus status) {
        return jobRepository.findByStatus(status);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiscoveryJob> getJobsByType(JobType jobType) {
        return jobRepository.findByJobType(jobType);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiscoveryJob> getJobsByAccount(String accountId) {
        return jobRepository.findByAccountId(accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiscoveryJob> getJobsByProvider(CloudProvider provider) {
        return jobRepository.findByCloudProvider(provider);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DiscoveryJob> findAll(String cloudConnectionId, String status, String type, Pageable pageable) {
        Specification<DiscoveryJob> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (status != null && !status.isBlank()) {
                try {
                    JobStatus jobStatus = JobStatus.valueOf(status.toUpperCase());
                    predicates.add(cb.equal(root.get("status"), jobStatus));
                } catch (IllegalArgumentException e) {
                    LOGGER.error("Invalid job status: {}", status);
                    throw new DiscoveryException(ErrorCode.INVALID_JOB_STATUS, "Invalid job status: " + status);
                }
            }

            if (type != null && !type.isBlank()) {
                try {
                    JobType jobType = JobType.valueOf(type.toUpperCase());
                    predicates.add(cb.equal(root.get("jobType"), jobType));
                } catch (IllegalArgumentException e) {
                    LOGGER.error("Invalid job type: {}", type);
                    throw new DiscoveryException(ErrorCode.INVALID_JOB_TYPE, "Invalid job type: " + type);
                }
            }

            if (cloudConnectionId != null && !cloudConnectionId.isBlank()) {
                // Assuming 'parameters' is a JSONB field and we are looking for a specific key-value pair.
                // This uses a PostgreSQL-specific function. Ensure your Hibernate dialect supports it
                // or a custom function is registered.
                // The key is 'originalScanRequest_cloudConnectionId' as per DiscoveryJobController
                Predicate jsonPredicate = cb.equal(
                    cb.function(
                        "jsonb_extract_path_text",
                        String.class,
                        root.get("parameters"),
                        cb.literal("originalScanRequest_cloudConnectionId")
                    ),
                    cloudConnectionId
                );
                predicates.add(jsonPredicate);
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return jobRepository.findAll(spec, pageable);
    }

    // --- Keep or adapt other query methods from the original service ---

    @Transactional(readOnly = true)
    public List<DiscoveryJob> getPeriodicJobs() {
        return jobRepository.findByIsPeriodicJobTrue();
    }

    @Transactional(readOnly = true)
    public List<DiscoveryJob> getOneTimeJobs() {
        return jobRepository.findByIsOneTimeJobTrue();
    }

    @Transactional(readOnly = true)
    public List<DiscoveryJob> getTriggerJobs() {
        return jobRepository.findByIsTriggerJobTrue();
    }

    @Transactional(readOnly = true)
    public List<DiscoveryJob> getScheduledJobs() {
        return jobRepository.findAllScheduledJobs();
    }

    @Transactional(readOnly = true)
    public Optional<DiscoveryJob> getJobByLastExecutionId(String lastExecutionId) {
        return jobRepository.findByLastExecutionId(lastExecutionId);
    }

    @Transactional(readOnly = true)
    public List<DiscoveryJob> getJobsByAccountAndProvider(String accountId, CloudProvider provider) {
        return jobRepository.findByAccountIdAndCloudProvider(accountId, provider);
    }

    @Transactional(readOnly = true)
    public List<DiscoveryJob> getJobsByAccountAndStatus(String accountId, JobStatus status) {
        return jobRepository.findByAccountIdAndStatus(accountId, status);
    }

    @Transactional(readOnly = true)
    public List<DiscoveryJob> getJobsAfter(String accountId, UUID referenceJobId) {
        return jobRepository.findJobsAfter(accountId, referenceJobId);
    }
}
