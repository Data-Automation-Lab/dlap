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
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.JobException;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.TableResult;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// --- BigQuery Strategy Implementation (Updated) ---
@Service
public class BigQueryLogFetcherStrategy implements ILogFetcherStrategy {

    private static final Logger log = LoggerFactory.getLogger(BigQueryLogFetcherStrategy.class);

    private final BigQuery bigqueryClient;
    private final Gson gson = new Gson();

    @Autowired
    public BigQueryLogFetcherStrategy(BigQuery bigqueryClient) {
        if (bigqueryClient == null) {
            throw new IllegalArgumentException("BigQuery client cannot be null for BigQueryLogFetcherStrategy");
        }
        this.bigqueryClient = bigqueryClient;
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
            log.error("BigQuery source details are missing in GcpLogProcessingConfig");
            throw new DiscoveryException(ErrorCode.MISSING_CONFIGURATION, "Missing BigQuery source details");
        }

        String sourceProjectId = config.getSourceProjectId() != null ? config.getSourceProjectId() : accountId;
        String datasetId = config.getBigQueryDatasetId();
        String tableId = config.getBigQueryTableId(); // Assumes partitioned table name (e.g.,
        // cloudaudit_googleapis_com_activity)

        if (datasetId == null || tableId == null) {
            log.error("BigQuery dataset ID or table ID is missing in configuration");
            throw new DiscoveryException(ErrorCode.MISSING_CONFIGURATION, "Missing BigQuery dataset/table ID");
        }

        Instant startInstant = (config.getLastProcessedTimestamp() != null) ? config.getLastProcessedTimestamp() : startTime.toInstant();
        Instant endInstant = endTime.toInstant();

        // Note: baseFilter from AnalysisOptions is difficult to translate reliably to
        // BQ SQL.
        // We rely on the sink having appropriate filters and apply exclusionFilters
        // later.
        if (baseFilter != null && !baseFilter.trim().isEmpty()) {
            log.warn("Base filter derived from AnalysisOptions is not applied when fetching from BigQuery source.");
        }

        String tableName = String.format("`%s.%s.%s`", sourceProjectId, datasetId, tableId);
        // Need to know the exact timestamp column name. Assuming 'timestamp'.
        String sql = String.format(
            "SELECT * FROM %s " + "WHERE timestamp > @checkpointTimestamp AND timestamp <= @endTimestamp " + "ORDER BY timestamp ASC",
            tableName
        );
        // Add LIMIT if batch size is specified?
        // if (options.getBatchSize() > 0) {
        // sql += " LIMIT @limit";
        // }

        log.debug("Executing BigQuery SQL: {} with params: checkpoint={}, end={}", sql, startInstant, endInstant);

        QueryJobConfiguration.Builder queryConfigBuilder = QueryJobConfiguration
            .newBuilder(sql)
            .addNamedParameter("checkpointTimestamp", QueryParameterValue.timestamp(startInstant.toEpochMilli() * 1000))
            .addNamedParameter("endTimestamp", QueryParameterValue.timestamp(endInstant.toEpochMilli() * 1000))
            .setUseLegacySql(false);

        // if (options.getBatchSize() > 0) {
        // queryConfigBuilder.addNamedParameter("limit",
        // QueryParameterValue.int64(options.getBatchSize()));
        // }

        QueryJobConfiguration queryConfig = queryConfigBuilder.build();
        List<LogEntryAdapter> logEntries = new ArrayList<>();
        Instant lastTimestampInBatch = startInstant; // Initialize checkpoint

        try {
            TableResult result = bigqueryClient.query(queryConfig);
            log.debug("BigQuery query executed successfully.");

            // Iterate through results
            for (FieldValueList row : result.iterateAll()) {
                // TODO: Implement robust mapping from FieldValueList to LogEntryAdapter
                LogEntryAdapter adapter = mapFieldValueListToLogEntryAdapter(row);
                if (adapter != null) {
                    logEntries.add(adapter);
                    // Track the timestamp of the last successfully processed entry
                    Instant currentTs = adapter.getTimestamp(); // Use getter method
                    if (currentTs != null && currentTs.isAfter(lastTimestampInBatch)) {
                        lastTimestampInBatch = currentTs;
                    }
                } else {
                    log.warn("Failed to map BigQuery row to LogEntryAdapter. Row: {}", row);
                }
            }
            log.info("BigQuery Fetch: Found {} entries. Last timestamp in batch: {}", logEntries.size(), lastTimestampInBatch);

            // Apply exclusion filters
            List<String> exclusionFilters = config.getExclusionFilters();
            if (exclusionFilters != null && !exclusionFilters.isEmpty()) {
                log.debug("Applying {} exclusion filters to BigQuery results", exclusionFilters.size());
                int beforeCount = logEntries.size();
                logEntries =
                    logEntries.stream().filter(entry -> !matchesExclusionFilter(entry, exclusionFilters)).collect(Collectors.toList());
                log.debug("Filtered {} entries down to {} after applying exclusion filters", beforeCount, logEntries.size());
            }
        } catch (JobException e) {
            log.error("BigQuery job failed for account {}: {}", accountId, e.getMessage(), e);
            throw new DiscoveryException(ErrorCode.PROVIDER_API_ERROR, "BigQuery query job failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("BigQuery query interrupted for account {}: {}", accountId, e.getMessage(), e);
            throw new DiscoveryException(ErrorCode.CRAWLER_INTERRUPTED, "BigQuery query interrupted", e);
        } catch (Exception e) { // Catch other potential mapping errors etc.
            log.error("Error processing BigQuery results for account {}: {}", accountId, e.getMessage(), e);
            throw new DiscoveryException(ErrorCode.PROVIDER_API_ERROR, "Failed to process BigQuery results", e);
        }

        // If no entries were found, advance checkpoint to end of window
        Instant nextCheckpoint = logEntries.isEmpty() ? endInstant : lastTimestampInBatch;

        return new FetchResult(logEntries, nextCheckpoint);
    }

