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

package com.dlap.discovery.providers.azure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Implementation of the Azure Cloud configuration service.
 */
@Service
@ConditionalOnProperty(name = "cloud.provider.azure.enabled", havingValue = "true", matchIfMissing = false)
public class AzureConfigServiceImpl extends AzureConfigService {

    @Value("${azure.tenant.id}")
    private String tenantId;

    @Value("${azure.client.id}")
    private String clientId;

    @Value("${azure.client.secret}")
    private String clientSecret;

    @Value("${azure.subscription.id}")
    private String subscriptionId;

    @Value("${azure.resource-group.name:#{null}}")
    private String resourceGroupName;

    @Value("${azure.region:westus}")
    private String region;

    @Value("${azure.keyvault.enabled:false}")
    private boolean keyVaultEnabled;

    @Value("${azure.keyvault.uri:#{null}}")
    private String keyVaultUri;

    @Override
    public String getTenantId() {
        return tenantId;
    }

    @Override
    public String getClientId() {
        return clientId;
    }

    @Override
    public String getClientSecret() {
        return clientSecret;
    }

    @Override
    public String getSubscriptionId() {
        return subscriptionId;
    }

    @Override
    public String getResourceGroupName() {
        return resourceGroupName;
    }

    @Override
    public String getRegion() {
        return region;
    }

    @Override
    public boolean isKeyVaultEnabled() {
        return keyVaultEnabled;
    }

    @Override
    public String getKeyVaultUri() {
        return keyVaultUri;
    }
}