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

package com.dlap.discovery.log.service.oracle;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.dlap.discovery.common.auth.impl.oracle.IOracleAuthenticationService;
import com.dlap.discovery.common.config.cloud.impl.oracle.OracleConfigService;
import com.dlap.discovery.common.model.ResourceChange;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.event.service.type.LogEvent;
import com.dlap.discovery.log.service.ILogAnalyzer;
import com.dlap.discovery.log.service.oracle.adapter.OracleLogEntryAdapter;
import com.dlap.discovery.log.service.oracle.adapter.OracleLoggingAdapter;

/**
 * Implementation of ILogAnalyzer that processes Oracle Cloud Audit logs
 * asynchronously and publishes ResourceChange events to Kafka.
 */
@Service
@Qualifier("oracleLogAnalyzer")
@ConditionalOnProperty(name = "cloud.provider.oci.enabled", havingValue = "true", matchIfMissing = false)
public class OracleLogAnalyzer implements ILogAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(OracleLogAnalyzer.class);
    private static final String PROVIDER_NAME = "oracle";

    private static final Set<String> SUPPORTED_OCI_TYPE_IDS = Set.of(
            "oci_compute_instance",
            "oci_objectstorage_bucket");

    private final OracleConfigService configService;
    private final IOracleAuthenticationService authService;
    private final CloudHierarchyRegistry hierarchyRegistry;
    private final OracleLoggingAdapter loggingAdapter;
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
        EVENT_TO_CHANGE_TYPE = Collections.unmodifiableMap(map);
    }

    @Autowired
    public OracleLogAnalyzer(
            @Autowired(required = false) OracleConfigService configService,
            @Autowired(required = false) IOracleAuthenticationService authService,
            CloudHierarchyRegistry hierarchyRegistry,
            @Autowired(required = false) OracleLoggingAdapter loggingAdapter,
            KafkaTemplate<String, LogEvent> kafkaTemplate) {
        this.configService = configService;
        this.authService = authService;
        this.hierarchyRegistry = hierarchyRegistry;
        this.loggingAdapter = loggingAdapter;
        this.kafkaTemplate = kafkaTemplate;

        // Log warning if required OCI services are not available
        if (configService == null) {
            log.warn("OracleConfigService is not available. Oracle log analysis will be limited.");
        }
        if (authService == null) {
            log.warn("OracleAuthenticationService is not available. Oracle log analysis will be limited.");
        }
        if (loggingAdapter == null) {
            log.warn("OracleLoggingAdapter is not available. Oracle log analysis will be disabled.");
        }
    }

    @Override
    public void triggerLogAnalysisAsync(String accountId, ZonedDateTime startTime, ZonedDateTime endTime,
            AnalysisOptions options) {
        // Check if required dependencies are available
        if (configService == null || authService == null || loggingAdapter == null) {
            log.error("Cannot trigger Oracle log analysis - required dependencies are not available");
            return;
        }

        log.info("Triggering async Oracle log analysis for account {} between {} and {}", accountId, startTime,
                endTime);
        performActualLogAnalysis(accountId, startTime, endTime, options);
    }

    @Async("discoveryAsyncExecutor")
    protected void performActualLogAnalysis(String accountId, ZonedDateTime startTime, ZonedDateTime endTime,
            AnalysisOptions options) {
        // Check if required dependencies are available
        if (configService == null || authService == null || loggingAdapter == null) {
            log.error("Cannot perform Oracle log analysis - required dependencies are not available");
            return;
        }

        log.info("Starting async Oracle log analysis for account {} between {} and {}", accountId, startTime, endTime);
        int processedCount = 0;
        int changeEventCount = 0;

        try {
            String filter = buildFilterString(accountId, startTime, endTime, options);
            log.debug("Using Oracle filter: {}", filter);

            List<OracleLogEntryAdapter> entries = loggingAdapter.queryLogs(
                    accountId, // Using account ID as tenancy ID
                    filter,
                    options != null && options.getBatchSize() > 0 ? options.getBatchSize() : 1000); // Default batch
                                                                                                    // size

            processedCount = entries.size();
            for (OracleLogEntryAdapter entry : entries) {
                try {
                    boolean changePublished = processSingleLogEntry(accountId, entry, options);
                    if (changePublished)
                        changeEventCount++;
                } catch (Exception e) {
                    log.error("Error processing single Oracle log entry: {}", e.getMessage(), e);
                }
                // Optional: Check batch size limit
            }

            log.info(
                    "Completed async Oracle log analysis for account {}. Processed {} entries, published {} change events.",
                    accountId, processedCount, changeEventCount);

        } catch (Exception e) {
            log.error("Error during async Oracle audit log analysis for account {}: {}", accountId, e.getMessage(), e);
            // Consider publishing an error event
        }
        // Checkpointing is not implemented for Oracle in this example
    }

    @Override
    public void processLogEventAsync(String accountId, Object logEvent, AnalysisOptions options) {
        // Check if required dependencies are available
        if (configService == null || authService == null) {
            log.error("Cannot process Oracle log event - required dependencies are not available");
            return;
        }

        log.info("Triggering async processing of single Oracle log event for account {}", accountId);
        processSingleLogEntry(accountId, logEvent, options);
    }

    /**
     * Processes a single log entry and publishes an event if applicable.
     * Returns true if a change event was published, false otherwise.
     */
    @Async("discoveryAsyncExecutor")
    protected boolean processSingleLogEntry(String accountId, Object logEvent, AnalysisOptions options) {
        // Check if required dependencies are available
        if (configService == null || authService == null) {
            log.error("Cannot process Oracle log event - required dependencies are not available");
            return false;
        }

        if (!(logEvent instanceof OracleLogEntryAdapter)) {
            log.warn("Log event is not an OracleLogEntryAdapter: {}", logEvent.getClass().getName());
            return false;
        }

        OracleLogEntryAdapter entry = (OracleLogEntryAdapter) logEvent;
        log.debug("Processing single Oracle log entry: EventID={}, EventName={}", entry.getEventId(),
                entry.getEventName());

        try {
            String eventName = entry.getEventName();
            String resourceTypeString = entry.getResourceType();
            ResourceType domainResourceType = mapOracleResourceType(resourceTypeString);

            // Apply filters from options
            if (domainResourceType == null || (options != null && options.getResourceTypes() != null &&
                    !options.getResourceTypes().isEmpty() &&
                    !options.getResourceTypes().contains(domainResourceType))) {
                log.trace("Skipping Oracle log entry due to resource type filter or mapping failure: {}",
                        resourceTypeString);
                return false;
            }

            ResourceChange.ChangeType changeType = determineChangeType(eventName);
            if (options != null && options.getChangeTypes() != null &&
                    !options.getChangeTypes().isEmpty() &&
                    !options.getChangeTypes().contains(changeType.name())) {
                log.trace("Skipping Oracle log entry due to change type filter: {}", changeType.name());
                return false;
            }

            ResourceChange change = createResourceChangeFromLogEntry(accountId, entry, domainResourceType);
            if (change != null) {
                publishLogEvent(accountId, change);
                return true;
            }

        } catch (Exception e) {
            log.error("Error processing Oracle log entry {}: {}", entry.getEventId(), e.getMessage(), e);
        }
        return false;
    }

    /**
     * Creates a ResourceChange object from an OracleLogEntryAdapter.
     */
    private ResourceChange createResourceChangeFromLogEntry(String accountId, OracleLogEntryAdapter entry,
            ResourceType domainResourceType) {
        String eventName = entry.getEventName();
        ResourceChange.ChangeType changeType = determineChangeType(eventName);
        String resourceId = entry.getResourceId();
        String resourceName = entry.getResourceName();

        if (resourceId == null || resourceId.isBlank()) {
            log.warn("Skipping Oracle log entry with missing resourceId: EventID={}", entry.getEventId());
            return null;
        }

        ResourceChange change = new ResourceChange(
                resourceId,
                domainResourceType,
                changeType,
                entry.getEventTime(),
                entry.getUserIdentity());

        change.setProjectId(accountId); // Using projectId for OCI Tenancy ID (passed as accountId)
        // Actor email might be part of identity or request details, needs investigation

        // Add details
        Map<String, String> details = new HashMap<>();
        details.put("oci.eventName", eventName);
        details.put("oci.eventType", entry.getEventType());
        details.put("oci.resourceName", resourceName);
        details.put("oci.resourceType", entry.getResourceType()); // Keep original type
        details.put("oci.region", entry.getRegion());
        details.put("oci.compartmentId", entry.getCompartmentId());
        details.put("oci.compartmentName", entry.getCompartmentName());
        details.put("oci.tenancyId", entry.getTenancyId());
        details.put("oci.eventId", entry.getEventId());
        details.put("oci.sourceIpAddress", entry.getSourceIpAddress());
        details.put("oci.userAgent", entry.getUserAgent());
        if (entry.getErrorCode() != null)
            details.put("oci.errorCode", entry.getErrorCode());
        if (entry.getErrorMessage() != null)
            details.put("oci.errorMessage", entry.getErrorMessage());
        entry.getRequestParameters().forEach((k, v) -> details.put("oci.request." + k, v));
        entry.getResponseElements().forEach((k, v) -> details.put("oci.response." + k, v));

        change.setDetails(details);
        return change;
    }

    /**
     * Helper method to publish log events to Kafka.
     */
    private void publishLogEvent(String accountId, ResourceChange change) {
        try {
            LogEvent event = new LogEvent(CloudProvider.OCI.toString(), accountId, change);
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
        if (type == null || type.id() == null)
            return false;
        return SUPPORTED_OCI_TYPE_IDS.contains(type.id());
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.OCI;
    }

    @Override
    public boolean hasNewLogs(String accountId, Instant since) {
        try {
            ZonedDateTime sinceZdt = since.atZone(ZoneOffset.UTC);
            String formattedTime = sinceZdt.format(DateTimeFormatter.ISO_INSTANT);
            String filter = "timeRange=(" + formattedTime + ",)";
            List<OracleLogEntryAdapter> entries = loggingAdapter.queryLogs(accountId, filter, 1);
            return !entries.isEmpty();
        } catch (Exception e) {
            log.error("Error checking for new Oracle logs for account {}: {}", accountId, e.getMessage(), e);
            return false;
        }
    }

    // --- Helper Methods ---
    private String buildFilterString(String accountId, ZonedDateTime startTime, ZonedDateTime endTime,
            AnalysisOptions options) {
        // This is a placeholder - in a real implementation, this would construct
        // an appropriate filter string based on the Oracle Audit API requirements
        StringBuilder filter = new StringBuilder();

        // Format times as needed by Oracle Cloud API
        String formattedStartTime = startTime.format(DateTimeFormatter.ISO_INSTANT);
        String formattedEndTime = endTime.format(DateTimeFormatter.ISO_INSTANT);

        filter.append("timeRange=(").append(formattedStartTime).append(",").append(formattedEndTime).append(")");

        // Add additional filters from options
        if (options.getFilters() != null && !options.getFilters().isEmpty()) {
            for (Map.Entry<String, String> filterEntry : options.getFilters().entrySet()) {
                filter.append(" AND ").append(filterEntry.getKey()).append("=").append(filterEntry.getValue());
            }
        }

        return filter.toString();
    }

    private ResourceType mapOracleResourceType(String resourceTypeString) {
        // Map Oracle-specific resource type strings to our internal type IDs
        String typeId;
        switch (resourceTypeString.toLowerCase()) {
            case "instance":
                typeId = "oci_compute_instance";
                break;
            case "bucket":
                typeId = "oci_objectstorage_bucket";
                break;
            default:
                log.warn("Unknown Oracle resource type: {}", resourceTypeString);
                typeId = "oci_unknown";
        }

        // Look up the ResourceType record from the registry
        ResourceType type = hierarchyRegistry.getResourceType(typeId);
        if (type == null) {
            log.warn("No registered ResourceType for ID: {}", typeId);
            // Create a fallback type if needed
            return new ResourceType(typeId, "Unknown Oracle Resource", null);
        }
        return type;
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
}