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

import java.util.concurrent.Callable;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.job.ExecutionMode;
import com.dlap.discovery.job.executable.CrawlerJavaExecutable;
import com.dlap.discovery.job.executable.NiFiExecutable;
import com.dlap.discovery.job.executable.NiFiTaskConfiguration;
import com.dlap.discovery.job.executable.SparkExecutable;
import com.dlap.discovery.job.executable.SparkJobConfiguration;

/**
 * A builder-style class to configure the execution details (mode and
 * executable)
 * for a DiscoveryJob instance.
 * This provides a fluent API for setting up how a job should run.
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE) // New instance per configuration task
public class JobConfiguration {

	private final DiscoveryJob job;

	/**
	 * Constructs a new configuration builder for the given job.
	 * Typically obtained via DiscoveryJobService.configureJob(job).
	 *
	 * @param job The DiscoveryJob entity to configure.
	 */
	public JobConfiguration(DiscoveryJob job) {
		if (job == null) {
			throw new IllegalArgumentException("DiscoveryJob cannot be null for configuration.");
		}
		this.job = job;
	}

	/**
	 * Configures the job for DEFAULT execution using the provided Java Callable.
	 *
	 * @param <R>      The return type of the callable.
	 * @param callable The Java Callable representing the job's task.
	 * @return This JobConfiguration instance for method chaining.
	 */
	public <R> JobConfiguration withDefaultExecution(Callable<R> callable) {
		if (callable == null) {
			throw new IllegalArgumentException("Callable cannot be null for DEFAULT execution.");
		}
		job.setExecutionMode(ExecutionMode.DEFAULT);
		job.setExecutable(new CrawlerJavaExecutable<>(callable));
		return this;
	}

	/**
	 * Configures the job for SPARK execution using the provided Spark job
	 * configuration.
	 *
	 * @param sparkConfig The configuration required to submit the Spark job.
	 * @return This JobConfiguration instance for method chaining.
	 */
	public JobConfiguration withSparkExecution(SparkJobConfiguration sparkConfig) {
		if (sparkConfig == null) {
			throw new IllegalArgumentException("SparkJobConfiguration cannot be null for SPARK execution.");
		}
		job.setExecutionMode(ExecutionMode.SPARK);
		job.setExecutable(new SparkExecutable(sparkConfig));
		return this;
	}

	/**
	 * Configures the job for NIFI execution using the provided NiFi task
	 * configuration.
	 *
	 * @param nifiConfig The configuration required to trigger the NiFi task.
	 * @return This JobConfiguration instance for method chaining.
	 */
	public JobConfiguration withNiFiExecution(NiFiTaskConfiguration nifiConfig) {
		if (nifiConfig == null) {
			throw new IllegalArgumentException("NiFiTaskConfiguration cannot be null for NIFI execution.");
		}
		job.setExecutionMode(ExecutionMode.NIFI);
		job.setExecutable(new NiFiExecutable(nifiConfig));
		return this;
	}

	/**
	 * Finalizes the configuration and returns the configured DiscoveryJob instance.
	 *
	 * @return The DiscoveryJob entity with execution details set.
	 */
	public DiscoveryJob build() {
		// Potentially add validation here to ensure an executable was set
		if (job.getExecutable() == null) {
			throw new IllegalStateException("Job execution details (executable) must be configured before building.");
		}
		return job;
	}
}