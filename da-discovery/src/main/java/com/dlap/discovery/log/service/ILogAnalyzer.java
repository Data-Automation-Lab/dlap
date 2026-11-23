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

package com.dlap.discovery.log.service;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.event.service.IEventPublisher;

/**
 * Interface for analyzing logs to detect resource changes.
 * Implementations should be specific to each cloud provider and operate
 * asynchronously,
 * publishing events for detected changes.
 */
public interface ILogAnalyzer extends IEventPublisher {

    /**
     * Initiates the asynchronous analysis of logs between the specified time range.
     * Implementations should perform analysis in the background and publish events
     * (e.g., to Kafka) for detected resource changes.
     *
     * @param accountId The account/project/tenant ID
     * @param startTime The start time for log analysis
     * @param endTime   The end time for log analysis
     * @param options   Optional parameters for the analysis
     */
    void triggerLogAnalysisAsync(String accountId, ZonedDateTime startTime,
            ZonedDateTime endTime, AnalysisOptions options);

    /**
     * Initiates the asynchronous analysis of a specific log entry or event.
     * Implementations should perform analysis in the background and publish events
     * (e.g., to Kafka) for detected resource changes.
     *
     * @param accountId The account/project/tenant ID
     * @param logEvent  The log event to analyze
     * @param options   Optional parameters for the analysis
     */
    void processLogEventAsync(String accountId, Object logEvent, AnalysisOptions options);

    /**
     * Check if this analyzer supports the specified resource type.
     *
     * @param type The resource type record to check
     * @return True if the resource type is supported, otherwise false
     */
    boolean supportsResourceType(ResourceType type);

    /**
     * Get the name of the cloud provider this analyzer supports.
     *
     * @return The cloud provider name
     */
    String getProviderName();

    /**
     * Gets the cloud provider this analyzer supports.
     *
     * @return The cloud provider
     */
    CloudProvider getProvider();

    /**
     * Checks if there are new logs available for analysis since the given
     * checkpoint.
     * This check should ideally be lightweight and non-blocking.
     *
     * @param accountId The account/project ID
     * @param since     The checkpoint time to check from
     * @return true if new logs likely exist, false otherwise
     */
    boolean hasNewLogs(String accountId, Instant since);

    /**
     * Class to hold log analysis options.
     */
    public class AnalysisOptions {
        private List<ResourceType> resourceTypes;
        private List<String> changeTypes;
        private Map<String, String> filters;
        private int batchSize;
        private int parallelism;
        private boolean includeMetadata;
        private String logLevel;

        public List<ResourceType> getResourceTypes() {
            return resourceTypes;
        }

        public void setResourceTypes(List<ResourceType> resourceTypes) {
            this.resourceTypes = resourceTypes;
        }

        public List<String> getChangeTypes() {
            return changeTypes;
        }

        public void setChangeTypes(List<String> changeTypes) {
            this.changeTypes = changeTypes;
        }

        public Map<String, String> getFilters() {
            return filters;
        }

        public void setFilters(Map<String, String> filters) {
            this.filters = filters;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getParallelism() {
            return parallelism;
        }

        public void setParallelism(int parallelism) {
            this.parallelism = parallelism;
        }

        public boolean isIncludeMetadata() {
            return includeMetadata;
        }

        public void setIncludeMetadata(boolean includeMetadata) {
            this.includeMetadata = includeMetadata;
        }

        public String getLogLevel() {
            return logLevel;
        }

        public void setLogLevel(String logLevel) {
            this.logLevel = logLevel;
        }

        /**
         * Builder for AnalysisOptions.
         */
        public static class Builder {
            private final AnalysisOptions options = new AnalysisOptions();

            public Builder resourceTypes(List<ResourceType> resourceTypes) {
                options.setResourceTypes(resourceTypes);
                return this;
            }

            public Builder changeTypes(List<String> changeTypes) {
                options.setChangeTypes(changeTypes);
                return this;
            }

            public Builder filters(Map<String, String> filters) {
                options.setFilters(filters);
                return this;
            }

            public Builder batchSize(int batchSize) {
                options.setBatchSize(batchSize);
                return this;
            }

            public Builder parallelism(int parallelism) {
                options.setParallelism(parallelism);
                return this;
            }

            public Builder includeMetadata(boolean includeMetadata) {
                options.setIncludeMetadata(includeMetadata);
                return this;
            }

            public Builder logLevel(String logLevel) {
                options.setLogLevel(logLevel);
                return this;
            }

            public AnalysisOptions build() {
                return options;
            }
        }

        public static Builder builder() {
            return new Builder();
        }
    }
}