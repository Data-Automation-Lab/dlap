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

package com.dlap.discovery.log.service.gcp.logfetch.impl;

import com.dlap.discovery.common.exception.DiscoveryException;
import com.dlap.discovery.crawler.exception.ErrorCode;
import com.dlap.discovery.log.service.ILogAnalyzer.AnalysisOptions;
import com.dlap.discovery.log.service.gcp.GcpLogAnalyzer.FetchResult;
import com.dlap.discovery.log.service.gcp.adapter.LogEntryAdapter;
import com.dlap.discovery.log.service.gcp.config.GcpLogProcessingConfig;
import com.dlap.discovery.log.service.gcp.logfetch.ILogFetcherStrategy;
import com.google.api.gax.paging.Page;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobListOption;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// --- GCS Strategy Implementation (Updated) ---
@Service
public class GcsLogFetcherStrategy implements ILogFetcherStrategy {

    private static final Logger log = LoggerFactory.getLogger(ApiLogFetcherStrategy.class);

    private final Storage storageClient;
    private final Gson gson;

    @Autowired
    public GcsLogFetcherStrategy(Storage storageClient, Gson gson) {
        if (storageClient == null) {
            throw new IllegalArgumentException("Storage client cannot be null for GcsLogFetcherStrategy");
        }
        if (gson == null) {
            throw new IllegalArgumentException("Gson parser cannot be null for GcsLogFetcherStrategy");
        }
        this.storageClient = storageClient;
        this.gson = gson;
    }

