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

package com.dlap.discovery.providers.gcp.auth;

import com.dlap.discovery.common.auth.CloudAuthenticationService;
import com.dlap.discovery.providers.gcp.config.GCPConfigService;
import com.dlap.discovery.common.service.AuthenticationException;
import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * GCP-specific implementation of CloudAuthenticationService.
 */
@Service("GCPAuthenticationServiceImpl")
public class GCPAuthenticationServiceImpl implements CloudAuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(GCPAuthenticationServiceImpl.class);

    private final GCPConfigService gcpConfigService;
    private volatile Credentials cachedCredentials;

    public GCPAuthenticationServiceImpl(GCPConfigService gcpConfigService) {
        this.gcpConfigService = gcpConfigService;
    }

    @Override
    public Credentials getCredentials() {
        if (cachedCredentials == null) {
            synchronized (this) {
                if (cachedCredentials == null) {
                    try {
                        cachedCredentials = GoogleCredentials.getApplicationDefault().createScoped(getRequiredScopes());
                    } catch (IOException e) {
                        log.error("Failed to obtain GCP credentials: {}", e.getMessage());
                        throw new AuthenticationException("Failed to obtain GCP credentials", e);
                    }
                }
            }
        }
        return cachedCredentials;
    }

    @Override
    public boolean validateAccess(String resourceId, String permission) {
        try {
            // For now, just check if we can get credentials
            getCredentials();
            return true;
        } catch (AuthenticationException e) {
            log.error("Failed to validate access to resource {} with permission {}: {}", resourceId, permission, e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Failed to validate access to resource {} with permission {}: {}", resourceId, permission, e.getMessage());
            return false;
        }
    }

    @Override
    public void refreshCredentials() {
        synchronized (this) {
            cachedCredentials = null;
            getCredentials(); // This will create new credentials
        }
    }

    @Override
    public Optional<String> getCurrentIdentity() {
        try {
            Credentials credentials = getCredentials();
            if (credentials instanceof ServiceAccountCredentials) {
                return Optional.of(((ServiceAccountCredentials) credentials).getClientEmail());
            }
            return Optional.empty();
        } catch (AuthenticationException e) {
            log.error("Failed to get current identity: {}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to get current identity: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean validateCredentials(String accountId, Map<String, String> parameters) {
        try {
            // Check if we can get credentials
            Credentials credentials = getCredentials();
            return credentials != null;
        } catch (AuthenticationException e) {
            log.warn("Failed to validate credentials for account {}: {}", accountId, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Failed to validate credentials for account {}: {}", accountId, e.getMessage());
            return false;
        }
    }

    /**
     * Gets the required scopes for GCP authentication.
     *
     * @return List of required scopes
     */
    private List<String> getRequiredScopes() {
        return Arrays.asList("https://www.googleapis.com/auth/cloud-platform", "https://www.googleapis.com/auth/cloud-platform.read-only");
    }
}
