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

package com.dlap.discovery.log.service.gcp.adapter;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Adapter for Google Cloud LogEntry to provide a simplified interface.
 * This decouples our application code from direct Google Cloud API
 * dependencies.
 */
public class LogEntryAdapter {

    private final String logName;
    private final String insertId;
    private final String severity;
    private final String resourceType;
    private final Map<String, String> resourceLabels;
    private final Map<String, String> labels;
    private final Instant timestamp;
    private final Map<String, String> operationInfo;
    private final String trace;
    private final boolean hasProtoPayload;
    private final Map<String, Object> protoPayloadMap;

    // TODO: Add more fields as needed.

    /**
     * Constructor for LogEntryAdapter.
     *
     * @param builder The builder to construct the adapter
     */
    private LogEntryAdapter(Builder builder) {
        this.logName = builder.logName;
        this.insertId = builder.insertId;
        this.severity = builder.severity;
        this.resourceType = builder.resourceType;
        this.resourceLabels = builder.resourceLabels;
        this.labels = builder.labels;
        this.timestamp = builder.timestamp;
        this.operationInfo = builder.operationInfo;
        this.trace = builder.trace;
        this.hasProtoPayload = builder.hasProtoPayload;
        this.protoPayloadMap = builder.protoPayloadMap;
    }

    /**
     * Gets the log name.
     *
     * @return The log name
     */
    public String getLogName() {
        return logName;
    }

    /**
     * Gets the insert ID.
     *
     * @return The insert ID
     */
    public String getInsertId() {
        return insertId;
    }

    /**
     * Gets the severity as a string.
     *
     * @return The severity string
     */
    public String getSeverity() {
        return severity;
    }

    /**
     * Gets the resource type.
     *
     * @return The resource type
     */
    public String getResourceType() {
        return resourceType;
    }

    /**
     * Gets the resource labels.
     *
     * @return The resource labels
     */
    public Map<String, String> getResourceLabels() {
        return resourceLabels;
    }

    /**
     * Gets the log entry labels.
     *
     * @return The labels
     */
    public Map<String, String> getLabels() {
        return labels;
    }

    /**
     * Gets the timestamp.
     *
     * @return The timestamp
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Checks if the log entry has an operation.
     *
     * @return True if the log entry has an operation, false otherwise
     */
    public boolean hasOperation() {
        return operationInfo != null && !operationInfo.isEmpty();
    }

    /**
     * Gets the operation information.
     *
     * @return The operation information
     */
    public Map<String, String> getOperationInfo() {
        return operationInfo;
    }

    /**
     * Gets the trace.
     *
     * @return The trace
     */
    public String getTrace() {
        return trace;
    }

    /**
     * Checks if the log entry has a proto payload.
     *
     * @return True if the log entry has a proto payload, false otherwise
     */
    public boolean hasProtoPayload() {
        return hasProtoPayload;
    }

    /**
     * Gets the proto payload as a map.
     *
     * @return The proto payload map or null if not available
     */
    public Map<String, Object> getProtoPayloadAsMap() {
        return protoPayloadMap;
    }

    /**
     * Builder for LogEntryAdapter.
     */
    public static class Builder {

        private String logName;
        private String insertId;
        private String severity;
        private String resourceType;
        private Map<String, String> resourceLabels = new HashMap<>();
        private Map<String, String> labels = new HashMap<>();
        private Instant timestamp;
        private Map<String, String> operationInfo = new HashMap<>();
        private String trace;
        private boolean hasProtoPayload;
        private Map<String, Object> protoPayloadMap = new HashMap<>();

        /**
         * Sets the log name.
         *
         * @param logName The log name
         * @return This builder
         */
        public Builder logName(String logName) {
            this.logName = logName;
            return this;
        }

        /**
         * Sets the insert ID.
         *
         * @param insertId The insert ID
         * @return This builder
         */
        public Builder insertId(String insertId) {
            this.insertId = insertId;
            return this;
        }

        /**
         * Sets the severity.
         *
         * @param severity The severity
         * @return This builder
         */
        public Builder severity(String severity) {
            this.severity = severity;
            return this;
        }

        /**
         * Sets the resource type.
         *
         * @param resourceType The resource type
         * @return This builder
         */
        public Builder resourceType(String resourceType) {
            this.resourceType = resourceType;
            return this;
        }

        /**
         * Sets the resource labels.
         *
         * @param resourceLabels The resource labels
         * @return This builder
         */
        public Builder resourceLabels(Map<String, String> resourceLabels) {
            this.resourceLabels = resourceLabels;
            return this;
        }

        /**
         * Sets the labels.
         *
         * @param labels The labels
         * @return This builder
         */
        public Builder labels(Map<String, String> labels) {
            this.labels = labels;
            return this;
        }

        /**
         * Sets the timestamp.
         *
         * @param timestamp The timestamp
         * @return This builder
         */
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Sets the operation information.
         *
         * @param operationInfo The operation information
         * @return This builder
         */
        public Builder operationInfo(Map<String, String> operationInfo) {
            this.operationInfo = operationInfo;
            return this;
        }

        /**
         * Sets the trace.
         *
         * @param trace The trace
         * @return This builder
         */
        public Builder trace(String trace) {
            this.trace = trace;
            return this;
        }

        /**
         * Sets whether the log entry has a proto payload.
         *
         * @param hasProtoPayload True if the log entry has a proto payload, false
         *                        otherwise
         * @return This builder
         */
        public Builder hasProtoPayload(boolean hasProtoPayload) {
            this.hasProtoPayload = hasProtoPayload;
            return this;
        }

        /**
         * Sets the proto payload map.
         *
         * @param protoPayloadMap The proto payload map
         * @return This builder
         */
        public Builder protoPayloadMap(Map<String, Object> protoPayloadMap) {
            this.protoPayloadMap = protoPayloadMap;
            return this;
        }

        /**
         * Builds the LogEntryAdapter.
         *
         * @return The built LogEntryAdapter
         */
        public LogEntryAdapter build() {
            return new LogEntryAdapter(this);
        }
    }
}
