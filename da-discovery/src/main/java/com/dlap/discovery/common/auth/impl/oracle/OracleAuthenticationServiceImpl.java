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

package com.dlap.discovery.common.auth.impl.oracle;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.dlap.discovery.common.config.cloud.impl.oracle.OracleConfigService;
import com.google.auth.Credentials;
import com.oracle.bmc.auth.AbstractAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.requests.ListAvailabilityDomainsRequest;

/**
 * Implementation of the Oracle Cloud authentication service.
 */
@Service("oracleAuthService")
@ConditionalOnProperty(name = "cloud.provider.oci.enabled", havingValue = "true", matchIfMissing = false)
public class OracleAuthenticationServiceImpl implements IOracleAuthenticationService {
    private static final Logger log = LoggerFactory.getLogger(OracleAuthenticationServiceImpl.class);

    private final OracleConfigService oracleConfigService;
    private AbstractAuthenticationDetailsProvider authProvider;

    /**
     * Creates a new OracleAuthenticationServiceImpl with the specified
     * configuration service.
     *
     * @param oracleConfigService The Oracle configuration service
     */
    @Autowired
    public OracleAuthenticationServiceImpl(OracleConfigService oracleConfigService) {
        this.oracleConfigService = oracleConfigService;
        // Lazy initialization or initialize here
        // initializeAuthProvider();
    }

    // Initialize provider on first use or in constructor
    private synchronized AbstractAuthenticationDetailsProvider ensureAuthProviderInitialized() throws IOException {
        if (authProvider == null) {
            try {
                String configPath = oracleConfigService.getConfigFilePath();
                String profile = oracleConfigService.getProfileName();
                log.info("Initializing Oracle authentication using config: {}, profile: {}", configPath, profile);
                this.authProvider = new ConfigFileAuthenticationDetailsProvider(configPath, profile);
            } catch (IOException e) {
                log.error("Failed to initialize Oracle Authentication Provider from config file: {}", e.getMessage(),
                        e);
                throw new IOException("Failed to load Oracle credentials", e); // Re-throw IOException as per interface
            } catch (Exception e) {
                log.error("Unexpected error initializing Oracle Authentication Provider: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to initialize Oracle Authentication Provider", e);
            }
        }
        return authProvider;
    }

    @Override
    public Object getAuthenticationDetailsProvider() throws IOException {
        return ensureAuthProviderInitialized();
    }

    @Override
    public boolean validateAccess() {
        try {
            AbstractAuthenticationDetailsProvider provider = ensureAuthProviderInitialized();
            // Get tenantId - requires casting or specific provider knowledge
            // Placeholder: Assume ConfigFileAuthenticationDetailsProvider is used
            String tenantId = (provider instanceof ConfigFileAuthenticationDetailsProvider)
                    ? ((ConfigFileAuthenticationDetailsProvider) provider).getTenantId()
                    : null;
            if (tenantId == null) {
                log.error("Could not determine tenant ID from auth provider: {}", provider.getClass().getName());
                return false;
            }

            IdentityClient identityClient = IdentityClient.builder().build(provider);
            identityClient.listAvailabilityDomains(ListAvailabilityDomainsRequest.builder()
                    .compartmentId(tenantId) // Use retrieved tenantId
                    .build());
            identityClient.close();
            log.info("Oracle credentials validated successfully for tenancy: {}", tenantId);
            return true;
        } catch (Exception e) {
            log.error("Oracle credential validation failed: {}", e.getMessage());
            if (e.getCause() instanceof java.net.UnknownHostException || e.getMessage().contains("Unauthorized")) {
                log.warn("Could not validate Oracle credentials. Check config, network, and permissions. Error: {}",
                        e.getMessage());
            } else {
                log.error("Unexpected error during Oracle credential validation: ", e);
            }
            return false;
        }
    }

    @Override
    public Optional<String> getCurrentIdentity() {
        try {
            AbstractAuthenticationDetailsProvider provider = ensureAuthProviderInitialized();
            // Get userId - requires casting or specific provider knowledge
            // Placeholder: Assume ConfigFileAuthenticationDetailsProvider is used
            String userId = (provider instanceof ConfigFileAuthenticationDetailsProvider)
                    ? ((ConfigFileAuthenticationDetailsProvider) provider).getUserId()
                    : null;
            return Optional.ofNullable(userId);
        } catch (Exception e) {
            log.error("Failed to get current Oracle identity: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Credentials getCredentials() {
        log.warn("getCredentials() called on OracleAuthenticationService. Returning null.");
        // This method is for compatibility; Oracle uses
        // AbstractAuthenticationDetailsProvider.
        return null;
    }

    @Override
    public boolean validateAccess(String resourceType, String resourceId) {
        log.info("Validating access to resource: type={}, id={}", resourceType, resourceId);
        try {
            // This would use Oracle Cloud API to check if current identity has access to
            // the resource
            // For implementation, you would use the authentication details provider to
            // create a
            // client for the appropriate service and check access permissions

            // For placeholder implementation, assume access is granted
            return true;
        } catch (Exception e) {
            log.error("Failed to validate access to resource: type={}, id={}", resourceType, resourceId, e);
            return false;
        }
    }

    @Override
    public void refreshCredentials() {
        log.info("Refreshing Oracle Cloud credentials (Placeholder - might re-initialize provider)");
        // Invalidate current provider so it gets re-initialized on next use
        synchronized (this) {
            this.authProvider = null;
        }
        // ensureAuthProviderInitialized(); // Optionally force re-initialization now
    }

    @Override
    public boolean validateCredentials(String accountId, Map<String, String> parameters) {
        log.info("Validating Oracle Cloud credentials for account {}", accountId);
        try {
            AbstractAuthenticationDetailsProvider provider = ensureAuthProviderInitialized();

            // Get tenantId - requires casting or specific provider knowledge
            String tenantId = (provider instanceof ConfigFileAuthenticationDetailsProvider)
                    ? ((ConfigFileAuthenticationDetailsProvider) provider).getTenantId()
                    : null;

            // Check if the tenancy ID matches the account ID if specified
            if (accountId != null && !accountId.isBlank() && tenantId != null && !tenantId.equals(accountId)) {
                log.warn("Account ID mismatch. Provided: {}, Found in config: {}", accountId, tenantId);
                return false;
            }

            // Use Identity service to test credentials
            IdentityClient identityClient = IdentityClient.builder().build(provider);
            identityClient.listAvailabilityDomains(ListAvailabilityDomainsRequest.builder()
                    .compartmentId(tenantId)
                    .build());
            identityClient.close();

            log.info("Successfully validated Oracle credentials for account: {}",
                    accountId != null ? accountId : tenantId);
            return true;
        } catch (Exception e) {
            log.error("Failed to validate Oracle credentials for account {}: {}", accountId, e.getMessage());
            return false;
        }
    }
}