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

package com.dlap.policyengine.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.dlap.policyengine.model.EventSeverity;
import com.dlap.policyengine.model.EventType;

/**
 * DTO for Event Center analytics and metrics dashboard.
 */
public class EventAnalyticsDTO {

    // Summary metrics
    private Long totalEventsToday;
    private Long totalEventsThisWeek;
    private Long totalEventsThisMonth;
    private Long totalActiveSubscriptions;
    private Long totalUsersWithSubscriptions;

    // Event distribution by type
    private List<EventTypeCount> eventsByType;

    // Event distribution by severity
    private List<EventSeverityCount> eventsBySeverity;

    // Event distribution by source service
    private List<SourceServiceCount> eventsBySourceService;

    // Top active subscriptions
    private List<TopSubscription> topActiveSubscriptions;

    // Recent event trends (hourly for last 24 hours)
    private List<EventTrendPoint> recentTrends;

    // Response time metrics
    private ResponseTimeMetrics responseMetrics;

    // Alert statistics
    private AlertStatistics alertStats;

    // Time range for this analytics report
    private Instant fromTime;
    private Instant toTime;
    private Instant generatedAt;

    // Constructors
    public EventAnalyticsDTO() {
        this.generatedAt = Instant.now();
    }

    public EventAnalyticsDTO(Instant fromTime, Instant toTime) {
        this.fromTime = fromTime;
        this.toTime = toTime;
        this.generatedAt = Instant.now();
    }

    // Getters and Setters

    public Long getTotalEventsToday() {
        return totalEventsToday;
    }

    public void setTotalEventsToday(Long totalEventsToday) {
        this.totalEventsToday = totalEventsToday;
    }

    public Long getTotalEventsThisWeek() {
        return totalEventsThisWeek;
    }

    public void setTotalEventsThisWeek(Long totalEventsThisWeek) {
        this.totalEventsThisWeek = totalEventsThisWeek;
    }

    public Long getTotalEventsThisMonth() {
        return totalEventsThisMonth;
    }

    public void setTotalEventsThisMonth(Long totalEventsThisMonth) {
        this.totalEventsThisMonth = totalEventsThisMonth;
    }

    public Long getTotalActiveSubscriptions() {
        return totalActiveSubscriptions;
    }

    public void setTotalActiveSubscriptions(Long totalActiveSubscriptions) {
        this.totalActiveSubscriptions = totalActiveSubscriptions;
    }

    public Long getTotalUsersWithSubscriptions() {
        return totalUsersWithSubscriptions;
    }

    public void setTotalUsersWithSubscriptions(Long totalUsersWithSubscriptions) {
        this.totalUsersWithSubscriptions = totalUsersWithSubscriptions;
    }

    public List<EventTypeCount> getEventsByType() {
        return eventsByType;
    }

    public void setEventsByType(List<EventTypeCount> eventsByType) {
        this.eventsByType = eventsByType;
    }

    public List<EventSeverityCount> getEventsBySeverity() {
        return eventsBySeverity;
    }

    public void setEventsBySeverity(List<EventSeverityCount> eventsBySeverity) {
        this.eventsBySeverity = eventsBySeverity;
    }

    public List<SourceServiceCount> getEventsBySourceService() {
        return eventsBySourceService;
    }

    public void setEventsBySourceService(List<SourceServiceCount> eventsBySourceService) {
        this.eventsBySourceService = eventsBySourceService;
    }

    public List<TopSubscription> getTopActiveSubscriptions() {
        return topActiveSubscriptions;
    }

    public void setTopActiveSubscriptions(List<TopSubscription> topActiveSubscriptions) {
        this.topActiveSubscriptions = topActiveSubscriptions;
    }

    public List<EventTrendPoint> getRecentTrends() {
        return recentTrends;
    }

    public void setRecentTrends(List<EventTrendPoint> recentTrends) {
        this.recentTrends = recentTrends;
    }

    public ResponseTimeMetrics getResponseMetrics() {
        return responseMetrics;
    }

    public void setResponseMetrics(ResponseTimeMetrics responseMetrics) {
        this.responseMetrics = responseMetrics;
    }

    public AlertStatistics getAlertStats() {
        return alertStats;
    }

    public void setAlertStats(AlertStatistics alertStats) {
        this.alertStats = alertStats;
    }

    public Instant getFromTime() {
        return fromTime;
    }

    public void setFromTime(Instant fromTime) {
        this.fromTime = fromTime;
    }

    public Instant getToTime() {
        return toTime;
    }

    public void setToTime(Instant toTime) {
        this.toTime = toTime;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
    }

    @Override
    public String toString() {
        return "EventAnalyticsDTO{" +
                "totalEventsToday=" + totalEventsToday +
                ", totalEventsThisWeek=" + totalEventsThisWeek +
                ", totalEventsThisMonth=" + totalEventsThisMonth +
                ", totalActiveSubscriptions=" + totalActiveSubscriptions +
                ", fromTime=" + fromTime +
                ", toTime=" + toTime +
                ", generatedAt=" + generatedAt +
                '}';
    }

    // Nested classes for analytics data structures

    public static class EventTypeCount {
        private EventType eventType;
        private Long count;
        private Double percentage;

        public EventTypeCount() {}

        public EventTypeCount(EventType eventType, Long count) {
            this.eventType = eventType;
            this.count = count;
        }

        public EventType getEventType() { return eventType; }
        public void setEventType(EventType eventType) { this.eventType = eventType; }
        public Long getCount() { return count; }
        public void setCount(Long count) { this.count = count; }
        public Double getPercentage() { return percentage; }
        public void setPercentage(Double percentage) { this.percentage = percentage; }
    }

