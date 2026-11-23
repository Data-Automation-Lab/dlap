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

package com.dlap.discovery.log.service.gcp;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.auth.CloudAuthenticationService;
import com.dlap.discovery.providers.gcp.config.GCPConfigService;
import com.dlap.discovery.common.constants.ResourceTypeConstants;
import com.dlap.discovery.common.exception.DiscoveryException;
import com.dlap.discovery.common.model.ResourceChange;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.log.service.LogAnalyzerSpinePublisher;
import com.dlap.discovery.log.config.LogAnalyzerProperties;
import com.dlap.discovery.log.service.ILogAnalyzer;
import com.dlap.discovery.log.service.LogFilterBuilder;
import com.dlap.discovery.log.service.gcp.adapter.GCPLoggingAdapter;
import com.dlap.discovery.log.service.gcp.adapter.LogEntryAdapter;
import com.dlap.discovery.log.service.gcp.config.GcpLogProcessingConfig;
import com.dlap.discovery.log.service.gcp.config.GcpLogSourceType;
import com.dlap.discovery.log.service.gcp.logfetch.ILogFetcherStrategy;
import com.dlap.discovery.log.service.gcp.logfetch.impl.ApiLogFetcherStrategy;
import com.dlap.discovery.log.service.gcp.logfetch.impl.BigQueryLogFetcherStrategy;
import com.dlap.discovery.log.service.gcp.logfetch.impl.GcsLogFetcherStrategy;
import com.dlap.discovery.log.service.gcp.service.IGcpLogConfigService;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.storage.Storage;
import com.google.gson.Gson;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Google Cloud Platform log analyzer implementation.
 * Processes GCP Audit Logs asynchronously and publishes ResourceChange events
 * to Kafka.
 * Supports multiple log sources (API, BigQuery, GCS).
 */
