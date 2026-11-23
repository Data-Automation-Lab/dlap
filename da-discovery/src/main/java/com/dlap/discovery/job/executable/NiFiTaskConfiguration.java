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

package com.dlap.discovery.job.executable;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Configuration for NiFi task execution.
 * Contains the necessary parameters to create and configure a NiFi processor.
 */
public class NiFiTaskConfiguration {
    private String processGroupId;
    private String processorType;
    private String processorId;
    private String jobName;
    private Map<String, String> properties;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Default constructor.
     */
    public NiFiTaskConfiguration() {
        this.properties = new HashMap<>();
    }

    /**
     * Constructor with parameters.
     *
     * @param processGroupId Process group ID
     * @param processorType  Type of processor
     * @param jobName        Job name
     * @param properties     Properties for the processor
     */
    public NiFiTaskConfiguration(String processGroupId, String processorType, String jobName,
            Map<String, String> properties) {
        this.processGroupId = processGroupId;
        this.processorType = processorType;
        this.jobName = jobName;
        this.properties = properties != null ? properties : new HashMap<>();
    }

    /**
     * Constructor with parameters including processor ID.
     *
     * @param processGroupId Process group ID
     * @param processorType  Type of processor
     * @param processorId    Processor ID
     * @param jobName        Job name
     * @param properties     Properties for the processor
     */
    public NiFiTaskConfiguration(String processGroupId, String processorType, String processorId, String jobName,
            Map<String, String> properties) {
        this.processGroupId = processGroupId;
        this.processorType = processorType;
        this.processorId = processorId;
        this.jobName = jobName;
        this.properties = properties != null ? properties : new HashMap<>();
    }

    /**
     * Gets the process group ID.
     *
     * @return Process group ID
     */
    public String getProcessGroupId() {
        return processGroupId;
    }

    /**
     * Sets the process group ID.
     *
     * @param processGroupId Process group ID
     */
    public void setProcessGroupId(String processGroupId) {
        this.processGroupId = processGroupId;
    }

    /**
     * Gets the processor type.
     *
     * @return Processor type
     */
    public String getProcessorType() {
        return processorType;
    }

    /**
     * Sets the processor type.
     *
     * @param processorType Processor type
     */
    public void setProcessorType(String processorType) {
        this.processorType = processorType;
    }

    /**
     * Gets the processor ID.
     *
     * @return Processor ID
     */
    public String getProcessorId() {
        return processorId;
    }

    /**
     * Sets the processor ID.
     *
     * @param processorId Processor ID
     */
    public void setProcessorId(String processorId) {
        this.processorId = processorId;
    }

    /**
     * Gets the job name.
     *
     * @return Job name
     */
    public String getJobName() {
        return jobName;
    }

    /**
     * Sets the job name.
     *
     * @param jobName Job name
     */
    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    /**
     * Gets the properties.
     *
     * @return Properties
     */
    public Map<String, String> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    /**
     * Sets the properties.
     *
     * @param properties Properties
     */
    public void setProperties(Map<String, String> properties) {
        this.properties = properties != null ? new HashMap<>(properties) : new HashMap<>();
    }

    /**
     * Adds a property.
     *
     * @param key   Property key
     * @param value Property value
     */
    public void addProperty(String key, String value) {
        this.properties.put(key, value);
    }

    /**
     * Gets the properties as a JSON string.
     *
     * @return Properties JSON string
     */
    public String getPropertiesJson() {
        try {
            return objectMapper.writeValueAsString(properties);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}