    @Override
    public FetchResult fetchNewLogs(
        String accountId,
        String baseFilter,
        ZonedDateTime startTime,
        ZonedDateTime endTime,
        AnalysisOptions options,
        GcpLogProcessingConfig config
    ) throws DiscoveryException {
        if (config == null || config.getLogSourceDetails() == null) {
            log.error("GCS source details are missing in GcpLogProcessingConfig");
            throw new DiscoveryException(ErrorCode.MISSING_CONFIGURATION, "Missing GCS source details");
        }

        String bucketName = config.getGcsBucketName();
        String prefix = config.getGcsObjectPrefix(); // Optional prefix
        // Use sourceProjectId if provided, otherwise assume bucket is in the target
        // accountId project
        // Note: Storage client might need specific project configuration if bucket
        // project differs
        // String sourceProjectId = config.getSourceProjectId();

        if (bucketName == null) {
            log.error("GCS bucket name is missing in configuration");
            throw new DiscoveryException(ErrorCode.MISSING_CONFIGURATION, "Missing GCS bucket name");
        }

        Instant startInstant = (config.getLastProcessedTimestamp() != null) ? config.getLastProcessedTimestamp() : startTime.toInstant();
        Instant endInstant = endTime.toInstant();

        // Note: baseFilter from AnalysisOptions is not applied when fetching from GCS.
        if (baseFilter != null && !baseFilter.trim().isEmpty()) {
            log.warn("Base filter derived from AnalysisOptions is not applied when fetching from GCS source.");
        }

        List<LogEntryAdapter> logEntries = new ArrayList<>();
        Instant lastTimestampInBatch = startInstant; // Initialize checkpoint

        try {
            List<BlobListOption> listOptions = new ArrayList<>();
            if (prefix != null && !prefix.isEmpty()) {
                listOptions.add(BlobListOption.prefix(prefix));
            }
            // We list all blobs under the prefix and filter by timestamp during parsing
            // because GCS object metadata timestamps (create/update) don't reliably match
            // log timestamps.
            // More advanced filtering could use object naming conventions if the sink
            // creates them (e.g., YYYY/MM/DD/HH).
            log.debug("Listing blobs in bucket '{}' with prefix '{}'", bucketName, prefix);
            Page<Blob> blobs = storageClient.list(bucketName, listOptions.toArray(new BlobListOption[0]));

            for (Blob blob : blobs.iterateAll()) {
                log.trace("Processing blob: {}", blob.getName());
                // Simple check: skip blobs likely older than our start window (basic
                // optimization)
                // Requires blob creation time to be somewhat reliable
                // if (blob.getCreateTimeOffsetDateTime() != null &&
                // blob.getCreateTimeOffsetDateTime().toInstant().isBefore(startInstant.minus(Duration.ofHours(1))))
                // { // Add buffer
                // log.trace("Skipping potentially old blob based on create time: {}",
                // blob.getName());
                // continue;
                // }

                // Read blob content
                byte[] content = blob.getContent();
                BufferedReader reader = null;
                try {
                    InputStream inputStream = new ByteArrayInputStream(content);
                    // Handle potential Gzip compression
                    if (blob.getName().endsWith(".gz") || "gzip".equalsIgnoreCase(blob.getContentEncoding())) {
                        inputStream = new GZIPInputStream(inputStream);
                    }
                    reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));

                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;

                        try {
                            // TODO: Map JSON object to LogEntryAdapter
                            LogEntryAdapter adapter = mapJsonToLogEntryAdapter(line);

                            if (adapter != null && adapter.getTimestamp() != null) {
                                Instant entryTs = adapter.getTimestamp(); // Use getter method
                                // Apply time window filtering and check against checkpoint
                                if (entryTs.isAfter(startInstant) && !entryTs.isAfter(endInstant)) {
                                    logEntries.add(adapter);
                                    // Track the latest timestamp within the valid window
                                    if (entryTs.isAfter(lastTimestampInBatch)) {
                                        lastTimestampInBatch = entryTs;
                                    }
                                } else {
                                    log.trace(
                                        "Skipping log entry outside time window or before checkpoint: T={} Start={} End={}",
                                        entryTs,
                                        startInstant,
                                        endInstant
                                    );
                                }
                            } else {
                                log.warn(
                                    "Failed to map GCS JSON line or missing timestamp. Line: {}",
                                    line.substring(0, Math.min(line.length(), 200))
                                );
                            }
                        } catch (JsonSyntaxException jsonEx) {
                            log.warn(
                                "Invalid JSON format in GCS file '{}': {}. Line: {}",
                                blob.getName(),
                                jsonEx.getMessage(),
                                line.substring(0, Math.min(line.length(), 200))
                            );
                        } catch (Exception mappingEx) {
                            log.error(
                                "Error mapping JSON line from GCS file '{}' to LogEntryAdapter: {}",
                                blob.getName(),
                                mappingEx.getMessage(),
                                mappingEx
                            );
                        }
                    }
                } finally {
                    if (reader != null) {
                        reader.close();
                    }
                }
            }
            log.info(
                "GCS Fetch: Processed blobs in '{}/{}'. Found {} entries in time window. Last timestamp: {}",
                bucketName,
                prefix,
                logEntries.size(),
                lastTimestampInBatch
            );

            // Apply exclusion filters
            List<String> exclusionFilters = config.getExclusionFilters();
            if (exclusionFilters != null && !exclusionFilters.isEmpty()) {
                log.debug("Applying {} exclusion filters to GCS results", exclusionFilters.size());
                int beforeCount = logEntries.size();
                logEntries =
                    logEntries.stream().filter(entry -> !matchesExclusionFilter(entry, exclusionFilters)).collect(Collectors.toList());
                log.debug("Filtered {} entries down to {} after applying exclusion filters", beforeCount, logEntries.size());
            }
        } catch (Exception e) {
            log.error(
                "Error fetching or processing logs from GCS bucket '{}' for account {}: {}",
                bucketName,
                accountId,
                e.getMessage(),
                e
            );
            // Use PROVIDER_API_ERROR as Storage interaction failed
            throw new DiscoveryException(ErrorCode.PROVIDER_API_ERROR, "Failed to process logs from GCS", e);
        }

        // If no entries were found, advance checkpoint to end of window
        Instant nextCheckpoint = logEntries.isEmpty() ? endInstant : lastTimestampInBatch;

        // GCS logs might not be perfectly ordered across files, sort before returning
        // if needed?
        // For checkpointing, using the max timestamp found is generally okay.
        // Collections.sort(logEntries,
        // Comparator.comparing(LogEntryAdapter::getTimestamp));

        return new FetchResult(logEntries, nextCheckpoint);
    }

    /**
     * Maps a JSON string (representing a single log entry) to a LogEntryAdapter.
     * This needs careful implementation based on the exact JSON schema
     * exported by Cloud Logging to GCS and the fields required by LogEntryAdapter.
     *
     * @param jsonLine A string containing a single JSON log entry.
     * @return A populated LogEntryAdapter, or null if mapping fails.
     */
    private LogEntryAdapter mapJsonToLogEntryAdapter(String jsonLine) {
        try {
            // Parse the JSON into a Map
            java.lang.reflect.Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> logMap = gson.fromJson(jsonLine, mapType);

            LogEntryAdapter.Builder builder = new LogEntryAdapter.Builder();

            // Extract timestamp (critical for checkpoint filtering)
            String timestampStr = (String) logMap.get("timestamp");
            if (timestampStr != null) {
                builder.timestamp(Instant.parse(timestampStr));
            }

            // Extract various fields by name

            // LogName
            String logName = (String) logMap.get("logName");
            if (logName != null) {
                builder.logName(logName);
            }

            // InsertId
            String insertId = (String) logMap.get("insertId");
            if (insertId != null) {
                builder.insertId(insertId);
            }

            // Severity
            String severity = (String) logMap.get("severity");
            if (severity != null) {
                builder.severity(severity);
            }

            // Extract resource information
            Map<String, Object> resource = (Map<String, Object>) logMap.get("resource");
            if (resource != null) {
                String resourceType = (String) resource.get("type");
                if (resourceType != null) {
                    builder.resourceType(resourceType);
                }

                // Extract resource labels
                Map<String, String> resourceLabels = new HashMap<>();
                Map<String, Object> rawResourceLabels = (Map<String, Object>) resource.get("labels");
                if (rawResourceLabels != null) {
                    for (Map.Entry<String, Object> label : rawResourceLabels.entrySet()) {
                        if (label.getValue() != null) {
                            resourceLabels.put(label.getKey(), label.getValue().toString());
                        }
                    }
                }
                builder.resourceLabels(resourceLabels);
            }

            // Extract labels
            Map<String, Object> labels = (Map<String, Object>) logMap.get("labels");
            if (labels != null) {
                Map<String, String> stringLabels = new HashMap<>();
                for (Map.Entry<String, Object> label : labels.entrySet()) {
                    if (label.getValue() != null) {
                        stringLabels.put(label.getKey(), label.getValue().toString());
                    }
                }
                builder.labels(stringLabels);
            }

            // Extract trace
            String trace = (String) logMap.get("trace");
            if (trace != null) {
                builder.trace(trace);
            }

            // Extract operation
            Map<String, Object> operation = (Map<String, Object>) logMap.get("operation");
            if (operation != null) {
                Map<String, String> operationInfo = new HashMap<>();
                for (Map.Entry<String, Object> op : operation.entrySet()) {
                    if (op.getValue() != null) {
                        operationInfo.put(op.getKey(), op.getValue().toString());
                    }
                }
                builder.operationInfo(operationInfo);
            }

            // Extract protoPayload (critical for audit logs)
            Map<String, Object> protoPayload = (Map<String, Object>) logMap.get("protoPayload");
            if (protoPayload != null) {
                builder.hasProtoPayload(true);
                builder.protoPayloadMap(protoPayload);
            }

            return builder.build();
        } catch (Exception e) {
            log.warn("Failed to map JSON to LogEntryAdapter: {}", e.getMessage());
            return null;
        }
    }
}
