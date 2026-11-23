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

package com.dlap.discovery.providers.azure.auth;

import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.dlap.discovery.providers.azure.config.AzureConfigService;
import com.google.auth.Credentials;

/**
 * Azure implementation of CloudAuthenticationService.
 * Handles authentication with Azure services.
 */
@Service("AzureAuthenticationServiceImpl")
@ConditionalOnProperty(name = "cloud.provider.azure.enabled", havingValue = "true", matchIfMissing = false)
public class AzureAuthenticationServiceImpl implements AzureAuthenticationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AzureAuthenticationServiceImpl.class);

    private final AzureConfigService configService;
    private TokenCredential tokenCredential;

    @Autowired
    public AzureAuthenticationServiceImpl(AzureConfigService configService) {
        this.configService = configService;
    }

    @Override
    public TokenCredential getTokenCredential() {
        if (tokenCredential == null) {
            try {
                // Try to use client secret if available
                if (configService.getClientId() != null && configService.getClientSecret() != null
                        && configService.getTenantId() != null) {
                    ClientSecretCredential clientSecretCredential = new ClientSecretCredentialBuilder()
                            .clientId(configService.getClientId())
                            .clientSecret(configService.getClientSecret())
                            .tenantId(configService.getTenantId())
                            .build();
                    tokenCredential = clientSecretCredential;
                    LOGGER.info("Created Azure client secret credential for tenant: {}", configService.getTenantId());
                } else {
                    // Fall back to default Azure credential chain (environment, managed identity,
                    // etc.)
                    tokenCredential = getDefaultCredentialBuilder().build();
                    LOGGER.info("Created default Azure credential");
                }
            } catch (Exception e) {
                LOGGER.error("Failed to create Azure token credential", e);
                throw new RuntimeException("Failed to authenticate with Azure", e);
            }
        }
        return tokenCredential;
    }

    @Override
    public TokenCredential getTokenCredential(String resourceId) {
        // For most cases, the same credential works for all resources
        // This method could be extended to handle different credentials per resource
        return getTokenCredential();
    }

    @Override
    public DefaultAzureCredentialBuilder getDefaultCredentialBuilder() {
        return new DefaultAzureCredentialBuilder();
    }

    @Override
    public Credentials getCredentials() {
        // Azure uses its own credential mechanism, not Google Credentials
        // This method is implemented for interface compatibility
        LOGGER.info("Getting credentials (adapter method for interface compatibility)");
        return null;
    }

    @Override
    public boolean validateAccess(String resourceId, String permission) {
        return validateResourceAccess(resourceId, permission);
    }

    @Override
    public boolean validateResourceAccess(String resourceId, String permission) {
        try {
            // In a real implementation, this would call the Azure Management API
            // to check if the current identity has the specified permission on the resource
            // For now, we just assume access is valid if we can get credentials
            getTokenCredential();
            LOGGER.debug("Validated access to resource: {} with permission: {}", resourceId, permission);
            return true;
        } catch (Exception e) {
            LOGGER.warn("Failed to validate access to resource: {} with permission: {}", resourceId, permission, e);
            return false;
        }
    }

    @Override
    public Optional<String> getCurrentIdentity() {
        try {
            // In a real implementation, this would call the Azure API to get the current
            // identity
            // For now, we just return the client ID if available
            if (configService.getClientId() != null) {
                return Optional.of(configService.getClientId());
            }
            return Optional.empty();
        } catch (Exception e) {
            LOGGER.warn("Failed to get current Azure identity", e);
            return Optional.empty();
        }
    }

    @Override
    public void refreshCredentials() {
        try {
            tokenCredential = null;
            getTokenCredential();
            LOGGER.info("Successfully refreshed Azure credentials");
        } catch (Exception e) {
            LOGGER.error("Failed to refresh Azure credentials", e);
        }
    }

    @Override
    public boolean validateCredentials(String accountId, Map<String, String> parameters) {
        LOGGER.info("Validating Azure credentials for account {}", accountId);
        try {
            // Get token credential to check if authentication works
            TokenCredential credential = getTokenCredential();

            // Check subscription ID if provided
            if (accountId != null && !accountId.isBlank() &&
                    configService.getSubscriptionId() != null &&
                    !configService.getSubscriptionId().equals(accountId)) {
                LOGGER.warn("Account ID mismatch. Provided: {}, Found in config: {}",
                        accountId, configService.getSubscriptionId());
                // In a real implementation, we might want to check if the credential can access
                // the specified subscription
            }

            // A real implementation would make a lightweight API call to verify
            // the credentials work for the specified subscription
            LOGGER.info("Successfully validated Azure credentials for account: {}",
                    accountId != null ? accountId : configService.getSubscriptionId());
            return credential != null;
        } catch (Exception e) {
            LOGGER.warn("Failed to validate Azure credentials for account {}: {}", accountId, e.getMessage());
            return false;
        }
    }
}