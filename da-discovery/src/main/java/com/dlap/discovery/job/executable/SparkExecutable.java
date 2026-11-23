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

import com.dlap.discovery.job.ExecutionMode;

/**
 * Represents an executable task for Spark.
 * Contains the configuration needed to execute a job in Apache Spark.
 */
public class SparkExecutable implements Executable {
    private final SparkJobConfiguration jobConfiguration;

    /**
     * Constructor with job configuration.
     *
     * @param jobConfiguration Spark job configuration
     */
    public SparkExecutable(SparkJobConfiguration jobConfiguration) {
        if (jobConfiguration == null) {
            throw new IllegalArgumentException("Job configuration must not be null");
        }
        this.jobConfiguration = jobConfiguration;
    }

    /**
     * Gets the job configuration.
     *
     * @return Spark job configuration
     */
    public SparkJobConfiguration getJobConfiguration() {
        return jobConfiguration;
    }

    @Override
    public ExecutionMode getMode() {
        return ExecutionMode.SPARK;
    }
}