    public static class EventSeverityCount {
        private EventSeverity severity;
        private Long count;
        private Double percentage;

        public EventSeverityCount() {}

        public EventSeverityCount(EventSeverity severity, Long count) {
            this.severity = severity;
            this.count = count;
        }

        public EventSeverity getSeverity() { return severity; }
        public void setSeverity(EventSeverity severity) { this.severity = severity; }
        public Long getCount() { return count; }
        public void setCount(Long count) { this.count = count; }
        public Double getPercentage() { return percentage; }
        public void setPercentage(Double percentage) { this.percentage = percentage; }
    }

    public static class SourceServiceCount {
        private String sourceService;
        private Long count;
        private Double percentage;

        public SourceServiceCount() {}

        public SourceServiceCount(String sourceService, Long count) {
            this.sourceService = sourceService;
            this.count = count;
        }

        public String getSourceService() { return sourceService; }
        public void setSourceService(String sourceService) { this.sourceService = sourceService; }
        public Long getCount() { return count; }
        public void setCount(Long count) { this.count = count; }
        public Double getPercentage() { return percentage; }
        public void setPercentage(Double percentage) { this.percentage = percentage; }
    }

    public static class TopSubscription {
        private String subscriptionName;
        private Long eventsMatched;
        private Long notificationsSent;
        private String ownerName;
        private Instant lastActivity;

        public TopSubscription() {}

        public TopSubscription(String subscriptionName, Long eventsMatched) {
            this.subscriptionName = subscriptionName;
            this.eventsMatched = eventsMatched;
        }

        public String getSubscriptionName() { return subscriptionName; }
        public void setSubscriptionName(String subscriptionName) { this.subscriptionName = subscriptionName; }
        public Long getEventsMatched() { return eventsMatched; }
        public void setEventsMatched(Long eventsMatched) { this.eventsMatched = eventsMatched; }
        public Long getNotificationsSent() { return notificationsSent; }
        public void setNotificationsSent(Long notificationsSent) { this.notificationsSent = notificationsSent; }
        public String getOwnerName() { return ownerName; }
        public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
        public Instant getLastActivity() { return lastActivity; }
        public void setLastActivity(Instant lastActivity) { this.lastActivity = lastActivity; }
    }

    public static class EventTrendPoint {
        private Instant timestamp;
        private Long eventCount;
        private Long criticalCount;
        private Long highCount;
        private Long mediumCount;
        private Long lowCount;

        public EventTrendPoint() {}

        public EventTrendPoint(Instant timestamp, Long eventCount) {
            this.timestamp = timestamp;
            this.eventCount = eventCount;
        }

        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
        public Long getEventCount() { return eventCount; }
        public void setEventCount(Long eventCount) { this.eventCount = eventCount; }
        public Long getCriticalCount() { return criticalCount; }
        public void setCriticalCount(Long criticalCount) { this.criticalCount = criticalCount; }
        public Long getHighCount() { return highCount; }
        public void setHighCount(Long highCount) { this.highCount = highCount; }
        public Long getMediumCount() { return mediumCount; }
        public void setMediumCount(Long mediumCount) { this.mediumCount = mediumCount; }
        public Long getLowCount() { return lowCount; }
        public void setLowCount(Long lowCount) { this.lowCount = lowCount; }
    }

    public static class ResponseTimeMetrics {
        private Double averageProcessingTimeMs;
        private Double averageNotificationTimeMs;
        private Long totalProcessedEvents;
        private Long failedNotifications;
        private Double successRate;

        public ResponseTimeMetrics() {}

        public Double getAverageProcessingTimeMs() { return averageProcessingTimeMs; }
        public void setAverageProcessingTimeMs(Double averageProcessingTimeMs) { this.averageProcessingTimeMs = averageProcessingTimeMs; }
        public Double getAverageNotificationTimeMs() { return averageNotificationTimeMs; }
        public void setAverageNotificationTimeMs(Double averageNotificationTimeMs) { this.averageNotificationTimeMs = averageNotificationTimeMs; }
        public Long getTotalProcessedEvents() { return totalProcessedEvents; }
        public void setTotalProcessedEvents(Long totalProcessedEvents) { this.totalProcessedEvents = totalProcessedEvents; }
        public Long getFailedNotifications() { return failedNotifications; }
        public void setFailedNotifications(Long failedNotifications) { this.failedNotifications = failedNotifications; }
        public Double getSuccessRate() { return successRate; }
        public void setSuccessRate(Double successRate) { this.successRate = successRate; }
    }

    public static class AlertStatistics {
        private Long totalAlertsTriggered;
        private Long totalNotificationsSent;
        private Long totalActionsExecuted;
        private Map<String, Long> alertsByChannel; // email, slack, webhook, etc.

        public AlertStatistics() {}

        public Long getTotalAlertsTriggered() { return totalAlertsTriggered; }
        public void setTotalAlertsTriggered(Long totalAlertsTriggered) { this.totalAlertsTriggered = totalAlertsTriggered; }
        public Long getTotalNotificationsSent() { return totalNotificationsSent; }
        public void setTotalNotificationsSent(Long totalNotificationsSent) { this.totalNotificationsSent = totalNotificationsSent; }
        public Long getTotalActionsExecuted() { return totalActionsExecuted; }
        public void setTotalActionsExecuted(Long totalActionsExecuted) { this.totalActionsExecuted = totalActionsExecuted; }
        public Map<String, Long> getAlertsByChannel() { return alertsByChannel; }
        public void setAlertsByChannel(Map<String, Long> alertsByChannel) { this.alertsByChannel = alertsByChannel; }
    }
} 