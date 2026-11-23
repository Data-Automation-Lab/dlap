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

package com.dlap.autodelete.service;

import com.dlap.autodelete.dto.DeletionConfigDTO;

public interface IDeletionConfigService {

    /**
     * Retrieves the current deletion configuration.
     * @return The current DeletionConfigDTO.
     */
    DeletionConfigDTO getDeletionConfig();

    /**
     * Updates the deletion configuration.
     * @param deletionConfigDTO The new configuration to apply.
     */
    void updateDeletionConfig(DeletionConfigDTO deletionConfigDTO);

    /**
     * Inserts the default global deletion configuration row if absent.
     *
     * <p>Separated from the {@code @PostConstruct} hook so that a proper Spring
     * transaction can be opened around it. Called indirectly via the bean proxy.
     */
    void initDefaultConfig();
} 