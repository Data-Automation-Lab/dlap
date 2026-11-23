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

import java.util.List;

import com.dlap.discovery.common.model.enums.CloudProvider;

/**
 * Registry for looking up log analyzers by cloud provider.
 */
public interface ILogAnalyzerRegistry {

	/**
	 * Gets a log analyzer for the specified cloud provider.
	 * 
	 * @param provider The cloud provider
	 * @return The log analyzer for the provider, or null if none found
	 */
	ILogAnalyzer getAnalyzer(CloudProvider provider);

	/**
	 * Gets all registered log analyzers.
	 * 
	 * @return List of all registered log analyzers
	 */
	List<ILogAnalyzer> getAllAnalyzers();

	/**
	 * Registers a log analyzer for a specific cloud provider.
	 *
	 * @param provider The cloud provider
	 * @param analyzer The log analyzer to register
	 * @throws IllegalArgumentException if provider or analyzer is null
	 */
	void registerLogAnalyzer(CloudProvider provider, ILogAnalyzer analyzer);

	/**
	 * Unregisters the log analyzer for a specific cloud provider.
	 *
	 * @param provider The cloud provider
	 * @return true if an analyzer was unregistered, false otherwise
	 */
	boolean unregisterLogAnalyzer(CloudProvider provider);
}