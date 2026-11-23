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

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.dlap.discovery.common.auth.CloudAuthenticationService;

import java.util.Optional;

/**
 * Service interface for Azure Cloud Authentication operations.
 */
public interface AzureAuthenticationService extends CloudAuthenticationService {

    /**
     * Gets an Azure token credential for authentication with Azure services.
     *
     * @return The token credential
     */
    TokenCredential getTokenCredential();

    /**
     * Gets an Azure token credential for a specific resource.
     *
     * @param resourceId The resource ID to get credentials for
     * @return The token credential
     */
    TokenCredential getTokenCredential(String resourceId);

    /**
     * Gets the default Azure credential builder.
     *
     * @return The default credential builder
     */
    DefaultAzureCredentialBuilder getDefaultCredentialBuilder();
    
    /**
     * Validates access to the specified Azure resource.
     *
     * @param resourceId The Azure resource ID to validate access for
     * @param permission The permission to validate (e.g., "Microsoft.Storage/storageAccounts/read")
     * @return true if the authenticated identity has the specified permission on the resource
     */
    boolean validateResourceAccess(String resourceId, String permission);
} 