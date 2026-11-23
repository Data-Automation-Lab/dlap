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
import org.springframework.stereotype.Component;

/**
 * Holds Azure configuration properties injected from the environment.
 * Only loads when Azure provider is explicitly enabled.
 */
@Component
@ConditionalOnProperty(name = "cloud.provider.azure.enabled", havingValue = "true", matchIfMissing = false)
public class AzureProperties {

    @Value("${azure.subscription-id:dummy-subscription}")
    private String subscriptionId;

    @Value("${azure.tenant-id:dummy-tenant}")
    private String tenantId;

    @Value("${azure.client-id:dummy-client}")
    private String clientId;

    @Value("${azure.client-secret:dummy-secret}")
    private String clientSecret;

    @Value("${azure.resource-group-name:dummy-resource-group}")
    private String resourceGroupName;

    @Value("${azure.region:eastus}")
    private String region;

    @Value("${azure.cosmos.database-name:da_catalog}")
    private String cosmosDatabaseName;

    @Value("${azure.cosmos.container-name:resources}")
    private String cosmosContainerName;

    @Value("${azure.storage.account-name:dummy-storage}")
    private String storageAccountName;

    @Value("${azure.storage.container-name:discovery}")
    private String storageContainerName;

    /**
     * Gets the Azure subscription ID.
     *
     * @return The subscription ID
     */
    public String getSubscriptionId() {
        return subscriptionId;
    }

    /**
     * Gets the Azure tenant ID.
     *
     * @return The tenant ID
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Gets the Azure client (application) ID.
     *
     * @return The client ID
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * Gets the Azure client secret.
     *
     * @return The client secret
     */
    public String getClientSecret() {
        return clientSecret;
    }

    /**
     * Gets the Azure resource group name.
     *
     * @return The resource group name
     */
    public String getResourceGroupName() {
        return resourceGroupName;
    }

    /**
     * Gets the Azure region.
     *
     * @return The region
     */
    public String getRegion() {
        return region;
    }

    /**
     * Gets the Azure Cosmos DB database name.
     *
     * @return The Cosmos DB database name
     */
    public String getCosmosDatabaseName() {
        return cosmosDatabaseName;
    }

    /**
     * Gets the Azure Cosmos DB container name.
     *
     * @return The Cosmos DB container name
     */
    public String getCosmosContainerName() {
        return cosmosContainerName;
    }

    /**
     * Gets the Azure Storage account name.
     *
     * @return The Storage account name
     */
    public String getStorageAccountName() {
        return storageAccountName;
    }

    /**
     * Gets the Azure Storage container name.
     *
     * @return The Storage container name
     */
    public String getStorageContainerName() {
        return storageContainerName;
    }
}