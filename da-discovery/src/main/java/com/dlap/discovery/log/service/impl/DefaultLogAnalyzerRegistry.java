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

package com.dlap.discovery.log.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.log.service.ILogAnalyzer;
import com.dlap.discovery.log.service.ILogAnalyzerRegistry;

/**
 * Default implementation of ILogAnalyzerRegistry using a concurrent map for
 * thread-safe
 * registration and retrieval of log analyzers.
 */
@Service
public class DefaultLogAnalyzerRegistry implements ILogAnalyzerRegistry {
	private static final Logger log = LoggerFactory.getLogger(DefaultLogAnalyzerRegistry.class);

	private final Map<CloudProvider, ILogAnalyzer> analyzers = new ConcurrentHashMap<>();

	@Override
	public ILogAnalyzer getAnalyzer(CloudProvider provider) {
		if (provider == null) {
			throw new IllegalArgumentException("Provider must not be null");
		}
		return analyzers.get(provider);
	}

	@Override
	public List<ILogAnalyzer> getAllAnalyzers() {
		return new ArrayList<>(analyzers.values());
	}

	@Override
	public void registerLogAnalyzer(CloudProvider provider, ILogAnalyzer analyzer) {
		if (provider == null) {
			throw new IllegalArgumentException("Provider must not be null");
		}
		if (analyzer == null) {
			throw new IllegalArgumentException("Analyzer must not be null");
		}

		ILogAnalyzer previous = analyzers.put(provider, analyzer);
		if (previous != null) {
			log.info("Replaced existing log analyzer for provider {}", provider);
		} else {
			log.info("Registered new log analyzer for provider {}", provider);
		}
	}

	@Override
	public boolean unregisterLogAnalyzer(CloudProvider provider) {
		if (provider == null) {
			throw new IllegalArgumentException("Provider must not be null");
		}

		ILogAnalyzer removed = analyzers.remove(provider);
		if (removed != null) {
			log.info("Unregistered log analyzer for provider {}", provider);
			return true;
		}
		return false;
	}
}