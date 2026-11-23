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

package com.dlap.discovery.job.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.dlap.discovery.job.ExecutionMode;
import com.dlap.discovery.job.executor.IJobExecutor;
import com.dlap.discovery.job.executor.impl.DefaultJobExecutor;
import com.dlap.discovery.job.executor.impl.NifiJobExecutor;
import com.dlap.discovery.job.executor.impl.SparkJobExecutor;
import com.dlap.discovery.job.scheduler.impl.DefaultDiscoveryScheduler;

import javax.annotation.PostConstruct;

/**
 * Configuration class for job executor selection.
 * Selects the appropriate job executor implementation based on the configured
 * mode.
 */
@Configuration
@ConfigurationProperties(prefix = "discovery.job")
public class JobExecutorConfig {

    @Value("${discovery.job.executor.mode:DEFAULT}")
    private String executorMode;

    @Autowired
    private DefaultDiscoveryScheduler scheduler;

    @Autowired(required = false)
    private DefaultJobExecutor defaultExecutor;

    @Autowired(required = false)
    private SparkJobExecutor sparkExecutor;

    @Autowired(required = false)
    private NifiJobExecutor nifiExecutor;

    public String getExecutorMode() {
        return executorMode;
    }

    public void setExecutorMode(String executorMode) {
        this.executorMode = executorMode;
    }

    @PostConstruct
    public void registerExecutors() {
        if (defaultExecutor != null) {
            scheduler.registerExecutor(ExecutionMode.DEFAULT, defaultExecutor);
        }
        if (sparkExecutor != null) {
            scheduler.registerExecutor(ExecutionMode.SPARK, sparkExecutor);
        }
        if (nifiExecutor != null) {
            scheduler.registerExecutor(ExecutionMode.NIFI, nifiExecutor);
        }
    }

    /**
     * Selects the job executor named by {@code discovery.job.executor.mode}: {@code DEFAULT} (in-process),
     * {@code SPARK} or {@code NIFI}. A mode whose executor bean was not created fails the start-up with
     * a message naming the flag that creates it, rather than falling back to another executor.
     *
     * @param defaultExecutor the in-process executor, when present
     * @param sparkExecutor   the Spark executor, present only with {@code discovery.job.executor.spark.enabled=true}
     * @param nifiExecutor    the NiFi executor, present only with {@code discovery.job.executor.nifi.enabled=true}
     * @return the executor for the configured mode
     * @throws IllegalStateException when the mode is unknown or its executor is not available
     */
    @Bean
    @Primary
    public IJobExecutor jobExecutor(
            @Autowired(required = false) DefaultJobExecutor defaultExecutor,
            @Autowired(required = false) SparkJobExecutor sparkExecutor,
            @Autowired(required = false) NifiJobExecutor nifiExecutor) {

        return switch (executorMode.toUpperCase()) {
            case "SPARK" -> {
                if (sparkExecutor == null) {
                    throw new IllegalStateException(unavailable("SPARK", "spark"));
                }
                yield sparkExecutor;
            }
            case "NIFI" -> {
                if (nifiExecutor == null) {
                    throw new IllegalStateException(unavailable("NIFI", "nifi"));
                }
                yield nifiExecutor;
            }
            case "DEFAULT" -> {
                if (defaultExecutor == null) {
                    throw new IllegalStateException(
                            "Job executor mode DEFAULT is configured but DefaultJobExecutor is not in the application context");
                }
                yield defaultExecutor;
            }
            default -> throw new IllegalStateException(
                    "Unsupported job executor mode: " + executorMode + " (discovery.job.executor.mode); use DEFAULT, SPARK or NIFI");
        };
    }

    /**
     * Builds the startup failure for a requested executor whose bean is not in the context, naming
     * the setting that creates it. The SPARK and NIFI executors exist only when
     * {@code discovery.job.executor.<name>.enabled=true}; the prod profile used to select SPARK
     * without that flag, so the application never started.
     *
     * @param mode the requested {@code discovery.job.executor.mode} value
     * @param name the executor's property segment, for the {@code enabled} flag
     * @return the exception message
     */
    private String unavailable(String mode, String name) {
        return "Job executor mode " + mode + " is configured (discovery.job.executor.mode=" + executorMode
                + ") but its executor is not available. Set discovery.job.executor." + name
                + ".enabled=true, or set discovery.job.executor.mode=DEFAULT.";
    }
}