    /**
     * Maps a BigQuery row (FieldValueList) to a LogEntryAdapter.
     * This needs careful implementation based on the exact BigQuery schema
     * exported by Cloud Logging and the fields required by LogEntryAdapter.
     *
     * @param row The FieldValueList representing a row from BigQuery.
     * @return A populated LogEntryAdapter.
     */
    private LogEntryAdapter mapFieldValueListToLogEntryAdapter(FieldValueList row) {
        // Implement a basic mapper with required fields
        try {
            // Create a builder
            LogEntryAdapter.Builder builder = new LogEntryAdapter.Builder();

            // Extract critical fields for filtering and analysis
            if (row.get("timestamp") != null && !row.get("timestamp").isNull()) {
                // BQ stores timestamp in microseconds since epoch
                long microseconds = row.get("timestamp").getTimestampValue();
                Instant timestamp = Instant.ofEpochSecond(microseconds / 1000000, (microseconds % 1000000) * 1000);
                builder.timestamp(timestamp);
            }

            if (row.get("logName") != null && !row.get("logName").isNull()) {
                builder.logName(row.get("logName").getStringValue());
            }

            if (row.get("severity") != null && !row.get("severity").isNull()) {
                builder.severity(row.get("severity").getStringValue());
            }

            if (row.get("insertId") != null && !row.get("insertId").isNull()) {
                builder.insertId(row.get("insertId").getStringValue());
            }

            // Handle resource fields
            if (row.get("resource") != null && !row.get("resource").isNull()) {
                FieldValueList resource = row.get("resource").getRecordValue();

                if (resource.get("type") != null && !resource.get("type").isNull()) {
                    builder.resourceType(resource.get("type").getStringValue());
                }

                // Handle resource labels
                if (resource.get("labels") != null && !resource.get("labels").isNull()) {
                    Map<String, String> resourceLabels = new HashMap<>();
                    FieldValueList labels = resource.get("labels").getRecordValue();

                    for (com.google.cloud.bigquery.FieldValue label : labels) {
                        String fieldName = label.getAttribute().name();
                        if (fieldName != null && label != null && !label.isNull()) {
                            resourceLabels.put(fieldName, label.getStringValue());
                        }
                    }

                    builder.resourceLabels(resourceLabels);
                }
            }

            // Handle operation information
            Map<String, String> operation = new HashMap<>();
            if (row.get("operation") != null && !row.get("operation").isNull()) {
                FieldValueList opFields = row.get("operation").getRecordValue();
                if (opFields.get("id") != null && !opFields.get("id").isNull()) {
                    operation.put("id", opFields.get("id").getStringValue());
                }
                if (opFields.get("producer") != null && !opFields.get("producer").isNull()) {
                    operation.put("producer", opFields.get("producer").getStringValue());
                }
                if (opFields.get("first") != null && !opFields.get("first").isNull()) {
                    operation.put("first", String.valueOf(opFields.get("first").getBooleanValue()));
                }
                if (opFields.get("last") != null && !opFields.get("last").isNull()) {
                    operation.put("last", String.valueOf(opFields.get("last").getBooleanValue()));
                }
            }
            if (!operation.isEmpty()) {
                builder.operationInfo(operation);
            }

            // Extract trace
            if (row.get("trace") != null && !row.get("trace").isNull()) {
                builder.trace(row.get("trace").getStringValue());
            }

            // Handle labels
            if (row.get("labels") != null && !row.get("labels").isNull()) {
                Map<String, String> labels = new HashMap<>();
                FieldValueList labelsList = row.get("labels").getRecordValue();

                for (com.google.cloud.bigquery.FieldValue label : labelsList) {
                    String fieldName = label.getAttribute().name();
                    if (fieldName != null && label != null && !label.isNull()) {
                        labels.put(fieldName, label.getStringValue());
                    }
                }

                builder.labels(labels);
            }

            // Handle protoPayload (this is critical for audit logs)
            if (row.get("protoPayload") != null && !row.get("protoPayload").isNull()) {
                builder.hasProtoPayload(true);

                // Convert to map - this will depend on how protoPayload is represented in BQ
                Map<String, Object> protoPayloadMap = new HashMap<>();
                // Get the protoPayload fields and map them
                // For now, just extract it as a string and we'll parse it
                String payloadJson = row.get("protoPayload").getStringValue();
                if (payloadJson != null && !payloadJson.isEmpty()) {
                    try {
                        // Use Gson to convert JSON string to map
                        java.lang.reflect.Type mapType = new TypeToken<Map<String, Object>>() {}.getType();
                        protoPayloadMap = gson.fromJson(payloadJson, mapType);
                        builder.protoPayloadMap(protoPayloadMap);
                    } catch (Exception e) {
                        log.warn("Failed to parse protoPayload JSON: {}", e.getMessage());
                    }
                }
            }

            // Build and return the adapter
            return builder.build();
        } catch (Exception e) {
            log.warn("Failed to map BigQuery row to LogEntryAdapter: {}", e.getMessage());
            return null;
        }
    }
}
