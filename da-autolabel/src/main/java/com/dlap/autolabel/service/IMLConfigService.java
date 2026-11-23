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

import java.util.Optional;

import com.dlap.llm.client.config.MLConfigRequest;

/**
 * Service interface for managing ML configurations for auto-labeling.
 */
public interface IMLConfigService {

    /**
     * Updates the ML configuration.
     *
     * @param mlConfigRequest The ML configuration request DTO.
     */
    void updateMlConfig(MLConfigRequest mlConfigRequest);

    /**
     * Retrieves the current ML configuration.
     *
     * @return The current {@link MLConfigRequest}, or {@link Optional#empty()}
     *         if no configuration has been persisted yet. Callers that require
     *         a configuration must treat an empty optional as an error
     *         condition - never substitute a default or mock.
     */
    Optional<MLConfigRequest> getMlConfig();
}
