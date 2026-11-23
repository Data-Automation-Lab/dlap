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

package com.dlap.discovery.service.stats.impl;

import com.dlap.discovery.client.feign.CatalogServiceClient;
import com.dlap.discovery.client.rest.dto.DiscoveryStatsDTO;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.job.JobStatus;
import com.dlap.discovery.job.service.IDiscoveryJobService;
import com.dlap.discovery.stats.service.IDiscoveryStatsService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class DiscoveryStatsServiceImpl implements IDiscoveryStatsService {

    private final IDiscoveryJobService jobService;
    private final CatalogServiceClient catalogServiceClient; // Will be a Feign client

    @Override
    public DiscoveryStatsDTO getDiscoveryStats() {
        log.debug("Calculating discovery statistics.");

        List<DiscoveryJob> allJobs = jobService.getAllJobs(); // Assuming this fetches all necessary job data

        long totalScansSubmitted = allJobs.size();
        long scansPending = allJobs.stream().filter(j -> j.getStatus() == JobStatus.PENDING).count();
        long scansRunning = allJobs.stream().filter(j -> j.getStatus() == JobStatus.RUNNING).count();
        long scansSucceeded = allJobs.stream().filter(j -> j.getStatus() == JobStatus.COMPLETED).count();
        long scansFailed = allJobs.stream().filter(j -> j.getStatus() == JobStatus.FAILED).count();
        long scansCancelled = allJobs.stream().filter(j -> j.getStatus() == JobStatus.CANCELLED).count(); // Assuming CANCELLED status exists

        double averageScanDurationSeconds = allJobs
            .stream()
            .filter(j -> j.getStatus() == JobStatus.COMPLETED && j.getStartedAt().isPresent() && j.getCompletedAt().isPresent())
            .mapToLong(j -> j.getCompletedAt().get().getEpochSecond() - j.getStartedAt().get().getEpochSecond())
            .average()
            .orElse(0.0);

        Instant now = Instant.now();
        Instant twentyFourHoursAgo = now.minus(24, ChronoUnit.HOURS);
        Instant sevenDaysAgo = now.minus(7, ChronoUnit.DAYS);

        long assetsAddedLast24h = sumAssetsAddedInPeriod(allJobs, twentyFourHoursAgo, now);
        long assetsAddedLast7d = sumAssetsAddedInPeriod(allJobs, sevenDaysAgo, now);

        // For "assetsDiscovered", if JobStatistics has "resourcesDiscovered" this can be used.
        // Assuming DiscoveryJob.JobStatistics.getResourcesDiscovered() provides this.
        long assetsDiscoveredLast24h = sumAssetsDiscoveredInPeriod(allJobs, twentyFourHoursAgo, now);
        long assetsDiscoveredLast7d = sumAssetsDiscoveredInPeriod(allJobs, sevenDaysAgo, now);

        Long totalAssetsInCatalog = 0L;
        try {
            totalAssetsInCatalog = catalogServiceClient.getTotalAssetsInCatalog();
        } catch (Exception e) {
            log.warn("Failed to fetch totalAssetsInCatalog from da-catalog: {}. Returning 0 for this metric.", e.getMessage());
            // Keep totalAssetsInCatalog as 0 or some indicator of unavailability
        }

        return DiscoveryStatsDTO
            .builder()
            .totalScansSubmitted(totalScansSubmitted)
            .scansPending(scansPending)
            .scansRunning(scansRunning)
            .scansSucceeded(scansSucceeded)
            .scansFailed(scansFailed)
            .scansCancelled(scansCancelled)
            .averageScanDurationSeconds(averageScanDurationSeconds)
            .totalAssetsInCatalog(totalAssetsInCatalog)
            .assetsAddedToCatalogLast24h(assetsAddedLast24h)
            .assetsAddedToCatalogLast7d(assetsAddedLast7d)
            .assetsDiscoveredLast24h(assetsDiscoveredLast24h)
            .assetsDiscoveredLast7d(assetsDiscoveredLast7d)
            .build();
    }

    private long sumAssetsAddedInPeriod(List<DiscoveryJob> jobs, Instant startTime, Instant endTime) {
        return jobs
            .stream()
            .filter(j -> j.getStatus() == JobStatus.COMPLETED && j.getCompletedAt().isPresent())
            .filter(j -> !j.getCompletedAt().get().isBefore(startTime) && j.getCompletedAt().get().isBefore(endTime))
            .map(DiscoveryJob::getStatistics) // Assumes getStatistics() is never null after COMPLETED
            .filter(Objects::nonNull)
            .mapToLong(DiscoveryJob.JobStatistics::getResourcesAdded)
            .sum();
    }

    private long sumAssetsDiscoveredInPeriod(List<DiscoveryJob> jobs, Instant startTime, Instant endTime) {
        return jobs
            .stream()
            .filter(j -> j.getCompletedAt().isPresent()) // Consider all jobs that have completed, regardless of status for "discovered"
            .filter(j -> !j.getCompletedAt().get().isBefore(startTime) && j.getCompletedAt().get().isBefore(endTime))
            .map(DiscoveryJob::getStatistics)
            .filter(Objects::nonNull)
            .mapToLong(DiscoveryJob.JobStatistics::getResourcesDiscovered) // Assumes this field exists and is populated
            .sum();
    }
}
