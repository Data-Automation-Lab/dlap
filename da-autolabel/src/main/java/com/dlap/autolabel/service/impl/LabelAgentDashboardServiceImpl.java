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

package com.dlap.autolabel.service.impl;

import com.dlap.autolabel.dto.LabelAgentKpis;
import com.dlap.autolabel.dto.RecentLabelActivity;
import com.dlap.autolabel.dto.RuleActivity;
import com.dlap.autolabel.dto.TopAppliedLabel;
import com.dlap.autolabel.entity.LabelingJobEntity;
import com.dlap.autolabel.exception.AutoLabelingException;
import com.dlap.autolabel.repository.LabelingJobRepository;
import com.dlap.autolabel.service.ILabelAgentDashboardService;
import com.dlap.common.constants.DlapConstants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of the SmartLabel Agent dashboard service.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LabelAgentDashboardServiceImpl implements ILabelAgentDashboardService {

    private final LabelingJobRepository labelingJobRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("MMM dd, HH:mm");

    @Override
    public LabelAgentKpis getLabelAgentKpis() {
        try {
            log.info("Calculating SmartLabel Agent KPIs from real database data...");
            
            LocalDateTime last24h = LocalDateTime.now().minusHours(24);
            
            // Get real data from database
            List<LabelingJobEntity> allJobs = labelingJobRepository.findAll();
            List<LabelingJobEntity> completedJobs = allJobs.stream()
                    .filter(job -> job.getStatus() == LabelingJobEntity.JobStatus.COMPLETED)
                    .collect(Collectors.toList());
            
            List<LabelingJobEntity> recentJobs = completedJobs.stream()
                    .filter(job -> job.getCompletedAt() != null && job.getCompletedAt().isAfter(last24h))
                    .collect(Collectors.toList());
            
            // Calculate real metrics from database
            int totalAssetsProcessed = completedJobs.stream()
                    .mapToInt(job -> job.getAssetsProcessed() != null ? job.getAssetsProcessed() : 0)
                    .sum();
            
            int totalAssetsSuccessfullyLabeled = completedJobs.stream()
                    .mapToInt(job -> job.getAssetsSuccessfullyLabeled() != null ? job.getAssetsSuccessfullyLabeled() : 0)
                    .sum();
            
            int totalAssetsFailed = completedJobs.stream()
                    .mapToInt(job -> job.getAssetsFailed() != null ? job.getAssetsFailed() : 0)
                    .sum();
            
            int assetsLast24h = recentJobs.stream()
                    .mapToInt(job -> job.getAssetsProcessed() != null ? job.getAssetsProcessed() : 0)
                    .sum();
            
            int labelsLast24h = recentJobs.stream()
                    .mapToInt(job -> job.getAssetsSuccessfullyLabeled() != null ? job.getAssetsSuccessfullyLabeled() : 0)
                    .sum();
            
            // Calculate real average processing time from database
            double avgProcessingTime = completedJobs.stream()
                    .filter(job -> job.getStartedAt() != null && job.getCompletedAt() != null)
                    .mapToDouble(job -> {
                        long seconds = java.time.Duration.between(job.getStartedAt(), job.getCompletedAt()).getSeconds();
                        return seconds;
                    })
                    .average()
                    .orElse(0.0); // Use 0 if no data available
            
            // Calculate real error rate from database
            double errorRate = totalAssetsProcessed > 0 ? (double) totalAssetsFailed / totalAssetsProcessed * 100 : 0.0;
            
            // Calculate real model accuracy from database
            double modelAccuracy = totalAssetsProcessed > 0 ? 
                    (double) (totalAssetsProcessed - totalAssetsFailed) / totalAssetsProcessed * 100 : 0.0;
            
            // Count real active models (jobs with ML config)
            long activeModels = allJobs.stream()
                    .filter(job -> job.getEffectiveMlConfig() != null)
                    .map(job -> job.getEffectiveMlConfig().getModelName())
                    .filter(Objects::nonNull)
                    .distinct()
                    .count();
            
            // Count real rule policies (jobs with scope and asset criteria)
            long rulePoliciesActive = allJobs.stream()
                    .filter(job -> job.getScope() != null && job.getScope().getAssetCriteria() != null)
                    .map(job -> job.getScope().getAssetCriteria().toString())
                    .distinct()
                    .count();
            
            // Count real ML policies (jobs with ML config)
            long mlPoliciesActive = allJobs.stream()
                    .filter(job -> job.getEffectiveMlConfig() != null)
                    .map(job -> job.getEffectiveMlConfig().getModelName())
                    .filter(Objects::nonNull)
                    .distinct()
                    .count();
            
            return LabelAgentKpis.builder()
                    .activeModels((int) activeModels)
                    .appliedLabelsTotal(totalAssetsSuccessfullyLabeled)
                    .appliedLabelsLast24h(labelsLast24h)
                    .modelAccuracy(Math.round(modelAccuracy * 10.0) / 10.0)
                    .rulePoliciesActive((int) rulePoliciesActive)
                    .mlPoliciesActive((int) mlPoliciesActive)
                    .assetsProcessed(totalAssetsProcessed)
                    .assetsProcessedLast24h(assetsLast24h)
                    .averageProcessingTime(Math.round(avgProcessingTime * 10.0) / 10.0)
                    .errorRate(Math.round(errorRate * 10.0) / 10.0)
                    .build();
                    
        } catch (Exception e) {
            log.error("Error calculating SmartLabel Agent KPIs", e);
            throw new AutoLabelingException("Failed to calculate SmartLabel Agent KPIs", e);
        }
    }

    @Override
    public List<TopAppliedLabel> getTopAppliedLabels(int limit) {
        try {
            log.info("Retrieving top applied labels with limit: {}", limit);
            
            List<LabelingJobEntity> completedJobs = labelingJobRepository.findAll().stream()
                    .filter(job -> job.getStatus() == LabelingJobEntity.JobStatus.COMPLETED)
                    .collect(Collectors.toList());
            
            // Aggregate real labels from database
            Map<String, Integer> labelCounts = new HashMap<>();
            Map<String, String> labelCategories = new HashMap<>();
            
            completedJobs.forEach(job -> {
                if (job.getLabelSummary() != null) {
                    job.getLabelSummary().forEach((labelName, count) -> {
                        labelCounts.merge(labelName, count, Integer::sum);
                        labelCategories.putIfAbsent(labelName, getCategoryForLabel(labelName));
                    });
                }
            });
            
            // Return real data sorted by count
            return labelCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(limit)
                    .map(entry -> TopAppliedLabel.builder()
                            .name(entry.getKey())
                            .value(entry.getValue())
                            .category(labelCategories.get(entry.getKey()))
                            .color(getColorForCategory(labelCategories.get(entry.getKey())))
                            .build())
                    .collect(Collectors.toList());
                    
        } catch (Exception e) {
            log.error("Error retrieving top applied labels", e);
            throw new AutoLabelingException("Failed to retrieve top applied labels", e);
        }
    }

    @Override
    public List<RuleActivity> getRuleActivities(int limit) {
        try {
            log.info("Retrieving rule activities with limit: {}", limit);
            
            List<LabelingJobEntity> completedJobs = labelingJobRepository.findAll().stream()
                    .filter(job -> job.getStatus() == LabelingJobEntity.JobStatus.COMPLETED)
                    .collect(Collectors.toList());
            
            // Create rule activities based on real job data
            Map<String, RuleActivity> ruleActivitiesMap = new HashMap<>();
            
            completedJobs.forEach(job -> {
                if (job.getScope() != null && job.getScope().getAssetCriteria() != null) {
                    String ruleSet = job.getScope().getAssetCriteria().toString();
                    
                    RuleActivity existing = ruleActivitiesMap.get(ruleSet);
                    if (existing != null) {
                        // Update existing rule activity
                        existing.setHitCount(existing.getHitCount() + (job.getAssetsSuccessfullyLabeled() != null ? job.getAssetsSuccessfullyLabeled() : 0));
                        existing.setAssetsAffected(existing.getAssetsAffected() + (job.getAssetsProcessed() != null ? job.getAssetsProcessed() : 0));
                        
                        // Update last triggered if this job is more recent
                        if (job.getCompletedAt() != null && 
                            (existing.getLastTriggered() == null || 
                             job.getCompletedAt().isAfter(parseTimeAgo(existing.getLastTriggered())))) {
                            existing.setLastTriggered(formatTimeAgo(job.getCompletedAt()));
                        }
                    } else {
                        // Create new rule activity
                        RuleActivity ruleActivity = RuleActivity.builder()
                                .ruleName(ruleSet)
                                .category(getCategoryForLabel(ruleSet))
                                .hitCount(job.getAssetsSuccessfullyLabeled() != null ? job.getAssetsSuccessfullyLabeled() : 0)
                                .successRate(calculateSuccessRate(job))
                                .lastTriggered(formatTimeAgo(job.getCompletedAt()))
                                .assetsAffected(job.getAssetsProcessed() != null ? job.getAssetsProcessed() : 0)
                                .build();
                        
                        ruleActivitiesMap.put(ruleSet, ruleActivity);
                    }
                }
            });
            
            // Return real rule activities sorted by hit count
            return ruleActivitiesMap.values().stream()
                    .sorted(Comparator.comparing(RuleActivity::getHitCount).reversed())
                    .limit(limit)
                    .collect(Collectors.toList());
            
        } catch (Exception e) {
            log.error("Error retrieving rule activities", e);
            throw new AutoLabelingException("Failed to retrieve rule activities", e);
        }
    }

    @Override
    public List<RecentLabelActivity> getRecentLabelActivities(int limit) {
        try {
            log.info("Retrieving recent label activities with limit: {}", limit);
            
            List<LabelingJobEntity> recentJobs = labelingJobRepository.findAll().stream()
                    .filter(job -> job.getStatus() == LabelingJobEntity.JobStatus.COMPLETED)
                    .sorted(Comparator.comparing(LabelingJobEntity::getCompletedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(limit)
                    .collect(Collectors.toList());
            
            List<RecentLabelActivity> activities = new ArrayList<>();
            
            for (LabelingJobEntity job : recentJobs) {
                // Extract real labels from job summary
                List<String> labelsApplied = new ArrayList<>();
                if (job.getLabelSummary() != null) {
                    labelsApplied.addAll(job.getLabelSummary().keySet());
                }
                
                // Determine real method used
                String method = determineMethod(job);
                
                // Calculate real confidence if available
                Double confidence = calculateConfidence(job);
                
                RecentLabelActivity activity = RecentLabelActivity.builder()
                        .id(job.getJobId())
                        .event("Labels Applied")
                        .assetName(job.getJobName() != null ? job.getJobName() : "Job " + job.getJobId())
                        .labelsApplied(labelsApplied)
                        .method(method)
                        .confidence(confidence)
                        .status(job.getStatus().toString())
                        .timestamp(formatTimeAgo(job.getCompletedAt()))
                        .details("Processed " + (job.getAssetsProcessed() != null ? job.getAssetsProcessed() : 0) + 
                                " assets, " + (job.getAssetsSuccessfullyLabeled() != null ? job.getAssetsSuccessfullyLabeled() : 0) + 
                                " successfully labeled")
                        .build();
                
                activities.add(activity);
            }
            
            return activities;
            
        } catch (Exception e) {
            log.error("Error retrieving recent label activities", e);
            throw new AutoLabelingException("Failed to retrieve recent label activities", e);
        }
    }

    @Override
    public boolean triggerLabelingRun() {
        // Publish a labeling-request event onto LABELING_REQUEST_EVENT. The
        // LabelingRequestConsumer (@KafkaListener) picks it up, enumerates
        // catalog assets, runs the label orchestrator, and persists a real
        // LabelingJob row that the dashboard KPIs read. This method is only the
        // producer; the durable side effect happens in the consumer.
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("requestId", requestId);
        event.put("triggerType", "MANUAL");
        event.put("scope", "ALL_UNLABELED");
        event.put("requestedAt", Instant.now().toString());
        event.put("sourceService", "da-autolabel");
        event.put("schemaVersion", "v1");

        try {
            String json = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(DlapConstants.KafkaTopic.LABELING_REQUEST_EVENT, requestId, json);
            log.info("Published labeling request {} to topic {}", requestId,
                    DlapConstants.KafkaTopic.LABELING_REQUEST_EVENT);
            return true;
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize labeling request event {}", requestId, e);
            return false;
        } catch (Exception e) {
            log.error("Failed to publish labeling request event {}", requestId, e);
            return false;
        }
    }

    @Override
    public List<Map<String, Object>> getRecentActivitiesDetailed(int limit) {
        try {
            log.info("Getting detailed recent activities with limit: {}", limit);
            
            List<Map<String, Object>> activities = new ArrayList<>();
            
            // Get recent completed jobs from database
            List<LabelingJobEntity> recentJobs = labelingJobRepository.findAll().stream()
                    .filter(job -> job.getStatus() == LabelingJobEntity.JobStatus.COMPLETED)
                    .sorted(Comparator.comparing(LabelingJobEntity::getCompletedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(limit)
                    .collect(Collectors.toList());
            
            for (LabelingJobEntity job : recentJobs) {
                Map<String, Object> activity = new HashMap<>();
                activity.put("id", job.getJobId());
                activity.put("event", "Labels Applied");
                activity.put("assetName", job.getJobName() != null ? job.getJobName() : "Job " + job.getJobId());
                activity.put("labelsApplied", getLabelsFromSummary(job.getLabelSummary()));
                activity.put("method", getMethodFromJob(job));
                activity.put("confidence", calculateRealConfidence(job));
                activity.put("status", job.getStatus().toString());
                activity.put("timestamp", formatTimeAgo(job.getCompletedAt()));
                activity.put("details", "Processed " + (job.getAssetsProcessed() != null ? job.getAssetsProcessed() : 0) + 
                        " assets, " + (job.getAssetsSuccessfullyLabeled() != null ? job.getAssetsSuccessfullyLabeled() : 0) + 
                        " successfully labeled");
                
                activities.add(activity);
            }
            
            return activities;
            
        } catch (Exception e) {
            log.error("Error getting detailed recent activities", e);
            throw new AutoLabelingException("Failed to get detailed recent activities", e);
        }
    }

    @Override
    public List<Map<String, Object>> getTopLabelsDetailed(int limit) {
        try {
            log.info("Getting detailed top labels with limit: {}", limit);
            
            List<Map<String, Object>> labels = new ArrayList<>();
            
            // Aggregate real labels from all completed jobs in database
            Map<String, Integer> labelCounts = new HashMap<>();
            List<LabelingJobEntity> completedJobs = labelingJobRepository.findAll().stream()
                    .filter(job -> job.getStatus() == LabelingJobEntity.JobStatus.COMPLETED)
                    .collect(Collectors.toList());
            
            for (LabelingJobEntity job : completedJobs) {
                if (job.getLabelSummary() != null) {
                    for (Map.Entry<String, Integer> entry : job.getLabelSummary().entrySet()) {
                        labelCounts.merge(entry.getKey(), entry.getValue(), Integer::sum);
                    }
                }
            }
            
            // Sort by count and take top labels
            List<Map.Entry<String, Integer>> sortedLabels = labelCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(limit)
                    .collect(Collectors.toList());
            
            for (Map.Entry<String, Integer> entry : sortedLabels) {
                Map<String, Object> label = new HashMap<>();
                label.put("name", entry.getKey());
                label.put("value", entry.getValue());
                label.put("category", getCategoryForLabel(entry.getKey()));
                label.put("color", getColorForLabel(entry.getKey()));
                
                labels.add(label);
            }
            
            return labels;
            
        } catch (Exception e) {
            log.error("Error getting detailed top labels", e);
            throw new AutoLabelingException("Failed to get detailed top labels", e);
        }
    }

    @Override
    public List<Map<String, Object>> getRulePerformance(int limit) {
        try {
            log.info("Getting rule performance with limit: {}", limit);
            
            List<Map<String, Object>> rules = new ArrayList<>();
            
            // Get real rule performance data from completed jobs
            List<LabelingJobEntity> completedJobs = labelingJobRepository.findAll().stream()
                    .filter(job -> job.getStatus() == LabelingJobEntity.JobStatus.COMPLETED)
                    .collect(Collectors.toList());
            
            // Group jobs by their scope/rule criteria to create rule performance data
            Map<String, List<LabelingJobEntity>> jobsByRule = completedJobs.stream()
                    .filter(job -> job.getScope() != null && job.getScope().getAssetCriteria() != null)
                    .collect(Collectors.groupingBy(job -> {
                        Map<String, String> criteria = job.getScope().getAssetCriteria();
                        return criteria.getOrDefault("dataType", "Unknown") + " " + 
                               criteria.getOrDefault("category", "Rule");
                    }));
            
            // Create rule performance data from real job data
            for (Map.Entry<String, List<LabelingJobEntity>> entry : jobsByRule.entrySet()) {
                String ruleName = entry.getKey();
                List<LabelingJobEntity> ruleJobs = entry.getValue();
                
                // Calculate real metrics from job data
                int totalHitCount = ruleJobs.stream()
                        .mapToInt(job -> job.getAssetsSuccessfullyLabeled() != null ? job.getAssetsSuccessfullyLabeled() : 0)
                        .sum();
                
                int totalAssetsAffected = ruleJobs.stream()
                        .mapToInt(job -> job.getAssetsProcessed() != null ? job.getAssetsProcessed() : 0)
                        .sum();
                
                double successRate = totalAssetsAffected > 0 ? 
                        (double) totalHitCount / totalAssetsAffected * 100 : 0.0;
                
                // Get the most recent job for last triggered time
                LocalDateTime lastTriggered = ruleJobs.stream()
                        .map(job -> job.getCompletedAt())
                        .filter(Objects::nonNull)
                        .max(LocalDateTime::compareTo)
                        .orElse(LocalDateTime.now());
                
                Map<String, Object> rule = new HashMap<>();
                rule.put("ruleName", ruleName);
                rule.put("category", getCategoryFromRuleName(ruleName));
                rule.put("hitCount", totalHitCount);
                rule.put("successRate", Math.round(successRate * 10.0) / 10.0);
                rule.put("lastTriggered", formatTimeAgo(lastTriggered));
                rule.put("assetsAffected", totalAssetsAffected);
                
                rules.add(rule);
            }
            
            // Sort by hit count and limit results
            return rules.stream()
                    .sorted((a, b) -> Integer.compare((Integer) b.get("hitCount"), (Integer) a.get("hitCount")))
                    .limit(limit)
                    .collect(Collectors.toList());
            
        } catch (Exception e) {
            log.error("Error getting rule performance", e);
            throw new AutoLabelingException("Failed to get rule performance", e);
        }
    }

    @Override
    public List<Map<String, Object>> getAssetEvents(int limit) {
        try {
            log.info("Getting asset events with limit: {}", limit);
            
            List<Map<String, Object>> events = new ArrayList<>();
            
            // Get recent completed jobs and create asset events
            List<LabelingJobEntity> recentJobs = labelingJobRepository.findAll().stream()
                    .filter(job -> job.getStatus() == LabelingJobEntity.JobStatus.COMPLETED)
                    .sorted(Comparator.comparing(LabelingJobEntity::getCompletedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(limit)
                    .collect(Collectors.toList());
            
            for (LabelingJobEntity job : recentJobs) {
                Map<String, Object> event = new HashMap<>();
                event.put("assetId", "asset-" + job.getJobId().substring(0, 8));
                event.put("assetName", job.getJobName() + " Dataset");
                event.put("event", "Labels Applied");
                event.put("labelsApplied", getLabelsFromSummary(job.getLabelSummary()));
                event.put("method", getMethodFromJob(job));
                event.put("status", "Success");
                event.put("timestamp", formatTimeAgo(job.getCompletedAt()));
                event.put("details", "Processed " + job.getAssetsSuccessfullyLabeled() + " assets");
                
                events.add(event);
            }
            
            return events;
            
        } catch (Exception e) {
            log.error("Error getting asset events", e);
            throw new AutoLabelingException("Failed to get asset events", e);
        }
    }

    @Override
    public List<Map<String, Object>> getLabelingMethods() {
        try {
            log.info("Getting labeling methods statistics from database");
            
            List<Map<String, Object>> methods = new ArrayList<>();
            
            // Get real method statistics from completed jobs
            List<LabelingJobEntity> completedJobs = labelingJobRepository.findAll().stream()
                    .filter(job -> job.getEffectiveMlConfig() != null)
                    .collect(Collectors.toList());
            
            // Group jobs by their ML configuration provider type
            Map<String, Long> methodCounts = completedJobs.stream()
                    .map(job -> {
                        String provider = job.getEffectiveMlConfig().getProviderType();
                        if (provider == null || provider.isEmpty()) {
                            return "Unknown";
                        }
                        return provider.toUpperCase();
                    })
                    .collect(Collectors.groupingBy(method -> method, Collectors.counting()));
            
            // Add rule-based jobs (jobs without ML config)
            long ruleBasedCount = completedJobs.stream()
                    .filter(job -> job.getEffectiveMlConfig() == null)
                    .count();
            
            if (ruleBasedCount > 0) {
                methodCounts.put("RULE_BASED", ruleBasedCount);
            }
            
            // Calculate total for percentage calculation
            long totalJobs = completedJobs.size();
            
            // Create method statistics from real data
            for (Map.Entry<String, Long> entry : methodCounts.entrySet()) {
                Map<String, Object> method = new HashMap<>();
                method.put("method", entry.getKey());
                method.put("count", entry.getValue());
                method.put("percentage", totalJobs > 0 ? (entry.getValue() * 100.0) / totalJobs : 0.0);
                
                methods.add(method);
            }
            
            return methods;
            
        } catch (Exception e) {
            log.error("Error getting labeling methods", e);
            throw new AutoLabelingException("Failed to get labeling methods", e);
        }
    }

    @Override
    public List<Map<String, Object>> getLabelingStatus() {
        try {
            log.info("Getting labeling status statistics");
            
            List<Map<String, Object>> statuses = new ArrayList<>();
            
            // Get real status counts from database
            List<LabelingJobEntity> allJobs = labelingJobRepository.findAll();
            
            Map<LabelingJobEntity.JobStatus, Long> statusCounts = allJobs.stream()
                    .collect(Collectors.groupingBy(job -> job.getStatus(), Collectors.counting()));
            
            for (LabelingJobEntity.JobStatus status : LabelingJobEntity.JobStatus.values()) {
                Map<String, Object> statusInfo = new HashMap<>();
                statusInfo.put("status", status.name());
                statusInfo.put("count", statusCounts.getOrDefault(status, 0L));
                statusInfo.put("percentage", allJobs.isEmpty() ? 0.0 : 
                    (statusCounts.getOrDefault(status, 0L) * 100.0) / allJobs.size());
                
                statuses.add(statusInfo);
            }
            
            return statuses;
            
        } catch (Exception e) {
            log.error("Error getting labeling status", e);
            throw new AutoLabelingException("Failed to get labeling status", e);
        }
    }

    // Helper methods
    private List<String> getLabelsFromSummary(Map<String, Integer> labelSummary) {
        if (labelSummary == null || labelSummary.isEmpty()) {
            return Arrays.asList("General", "Data");
        }
        return new ArrayList<>(labelSummary.keySet());
    }

    private String getMethodFromJob(LabelingJobEntity job) {
        if (job.getEffectiveMlConfig() != null) {
            String provider = job.getEffectiveMlConfig().getProviderType();
            if ("openai".equalsIgnoreCase(provider)) {
                return "ML Model (OpenAI)";
            } else if ("ollama".equalsIgnoreCase(provider)) {
                return "ML Model (Ollama)";
            } else {
                return "ML Model";
            }
        }
        return "Rule-Based";
    }

    private String getCategoryForLabel(String labelName) {
        if (labelName.toLowerCase().contains("pii") || labelName.toLowerCase().contains("personal")) {
            return "Security";
        } else if (labelName.toLowerCase().contains("financial") || labelName.toLowerCase().contains("payment")) {
            return "Financial";
        } else if (labelName.toLowerCase().contains("customer")) {
            return "Customer Data";
        } else if (labelName.toLowerCase().contains("contact") || labelName.toLowerCase().contains("address")) {
            return "Contact";
        } else {
            return "Classification";
        }
    }

    private String getColorForLabel(String labelName) {
        if (labelName.toLowerCase().contains("pii") || labelName.toLowerCase().contains("personal")) {
            return "#ef4444"; // Red
        } else if (labelName.toLowerCase().contains("financial") || labelName.toLowerCase().contains("payment")) {
            return "#f59e0b"; // Amber
        } else if (labelName.toLowerCase().contains("customer")) {
            return "#10b981"; // Green
        } else if (labelName.toLowerCase().contains("contact") || labelName.toLowerCase().contains("address")) {
            return "#3b82f6"; // Blue
        } else {
            return "#8b5cf6"; // Purple
        }
    }

    private String getColorForCategory(String category) {
        Map<String, String> colorMap = Map.of(
                "Security", "#ef4444",
                "Classification", "#3b82f6",
                "Domain", "#10b981",
                "Status", "#84cc16",
                "PII", "#ef4444",
                "Financial", "#f59e0b",
                "Customer Data", "#10b981",
                "Temporary", "#6b7280"
        );
        return colorMap.getOrDefault(category, "#8b5cf6");
    }
    
    private double calculateSuccessRate(LabelingJobEntity job) {
        if (job.getAssetsProcessed() == null || job.getAssetsProcessed() == 0) {
            return 0.0;
        }
        return (double) (job.getAssetsSuccessfullyLabeled() != null ? job.getAssetsSuccessfullyLabeled() : 0) / 
               job.getAssetsProcessed() * 100;
    }
    
    private String determineMethod(LabelingJobEntity job) {
        if (job.getEffectiveMlConfig() != null) {
            String provider = job.getEffectiveMlConfig().getProviderType();
            if (provider != null && !provider.isEmpty()) {
                return "ML Model (" + provider.toUpperCase() + ")";
            }
            return "ML Model";
        }
        return "Rule-Based";
    }
    
    private Double calculateConfidence(LabelingJobEntity job) {
        if (job.getAssetsProcessed() != null && job.getAssetsProcessed() > 0) {
            double successRate = (double) (job.getAssetsSuccessfullyLabeled() != null ? job.getAssetsSuccessfullyLabeled() : 0) / 
                    job.getAssetsProcessed();
            return Math.round(successRate * 100 * 10.0) / 10.0;
        }
        return null;
    }
    
    private LocalDateTime parseTimeAgo(String timeAgo) {
        // Simple parser for time ago strings - in production, you'd want a more robust solution
        if (timeAgo == null || timeAgo.equals("Unknown")) {
            return LocalDateTime.MIN;
        }
        
        try {
            if (timeAgo.contains("minutes ago")) {
                int minutes = Integer.parseInt(timeAgo.split(" ")[0]);
                return LocalDateTime.now().minusMinutes(minutes);
            } else if (timeAgo.contains("hours ago")) {
                int hours = Integer.parseInt(timeAgo.split(" ")[0]);
                return LocalDateTime.now().minusHours(hours);
            } else if (timeAgo.contains("days ago")) {
                int days = Integer.parseInt(timeAgo.split(" ")[0]);
                return LocalDateTime.now().minusDays(days);
            }
        } catch (Exception e) {
            log.warn("Could not parse time ago string: {}", timeAgo);
        }
        
        return LocalDateTime.MIN;
    }
    
    private String formatTimeAgo(LocalDateTime dateTime) {
        if (dateTime == null) {
            return "Unknown";
        }
        
        long minutesAgo = java.time.Duration.between(dateTime, LocalDateTime.now()).toMinutes();
        
        if (minutesAgo < 1) {
            return "Just now";
        } else if (minutesAgo < 60) {
            return minutesAgo + " minutes ago";
        } else if (minutesAgo < 1440) { // 24 hours
            long hoursAgo = minutesAgo / 60;
            return hoursAgo + " hours ago";
        } else {
            long daysAgo = minutesAgo / 1440;
            return daysAgo + " days ago";
        }
    }

    private String getCategoryFromRuleName(String ruleName) {
        if (ruleName.toLowerCase().contains("pii") || ruleName.toLowerCase().contains("personal")) {
            return "PII";
        } else if (ruleName.toLowerCase().contains("financial") || ruleName.toLowerCase().contains("payment")) {
            return "Financial";
        } else if (ruleName.toLowerCase().contains("customer")) {
            return "Customer Data";
        } else if (ruleName.toLowerCase().contains("contact") || ruleName.toLowerCase().contains("address")) {
            return "Contact";
        } else {
            return "General";
        }
    }

    private double calculateRealConfidence(LabelingJobEntity job) {
        // Calculate confidence based on success rate
        if (job.getAssetsProcessed() != null && job.getAssetsProcessed() > 0) {
            double successRate = (double) (job.getAssetsSuccessfullyLabeled() != null ? job.getAssetsSuccessfullyLabeled() : 0) / 
                    job.getAssetsProcessed();
            return Math.round(successRate * 100 * 10.0) / 10.0; // Convert to percentage with 1 decimal place
        }
        return 0.0;
    }
} 