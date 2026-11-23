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
import java.util.List;
import java.util.Map;

import com.dlap.discovery.common.model.ResourceChange;

/**
 * Record representing the result of a log analysis operation.
 * Contains information about the changes found and the time range analyzed.
 * 
 * @param changes   Changes found during analysis
 * @param startTime Start time of the analysis period
 * @param endTime   End time of the analysis period
 * @param metadata  Additional metadata from the analysis
 */
public record AnalysisResult(
		List<ResourceChange> changes,
		Instant startTime,
		Instant endTime,
		Map<String, Object> metadata) {
	/**
	 * Returns the number of changes found.
	 * 
	 * @return Number of changes
	 */
	public int getChangeCount() {
		return changes != null ? changes.size() : 0;
	}

	/**
	 * Returns whether any changes were found.
	 * 
	 * @return true if changes were found, false otherwise
	 */
	public boolean hasChanges() {
		return changes != null && !changes.isEmpty();
	}
}