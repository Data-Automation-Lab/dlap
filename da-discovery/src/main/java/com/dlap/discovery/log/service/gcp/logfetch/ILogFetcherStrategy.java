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

package com.dlap.discovery.log.service.gcp.logfetch;

import com.dlap.discovery.common.exception.DiscoveryException;
import com.dlap.discovery.log.service.ILogAnalyzer.AnalysisOptions;
import com.dlap.discovery.log.service.gcp.GcpLogAnalyzer.FetchResult;
import com.dlap.discovery.log.service.gcp.adapter.LogEntryAdapter;
import com.dlap.discovery.log.service.gcp.config.GcpLogProcessingConfig;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

// --- New LogFetcherStrategy Interface (Updated) ---
/**
 * Internal strategy for fetching logs from different GCP sources.
 */
public interface ILogFetcherStrategy {
    /**
     * Fetches and filters new log entries based on the configuration and
     * checkpoint.
     *
     * @param accountId  Project ID to query within.
     * @param baseFilter Base filter string (excluding time constraints handled by
     *                   strategy).
     * @param startTime  The start time if no checkpoint exists.
     * @param endTime    The end time for the query window.
     * @param options    Analysis options.
     * @param config     GCP specific processing config including checkpoint, source
     *                   details, and exclusion filters.
     * @return FetchResult containing filtered logs and the next checkpoint
     *         timestamp.
     * @throws DiscoveryException If fetching or filtering fails.
     */
    FetchResult fetchNewLogs(
        String accountId,
        String baseFilter,
        ZonedDateTime startTime,
        ZonedDateTime endTime,
        AnalysisOptions options,
        GcpLogProcessingConfig config
    ) throws DiscoveryException;

    /**
     * Checks if a LogEntryAdapter matches any of the provided exclusion filters.
     * Each strategy may have optimized filtering based on its source
     * characteristics.
     *
     * @param entry            The log entry adapter.
     * @param exclusionFilters A list of Cloud Logging filter strings.
     * @return True if the entry matches ANY of the exclusion filters, false
     *         otherwise.
     */
    default boolean matchesExclusionFilter(LogEntryAdapter entry, List<String> exclusionFilters) {
        // Skip filtering if no exclusion filters provided
        if (exclusionFilters == null || exclusionFilters.isEmpty()) {
            return false;
        }

        // Enhanced implementation supporting more filter patterns
        for (String filter : exclusionFilters) {
            // Simple logName exact match or contains
            if (filter.contains("logName=") || filter.contains("logName:")) {
                String pattern = filter.replaceAll(".*logName[:=]\\s*[\"']?([^\"'\\s)]+)[\"']?.*", "$1");
                if (!pattern.equals(filter) && entry.getLogName() != null) {
                    // Check for exact match or contains based on pattern
                    if (pattern.startsWith("*") && pattern.endsWith("*")) {
                        // Contains
                        String subPattern = pattern.substring(1, pattern.length() - 1);
                        if (entry.getLogName().contains(subPattern)) {
                            return true;
                        }
                    } else if (pattern.startsWith("*")) {
                        // Ends with
                        String suffix = pattern.substring(1);
                        if (entry.getLogName().endsWith(suffix)) {
                            return true;
                        }
                    } else if (pattern.endsWith("*")) {
                        // Starts with
                        String prefix = pattern.substring(0, pattern.length() - 1);
                        if (entry.getLogName().startsWith(prefix)) {
                            return true;
                        }
                    } else if (entry.getLogName().equals(pattern) || entry.getLogName().contains(pattern)) {
                        // Exact match or simple contains
                        return true;
                    }
                }
            }

            // Simple severity exact match
            if (filter.contains("severity=") || filter.contains("severity:")) {
                String pattern = filter.replaceAll(".*severity[:=]\\s*[\"']?([^\"'\\s)]+)[\"']?.*", "$1");
                if (!pattern.equals(filter) && entry.getSeverity() != null && entry.getSeverity().equalsIgnoreCase(pattern)) {
                    return true;
                }
            }

            // Simple resource.type match
            if (filter.contains("resource.type=") || filter.contains("resource.type:")) {
                String pattern = filter.replaceAll(".*resource\\.type[:=]\\s*[\"']?([^\"'\\s)]+)[\"']?.*", "$1");
                if (!pattern.equals(filter) && entry.getResourceType() != null) {
                    if (pattern.contains("*")) {
                        // Wildcard pattern
                        String regex = pattern.replace("*", ".*");
                        if (entry.getResourceType().matches(regex)) {
                            return true;
                        }
                    } else if (entry.getResourceType().equalsIgnoreCase(pattern)) {
                        // Exact match
                        return true;
                    }
                }
            }

            // Match on resource labels
            if (filter.contains("resource.labels.")) {
                for (Map.Entry<String, String> label : entry.getResourceLabels().entrySet()) {
                    String labelPattern = "resource.labels." + label.getKey();
                    if (filter.contains(labelPattern)) {
                        String valuePattern = filter.replaceAll(".*" + labelPattern + "[:=]\\s*[\"']?([^\"'\\s)]+)[\"']?.*", "$1");
                        if (!valuePattern.equals(filter) && label.getValue() != null && label.getValue().equalsIgnoreCase(valuePattern)) {
                            return true;
                        }
                    }
                }
            }

            // Match on labels
            if (filter.contains("labels.")) {
                for (Map.Entry<String, String> label : entry.getLabels().entrySet()) {
                    String labelPattern = "labels." + label.getKey();
                    if (filter.contains(labelPattern)) {
                        String valuePattern = filter.replaceAll(".*" + labelPattern + "[:=]\\s*[\"']?([^\"'\\s)]+)[\"']?.*", "$1");
                        if (!valuePattern.equals(filter) && label.getValue() != null && label.getValue().equalsIgnoreCase(valuePattern)) {
                            return true;
                        }
                    }
                }
            }

            // Trace filter
            if ((filter.contains("trace=") || filter.contains("trace:")) && entry.getTrace() != null) {
                String pattern = filter.replaceAll(".*trace[:=]\\s*[\"']?([^\"'\\s)]+)[\"']?.*", "$1");
                if (!pattern.equals(filter) && entry.getTrace().contains(pattern)) {
                    return true;
                }
            }

            // Check for operation.id filter
            if (entry.hasOperation() && filter.contains("operation.id")) {
                String opId = entry.getOperationInfo().get("id");
                if (opId != null) {
                    String pattern = filter.replaceAll(".*operation.id[:=]\\s*[\"']?([^\"'\\s)]+)[\"']?.*", "$1");
                    if (!pattern.equals(filter) && opId.contains(pattern)) {
                        return true;
                    }
                }
            }

            // protoPayload filtering for AuditLog methodName
            if (entry.hasProtoPayload() && filter.contains("protoPayload.methodName")) {
                Map<String, Object> payload = entry.getProtoPayloadAsMap();
                if (payload != null && payload.containsKey("methodName")) {
                    String methodName = payload.get("methodName").toString();
                    String pattern = filter.replaceAll(".*protoPayload.methodName[:=]\\s*[\"']?([^\"'\\s)]+)[\"']?.*", "$1");
                    if (!pattern.equals(filter)) {
                        if (pattern.contains("*")) {
                            // Handle wildcard
                            String regex = pattern.replace("*", ".*");
                            if (methodName.matches(regex)) {
                                return true;
                            }
                        } else if (methodName.equals(pattern) || methodName.contains(pattern)) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }
}
