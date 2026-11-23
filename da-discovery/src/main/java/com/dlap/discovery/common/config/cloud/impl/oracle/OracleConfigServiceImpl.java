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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Implementation of the Oracle Cloud Infrastructure configuration service.
 */
@Service
@ConditionalOnProperty(name = "cloud.provider.oci.enabled", havingValue = "true", matchIfMissing = false)
public class OracleConfigServiceImpl implements OracleConfigService {

    @Value("${oracle.config.file.path:~/.oci/config}")
    private String configFilePath;

    @Value("${oracle.config.profile:DEFAULT}")
    private String profileName;

    @Value("${oracle.tenancy.id}")
    private String tenancyId;

    @Value("${oracle.region}")
    private String region;

    @Value("${oracle.compartment.id}")
    private String compartmentId;

    @Override
    public String getConfigFilePath() {
        return configFilePath;
    }

    @Override
    public String getProfileName() {
        return profileName;
    }

    @Override
    public String getTenancyId() {
        return tenancyId;
    }

    @Override
    public String getRegion() {
        return region;
    }

    @Override
    public String getCompartmentId() {
        return compartmentId;
    }

    @Override
    public String getDefaultCompartment() {
        return compartmentId;
    }
}