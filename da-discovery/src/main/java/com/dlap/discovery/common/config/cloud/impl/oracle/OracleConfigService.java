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

package com.dlap.discovery.common.config.cloud.impl.oracle;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Service interface for accessing Oracle Cloud configuration values.
 */
@Service
@ConditionalOnProperty(name = "cloud.provider.oci.enabled", havingValue = "true", matchIfMissing = false)
public interface OracleConfigService {

    /**
     * Gets the path to the Oracle Cloud config file.
     *
     * @return The path to the Oracle Cloud config file
     */
    String getConfigFilePath();

    /**
     * Gets the Oracle Cloud profile to use.
     *
     * @return The Oracle Cloud profile name
     */
    String getProfileName();

    /**
     * Gets the Oracle Cloud tenancy OCID.
     *
     * @return The tenancy OCID
     */
    String getTenancyId();

    /**
     * Gets the Oracle Cloud region.
     *
     * @return The Oracle Cloud region
     */
    String getRegion();

    /**
     * Gets the Oracle Cloud compartment OCID.
     *
     * @return The compartment OCID
     */
    String getCompartmentId();

    /**
     * Gets the default compartment for Oracle Cloud.
     *
     * @return The default compartment
     */
    String getDefaultCompartment();
}