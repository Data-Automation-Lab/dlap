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
import com.dlap.discovery.log.service.gcp.adapter.GCPLoggingAdapter;
import com.dlap.discovery.log.service.gcp.adapter.LogEntryAdapter;
import com.dlap.discovery.log.service.gcp.config.GcpLogProcessingConfig;
import com.dlap.discovery.log.service.gcp.logfetch.ILogFetcherStrategy;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// --- Api Strategy Implementation (Updated) ---
@Service
public class ApiLogFetcherStrategy implements ILogFetcherStrategy {

    private static final Logger log = LoggerFactory.getLogger(ApiLogFetcherStrategy.class);

    private final GCPLoggingAdapter loggingAdapter;

    @Autowired
    public ApiLogFetcherStrategy(GCPLoggingAdapter loggingAdapter) {
        this.loggingAdapter = loggingAdapter;
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
        // Define formatter for GCP API timestamp filter (RFC3339Nano)
        DateTimeFormatter gcpTimestampFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

        // Apply time range: Use checkpoint if available, otherwise use startTime
        Instant startInstant = (config != null && config.getLastProcessedTimestamp() != null)
            ? config.getLastProcessedTimestamp()
            : startTime.toInstant();
        Instant endInstant = endTime.toInstant();

        // Format timestamps for the filter string
        String formattedStart = gcpTimestampFormatter.format(startInstant.atZone(ZoneOffset.UTC));
        String formattedEnd = gcpTimestampFormatter.format(endInstant.atZone(ZoneOffset.UTC));

        // Construct the final filter string manually
        // Start with the base filter, add time constraints
        StringBuilder filterStringBuilder = new StringBuilder();
        if (baseFilter != null && !baseFilter.trim().isEmpty()) {
            filterStringBuilder.append("(").append(baseFilter).append(")"); // Group base filter
        }

        // Add time filters, ensuring AND is used correctly
        if (filterStringBuilder.length() > 0) {
            filterStringBuilder.append(" AND ");
        }
        filterStringBuilder.append("timestamp > \"").append(formattedStart).append("\"");
        filterStringBuilder.append(" AND timestamp <= \"").append(formattedEnd).append("\"");

        String finalFilter = filterStringBuilder.toString();
        log.debug("Using API filter: {}", finalFilter);

        try {
            List<LogEntryAdapter> entries = loggingAdapter.queryLogs(
                accountId,
                finalFilter,
                options.getBatchSize() > 0 ? options.getBatchSize() : 1000
            );

            // Apply exclusion filters
            // TODO : Add support for exclusion filters in the API query
            List<String> exclusionFilters = config.getExclusionFilters();
            if (exclusionFilters != null && !exclusionFilters.isEmpty()) {
                log.debug("Applying {} exclusion filters to API results", exclusionFilters.size());
                int beforeCount = entries.size();
                entries = entries.stream().filter(entry -> !matchesExclusionFilter(entry, exclusionFilters)).collect(Collectors.toList());
                log.debug("Filtered {} entries down to {} after applying exclusion filters", beforeCount, entries.size());
            }

            Instant nextCheckpoint = startInstant;
            if (!entries.isEmpty()) {
                Instant maxTimestamp = startInstant;
                for (LogEntryAdapter entry : entries) {
                    Instant entryTs = entry.getTimestamp(); // Use getter method
                    if (entryTs != null && entryTs.isAfter(maxTimestamp)) {
                        maxTimestamp = entryTs;
                    }
                }
                nextCheckpoint = maxTimestamp;
            } else {
                nextCheckpoint = endInstant;
            }

            log.info("API Fetch: Found {} entries after filtering. New checkpoint: {}", entries.size(), nextCheckpoint);
            return new FetchResult(entries, nextCheckpoint);
        } catch (Exception e) {
            log.error("Error fetching logs via API for account {}: {}", accountId, e.getMessage(), e);
            // Use PROVIDER_API_ERROR as the most fitting existing error code
            throw new DiscoveryException(
                ErrorCode.PROVIDER_API_ERROR,
                "Failed to fetch logs via Logging API for account " + accountId + ": " + e.getMessage(),
                e
            );
        }
    }
}
