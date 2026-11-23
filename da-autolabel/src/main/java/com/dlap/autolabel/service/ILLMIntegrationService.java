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

package com.dlap.autolabel.service;

import com.dlap.llm.client.config.MLConfigRequest;
import java.util.List;

/**
 * Service interface for integrating with various LLM clients.
 */
public interface ILLMIntegrationService {

    /**
     * Suggests labels for a given asset content using the configured LLM.
     *
     * @param assetContent The content or metadata of the asset to label.
     * @param mlConfig The ML configuration specifying which LLM to use and its parameters.
     * @return A list of suggested labels.
     * @throws Exception if there is an error during LLM interaction or if the client is not found.
     */
    List<String> suggestLabelsForAsset(String assetContent, MLConfigRequest mlConfig) throws Exception;

} 