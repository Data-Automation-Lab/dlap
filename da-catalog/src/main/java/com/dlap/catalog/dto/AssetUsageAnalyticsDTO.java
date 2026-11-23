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

package com.dlap.catalog.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DTO for asset usage analytics.
 *
 * <p>Every value in this DTO comes from data the catalog has stored for the asset
 * (the access counter and last-access timestamp that the lifecycle listener writes
 * into {@code dlap_usage_metrics}). A field that has no stored data source is
 * {@code null}: fields inside a section are written as JSON {@code null}, and a null
 * top-level list (such as {@code popularQueries}) is omitted. Consumers must treat
 * either form as "not available", never as zero. {@link #getAvailable()} is
 * {@code false} when the catalog holds no usage signal at all for the asset.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AssetUsageAnalyticsDTO {

    /** True when the catalog holds at least one stored usage signal for the asset. */
    private Boolean available;
    /** Short, human-readable statement of where the values come from (or why there are none). */
    private String basis;
    private UsageMetricsDTO usageMetrics;
    private UserAnalyticsDTO userAnalytics;
    private AccessPatternsDTO accessPatterns;
    private PerformanceMetricsDTO performanceMetrics;
    private List<PopularQueryDTO> popularQueries;
    private List<RecommendationDTO> recommendations;

    // Constructor
    public AssetUsageAnalyticsDTO() {}

    // Getters and Setters
    public Boolean getAvailable() {
        return available;
    }

    public void setAvailable(Boolean available) {
        this.available = available;
    }

    public String getBasis() {
        return basis;
    }

    public void setBasis(String basis) {
        this.basis = basis;
    }

    public UsageMetricsDTO getUsageMetrics() {
        return usageMetrics;
    }

    public void setUsageMetrics(UsageMetricsDTO usageMetrics) {
        this.usageMetrics = usageMetrics;
    }

    public UserAnalyticsDTO getUserAnalytics() {
        return userAnalytics;
    }

    public void setUserAnalytics(UserAnalyticsDTO userAnalytics) {
        this.userAnalytics = userAnalytics;
    }

    public AccessPatternsDTO getAccessPatterns() {
        return accessPatterns;
    }

    public void setAccessPatterns(AccessPatternsDTO accessPatterns) {
        this.accessPatterns = accessPatterns;
    }

    public PerformanceMetricsDTO getPerformanceMetrics() {
        return performanceMetrics;
    }

    public void setPerformanceMetrics(PerformanceMetricsDTO performanceMetrics) {
        this.performanceMetrics = performanceMetrics;
    }

    public List<PopularQueryDTO> getPopularQueries() {
        return popularQueries;
    }

    public void setPopularQueries(List<PopularQueryDTO> popularQueries) {
        this.popularQueries = popularQueries;
    }

    public List<RecommendationDTO> getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(List<RecommendationDTO> recommendations) {
        this.recommendations = recommendations;
    }

    /**
     * Usage metrics showing overall asset access statistics.
     */
    public static class UsageMetricsDTO {
        private Long totalAccesses;
        private Integer uniqueUsers;
        private Double avgAccessesPerDay;
        private Instant lastAccessed;
        private Integer peakUsageHour;
        private String usageTrend; // INCREASING, DECREASING, STABLE

        public UsageMetricsDTO() {}

        public Long getTotalAccesses() {
            return totalAccesses;
        }

        public void setTotalAccesses(Long totalAccesses) {
            this.totalAccesses = totalAccesses;
        }

        public Integer getUniqueUsers() {
            return uniqueUsers;
        }

        public void setUniqueUsers(Integer uniqueUsers) {
            this.uniqueUsers = uniqueUsers;
        }

        public Double getAvgAccessesPerDay() {
            return avgAccessesPerDay;
        }

        public void setAvgAccessesPerDay(Double avgAccessesPerDay) {
            this.avgAccessesPerDay = avgAccessesPerDay;
        }

        public Instant getLastAccessed() {
            return lastAccessed;
        }

        public void setLastAccessed(Instant lastAccessed) {
            this.lastAccessed = lastAccessed;
        }

        public Integer getPeakUsageHour() {
            return peakUsageHour;
        }

        public void setPeakUsageHour(Integer peakUsageHour) {
            this.peakUsageHour = peakUsageHour;
        }

        public String getUsageTrend() {
            return usageTrend;
        }

        public void setUsageTrend(String usageTrend) {
            this.usageTrend = usageTrend;
        }
    }

    /**
     * User analytics showing who is accessing the asset and how.
     */
    public static class UserAnalyticsDTO {
        private List<TopUserDTO> topUsers;
        private Map<String, Double> departmentBreakdown;

        public UserAnalyticsDTO() {}

        public List<TopUserDTO> getTopUsers() {
            return topUsers;
        }

        public void setTopUsers(List<TopUserDTO> topUsers) {
            this.topUsers = topUsers;
        }

        public Map<String, Double> getDepartmentBreakdown() {
            return departmentBreakdown;
        }

        public void setDepartmentBreakdown(Map<String, Double> departmentBreakdown) {
            this.departmentBreakdown = departmentBreakdown;
        }
    }

    /**
     * Individual user access information.
     */
    public static class TopUserDTO {
        private UUID userId;
        private String username;
        private Integer accessCount;
        private Instant lastAccess;
        private String accessPattern; // REGULAR, OCCASIONAL, HEAVY

        public TopUserDTO() {}

        public TopUserDTO(UUID userId, String username, Integer accessCount, Instant lastAccess, String accessPattern) {
            this.userId = userId;
            this.username = username;
            this.accessCount = accessCount;
            this.lastAccess = lastAccess;
            this.accessPattern = accessPattern;
        }

        public UUID getUserId() {
            return userId;
        }

        public void setUserId(UUID userId) {
            this.userId = userId;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public Integer getAccessCount() {
            return accessCount;
        }

        public void setAccessCount(Integer accessCount) {
            this.accessCount = accessCount;
        }

        public Instant getLastAccess() {
            return lastAccess;
        }

        public void setLastAccess(Instant lastAccess) {
            this.lastAccess = lastAccess;
        }

        public String getAccessPattern() {
            return accessPattern;
        }

        public void setAccessPattern(String accessPattern) {
            this.accessPattern = accessPattern;
        }
    }

    /**
     * Access patterns showing temporal usage distribution.
     */
    public static class AccessPatternsDTO {
        private List<Integer> hourlyDistribution; // 24 hour array
        private List<Integer> weeklyDistribution; // 7 day array
        private List<MonthlyTrendDTO> monthlyTrend;

        public AccessPatternsDTO() {}

        public List<Integer> getHourlyDistribution() {
            return hourlyDistribution;
        }

        public void setHourlyDistribution(List<Integer> hourlyDistribution) {
            this.hourlyDistribution = hourlyDistribution;
        }

        public List<Integer> getWeeklyDistribution() {
            return weeklyDistribution;
        }

        public void setWeeklyDistribution(List<Integer> weeklyDistribution) {
            this.weeklyDistribution = weeklyDistribution;
        }

        public List<MonthlyTrendDTO> getMonthlyTrend() {
            return monthlyTrend;
        }

        public void setMonthlyTrend(List<MonthlyTrendDTO> monthlyTrend) {
            this.monthlyTrend = monthlyTrend;
        }
    }

    /**
     * Monthly usage trend data.
     */
    public static class MonthlyTrendDTO {
        private String month; // YYYY-MM format
        private Integer totalAccesses;
        private Integer uniqueUsers;

        public MonthlyTrendDTO() {}

        public MonthlyTrendDTO(String month, Integer totalAccesses, Integer uniqueUsers) {
            this.month = month;
            this.totalAccesses = totalAccesses;
            this.uniqueUsers = uniqueUsers;
        }

        public String getMonth() {
            return month;
        }

        public void setMonth(String month) {
            this.month = month;
        }

        public Integer getTotalAccesses() {
            return totalAccesses;
        }

        public void setTotalAccesses(Integer totalAccesses) {
            this.totalAccesses = totalAccesses;
        }

        public Integer getUniqueUsers() {
            return uniqueUsers;
        }

        public void setUniqueUsers(Integer uniqueUsers) {
            this.uniqueUsers = uniqueUsers;
        }
    }

    /**
     * Performance metrics for asset access.
     */
    public static class PerformanceMetricsDTO {
        private String avgQueryTime;
        private String slowestQuery;
        private String fastestQuery;
        private Double timeoutRate;
        private Double errorRate;

        public PerformanceMetricsDTO() {}

        public String getAvgQueryTime() {
            return avgQueryTime;
        }

        public void setAvgQueryTime(String avgQueryTime) {
            this.avgQueryTime = avgQueryTime;
        }

        public String getSlowestQuery() {
            return slowestQuery;
        }

        public void setSlowestQuery(String slowestQuery) {
            this.slowestQuery = slowestQuery;
        }

        public String getFastestQuery() {
            return fastestQuery;
        }

        public void setFastestQuery(String fastestQuery) {
            this.fastestQuery = fastestQuery;
        }

        public Double getTimeoutRate() {
            return timeoutRate;
        }

        public void setTimeoutRate(Double timeoutRate) {
            this.timeoutRate = timeoutRate;
        }

        public Double getErrorRate() {
            return errorRate;
        }

        public void setErrorRate(Double errorRate) {
            this.errorRate = errorRate;
        }
    }

    /**
     * Popular query patterns and their performance.
     */
    public static class PopularQueryDTO {
        private String queryPattern;
        private Integer frequency;
        private String avgExecutionTime;

        public PopularQueryDTO() {}

        public PopularQueryDTO(String queryPattern, Integer frequency, String avgExecutionTime) {
            this.queryPattern = queryPattern;
            this.frequency = frequency;
            this.avgExecutionTime = avgExecutionTime;
        }

        public String getQueryPattern() {
            return queryPattern;
        }

        public void setQueryPattern(String queryPattern) {
            this.queryPattern = queryPattern;
        }

        public Integer getFrequency() {
            return frequency;
        }

        public void setFrequency(Integer frequency) {
            this.frequency = frequency;
        }

        public String getAvgExecutionTime() {
            return avgExecutionTime;
        }

        public void setAvgExecutionTime(String avgExecutionTime) {
            this.avgExecutionTime = avgExecutionTime;
        }
    }

    /**
     * Optimization recommendations based on usage analysis.
     */
    public static class RecommendationDTO {
        private String type; // OPTIMIZATION, MAINTENANCE, SECURITY, COMPLIANCE
        private String priority; // HIGH, MEDIUM, LOW
        private String description;
        private String potentialImpact;

        public RecommendationDTO() {}

        public RecommendationDTO(String type, String priority, String description, String potentialImpact) {
            this.type = type;
            this.priority = priority;
            this.description = description;
            this.potentialImpact = potentialImpact;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getPriority() {
            return priority;
        }

        public void setPriority(String priority) {
            this.priority = priority;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getPotentialImpact() {
            return potentialImpact;
        }

        public void setPotentialImpact(String potentialImpact) {
            this.potentialImpact = potentialImpact;
        }
    }
} 