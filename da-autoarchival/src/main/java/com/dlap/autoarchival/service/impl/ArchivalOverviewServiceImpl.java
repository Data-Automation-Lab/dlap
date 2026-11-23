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

package com.dlap.autoarchival.service.impl;

import com.dlap.autoarchival.dto.ArchivalKpisDTO;
import com.dlap.autoarchival.dto.ArchivalOverviewResponseDTO;
import com.dlap.autoarchival.dto.ArchivalStatusDataDTO;
import com.dlap.autoarchival.dto.CostSavingsTrendDTO;
import com.dlap.autoarchival.dto.RecentActivityDTO;
import com.dlap.autoarchival.dto.StorageConsumerDTO;
import com.dlap.autoarchival.executor.ArchiveRuleEntity;
import com.dlap.autoarchival.executor.ArchiveRuleRepository;
import com.dlap.autoarchival.model.ArchivalActivityEntity;
import com.dlap.autoarchival.model.ArchivalConfigEntity;
import com.dlap.autoarchival.model.ArchivalMetricsEntity;
import com.dlap.autoarchival.model.ArchivalStatus;
import com.dlap.autoarchival.model.ArchivalTaskEntity;
import com.dlap.autoarchival.model.StorageConsumerEntity;
import com.dlap.autoarchival.repository.ArchivalActivityRepository;
import com.dlap.autoarchival.repository.ArchivalConfigRepository;
import com.dlap.autoarchival.repository.ArchivalMetricsRepository;
import com.dlap.autoarchival.repository.ArchivalTaskRepository;
import com.dlap.autoarchival.repository.StorageConsumerRepository;
import com.dlap.autoarchival.model.ArchivalTaskEntity;
import com.dlap.autoarchival.service.IArchivalOverviewService;
import com.dlap.autoarchival.service.IArchivalTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation of IArchivalOverviewService.
 * Provides comprehensive data for the archival agent overview dashboard.
 * 
 * This implementation uses ONLY database queries - no hardcoded data.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArchivalOverviewServiceImpl implements IArchivalOverviewService {

    private final ArchivalMetricsRepository archivalMetricsRepository;
    private final ArchivalActivityRepository archivalActivityRepository;
    private final ArchivalTaskRepository archivalTaskRepository;
    private final ArchivalConfigRepository archivalConfigRepository;
    private final StorageConsumerRepository storageConsumerRepository;
    private final IArchivalTaskService archivalTaskService;
    private final ArchiveRuleRepository archiveRuleRepository;

    /**
     * Assets archived, counted from proof (Q-605). A rule row is {@code EXECUTED} only when a provider log event
     * showed the transition; a rule that is merely {@code APPLIED} (in place, nothing seen to move yet) is not
     * counted, and neither is a task that shows {@code RULE_APPLIED}. Tasks that finished {@code COMPLETED} before
     * native rules existed (the older direct path, which nothing sets any more) keep counting as they did.
     */
    private long archivedAssets() {
        return archiveRuleRepository.countByStatus(ArchiveRuleEntity.STATUS_EXECUTED)
                + archivalTaskRepository.countByStatus(ArchivalStatus.COMPLETED);
    }

    @Override
    public ArchivalOverviewResponseDTO getArchivalOverview() {
        log.info("Retrieving comprehensive archival overview data");
        
        return ArchivalOverviewResponseDTO.builder()
                .kpis(getArchivalKpis())
                .costTrends(getCostSavingsTrends(7))
                .archivalStatusData(getArchivalStatusData())
                .recentActivities(getRecentActivities(10))
                .storageConsumers(getStorageConsumers(10))
                .build();
    }

    @Override
    public ArchivalKpisDTO getArchivalKpis() {
        log.info("Retrieving archival KPIs from real archival task data");

        // KPIs are computed from the ArchivalTaskRepository - the table that
        // PolicyActionEventListener actually populates. The former source
        // (ArchivalMetricsRepository) is an aggregation table that is never
        // written, so it always reported 0. Every count below reflects real
        // rows; when the table is empty the KPI is honestly 0.

        // assetsArchived: assets whose transition a provider log event proved (EXECUTED), not RULE_APPLIED.
        long assetsArchived = archivedAssets();

        // failedArchival: tasks that failed to archive.
        long failedArchival = archivalTaskRepository.countByStatus(ArchivalStatus.FAILED);

        // pendingArchival: tasks awaiting or undergoing archival - everything
        // that has entered the pipeline but not yet reached a terminal state.
        long pendingArchival =
                archivalTaskRepository.countByStatus(ArchivalStatus.PENDING_APPROVAL)
                        + archivalTaskRepository.countByStatus(ArchivalStatus.APPROVED)
                        + archivalTaskRepository.countByStatus(ArchivalStatus.SUBMITTED)
                        + archivalTaskRepository.countByStatus(ArchivalStatus.IN_PROGRESS)
                        // a rule in place that no provider event has shown to fire yet is still pending
                        + archivalTaskRepository.countByStatus(ArchivalStatus.RULE_APPLIED)
                        + archivalTaskRepository.countByStatus(ArchivalStatus.RULE_RENDERED);

        // partiallyCompleted counts as an identified asset but is neither a
        // clean success nor an outright failure - include it in the total only.
        long partiallyCompleted = archivalTaskRepository.countByStatus(ArchivalStatus.PARTIALLY_COMPLETED);

        // assetsIdentified: every asset the archival pipeline has taken in.
        long assetsIdentified = assetsArchived + failedArchival + pendingArchival + partiallyCompleted;

        // activePolicies: this service holds a single global archival config
        // (fixed id 1L), not a per-policy table. Report 1 when archival is
        // enabled, else 0. Fabricating a policy count would be dishonest.
        int activePolicies = countActiveArchivalConfig();

        return ArchivalKpisDTO.builder()
                .activePolicies(activePolicies)
                .assetsIdentified((int) assetsIdentified)
                .assetsArchived((int) assetsArchived)
                // Storage/savings are not yet aggregated from real byte data:
                // report honest zeros rather than fabricated figures.
                .estimatedMonthlySavings(0.0)
                .totalSavingsToDate(0.0)
                .storageReclaimed("0 TB")
                .pendingArchival((int) pendingArchival)
                .failedArchival((int) failedArchival)
                .averageArchivalTime(0.0)
                .lastPolicyRun(formatTimeAgo(findLastArchivalActivity()))
                .build();
    }

    /**
     * Returns 1 when a global archival config row exists and is enabled, else 0.
     * This service has no per-policy table, so "active policies" honestly maps to
     * whether archival is switched on.
     *
     * @return 1 if archival is enabled, otherwise 0
     */
    private int countActiveArchivalConfig() {
        return archivalConfigRepository.findById(1L)
                .filter(ArchivalConfigEntity::isEnabled)
                .map(cfg -> 1)
                .orElse(0);
    }

    /**
     * Finds the timestamp of the most recently completed or started archival task
     * to drive the "last policy run" KPI. Returns {@code null} when no task has run.
     *
     * @return timestamp of the latest archival task activity, or {@code null}
     */
    private LocalDateTime findLastArchivalActivity() {
        return archivalTaskRepository.findMostRecentTasks().stream()
                .findFirst()
                .map(ArchivalTaskEntity::getSubmittedAt)
                .orElse(null);
    }

    @Override
    public List<CostSavingsTrendDTO> getCostSavingsTrends(Integer weeks) {
        log.info("Retrieving cost savings trends for {} weeks from database", weeks);
        
        // Calculate the start date for the specified number of weeks
        LocalDateTime startDate = LocalDateTime.now().minusWeeks(weeks);
        
        // Get metrics for the specified time period
        List<ArchivalMetricsEntity> metrics = archivalMetricsRepository.findMetricsForLastDays(startDate);
        
        List<CostSavingsTrendDTO> trends = new ArrayList<>();
        
        if (!metrics.isEmpty()) {
            // Convert metrics to trend DTOs
            for (ArchivalMetricsEntity metric : metrics) {
                trends.add(CostSavingsTrendDTO.builder()
                        .date(metric.getMetricDate().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE))
                        .monthlySavings(metric.getEstimatedMonthlySavings() != null ? metric.getEstimatedMonthlySavings() : 0.0)
                        .cumulativeSavings(metric.getTotalSavingsToDate() != null ? metric.getTotalSavingsToDate() : 0.0)
                        .storageReclaimed(metric.getStorageReclaimedTb() != null ? metric.getStorageReclaimedTb() : 0.0)
                        .build());
            }
        }
        
        // If no data exists, return empty list
        return trends;
    }

    @Override
    public List<ArchivalStatusDataDTO> getArchivalStatusData() {
        log.info("Retrieving archival status distribution data from database");
        
        List<ArchivalStatusDataDTO> statusData = new ArrayList<>();
        
        // Get counts from archival tasks
        long archivedCount = archivedAssets();
        long pendingCount = archivalTaskRepository.countByStatus(ArchivalStatus.PENDING_APPROVAL);
        long inProgressCount = archivalTaskRepository.countByStatus(ArchivalStatus.IN_PROGRESS);
        long failedCount = archivalTaskRepository.countByStatus(ArchivalStatus.FAILED);
        
        // Get the most recent metrics for additional data
        Optional<ArchivalMetricsEntity> recentMetrics = archivalMetricsRepository.findMostRecentMetrics();
        long activeCount = 0;
        long recentlyAccessedCount = 0;
        
        if (recentMetrics.isPresent()) {
            ArchivalMetricsEntity metrics = recentMetrics.get();
            activeCount = metrics.getAssetsIdentified() != null ? metrics.getAssetsIdentified() : 0;
            recentlyAccessedCount = activeCount - (metrics.getAssetsArchived() != null ? metrics.getAssetsArchived() : 0);
        }
        
        // Build status data with colors
        statusData.add(ArchivalStatusDataDTO.builder()
                .name("Archived")
                .value((int) archivedCount)
                .color("#10b981")
                .build());
        
        statusData.add(ArchivalStatusDataDTO.builder()
                .name("Identified for Archival")
                .value((int) pendingCount)
                .color("#f59e0b")
                .build());
        
        statusData.add(ArchivalStatusDataDTO.builder()
                .name("Active")
                .value((int) activeCount)
                .color("#3b82f6")
                .build());
        
        statusData.add(ArchivalStatusDataDTO.builder()
                .name("Recently Accessed")
                .value((int) recentlyAccessedCount)
                .color("#6b7280")
                .build());
        
        statusData.add(ArchivalStatusDataDTO.builder()
                .name("Failed Archival")
                .value((int) failedCount)
                .color("#ef4444")
                .build());
        
        return statusData;
    }

    @Override
    public List<RecentActivityDTO> getRecentActivities(Integer limit) {
        log.info("Retrieving recent archival activities (limit: {}) from database", limit);
        
        // Get recent activities from database with limit
        List<ArchivalActivityEntity> activities = archivalActivityRepository.findRecentActivities(PageRequest.of(0, limit));
        
        List<RecentActivityDTO> recentActivities = new ArrayList<>();
        
        for (ArchivalActivityEntity activity : activities) {
            recentActivities.add(RecentActivityDTO.builder()
                    .id(activity.getActivityId())
                    .event(activity.getEventType())
                    .assetName(activity.getAssetName() != null ? activity.getAssetName() : "Unknown Asset")
                    .policy(activity.getPolicyName() != null ? activity.getPolicyName() : "Default Policy")
                    .storageSize(formatStorageSizeGb(activity.getStorageSizeGb()))
                    .savingsAmount(activity.getSavingsAmount() != null ? activity.getSavingsAmount() : 0.0)
                    .status(activity.getStatus())
                    .timestamp(formatTimeAgo(activity.getActivityTimestamp()))
                    .details(activity.getDetails() != null ? activity.getDetails() : "No details available")
                    .build());
        }
        
        return recentActivities;
    }

    @Override
    public List<StorageConsumerDTO> getStorageConsumers(Integer limit) {
        log.info("Retrieving top storage consumers (limit: {}) from database", limit);
        
        // Get top storage consumers from database
        List<StorageConsumerEntity> consumers = storageConsumerRepository.findTopStorageConsumers(PageRequest.of(0, limit));
        
        List<StorageConsumerDTO> storageConsumers = new ArrayList<>();
        
        for (StorageConsumerEntity consumer : consumers) {
            storageConsumers.add(StorageConsumerDTO.builder()
                    .name(consumer.getConsumerName())
                    .currentSize(formatStorageSize(consumer.getCurrentSizeTb()))
                    .lastAccessed(formatTimeAgo(consumer.getLastAccessed()))
                    .cost(consumer.getMonthlyCost() != null ? consumer.getMonthlyCost() : 0.0)
                    .recommendation(consumer.getRecommendation() != null ? consumer.getRecommendation() : "Monitor")
                    .priority(consumer.getPriority() != null ? consumer.getPriority() : "Medium")
                    .build());
        }
        
        return storageConsumers;
    }

    @Override
    public Boolean triggerManualArchivalRun() {
        log.info("Triggering manual archival run");

        try {
            ArchivalActivityEntity activity = new ArchivalActivityEntity();
            activity.setActivityId(UUID.randomUUID().toString());
            activity.setEventType("MANUAL_RUN_REQUESTED");
            activity.setStatus("PENDING");
            activity.setActivityTimestamp(LocalDateTime.now());
            activity.setDetails("Manual archival run requested - dispatching pending archival tasks.");
            archivalActivityRepository.save(activity);

            // Dispatch every task that is waiting to execute to the async worker.
            // APPROVED (post-approval) and SUBMITTED (no-approval-required) tasks
            // have never been queued in the past; this is the hook that finally
            // drives them through the cloud storage layer.
            List<ArchivalTaskEntity> pendingTasks = new ArrayList<>();
            pendingTasks.addAll(archivalTaskRepository.findByStatusOrderBySubmittedAtDesc(ArchivalStatus.APPROVED));
            pendingTasks.addAll(archivalTaskRepository.findByStatusOrderBySubmittedAtDesc(ArchivalStatus.SUBMITTED));
            for (ArchivalTaskEntity task : pendingTasks) {
                try {
                    archivalTaskService.processArchivalTaskAsync(task.getTaskId());
                } catch (RuntimeException ex) {
                    log.error("Failed to dispatch manual archival for task {}: {}", task.getTaskId(), ex.getMessage(), ex);
                }
            }
            log.info("Manual archival run dispatched {} task(s)", pendingTasks.size());
            return true;
        } catch (Exception e) {
            log.error("Error during manual archival run", e);
            return false;
        }
    }

    /**
     * Helper method to format storage size from TB to human-readable format.
     *
     * @param sizeTb Size in terabytes
     * @return Formatted storage size string
     */
    private String formatStorageSize(Double sizeTb) {
        if (sizeTb == null || sizeTb == 0.0) {
            return "0 TB";
        }
        
        if (sizeTb >= 1.0) {
            return String.format("%.1f TB", sizeTb);
        } else {
            double sizeGb = sizeTb * 1024;
            if (sizeGb >= 1.0) {
                return String.format("%.1f GB", sizeGb);
            } else {
                double sizeMb = sizeGb * 1024;
                return String.format("%.0f MB", sizeMb);
            }
        }
    }

    /**
     * Helper method to format storage size from GB to human-readable format.
     *
     * @param sizeGb Size in gigabytes
     * @return Formatted storage size string
     */
    private String formatStorageSizeGb(Double sizeGb) {
        if (sizeGb == null || sizeGb == 0.0) {
            return "0 GB";
        }
        
        if (sizeGb >= 1024.0) {
            double sizeTb = sizeGb / 1024.0;
            return String.format("%.1f TB", sizeTb);
        } else {
            return String.format("%.1f GB", sizeGb);
        }
    }

    /**
     * Helper method to format time ago from LocalDateTime.
     *
     * @param dateTime LocalDateTime to format
     * @return Formatted time ago string
     */
    private String formatTimeAgo(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "Never";
        }
        
        LocalDateTime now = LocalDateTime.now();
        long minutes = java.time.Duration.between(dateTime, now).toMinutes();
        
        if (minutes < 1) {
            return "Just now";
        } else if (minutes < 60) {
            return minutes + " minutes ago";
        } else if (minutes < 1440) { // 24 hours
            long hours = minutes / 60;
            return hours + " hours ago";
        } else {
            long days = minutes / 1440;
            return days + " days ago";
        }
    }
} 