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

package com.dlap.discovery.job.scheduler.impl;

import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.job.ExecutionMode;
import com.dlap.discovery.job.executor.IJobExecutor;
import com.dlap.discovery.job.scheduler.IDiscoveryScheduler;
import com.dlap.discovery.job.service.ExecutorNotFoundException;
import com.dlap.discovery.job.service.JobExecutionException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

/**
 * Default implementation of IDiscoveryScheduler.
 * Uses Spring's TaskScheduler to manage job scheduling and maintains a registry
 * of executors.
 */
@Service
public class DefaultDiscoveryScheduler implements IDiscoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DefaultDiscoveryScheduler.class);

    // TODO: This will be single point of failure as job schedule is stored in
    // memory,
    // we should change this to persistant storage post MVP.
    private final TaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final Map<ExecutionMode, IJobExecutor> executors = new ConcurrentHashMap<>();
    private final Map<String, DiscoveryJob> scheduledJobs = new ConcurrentHashMap<>();

    @Autowired
    public DefaultDiscoveryScheduler(TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    @Override
    public boolean scheduleJob(DiscoveryJob job) {
        if (job == null) {
            throw new IllegalArgumentException("Job must not be null");
        }

        String scheduleInfo = job.getScheduleInfo().orElse(null);
        if (scheduleInfo == null) {
            log.warn("Job {} has no schedule information", job.getJobId());
            return false;
        }

        try {
            // Schedule the job using the provided schedule info
            ScheduledFuture<?> future = taskScheduler.schedule(() -> executeJob(job), new CronTrigger(scheduleInfo));

            scheduledTasks.put(job.getJobId().toString(), future);
            scheduledJobs.put(job.getJobId().toString(), job);
            log.info("Scheduled job {} with schedule {}", job.getJobId(), scheduleInfo);
            return true;
        } catch (Exception e) {
            log.error("Failed to schedule job {}", job.getJobId(), e);
            return false;
        }
    }

    @Override
    public Future<?> executeJob(DiscoveryJob job) {
        if (job == null) {
            throw new IllegalArgumentException("Job must not be null");
        }

        ExecutionMode executionMode = job.getExecutionMode();
        IJobExecutor executor = executors.get(executionMode);

        if (executor == null) {
            String message = String.format("No executor registered for mode: %s", executionMode);
            log.error(message);
            throw new ExecutorNotFoundException(executionMode, message);
        }

        try {
            log.info("Executing job {} using executor for mode {}", job.getJobId(), executionMode);
            return executor.execute(job);
        } catch (Exception e) {
            String message = String.format("Failed to execute job %s: %s", job.getJobId(), e.getMessage());
            log.error(message, e);
            throw new JobExecutionException(message, e);
        }
    }

    @Override
    public boolean unscheduleJob(String jobId) {
        ScheduledFuture<?> future = scheduledTasks.remove(jobId);
        scheduledJobs.remove(jobId);
        if (future != null) {
            future.cancel(false);
            log.info("Unscheduled job {}", jobId);
            return true;
        }
        return false;
    }

    @Override
    public List<DiscoveryJob> getScheduledJobs() {
        return List.copyOf(scheduledJobs.values());
    }

    @Override
    public DiscoveryJob getScheduledJob(String jobId) {
        return scheduledJobs.get(jobId);
    }

    @Override
    public boolean updateSchedule(String jobId, String scheduleInfo) {
        DiscoveryJob job = scheduledJobs.get(jobId);
        if (job == null) {
            return false;
        }

        // Unschedule the existing job
        unscheduleJob(jobId);

        // Update the schedule info and reschedule
        job.setScheduleInfo(scheduleInfo);

        // Assuming the job entity might need persisting after update
        // This responsibility might lie in the service layer calling this method
        // Example: jobRepository.save(job);

        return scheduleJob(job);
    }

    @Override
    public void registerExecutor(ExecutionMode executionMode, IJobExecutor executor) {
        if (executionMode == null || executor == null) {
            throw new IllegalArgumentException("ExecutionMode and executor must not be null");
        }
        executors.put(executionMode, executor);
        log.info("Registered executor for mode: {}", executionMode);
    }

    @Override
    public boolean unregisterExecutor(ExecutionMode executionMode) {
        IJobExecutor removed = executors.remove(executionMode);
        if (removed != null) {
            log.info("Unregistered executor for mode: {}", executionMode);
            return true;
        }
        return false;
    }

    @Override
    public IJobExecutor getExecutor(ExecutionMode executionMode) {
        return executors.get(executionMode);
    }
}
