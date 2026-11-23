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

package com.dlap.discovery.test.util;

import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.job.JobType;
import com.dlap.discovery.job.config.JobConfiguration;
import com.dlap.discovery.job.service.IDiscoveryJobService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Job service that records what its caller did, for tests.
 *
 * <p>{@link #configureJob} returns a real {@link JobConfiguration} and {@link #executeJob}
 * refuses a job that has no executable, exactly like {@code DiscoveryJobService}, so a job
 * only "runs" once its caller has attached one. Hand-rolled instead of a Mockito mock: the
 * Mockito ByteBuddy agent is unstable on this JDK.
 */
public class FakeJobService implements IDiscoveryJobService {

    /** Number of times {@link #createJob} was called. */
    public final AtomicInteger createCount = new AtomicInteger();
    /** Every job {@link #createJob} returned, in order. */
    public final List<DiscoveryJob> created = new ArrayList<>();
    /** Every instance {@link #saveJob} returned, in order. */
    public final List<DiscoveryJob> saved = new ArrayList<>();
    /** The job most recently accepted by {@link #executeJob}. */
    public volatile DiscoveryJob executed;
    /** When set, {@link #executeJob} throws it instead of running the job. */
    public volatile RuntimeException executeFailure;
    /** When true, {@link #saveJob} returns a copy, as JPA merge does for a detached entity. */
    public boolean saveReturnsCopy;
    /** When set, {@link #executeJob} counts down {@link #entered} and waits for this latch. */
    public volatile CountDownLatch blockExecution;
    /** Counted down when a blocked {@link #executeJob} call has started. */
    public final CountDownLatch entered = new CountDownLatch(1);

    @Override
    public DiscoveryJob createJob(JobType type, String accountId, CloudProvider provider, String jobName) {
        createCount.incrementAndGet();
        DiscoveryJob job = new DiscoveryJob(UUID.randomUUID());
        job.setJobType(type);
        job.setAccountId(accountId);
        job.setCloudProvider(provider);
        job.setJobName(jobName);
        created.add(job);
        return job;
    }

    @Override
    public Future<?> executeJob(DiscoveryJob job) {
        if (job.getExecutable() == null) {
            throw new IllegalStateException("Job cannot be executed without configuration. Use configureJob() first.");
        }
        if (executeFailure != null) {
            throw executeFailure;
        }
        this.executed = job;
        CountDownLatch block = blockExecution;
        if (block != null) {
            entered.countDown();
            try {
                block.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public DiscoveryJob saveJob(DiscoveryJob job) {
        DiscoveryJob result = job;
        if (saveReturnsCopy) {
            result = new DiscoveryJob(job.getJobId());
            result.setJobType(job.getJobType());
            result.setAccountId(job.getAccountId());
            result.setCloudProvider(job.getCloudProvider());
            result.setJobName(job.getJobName());
            result.setParameters(job.getParameters());
            if (job.getStatus() == JobStatus.FAILED) {
                result.setStatus(JobStatus.FAILED);
            }
        }
        saved.add(result);
        return result;
    }

    @Override
    public JobConfiguration configureJob(DiscoveryJob job) {
        return new JobConfiguration(job);
    }

    @Override
    public void deleteJob(UUID jobId) {
    }

    @Override
    public Optional<DiscoveryJob> getJob(UUID jobId) {
        return Optional.empty();
    }

    @Override
    public List<DiscoveryJob> getAllJobs() {
        return List.of();
    }

    @Override
    public List<DiscoveryJob> getJobsByStatus(JobStatus status) {
        return List.of();
    }

    @Override
    public List<DiscoveryJob> getJobsByType(JobType jobType) {
        return List.of();
    }

    @Override
    public List<DiscoveryJob> getJobsByAccount(String accountId) {
        return List.of();
    }

    @Override
    public List<DiscoveryJob> getJobsByProvider(CloudProvider provider) {
        return List.of();
    }

    @Override
    public Page<DiscoveryJob> findAll(String cloudConnectionId, String status, String type, Pageable pageable) {
        return Page.empty();
    }
}
