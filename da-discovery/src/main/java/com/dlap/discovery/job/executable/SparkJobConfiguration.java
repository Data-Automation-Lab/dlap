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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Configuration for Spark job execution.
 * Contains the necessary parameters to submit a job to Apache Spark.
 */
public class SparkJobConfiguration {
    private String appJarPath;
    private String mainClass;
    private String appName;
    private String master = "yarn";
    private String driverMemory = "1g";
    private List<String> arguments;
    private Map<String, String> sparkProperties;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Default constructor.
     */
    public SparkJobConfiguration() {
        this.arguments = new ArrayList<>();
        this.sparkProperties = new HashMap<>();
    }

    /**
     * Constructor with parameters.
     *
     * @param appJarPath JAR file path
     * @param mainClass  Main class
     * @param appName    Application name
     */
    public SparkJobConfiguration(String appJarPath, String mainClass, String appName) {
        this.appJarPath = appJarPath;
        this.mainClass = mainClass;
        this.appName = appName;
        this.arguments = new ArrayList<>();
        this.sparkProperties = new HashMap<>();
    }

    /**
     * Gets the application JAR path.
     *
     * @return Application JAR path
     */
    public String getAppJarPath() {
        return appJarPath;
    }

    /**
     * Sets the application JAR path.
     *
     * @param appJarPath Application JAR path
     */
    public void setAppJarPath(String appJarPath) {
        this.appJarPath = appJarPath;
    }

    /**
     * Gets the main class.
     *
     * @return Main class
     */
    public String getMainClass() {
        return mainClass;
    }

    /**
     * Sets the main class.
     *
     * @param mainClass Main class
     */
    public void setMainClass(String mainClass) {
        this.mainClass = mainClass;
    }

    /**
     * Gets the application name.
     *
     * @return Application name
     */
    public String getAppName() {
        return appName;
    }

    /**
     * Sets the application name.
     *
     * @param appName Application name
     */
    public void setAppName(String appName) {
        this.appName = appName;
    }

    /**
     * Gets the master.
     *
     * @return Master
     */
    public String getMaster() {
        return master;
    }

    /**
     * Sets the master.
     *
     * @param master Master
     */
    public void setMaster(String master) {
        this.master = master;
    }

    /**
     * Gets the driver memory.
     *
     * @return Driver memory
     */
    public String getDriverMemory() {
        return driverMemory;
    }

    /**
     * Sets the driver memory.
     *
     * @param driverMemory Driver memory
     */
    public void setDriverMemory(String driverMemory) {
        this.driverMemory = driverMemory;
    }

    /**
     * Gets the arguments.
     *
     * @return Arguments
     */
    public List<String> getArguments() {
        return Collections.unmodifiableList(arguments);
    }

    /**
     * Sets the arguments.
     *
     * @param arguments Arguments
     */
    public void setArguments(List<String> arguments) {
        this.arguments = arguments != null ? new ArrayList<>(arguments) : new ArrayList<>();
    }

    /**
     * Adds an argument.
     *
     * @param argument Argument
     */
    public void addArgument(String argument) {
        this.arguments.add(argument);
    }

    /**
     * Gets the arguments as a JSON string.
     *
     * @return Arguments JSON string
     */
    public String getArgumentsJson() {
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /**
     * Gets the Spark properties.
     *
     * @return Spark properties
     */
    public Map<String, String> getSparkProperties() {
        return Collections.unmodifiableMap(sparkProperties);
    }

    /**
     * Gets the Spark configuration.
     * Alias for getSparkProperties() to match executor usage.
     *
     * @return Spark configuration properties
     */
    public Map<String, String> getSparkConf() {
        return getSparkProperties();
    }

    /**
     * Gets the JAR path.
     * Alias for getAppJarPath() to match executor usage.
     *
     * @return Application JAR path
     */
    public String getJarPath() {
        return getAppJarPath();
    }

    /**
     * Sets the Spark properties.
     *
     * @param sparkProperties Spark properties
     */
    public void setSparkProperties(Map<String, String> sparkProperties) {
        this.sparkProperties = sparkProperties != null ? new HashMap<>(sparkProperties) : new HashMap<>();
    }

    /**
     * Adds a Spark property.
     *
     * @param key   Property key
     * @param value Property value
     */
    public void addSparkProperty(String key, String value) {
        this.sparkProperties.put(key, value);
    }
}