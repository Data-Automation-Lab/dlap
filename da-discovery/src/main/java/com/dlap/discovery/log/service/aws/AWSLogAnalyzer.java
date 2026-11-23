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

package com.dlap.discovery.log.service.aws;

import static com.dlap.discovery.common.constants.LoggingConstants.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.auth.CloudAuthenticationService;
import com.dlap.discovery.providers.aws.config.AWSConfigService;
import com.dlap.discovery.common.model.ResourceChange;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.event.service.type.LogEvent;
import com.dlap.discovery.log.service.ILogAnalyzer;
import com.dlap.discovery.log.service.aws.adapter.AWSLogEntryAdapter;
import com.dlap.discovery.log.service.aws.adapter.AWSLoggingAdapter;

/**
 * AWS log analyzer implementation.
 * Processes AWS CloudTrail logs asynchronously and publishes ResourceChange
 * events to Kafka.
 */
@Service("awsLogAnalyzer")
@ConditionalOnProperty(name = "cloud.provider.aws.enabled", havingValue = "true", matchIfMissing = false)
public class AWSLogAnalyzer implements ILogAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(AWSLogAnalyzer.class);

    private final AWSConfigService awsConfigService;
    private final CloudAuthenticationService authService;
    private final CloudHierarchyRegistry hierarchyRegistry;
    private final AWSLoggingAdapter loggingAdapter;
    private final KafkaTemplate<String, LogEvent> kafkaTemplate;

    @Value("${kafka.topics.log-events:dlap.log.event.normalized}")
    private String logEventsTopic;

    /**
     * Mapping of event names to change types.
     */
    private static final Map<String, ResourceChange.ChangeType> EVENT_TO_CHANGE_TYPE;
    static {
        Map<String, ResourceChange.ChangeType> map = new HashMap<>();
        map.put("Create", ResourceChange.ChangeType.CREATE);
        map.put("Update", ResourceChange.ChangeType.UPDATE);
        map.put("Delete", ResourceChange.ChangeType.DELETE);
        map.put("Get", ResourceChange.ChangeType.ACCESS);
        map.put("List", ResourceChange.ChangeType.ACCESS);
        map.put("Describe", ResourceChange.ChangeType.ACCESS);
        map.put("Put", ResourceChange.ChangeType.UPDATE);
        EVENT_TO_CHANGE_TYPE = Collections.unmodifiableMap(map);
    }

    @Autowired
    public AWSLogAnalyzer(
            @Autowired(required = false) AWSConfigService awsConfigService,
            @Autowired(required = false) @Qualifier("awsAuthenticationServiceImpl") CloudAuthenticationService authService,
            CloudHierarchyRegistry hierarchyRegistry,
            @Autowired(required = false) AWSLoggingAdapter loggingAdapter,
            @Qualifier("logEventKafkaTemplate") KafkaTemplate<String, LogEvent> kafkaTemplate) {
        this.awsConfigService = awsConfigService;
        this.authService = authService;
        this.hierarchyRegistry = hierarchyRegistry;
        this.loggingAdapter = loggingAdapter;
        this.kafkaTemplate = kafkaTemplate;

        // Log warning if required AWS services are not available
        if (awsConfigService == null) {
            log.warn("AWSConfigService is not available. AWS log analysis will be limited.");
        }
        if (authService == null) {
            log.warn("AWS Authentication Service is not available. AWS log analysis will be limited.");
        }
        if (loggingAdapter == null) {
            log.warn("AWSLoggingAdapter is not available. AWS log analysis will be disabled.");
        }
    }

    @Override
    public void triggerLogAnalysisAsync(String accountId, ZonedDateTime startTime,
            ZonedDateTime endTime, AnalysisOptions options) {
        // Check if required dependencies are available
        if (loggingAdapter == null || awsConfigService == null || authService == null) {
            log.error("Cannot trigger AWS log analysis - required dependencies are not available");
            return;
        }

        log.info("Triggering async AWS log analysis for account {} between {} and {}",
                accountId, startTime, endTime);
        performActualLogAnalysis(accountId, startTime, endTime, options);
    }

    @Async("discoveryAsyncExecutor")
    protected void performActualLogAnalysis(String accountId, ZonedDateTime startTime,
            ZonedDateTime endTime, AnalysisOptions options) {
        // Check if required dependencies are available
        if (loggingAdapter == null || awsConfigService == null || authService == null) {
            log.error("Cannot perform AWS log analysis - required dependencies are not available");
            return;
        }

        int changeCount = 0;
        if (accountId == null || accountId.isBlank()) {
            log.error("Account ID is required for AWS log analysis");
            return;
        }
        log.info("Starting async AWS log analysis for account {} between {} and {}",
                accountId, startTime, endTime);

        try {
            String filter = buildFilterString(accountId, startTime, endTime, options);
            log.debug("Using filter: {}", filter);

            List<AWSLogEntryAdapter> entries = loggingAdapter.queryLogs(
                    accountId,
                    filter,
                    options.getBatchSize() > 0 ? options.getBatchSize() : 1000); // Default batch size

            for (AWSLogEntryAdapter entry : entries) {
                try {
                    processSingleLogEntry(accountId, entry, options);
                    changeCount++;
                } catch (Exception e) {
                    log.error("Error processing single log entry: {}", e.getMessage(), e);
                }

                // Optional: Check batch size limit if needed during async processing
                // if (options.getBatchSize() > 0 && changeCount >= options.getBatchSize()) {
                // log.info("Reached batch size limit of {} changes", options.getBatchSize());
                // break;
                // }
            }
            log.info("Completed async AWS log analysis for account {}. Found {} potential changes.",
                    accountId, changeCount);

        } catch (Exception e) {
            log.error("Error during async AWS log analysis for account {}: {}", accountId, e.getMessage(), e);
            // Consider publishing an error event
        }
    }

    @Override
    public void processLogEventAsync(String accountId, Object logEvent, AnalysisOptions options) {
        // Check if required dependencies are available
        if (awsConfigService == null || authService == null) {
            log.error("Cannot process AWS log event - required dependencies are not available");
            return;
        }

        log.info("Triggering async processing of single AWS log event for account {}", accountId);
        processSingleLogEntry(accountId, logEvent, options);
    }

    @Async("discoveryAsyncExecutor")
    protected void processSingleLogEntry(String accountId, Object logEvent, AnalysisOptions options) {
        if (!(logEvent instanceof AWSLogEntryAdapter)) {
            log.warn("Log event is not an AWSLogEntryAdapter: {}", logEvent.getClass().getName());
            return;
        }

        AWSLogEntryAdapter entry = (AWSLogEntryAdapter) logEvent;
        log.debug("Processing single AWS log entry: EventID={}, EventName={}", entry.getEventId(),
                entry.getEventName());

        try {
            String eventName = entry.getEventName();
            String eventSource = entry.getEventSource();
            ResourceType resourceType = mapAWSServiceToResourceType(eventSource);

            // Apply filters from options
            if (options != null) {
                if (options.getResourceTypes() != null &&
                        !options.getResourceTypes().isEmpty() &&
                        (resourceType == null || !options.getResourceTypes().contains(resourceType))) {
                    log.trace("Skipping event due to resource type filter");
                    return;
                }

                ResourceChange.ChangeType changeType = determineChangeType(eventName);
                if (options.getChangeTypes() != null &&
                        !options.getChangeTypes().isEmpty() &&
                        !options.getChangeTypes().contains(changeType.name())) {
                    log.trace("Skipping event due to change type filter");
                    return;
                }
            }

            ResourceChange change = createResourceChangeFromLogEntry(accountId, entry, resourceType);
            if (change != null) {
                publishLogEvent(accountId, change);
            }

        } catch (Exception e) {
            log.error("Error processing AWS log entry {}: {}", entry.getEventId(), e.getMessage(), e);
            // Consider publishing an error event
        }
    }

    /**
     * Creates a ResourceChange object from an AWSLogEntryAdapter.
     */
    private ResourceChange createResourceChangeFromLogEntry(String accountId, AWSLogEntryAdapter entry,
            ResourceType resourceType) {
        String eventName = entry.getEventName();
        ResourceChange.ChangeType changeType = determineChangeType(eventName);
        String eventSource = entry.getEventSource();

        // Fallback if resource type mapping failed earlier
        if (resourceType == null) {
            resourceType = mapAWSServiceToResourceType(eventSource);
        }

        String resourceId = "unknown";
        String resourceName = "unknown";
        Map<String, String> resources = entry.getResources();
        if (resources != null && !resources.isEmpty()) {
            if (resources.containsKey("resourceName")) {
                resourceName = resources.get("resourceName");
                resourceId = resourceName;
            }
            if (resources.containsKey("resourceId")) {
                resourceId = resources.get("resourceId");
            }
        }

        ResourceChange change = new ResourceChange(
                resourceId,
                resourceType,
                changeType,
                entry.getEventTime(),
                entry.getUserIdentity());

        change.setProjectId(accountId); // Using projectId field for accountId
        change.setActorEmail(entry.getUserIdentity()); // Best guess for actor email

        // Add details
        Map<String, String> details = new HashMap<>();
        details.put("sourceIp", entry.getSourceIpAddress());
        details.put("region", entry.getAwsRegion());
        details.put("resourceName", resourceName);
        details.put("eventSource", eventSource);
        details.put("eventName", eventName);
        details.put("userAgent", entry.getUserAgent());
        details.put("eventId", entry.getEventId());
        // Add error info if present
        if (entry.getErrorCode() != null) {
            details.put("errorCode", entry.getErrorCode());
            details.put("errorMessage", entry.getErrorMessage());
        }
        change.setDetails(details);

        return change;
    }

    /**
     * Helper method to publish log events to Kafka.
     */
    private void publishLogEvent(String accountId, ResourceChange change) {
        try {
            LogEvent event = new LogEvent(CloudProvider.AWS.toString(), accountId, change);
            log.debug("Publishing log event: {}", event);
            kafkaTemplate.send(logEventsTopic, event.getEventId(), event);
            log.info("Published log event for resource: {}, change type: {}",
                    change.getResourceId(), change.getChangeType());
        } catch (Exception e) {
            log.error("Error publishing log event for account {}: {}",
                    accountId, e.getMessage(), e);
        }
    }

    // --- Other ILogAnalyzer Methods ---

    @Override
    public boolean supportsResourceType(ResourceType type) {
        return type != null && type.service() != null && type.service().provider() == CloudProvider.AWS;
    }

    @Override
    public String getProviderName() {
        return AWS_PROVIDER_NAME;
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.AWS;
    }

    @Override
    public boolean hasNewLogs(String accountId, Instant since) {
        try {
            ZonedDateTime sinceZdt = since.atZone(ZoneOffset.UTC);
            String filter = "EventTime > '" + sinceZdt.format(DateTimeFormatter.ISO_INSTANT) + "'";
            List<AWSLogEntryAdapter> entries = loggingAdapter.queryLogs(accountId, filter, 1);
            return !entries.isEmpty();
        } catch (Exception e) {
            log.error("Error checking for new logs for account {}: {}", accountId, e.getMessage(), e);
            return false;
        }
    }

    // --- Helper Methods (buildFilterString, mapAWSServiceToResourceType, etc.) ---
    // These remain largely the same as they support the core logic

    private String buildFilterString(String accountId, ZonedDateTime startTime, ZonedDateTime endTime,
            AnalysisOptions options) {
        StringBuilder filter = new StringBuilder();

        // Add time range filter
        filter.append("EventTime >= '").append(startTime.format(DateTimeFormatter.ISO_INSTANT))
                .append("' AND EventTime <= '").append(endTime.format(DateTimeFormatter.ISO_INSTANT)).append("'");

        // Add resource types filter if specified
        if (options != null && options.getResourceTypes() != null && !options.getResourceTypes().isEmpty()) {
            filter.append(" AND (");
            boolean first = true;

            for (ResourceType type : options.getResourceTypes()) {
                // Map ResourceType to AWS service string
                String awsService = mapResourceTypeToAWSService(type);
                if (awsService != null) {
                    if (!first) {
                        filter.append(" OR ");
                    }
                    filter.append("EventSource = '").append(awsService).append("'");
                    first = false;
                }
            }

            filter.append(")");
        }

        // Add change types filter if specified
        if (options != null && options.getChangeTypes() != null && !options.getChangeTypes().isEmpty()) {
            filter.append(" AND (");
            boolean first = true;

            for (String changeType : options.getChangeTypes()) {
                // Map change type to AWS event name patterns
                List<String> eventPatterns = mapChangeTypeToAWSEvents(changeType);
                for (String pattern : eventPatterns) {
                    if (!first) {
                        filter.append(" OR ");
                    }
                    filter.append("EventName LIKE '").append(pattern).append("'");
                    first = false;
                }
            }

            filter.append(")");
        }

        // Add custom filters if specified
        if (options != null && options.getFilters() != null && !options.getFilters().isEmpty()) {
            for (Map.Entry<String, String> filterEntry : options.getFilters().entrySet()) {
                filter.append(" AND ").append(filterEntry.getKey()).append(" = '").append(filterEntry.getValue())
                        .append("'");
            }
        }

        return filter.toString();
    }

    private ResourceType mapAWSServiceToResourceType(String service) {
        String typeId;
        // Map AWS service string to ResourceType ID
        if (service.contains("s3")) {
            typeId = "aws_s3_bucket";
        } else if (service.contains("ec2")) {
            typeId = "aws_ec2_instance";
        } else if (service.contains("rds")) {
            typeId = "aws_rds_database";
        } else if (service.contains("dynamodb")) {
            typeId = "aws_dynamodb_table";
        } else if (service.contains("lambda")) {
            typeId = "aws_lambda_function";
        } else {
            // Default to a generic AWS resource type
            typeId = "aws_resource";
        }

        // Look up ResourceType record by ID
        ResourceType type = hierarchyRegistry.getResourceType(typeId);
        if (type == null) {
            log.warn("No ResourceType found for AWS service: {} (mapped to ID: {})", service, typeId);
            // Create a fallback type if needed
            // Consider if a fallback is appropriate or if null should be returned
            return null; // Returning null might be clearer
        }
        return type;
    }

    private String mapResourceTypeToAWSService(ResourceType type) {
        if (type == null || type.id() == null) {
            return null;
        }

        String serviceString;
        switch (type.id()) {
            case "aws_s3_bucket":
                serviceString = "s3.amazonaws.com";
                break;
            case "aws_ec2_instance":
                serviceString = "ec2.amazonaws.com";
                break;
            case "aws_rds_database":
                serviceString = "rds.amazonaws.com";
                break;
            case "aws_dynamodb_table":
                serviceString = "dynamodb.amazonaws.com";
                break;
            case "aws_lambda_function":
                serviceString = "lambda.amazonaws.com";
                break;
            default:
                log.warn("No known AWS service for ResourceType: {}", type.id());
                return null;
        }
        return serviceString;
    }

    private ResourceChange.ChangeType determineChangeType(String eventName) {
        // Check for exact matches
        for (Map.Entry<String, ResourceChange.ChangeType> entry : EVENT_TO_CHANGE_TYPE.entrySet()) {
            if (eventName.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        // Default to UPDATE if we can't determine
        return ResourceChange.ChangeType.UPDATE;
    }

    private List<String> mapChangeTypeToAWSEvents(String changeType) {
        List<String> patterns = new ArrayList<>();

        if (changeType == null) {
            return patterns;
        }

        switch (changeType.toUpperCase()) {
            case "CREATE":
                patterns.add("Create%");
                patterns.add("%Create");
                break;
            case "UPDATE":
                patterns.add("Update%");
                patterns.add("%Update");
                patterns.add("Modify%");
                patterns.add("%Modify");
                patterns.add("Put%");
                break;
            case "DELETE":
                patterns.add("Delete%");
                patterns.add("%Delete");
                patterns.add("Remove%");
                break;
            case "ACCESS":
                patterns.add("Get%");
                patterns.add("List%");
                patterns.add("Describe%");
                break;
            default:
                log.warn("Unknown change type: {}", changeType);
        }

        return patterns;
    }

    // Removed analyzeLogsBetween, analyzeLogEvent, analyzeLogs, extractMetadata
    // implementations
    // Removed PreDestroy cleanup method (managed by Spring container)
}