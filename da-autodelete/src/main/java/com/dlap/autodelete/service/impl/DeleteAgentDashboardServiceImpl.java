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

package com.dlap.autodelete.service.impl;

import com.dlap.autodelete.domain.SoftDeleteRecordEntity;
import com.dlap.autodelete.dto.*;
import com.dlap.autodelete.model.DeletionTaskEntity;
import com.dlap.autodelete.model.DeletionStatus;
import com.dlap.autodelete.repository.DeletionTaskRepository;
import com.dlap.autodelete.repository.SoftDeleteRecordRepository;
import com.dlap.autodelete.service.IDeleteAgentDashboardService;
import com.dlap.autodelete.service.IDeletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service implementation for Delete Agent dashboard functionality.
 * All data is retrieved dynamically from the database.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeleteAgentDashboardServiceImpl implements IDeleteAgentDashboardService {

    /**
     * Terminal soft-delete states in {@code soft_delete_records}.
     * SOFT_DELETED = queued, awaiting the hard-delete window (pending);
     * HARD_DELETED = deletion completed; HARD_DELETE_FAILED = failed;
     * RESTORED = operator-restored (not a deletion).
     */
    private static final String STATUS_SOFT_DELETED = "SOFT_DELETED";
    private static final String STATUS_HARD_DELETED = "HARD_DELETED";
    private static final String STATUS_HARD_DELETE_FAILED = "HARD_DELETE_FAILED";
    private static final String STATUS_RESTORED = "RESTORED";

    private final DeletionTaskRepository deletionTaskRepository;
    private final IDeletionService deletionService;
    private final SoftDeleteRecordRepository softDeleteRecordRepository;

    @Override
    public DeleteKpisDTO getDeleteKpis() {
        log.info("Retrieving Delete Agent KPIs from soft_delete_records (real deletion activity)");

        // KPIs are sourced from soft_delete_records - the table that policy-driven DELETE
        // actions actually write to (DeletionServiceImpl.queueSoftDelete). Each row is one
        // asset in the delete lifecycle. Status mapping:
        //   SOFT_DELETED       -> queued, awaiting the hard-delete window  (pending)
        //   HARD_DELETED       -> deletion completed                       (deleted)
        //   HARD_DELETE_FAILED -> hard-delete attempt failed               (failed)
        //   RESTORED           -> operator undid the delete                (excluded)
        // Empty table => honest zeros; nothing is fabricated.
        Instant last24Hours = Instant.now().minus(Duration.ofHours(24));

        long assetsDeleted = softDeleteRecordRepository.countByStatus(STATUS_HARD_DELETED);
        long assetsDeletedLast24h =
                softDeleteRecordRepository.countByStatusAndHardDeletedAtAfter(STATUS_HARD_DELETED, last24Hours);
        long pendingDeletion = softDeleteRecordRepository.countByStatus(STATUS_SOFT_DELETED);
        long failedDeletion = softDeleteRecordRepository.countByStatus(STATUS_HARD_DELETE_FAILED);

        // Reclaimed bytes: the catalog's size of each record the provider's own log proved deleted
        // (HARD_DELETED, written by ProofRecorder; Q-619). An unknown size contributes zero, and a
        // bucket or container rule in force (RULE_IN_FORCE) is not a deletion of the bucket, so this is a
        // conservative lower bound rather than an estimate.
        long totalBytesReclaimed = softDeleteRecordRepository.sumSizeBytesByStatus(STATUS_HARD_DELETED);
        double totalGbReclaimed = totalBytesReclaimed / (1024.0 * 1024.0 * 1024.0);
        String storageReclaimed = formatStorageSize(totalGbReclaimed);
        // Cost savings still require billing integration - keep at zero until that lands.
        double estimatedMonthlySavings = 0.0;
        double totalSavingsToDate = 0.0;

        // Compliance score = completed deletes / (completed + failed) attempts.
        // No terminal attempts yet => 100.0 (nothing has gone wrong).
        long terminalAttempts = assetsDeleted + failedDeletion;
        double complianceScore = terminalAttempts > 0
                ? ((double) assetsDeleted / terminalAttempts) * 100.0
                : 100.0;

        // Average deletion time = queued-at -> hard-deleted-at across completed records.
        double averageDeletionTime = calculateAverageSoftDeleteDuration();

        // Last policy run = the most recent time a policy queued a delete.
        String lastPolicyRun = getLastPolicyRunTime();

        return DeleteKpisDTO.builder()
                .activePolicies(calculateActivePolicies())
                .assetsDeleted(assetsDeleted)
                .assetsDeletedLast24h((int) assetsDeletedLast24h)
                .pendingDeletion((int) pendingDeletion)
                .failedDeletion((int) failedDeletion)
                .storageReclaimed(storageReclaimed)
                .estimatedMonthlySavings(estimatedMonthlySavings)
                .totalSavingsToDate(totalSavingsToDate)
                .complianceScore(Math.round(complianceScore * 10.0) / 10.0) // Round to 1 decimal
                .averageDeletionTime(Math.round(averageDeletionTime * 10.0) / 10.0) // Round to 1 decimal
                .lastPolicyRun(lastPolicyRun)
                .build();
    }

    @Override
    public List<StorageReclaimedTrendDTO> getStorageReclaimedTrends(int weeks) {
        log.info("Retrieving storage reclaimed trends for {} weeks from database", weeks);
        
        List<StorageReclaimedTrendDTO> trends = new ArrayList<>();
        LocalDateTime since = LocalDateTime.now().minusWeeks(weeks);
        
        // Get trend data from database. Current schema provides only asset counts, not storage bytes.
        // TODO: when bytes reclaimed are persisted per task, replace this with storage-based aggregation.
        List<Object[]> storageData = deletionTaskRepository.getStorageReclaimedTrends(since);
        
        // Group data by date and calculate totals
        Map<LocalDate, List<Object[]>> dataByDate = storageData.stream()
                .collect(Collectors.groupingBy(row -> {
                    LocalDateTime dateTime = (LocalDateTime) row[0];
                    return dateTime.toLocalDate();
                }));
        
        // Generate trends for each week
        for (int i = 0; i < weeks; i++) {
            LocalDate date = LocalDate.now().minusWeeks(weeks - i - 1);
            
            // Get data for this date
            List<Object[]> dateData = dataByDate.getOrDefault(date, new ArrayList<>());
            
            // Calculate totals for different categories (simplified - in real implementation you'd have policy types)
            double totalReclaimed = dateData.stream()
                    .mapToDouble(row -> ((Number) row[1]).doubleValue())
                    .sum();
            
            // Category breakdown requires policy metadata persisted on tasks.
            double retention = 0.0;
            double gdpr = 0.0;
            double duplicateData = 0.0;
            double qualityIssues = 0.0;
            double storageOptimization = 0.0;
            
            trends.add(StorageReclaimedTrendDTO.builder()
                    .date(date)
                    .retention(retention)
                    .gdpr(gdpr)
                    .duplicateData(duplicateData)
                    .qualityIssues(qualityIssues)
                    .storageOptimization(storageOptimization)
                    .totalReclaimed(totalReclaimed)
                    .build());
        }
        
        return trends;
    }

    @Override
    public List<DeletionByPolicyDTO> getDeletionByPolicyData() {
        log.info("Retrieving deletion by policy data from database");
        
        // Get actual deletion statistics by task name from database
        List<Object[]> policyStats = deletionTaskRepository.getDeletionStatsByTaskName();
        
        List<DeletionByPolicyDTO> policyData = new ArrayList<>();
        String[] colors = {"#3b82f6", "#10b981", "#f59e0b", "#ef4444", "#8b5cf6", "#06b6d4", "#84cc16"};
        int colorIndex = 0;
        
        for (Object[] stat : policyStats) {
            String taskName = (String) stat[0];
            Long count = (Long) stat[1];
            
            // Map task names to policy categories
            String policyName = mapTaskNameToPolicy(taskName);
            
            policyData.add(DeletionByPolicyDTO.builder()
                    .name(policyName)
                    .value(count.intValue())
                    .color(colors[colorIndex % colors.length])
                    .build());
            
            colorIndex++;
        }
        
        // If no data, return empty list
        if (policyData.isEmpty()) {
            log.info("No deletion policy data found in database");
        }
        
        return policyData;
    }

    @Override
    public List<RecentActivityDTO> getRecentActivities(int limit) {
        log.info("Retrieving recent activities from soft_delete_records, limit: {}", limit);

        // Recent deletion lifecycle events come from soft_delete_records, newest queued first.
        Pageable pageable = PageRequest.of(0, limit);
        List<SoftDeleteRecordEntity> recent =
                softDeleteRecordRepository.findAllByOrderByQueuedAtDesc(pageable).getContent();

        List<RecentActivityDTO> activities = new ArrayList<>();
        for (SoftDeleteRecordEntity rec : recent) {
            activities.add(RecentActivityDTO.builder()
                    .id(rec.getId() != null ? rec.getId().toString() : null)
                    .event(getEventTypeForSoftDelete(rec.getStatus()))
                    .assetName(rec.getAssetUri() != null
                            ? rec.getAssetUri()
                            : (rec.getAssetId() != null ? rec.getAssetId().toString() : "Unknown asset"))
                    .policy(rec.getPolicyId() != null ? rec.getPolicyId() : "Unknown Policy")
                    .assetSize("1 asset")
                    .reason(rec.getAuditNote() != null ? rec.getAuditNote() : "Policy-driven deletion")
                    .status(rec.getStatus())
                    .timestamp(toLocalDateTime(rec.getQueuedAt()))
                    .details(generateSoftDeleteDetails(rec))
                    .build());
        }
        return activities;
    }

    @Override
    public List<RiskAssetDTO> getRiskAssets(int limit) {
        log.info("Retrieving risk assets from soft_delete_records, limit: {}", limit);

        // Risk assets = records still in the soft-delete window (SOFT_DELETED): they are
        // scheduled to be hard-deleted unless an operator restores them first.
        List<SoftDeleteRecordEntity> pending =
                softDeleteRecordRepository.findByStatusOrderByQueuedAtAsc(STATUS_SOFT_DELETED);

        List<RiskAssetDTO> riskAssets = new ArrayList<>();
        for (SoftDeleteRecordEntity rec : pending.stream().limit(limit).collect(Collectors.toList())) {
            riskAssets.add(RiskAssetDTO.builder()
                    .name(rec.getAssetUri() != null
                            ? rec.getAssetUri()
                            : (rec.getAssetId() != null ? rec.getAssetId().toString() : "Unknown asset"))
                    .type(rec.getCloudProvider() != null ? rec.getCloudProvider() : "Unknown")
                    .size("1 asset")
                    .lastAccessed(null) // requires catalog-backed last-access data (not in this schema)
                    .risk(calculateRiskLevelForSoftDelete(rec))
                    .scheduledDeletion(toLocalDateTime(rec.getScheduledHardDeleteAt()))
                    .reason(rec.getAuditNote() != null ? rec.getAuditNote() : "Scheduled for hard delete")
                    .build());
        }
        return riskAssets;
    }

    @Override
    public boolean triggerManualDeletionRun() {
        // RET-02: a manual run is one retire sweep now. It moves due soft deletes forward through the
        // same approval, legal-hold, coverage and dry-run rules as the scheduled sweep; the row locks
        // (FOR UPDATE SKIP LOCKED) keep it from acting on a row the scheduled sweep holds.
        log.info("Manual deletion run: one retire sweep");
        try {
            int handled = deletionService.processDueHardDeletes();
            log.info("Manual deletion run handled {} row(s) and task(s)", handled);
            return true;
        } catch (RuntimeException ex) {
            log.error("Manual deletion run failed", ex);
            return false;
        }
    }

    @Override
    public TotalStorageReclaimedDTO getTotalStorageReclaimed() {
        log.info("Retrieving total storage reclaimed data");
        
        // Get all completed deletion tasks
        List<DeletionTaskEntity> completedTasks = deletionTaskRepository.findByStatus(DeletionStatus.COMPLETED);
        
        // Calculate total assets deleted
        long totalAssetsDeleted = completedTasks.stream()
                .mapToLong(DeletionTaskEntity::getAssetsProcessedSuccessfully)
                .sum();
        
        // Bytes of the records the provider's log proved deleted (Q-619).
        long totalBytes = softDeleteRecordRepository.sumSizeBytesByStatus(STATUS_HARD_DELETED);
        double totalStorageReclaimedGB = totalBytes / (1024.0 * 1024.0 * 1024.0);
        String totalStorageReclaimed = formatStorageSize(totalStorageReclaimedGB);
        
        // Calculate monthly and yearly storage reclaimed
        LocalDateTime monthStart = LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
        LocalDateTime yearStart = LocalDateTime.now().withDayOfYear(1).withHour(0).withMinute(0).withSecond(0);
        
        List<DeletionTaskEntity> thisMonthTasks = completedTasks.stream()
                .filter(task -> task.getCompletedAt() != null && task.getCompletedAt().isAfter(monthStart))
                .toList();
        
        List<DeletionTaskEntity> thisYearTasks = completedTasks.stream()
                .filter(task -> task.getCompletedAt() != null && task.getCompletedAt().isAfter(yearStart))
                .toList();
        
        long thisMonthAssets = thisMonthTasks.stream()
                .mapToLong(DeletionTaskEntity::getAssetsProcessedSuccessfully)
                .sum();
        
        long thisYearAssets = thisYearTasks.stream()
                .mapToLong(DeletionTaskEntity::getAssetsProcessedSuccessfully)
                .sum();
        
        long monthBytes = softDeleteRecordRepository.sumSizeBytesByStatusSince(STATUS_HARD_DELETED,
                monthStart.toInstant(java.time.ZoneOffset.UTC));
        long yearBytes = softDeleteRecordRepository.sumSizeBytesByStatusSince(STATUS_HARD_DELETED,
                yearStart.toInstant(java.time.ZoneOffset.UTC));
        double thisMonthStorageGB = monthBytes / (1024.0 * 1024.0 * 1024.0);
        double thisYearStorageGB = yearBytes / (1024.0 * 1024.0 * 1024.0);
        
        String storageReclaimedThisMonth = formatStorageSize(thisMonthStorageGB);
        String storageReclaimedThisYear = formatStorageSize(thisYearStorageGB);
        
        double averageStoragePerAsset = totalAssetsDeleted > 0 ? totalStorageReclaimedGB / totalAssetsDeleted : 0.0;
        
        return TotalStorageReclaimedDTO.builder()
                .totalStorageReclaimed(totalStorageReclaimed)
                .totalStorageReclaimedGB(totalStorageReclaimedGB)
                .totalAssetsDeleted(totalAssetsDeleted)
                .lastUpdated(LocalDateTime.now())
                .storageReclaimedThisMonth(storageReclaimedThisMonth)
                .storageReclaimedThisYear(storageReclaimedThisYear)
                .averageStoragePerAsset(averageStoragePerAsset)
                .build();
    }

    @Override
    public TotalCostSavingsDTO getTotalCostSavings() {
        log.info("Retrieving total cost savings data");
        
        // Get all completed deletion tasks
        List<DeletionTaskEntity> completedTasks = deletionTaskRepository.findByStatus(DeletionStatus.COMPLETED);
        
        // Calculate total assets deleted
        long totalAssetsDeleted = completedTasks.stream()
                .mapToLong(DeletionTaskEntity::getAssetsProcessedSuccessfully)
                .sum();
        
        // TODO: requires billing integration / persisted savings metrics per task.
        BigDecimal totalSavingsToDate = BigDecimal.ZERO;
        BigDecimal estimatedMonthlySavings = BigDecimal.ZERO;
        
        // Calculate monthly and yearly savings
        LocalDateTime monthStart = LocalDateTime.now().withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
        LocalDateTime yearStart = LocalDateTime.now().withDayOfYear(1).withHour(0).withMinute(0).withSecond(0);
        
        List<DeletionTaskEntity> thisMonthTasks = completedTasks.stream()
                .filter(task -> task.getCompletedAt() != null && task.getCompletedAt().isAfter(monthStart))
                .toList();
        
        List<DeletionTaskEntity> thisYearTasks = completedTasks.stream()
                .filter(task -> task.getCompletedAt() != null && task.getCompletedAt().isAfter(yearStart))
                .toList();
        
        long thisMonthAssets = thisMonthTasks.stream()
                .mapToLong(DeletionTaskEntity::getAssetsProcessedSuccessfully)
                .sum();
        
        long thisYearAssets = thisYearTasks.stream()
                .mapToLong(DeletionTaskEntity::getAssetsProcessedSuccessfully)
                .sum();
        
        BigDecimal savingsThisMonth = BigDecimal.ZERO;
        BigDecimal savingsThisYear = BigDecimal.ZERO;
        
        double averageSavingsPerAsset = totalAssetsDeleted > 0 ? 
            totalSavingsToDate.doubleValue() / totalAssetsDeleted : 0.0;
        
        // Assume 15% savings of original costs
        double savingsPercentage = 15.0;
        
        return TotalCostSavingsDTO.builder()
                .totalSavingsToDate(totalSavingsToDate)
                .estimatedMonthlySavings(estimatedMonthlySavings)
                .savingsThisMonth(savingsThisMonth)
                .savingsThisYear(savingsThisYear)
                .totalAssetsDeleted(totalAssetsDeleted)
                .lastUpdated(LocalDateTime.now())
                .currency("USD")
                .averageSavingsPerAsset(averageSavingsPerAsset)
                .savingsPercentage(savingsPercentage)
                .build();
    }

    // Helper methods
    
    private String formatStorageSize(double sizeGB) {
        if (sizeGB >= 1024) {
            return String.format("%.1f TB", sizeGB / 1024);
        } else {
            return String.format("%.1f GB", sizeGB);
        }
    }
    
    private String getLastPolicyRunTime() {
        // "Last policy run" = the most recent moment a policy queued a delete
        // into soft_delete_records.
        Instant lastRun = softDeleteRecordRepository.findMaxQueuedAt().orElse(null);
        if (lastRun == null) {
            return "No recent runs";
        }

        long minutesAgo = Duration.between(lastRun, Instant.now()).toMinutes();
        if (minutesAgo < 0) {
            minutesAgo = 0;
        }
        if (minutesAgo < 60) {
            return minutesAgo + " minutes ago";
        } else if (minutesAgo < 1440) {
            return (minutesAgo / 60) + " hours ago";
        } else {
            return (minutesAgo / 1440) + " days ago";
        }
    }

    private int calculateActivePolicies() {
        // Distinct policy ids that have driven at least one soft-delete.
        return (int) softDeleteRecordRepository.countDistinctPolicyIds();
    }

    /**
     * Average wall-clock minutes between a record being queued (SOFT_DELETED)
     * and its hard-delete completing, over all HARD_DELETED records. Returns 0
     * when there are no completed deletions.
     */
    private double calculateAverageSoftDeleteDuration() {
        List<SoftDeleteRecordEntity> completed =
                softDeleteRecordRepository.findByStatusOrderByQueuedAtAsc(STATUS_HARD_DELETED);
        if (completed.isEmpty()) {
            return 0.0;
        }
        long totalMinutes = 0L;
        int counted = 0;
        for (SoftDeleteRecordEntity rec : completed) {
            if (rec.getQueuedAt() != null && rec.getHardDeletedAt() != null) {
                long minutes = Duration.between(rec.getQueuedAt(), rec.getHardDeletedAt()).toMinutes();
                totalMinutes += Math.max(minutes, 0);
                counted++;
            }
        }
        return counted > 0 ? totalMinutes / (double) counted : 0.0;
    }
    
    private String mapTaskNameToPolicy(String taskName) {
        if (taskName == null) {
            return "Unknown Policy";
        }
        
        String lowerName = taskName.toLowerCase();
        if (lowerName.contains("retention")) {
            return "Retention Policies";
        } else if (lowerName.contains("gdpr")) {
            return "GDPR Requests";
        } else if (lowerName.contains("duplicate")) {
            return "Duplicate Data";
        } else if (lowerName.contains("quality")) {
            return "Quality Issues";
        } else if (lowerName.contains("storage") || lowerName.contains("optimization")) {
            return "Storage Optimization";
        } else if (lowerName.contains("cleanup")) {
            return "Data Cleanup";
        } else {
            return taskName;
        }
    }
    
    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String getEventTypeForSoftDelete(String status) {
        if (status == null) {
            return "Deletion Event";
        }
        switch (status) {
            case STATUS_HARD_DELETED:
                return "Asset Deleted";
            case STATUS_HARD_DELETE_FAILED:
                return "Deletion Failed";
            case STATUS_SOFT_DELETED:
                return "Deletion Scheduled";
            case STATUS_RESTORED:
                return "Asset Restored";
            default:
                return "Deletion Event";
        }
    }

    private String generateSoftDeleteDetails(SoftDeleteRecordEntity rec) {
        String status = rec.getStatus();
        if (STATUS_HARD_DELETED.equals(status)) {
            return "Hard-deleted at " + rec.getHardDeletedAt();
        } else if (STATUS_HARD_DELETE_FAILED.equals(status)) {
            return rec.getLastError() != null ? rec.getLastError() : "Hard-delete failed";
        } else if (STATUS_SOFT_DELETED.equals(status)) {
            return "Scheduled for hard-delete at " + rec.getScheduledHardDeleteAt();
        } else if (STATUS_RESTORED.equals(status)) {
            return "Restored at " + rec.getRestoredAt();
        }
        return "Status: " + status;
    }

    private String calculateRiskLevelForSoftDelete(SoftDeleteRecordEntity rec) {
        // Risk rises as the hard-delete deadline approaches. Once the window has
        // passed the sweeper will delete on its next run, so that is High.
        if (rec.getScheduledHardDeleteAt() == null) {
            return "Medium";
        }
        long hoursUntilDelete = Duration.between(Instant.now(), rec.getScheduledHardDeleteAt()).toHours();
        if (hoursUntilDelete <= 24) {
            return "High";
        } else if (hoursUntilDelete <= 24 * 7) {
            return "Medium";
        }
        return "Low";
    }

}