@Service("gcpLogAnalyzer")
@ConditionalOnProperty(name = "cloud.provider.gcp.enabled", havingValue = "true", matchIfMissing = false)
public class GcpLogAnalyzer implements ILogAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(GcpLogAnalyzer.class);

    private final GCPConfigService gcpConfigService;
    private final CloudAuthenticationService authService;
    private final CloudHierarchyRegistry hierarchyRegistry;
    private final GCPLoggingAdapter loggingAdapter;
    private final BigQuery bigqueryClient;
    private final Storage storageClient;
    private final Gson gson = new Gson();
    private final IGcpLogConfigService logConfigService;
    private final LogAnalyzerSpinePublisher spinePublisher;
    private final LogAnalyzerProperties logAnalyzerProperties;

    /**
     * Mapping of operation keywords to change types.
     * Initialized from configuration in constructor.
     */
    private final Map<String, ResourceChange.ChangeType> OPERATION_TO_CHANGE_TYPE;

    @Autowired
    public GcpLogAnalyzer(
        GCPConfigService gcpConfigService,
        @Qualifier("GCPAuthenticationServiceImpl") CloudAuthenticationService authService,
        CloudHierarchyRegistry hierarchyRegistry,
        GCPLoggingAdapter loggingAdapter,
        @Autowired(required = false) BigQuery bigqueryClient,
        @Autowired(required = false) Storage storageClient,
        IGcpLogConfigService logConfigService,
        LogAnalyzerSpinePublisher spinePublisher,
        LogAnalyzerProperties logAnalyzerProperties
    ) {
        this.gcpConfigService = gcpConfigService;
        this.authService = authService;
        this.hierarchyRegistry = hierarchyRegistry;
        this.loggingAdapter = loggingAdapter;
        this.bigqueryClient = bigqueryClient;
        this.storageClient = storageClient;
        this.logConfigService = logConfigService;
        this.spinePublisher = spinePublisher;
        this.logAnalyzerProperties = logAnalyzerProperties;

        // Initialize operation to change type map from configuration
        this.OPERATION_TO_CHANGE_TYPE = this.logAnalyzerProperties.buildOperationToChangeTypeMap();

        if (bigqueryClient == null) {
            log.warn("BigQuery client is not available. BigQuery log source will be disabled.");
        }

        if (storageClient == null) {
            log.warn("Storage client is not available. GCS log source will be disabled.");
        }
    }

    // --- New FetchResult Class ---
    /**
     * Holds the result of a log fetching operation, including the logs
     * and the checkpoint for the next run.
     */
    public static class FetchResult {

        final List<LogEntryAdapter> logEntries;
        final Instant newCheckpoint; // Timestamp of the last processed entry in this batch

        public FetchResult(List<LogEntryAdapter> logEntries, Instant newCheckpoint) {
            this.logEntries = logEntries != null ? logEntries : Collections.emptyList();
            this.newCheckpoint = newCheckpoint;
        }

        static FetchResult empty(Instant checkpoint) {
            return new FetchResult(Collections.emptyList(), checkpoint);
        }
    }

    @Override
    public void triggerLogAnalysisAsync(String accountId, ZonedDateTime startTime, ZonedDateTime endTime, AnalysisOptions options) {
        log.info("Triggering async GCP log analysis for project {} between {} and {}", accountId, startTime, endTime);
        performActualLogAnalysis(accountId, startTime, endTime, options);
    }

    @Async("discoveryAsyncExecutor")
    protected void performActualLogAnalysis(String accountId, ZonedDateTime startTime, ZonedDateTime endTime, AnalysisOptions options) {
        Instant finalCheckpoint = null;
        int processedCount = 0;
        int changeEventCount = 0;

        if (accountId == null || accountId.isBlank()) {
            log.error("Account ID (Project ID) is required for GCP log analysis");
            return;
        }
        log.info("Starting async GCP log analysis for project {} between {} and {}", accountId, startTime, endTime);

        GcpLogProcessingConfig processingConfig = loadProcessingConfig(accountId, options);
        if (processingConfig == null) {
            log.error("Failed to load processing configuration for account {}", accountId);
            return;
        } else {
            log.debug("Loaded processing configuration for account {}: {}", accountId, processingConfig.getLogSourceDetails());
        }
        ILogFetcherStrategy fetcher = selectFetcherStrategy(processingConfig);
        if (fetcher == null) {
            log.error("No valid log fetcher strategy available for account {}", accountId);
            return;
        }
        log.debug("Using fetcher strategy: {}", fetcher.getClass().getSimpleName());

        try {
            String baseFilter = buildBaseFilter(accountId, options);
            log.debug("Using base filter: {}", baseFilter);

            FetchResult fetchResult = fetcher.fetchNewLogs(accountId, baseFilter, startTime, endTime, options, processingConfig);
            List<LogEntryAdapter> entriesToProcess = fetchResult.logEntries;
            finalCheckpoint = fetchResult.newCheckpoint;
            processedCount = entriesToProcess.size();

            for (LogEntryAdapter entry : entriesToProcess) {
                try {
                    boolean changePublished = processSingleLogEntry(accountId, entry, options);
                    if (changePublished) changeEventCount++;
                } catch (Exception e) {
                    log.error("Error processing single log entry: {}\\nEntry: {}", e.getMessage(), entry, e);
                }
                // Optional: Check batch size limit (less critical in async processing)
            }

            log.info(
                "Completed async GCP log analysis for account {} using {} source. Processed {} entries, published {} change events. Next checkpoint: {}",
                accountId,
                processingConfig.getLogSourceType(),
                processedCount,
                changeEventCount,
                finalCheckpoint
            );

            if (finalCheckpoint != null) {
                saveCheckpoint(accountId, options, processingConfig, finalCheckpoint);
            }
        } catch (DiscoveryException e) {
            log.error("Log analysis failed for account {}: {}", accountId, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Unexpected error during log analysis for account {}: {}", accountId, e.getMessage(), e);
        }
    }

    @Override
    public void processLogEventAsync(String accountId, Object logEvent, AnalysisOptions options) {
        log.info("Triggering async processing of single GCP log event for account {}", accountId);
        processSingleLogEntry(accountId, logEvent, options);
    }

    /**
     * Processes a single log entry (from any source) and publishes an event if
     * applicable.
     * Returns true if a change event was published, false otherwise.
     */
    @Async("discoveryAsyncExecutor") // Apply async here too
    protected boolean processSingleLogEntry(String accountId, Object logEvent, AnalysisOptions options) {
        if (!(logEvent instanceof LogEntryAdapter)) {
            log.warn("Log event is not a GCP LogEntryAdapter: {}", logEvent.getClass().getName());
            return false;
        }

        LogEntryAdapter entry = (LogEntryAdapter) logEvent;
        log.debug("Processing single GCP log entry: InsertID={}, LogName={}", entry.getInsertId(), entry.getLogName());

        try {
            String gcpResourceTypeString = entry.getResourceType();
            ResourceType domainResourceType = mapGCPResourceType(gcpResourceTypeString);

            if (
                domainResourceType == null ||
                (options != null &&
                    options.getResourceTypes() != null &&
                    !options.getResourceTypes().isEmpty() &&
                    !options.getResourceTypes().contains(domainResourceType))
            ) {
                log.trace("Skipping log entry due to resource type filter or mapping failure: {}", gcpResourceTypeString);
                return false;
            }

            if (!entry.hasProtoPayload()) {
                log.trace("Skipping log entry with no protoPayload: {}", entry.getLogName());
                return false;
            }

            Map<String, Object> payload = entry.getProtoPayloadAsMap();
            if (payload == null || payload.isEmpty()) {
                log.trace("Skipping log entry with empty or null protoPayload");
                return false;
            }

            String methodName = getStringValue(payload, "methodName");
            ResourceChange.ChangeType changeType = determineChangeType(methodName);

            if (
                options != null &&
                options.getChangeTypes() != null &&
                !options.getChangeTypes().isEmpty() &&
                !options.getChangeTypes().contains(changeType.name())
            ) {
                log.trace("Skipping log entry due to change type filter: {}", changeType.name());
                return false;
            }

            ResourceChange change = createResourceChangeFromLogEntry(accountId, entry, domainResourceType, payload);
            if (change != null) {
                publishLogEvent(accountId, change);
                return true;
            }
        } catch (Exception e) {
            log.error("Error processing GCP log entry {}: {}", entry.getInsertId(), e.getMessage(), e);
        }
        return false;
    }

    /**
     * Creates a ResourceChange object from a LogEntryAdapter and its payload.
     */
    private ResourceChange createResourceChangeFromLogEntry(
        String accountId,
        LogEntryAdapter entry,
        ResourceType domainResourceType,
        Map<String, Object> payload
    ) {
        String methodName = getStringValue(payload, "methodName");
        ResourceChange.ChangeType changeType = determineChangeType(methodName);
        String gcpResourceTypeString = entry.getResourceType();

        // Extract Resource ID and Name (Improved Logic)
        String resourceNameFull = getStringValue(payload, "resourceName");
        if (resourceNameFull == null || resourceNameFull.isEmpty()) {
            resourceNameFull = extractResourceNameFromRequestOrResponse(payload);
        }
        if (resourceNameFull == null || resourceNameFull.isEmpty()) {
            log.debug("Could not determine resource name for log entry: {}", entry.getLogName());
            return null; // Cannot create change without resource identifier
        }
        String resourceIdShort = extractResourceId(resourceNameFull); // Best effort extraction

        // Extract Actor Info
        String actor = getStringValue(payload, "authenticationInfo.principalEmail");
        String actorEmail = actor;
        if (actor == null || actor.isEmpty()) {
            actor = getStringValue(payload, "requestMetadata.callerSuppliedUserAgent", "system");
        }

        ResourceChange change = new ResourceChange(
            resourceIdShort, // Use extracted short ID/name
            domainResourceType,
            changeType,
            entry.getTimestamp(),
            actor
        );

        change.setProjectId(accountId);
        if (actorEmail != null) change.setActorEmail(actorEmail);

        // Add details
        Map<String, String> details = new HashMap<>();
        details.put("gcp.logName", entry.getLogName());
        details.put("gcp.insertId", entry.getInsertId());
        details.put("gcp.severity", entry.getSeverity());
        details.put("gcp.resourceTypeOriginal", gcpResourceTypeString); // Keep original GCP type
        details.put("gcp.methodName", methodName);
        details.put("gcp.resourceNameFull", resourceNameFull); // Full path/identifier from log

        String sourceIp = getStringValue(payload, "requestMetadata.callerIp");
        if (sourceIp != null) details.put("gcp.sourceIp", sourceIp);
        String userAgent = getStringValue(payload, "requestMetadata.callerSuppliedUserAgent");
        if (userAgent != null) details.put("gcp.userAgent", userAgent);
        if (entry.getOperationInfo() != null) entry.getOperationInfo().forEach((k, v) -> details.put("gcp.operation." + k, v));
        if (entry.getTrace() != null) details.put("gcp.trace", entry.getTrace());
        entry.getLabels().forEach((k, v) -> details.put("gcp.logLabel." + k, v));
        entry.getResourceLabels().forEach((k, v) -> details.put("gcp.resourceLabel." + k, v));

        // Selectively add payload details (Example)
        Map<String, Object> request = getNestedMap(payload, "request");
        if (request != null) {
            String requestRegion = getStringValue(request, "region", getStringValue(request, "location"));
            if (requestRegion != null) details.put("gcp.request.region", requestRegion);
            // Add more selective request details based on method/resource...
        }
        Map<String, Object> response = getNestedMap(payload, "response");
        if (response != null) {
            String responseSelfLink = getStringValue(response, "selfLink");
            if (responseSelfLink != null) details.put("gcp.response.selfLink", responseSelfLink);
            // Add more selective response details...
        }

        change.setDetails(details);
        return change;
    }

    /**
     * Publishes a detected change onto the normalized log-event spine in the canonical flat
     * {@code DlapLogEvent} shape (via {@link LogAnalyzerSpinePublisher}) so the router and
     * da-catalog consume it - rather than the legacy nested {@code LogEvent{@class}} shape,
     * which dead-lettered at the router.
     */
    private void publishLogEvent(String accountId, ResourceChange change) {
        spinePublisher.publish(CloudProvider.GCP.toString(), accountId, change);
    }

    // --- Other ILogAnalyzer Methods (supportsResourceType, getProviderName,
    // getProvider, hasNewLogs) ---
    @Override
    public boolean supportsResourceType(ResourceType type) {
        return type != null && type.service() != null && type.service().provider() == CloudProvider.GCP;
    }

    @Override
    public String getProviderName() {
        return CloudProvider.GCP.name();
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.GCP;
    }

    @Override
    public boolean hasNewLogs(String accountId, Instant since) {
        try {
            String filter = "timestamp > \"" + since.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\"";
            // Use API strategy for checking new logs as it should always be available
            ILogFetcherStrategy fetcher = new ApiLogFetcherStrategy(this.loggingAdapter);
            GcpLogProcessingConfig dummyConfig = new GcpLogProcessingConfig(); // Use dummy config for simple check
            FetchResult result = fetcher.fetchNewLogs(
                accountId,
                filter,
                ZonedDateTime.now(),
                ZonedDateTime.now(),
                new AnalysisOptions(),
                dummyConfig
            );
            return !result.logEntries.isEmpty();
        } catch (Exception e) {
            log.error("Error checking for new logs for account {}: {}", accountId, e.getMessage(), e);
            return false;
        }
    }

    // --- Helper Methods (loadProcessingConfig, saveCheckpoint, buildBaseFilter,
    // mappings, extractors) ---
    private GcpLogProcessingConfig loadProcessingConfig(String accountId, AnalysisOptions options) {
        // Create a default configuration
        GcpLogProcessingConfig config = new GcpLogProcessingConfig();

        // Try to load source type from environment/properties first
        String sourceTypeStr = System.getProperty("gcp.log.source.type");
        if (sourceTypeStr != null) {
            try {
                config.setLogSourceType(GcpLogSourceType.valueOf(sourceTypeStr.toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid GcpLogSourceType from system property: {}. Using default API.", sourceTypeStr);
            }
        }

        // Load saved checkpoint from database via service
        Instant checkpoint = logConfigService.loadCheckpoint(accountId, options);
        if (checkpoint != null) {
            config.setLastProcessedTimestamp(checkpoint);
        }

        // Load source details from database via service
        Map<String, String> sourceDetails = logConfigService.loadSourceDetails(accountId, config.getLogSourceType());
        if (sourceDetails != null && !sourceDetails.isEmpty()) {
            config.setLogSourceDetails(sourceDetails);
        }

        // Load exclusion filters from database via service
        List<String> exclusionFilters = logConfigService.loadExclusionFilters(accountId);
        if (exclusionFilters != null && !exclusionFilters.isEmpty()) {
            config.setExclusionFilters(exclusionFilters);
        }

        return config;
    }

    private void saveCheckpoint(String accountId, AnalysisOptions options, GcpLogProcessingConfig config, Instant checkpoint) {
        if (checkpoint == null) {
            return;
        }

        // Update the in-memory config
        config.setLastProcessedTimestamp(checkpoint);

        // Persist via service
        boolean success = logConfigService.saveCheckpoint(accountId, options, checkpoint);
        if (success) {
            log.info("Updated checkpoint for account {} to {}", accountId, checkpoint);
        } else {
            log.warn("Failed to persist checkpoint for account {} to {}", accountId, checkpoint);
        }
    }

    private String buildBaseFilter(String accountId, AnalysisOptions options) {
        LogFilterBuilder baseFilterBuilder = new LogFilterBuilder().withGcpAuditLog(accountId); // Start with audit logs

        if (options != null && options.getResourceTypes() != null && !options.getResourceTypes().isEmpty()) {
            List<String> gcpTypes = new ArrayList<>();
            for (ResourceType type : options.getResourceTypes()) {
                String gcpTypeString = mapResourceTypeToGCP(type);
                if (gcpTypeString != null) gcpTypes.add(gcpTypeString);
            }
            if (!gcpTypes.isEmpty()) baseFilterBuilder.withGcpResourceTypes(gcpTypes);
        }

        if (options != null && options.getChangeTypes() != null && !options.getChangeTypes().isEmpty()) {
            List<String> gcpMethods = new ArrayList<>();
            for (String changeType : options.getChangeTypes()) {
                String gcpMethodPattern = mapChangeTypeToGCP(changeType);
                if (gcpMethodPattern != null) gcpMethods.add(gcpMethodPattern);
            }
            if (!gcpMethods.isEmpty()) baseFilterBuilder.withGcpMethodNames(gcpMethods);
        }

        // Custom filters are handled by exclusion filtering or specific strategies for
        // now
        // if (options != null && options.getFilters() != null &&
        // !options.getFilters().isEmpty()) { ... }

        return baseFilterBuilder.build();
    }

    private ResourceChange.ChangeType determineChangeType(String methodName) {
        if (methodName == null || methodName.isEmpty()) {
            return ResourceChange.ChangeType.UNKNOWN;
        }

        String lowerMethod = methodName.toLowerCase();

        // First check for exact matches
        for (Map.Entry<String, ResourceChange.ChangeType> entry : OPERATION_TO_CHANGE_TYPE.entrySet()) {
            if (lowerMethod.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }

        // Then check for contains matches
        if (lowerMethod.contains("create") || lowerMethod.contains("insert")) {
            return ResourceChange.ChangeType.CREATE;
        } else if (lowerMethod.contains("update") || lowerMethod.contains("patch")) {
            return ResourceChange.ChangeType.UPDATE;
        } else if (lowerMethod.contains("delete")) {
            return ResourceChange.ChangeType.DELETE;
        } else if (lowerMethod.contains("get") || lowerMethod.contains("list") || lowerMethod.contains("read")) {
            return ResourceChange.ChangeType.ACCESS;
        } else if (lowerMethod.contains("permission") || lowerMethod.contains("iam")) {
            return ResourceChange.ChangeType.PERMISSION;
        }

        return ResourceChange.ChangeType.UNKNOWN;
    }

    private ResourceType mapGCPResourceType(String gcpResourceType) {
        if (gcpResourceType == null || gcpResourceType.isBlank()) return null;

        String mappedId = null;
        if (gcpResourceType.equals("gce_instance")) {
            mappedId = "gcp_compute_instance"; // Or "gcp_gce_instance" depending on config
        } else if (gcpResourceType.equals("cloudsql_database")) {
            mappedId = "gcp_cloudsql_instance";
        } else if (gcpResourceType.equals("gcs_bucket")) {
            mappedId = "gcp_gcs_bucket";
        } else if (gcpResourceType.equals("bigquery_dataset")) {
            mappedId = "gcp_bigquery_dataset";
        } else if (gcpResourceType.equals("bigquery_table")) {
            mappedId = "gcp_bigquery_table";
        } else {
            log.debug("Unmapped GCP resource type encountered: {}", gcpResourceType);
        }

        if (mappedId != null) {
            ResourceType rt = hierarchyRegistry.getResourceType(mappedId);
            if (rt == null) {
                log.warn("Mapped GCP type '{}' to ID '{}', but it was not found in the CloudHierarchyRegistry.", gcpResourceType, mappedId);
            }
            return rt;
        }

        return null; // Return null if no mapping found
    }

    private String mapResourceTypeToGCP(ResourceType resourceType) {
        if (resourceType == null) {
            return null;
        }

        // Use constants for resource type IDs
        String id = resourceType.id();
        if (ResourceTypeConstants.GCP_COMPUTE_INSTANCE.equals(id)) {
            return "gce_instance";
        } else if ("gcp_cloudsql_instance".equals(id)) {
            return "cloudsql_database";
        } else if ("gcp_gcs_bucket".equals(id)) {
            return "gcs_bucket";
        } else if (ResourceTypeConstants.GCP_BIGQUERY_DATASET.equals(id)) {
            return "bigquery_dataset";
        } else if (ResourceTypeConstants.GCP_BIGQUERY_TABLE.equals(id)) {
            return "bigquery_table";
        }

        // Fallback or default mapping if needed
        log.warn("No specific GCP log filter type found for ResourceType ID: {}. Using generic resource.type filter might be needed.", id);
        // Returning null might be better than guessing, depends on filter logic
        return null;
    }

    private String extractResourceId(String resourceName) {
        if (resourceName == null || resourceName.isEmpty()) {
            return "unknown";
        }

        // Extract the last part of the path as the ID
        String[] parts = resourceName.split("/");
        if (parts.length > 0) {
            return parts[parts.length - 1];
        }

        return resourceName;
    }

    private String extractResourceNameFromPath(String resourcePath) {
        if (resourcePath == null || resourcePath.isEmpty()) {
            return "unknown";
        }

        String[] parts = resourcePath.split("/");
        if (parts.length > 0) {
            return parts[parts.length - 1];
        }

        return resourcePath;
    }

    private String mapChangeTypeToGCP(String changeType) {
        if (changeType == null || changeType.isEmpty()) {
            return "*";
        }

        switch (changeType.toUpperCase()) {
            case "CREATE":
                return "create*";
            case "UPDATE":
                return "update*";
            case "DELETE":
                return "delete*";
            case "ACCESS":
                return "get*";
            case "PERMISSION":
                return "iam*";
            default:
                return "*";
        }
    }

    private String getStringValue(Map<String, Object> map, String path) {
        if (map == null || path == null || path.isEmpty()) {
            return null;
        }

        String[] parts = path.split("\\.");
        Map<String, Object> current = map;

        for (int i = 0; i < parts.length - 1; i++) {
            if (!current.containsKey(parts[i]) || !(current.get(parts[i]) instanceof Map)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> nestedMap = (Map<String, Object>) current.get(parts[i]);
            current = nestedMap;
        }

        String lastPart = parts[parts.length - 1];
        if (!current.containsKey(lastPart)) {
            return null;
        }

        Object value = current.get(lastPart);
        if (value == null) {
            return null;
        }

        return value.toString();
    }

    private String getStringValue(Map<String, Object> map, String path, String defaultValue) {
        String value = getStringValue(map, path);
        return value != null ? value : defaultValue;
    }

    private Map<String, Object> getNestedMap(Map<String, Object> map, String path) {
        if (map == null || path == null || path.isEmpty()) {
            return null;
        }

        String[] parts = path.split("\\.");
        Map<String, Object> current = map;

        for (int i = 0; i < parts.length; i++) {
            if (!current.containsKey(parts[i]) || !(current.get(parts[i]) instanceof Map)) {
                return null;
            }
            current = (Map<String, Object>) current.get(parts[i]);
        }

        return current;
    }

    private void flattenMapToStringMap(Map<String, Object> map, String prefix, Map<String, String> result) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();

            if (entry.getValue() instanceof Map) {
                flattenMapToStringMap((Map<String, Object>) entry.getValue(), key, result);
            } else if (entry.getValue() != null) {
                // Only add non-null values, truncate if too long
                String value = entry.getValue().toString();
                if (value.length() > 1000) {
                    value = value.substring(0, 997) + "...";
                }
                result.put(key, value);
            }
        }
    }

    private String extractResourceNameFromRequestOrResponse(Map<String, Object> payload) {
        Map<String, Object> requestMap = getNestedMap(payload, "request");
        if (requestMap != null) {
            String name = getStringValue(requestMap, "name");
            if (name != null) return name;
            // Add other common fields like instanceId, bucketId etc.
        }
        Map<String, Object> responseMap = getNestedMap(payload, "response");
        if (responseMap != null) {
            String name = getStringValue(responseMap, "name");
            if (name != null) return name;
            String selfLink = getStringValue(responseMap, "selfLink");
            if (selfLink != null) return extractResourceNameFromPath(selfLink); // Try extracting from selfLink
        }
        return null;
    }

    private ILogFetcherStrategy selectFetcherStrategy(GcpLogProcessingConfig processingConfig) {
        switch (processingConfig.getLogSourceType()) {
            case BIGQUERY:
                if (this.bigqueryClient == null) {
                    log.warn("BigQuery log source requested but BigQuery client is not available. Falling back to API.");
                    return new ApiLogFetcherStrategy(this.loggingAdapter);
                }
                log.info("Using BigQuery log source for GCP log analysis");
                return new BigQueryLogFetcherStrategy(this.bigqueryClient);
            case GCS:
                if (this.storageClient == null) {
                    log.warn("GCS log source requested but Storage client is not available. Falling back to API.");
                    return new ApiLogFetcherStrategy(this.loggingAdapter);
                }
                log.info("Using GCS log source for GCP log analysis");
                return new GcsLogFetcherStrategy(this.storageClient, this.gson);
            case API:
            default:
                return new ApiLogFetcherStrategy(this.loggingAdapter);
        }
    }
}
