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

package com.dlap.discovery.log.service.gcp.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.dlap.discovery.log.service.ILogAnalyzer.AnalysisOptions;
import com.dlap.discovery.log.service.gcp.config.GcpLogSourceType;

/**
 * Service interface for GCP log configuration management.
 * Provides methods to manage checkpoints, source details, and exclusion
 * filters.
 */
public interface IGcpLogConfigService {

    /**
     * Save a checkpoint for a specific account and options combination.
     * 
     * @param accountId  The account ID
     * @param options    The analysis options
     * @param checkpoint The checkpoint timestamp
     * @return true if the operation was successful
     */
    boolean saveCheckpoint(String accountId, AnalysisOptions options, Instant checkpoint);

    /**
     * Load the checkpoint for a specific account and options combination.
     * 
     * @param accountId The account ID
     * @param options   The analysis options
     * @return The checkpoint timestamp, or null if not found
     */
    Instant loadCheckpoint(String accountId, AnalysisOptions options);

    /**
     * Save source details for a specific account and source type.
     * 
     * @param accountId  The account ID
     * @param sourceType The source type
     * @param details    The source details map
     * @return true if the operation was successful
     */
    boolean saveSourceDetails(String accountId, GcpLogSourceType sourceType, Map<String, String> details);

    /**
     * Load source details for a specific account and source type.
     * 
     * @param accountId  The account ID
     * @param sourceType The source type
     * @return The source details map, or an empty map if not found
     */
    Map<String, String> loadSourceDetails(String accountId, GcpLogSourceType sourceType);

    /**
     * Save exclusion filters for a specific account.
     * 
     * @param accountId The account ID
     * @param filters   The list of exclusion filter strings
     * @return true if the operation was successful
     */
    boolean saveExclusionFilters(String accountId, List<String> filters);

    /**
     * Load exclusion filters for a specific account.
     * 
     * @param accountId The account ID
     * @return The list of exclusion filter strings, or an empty list if not found
     */
    List<String> loadExclusionFilters(String accountId);

    /**
     * Generate a deterministic hash for the given analysis options.
     * 
     * @param options The analysis options
     * @return A hash string
     */
    String generateOptionsHash(AnalysisOptions options);
}