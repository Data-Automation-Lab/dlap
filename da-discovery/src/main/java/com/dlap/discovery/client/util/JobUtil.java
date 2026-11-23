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

package com.dlap.discovery.client.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.job.JobType;

public class JobUtil {
    
    public static int calculateNewAssets(DiscoveryJob job) {
        // Get actual new assets count from job statistics
        DiscoveryJob.JobStatistics stats = job.getStatistics();
        if (stats != null) {
            return stats.getResourcesAdded();
        }
        return 0;
    }

    public static int calculateUpdatedAssets(DiscoveryJob job) {
        // Get actual updated assets count from job statistics
        DiscoveryJob.JobStatistics stats = job.getStatistics();
        if (stats != null) {
            return stats.getResourcesUpdated();
        }
        return 0;
    }

    public static String calculateJobDuration(DiscoveryJob job) {
        // Calculate actual duration between job start and end times
        Optional<Instant> startTime = job.getStartedAt();
        Optional<Instant> endTime = job.getCompletedAt();
        
        if (startTime.isPresent() && endTime.isPresent()) {
            long durationMinutes = ChronoUnit.MINUTES.between(startTime.get(), endTime.get());
            return String.format("%d min", durationMinutes);
        } else if (startTime.isPresent()) {
            // If job is still running, calculate duration until now
            long durationMinutes = ChronoUnit.MINUTES.between(startTime.get(), Instant.now());
            return String.format("%d min (running)", durationMinutes);
        }
        return "N/A";
    }

    public static String getJobEventName(JobType jobType) {
        switch (jobType) {
            case RESOURCE_CRAWLER: return "Full Scan Completed";
            default: return "Discovery Job";
        }
    }

    public static String mapJobStatusToActivityStatus(JobStatus status) {
        switch (status) {
            case COMPLETED: return "Success";
            case RUNNING: return "Partial";
            case FAILED: return "Failed";
            default: return "Success";
        }
    }
} 