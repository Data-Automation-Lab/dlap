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

package com.dlap.adminservice.permissioncheck.providers.azure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The Azure block of the permission self-check, {@code dlap.permission-check.azure.*}. The
 * permissions API answers for the caller, so the token must belong to the Actor identity: run
 * da-admin-service as that managed identity ({@code managed-identity-client-id} picks a
 * user-assigned one), or point {@code access-token-file} at an Azure Resource Manager token of it.
 */
@ConfigurationProperties(prefix = "dlap.permission-check.azure")
public class AzurePermissionCheckProperties {

    private String managementEndpoint = "https://management.azure.com";
    private String imdsEndpoint = "http://169.254.169.254";
    private String managedIdentityClientId = "";
    private String accessTokenFile = "";
    private Map<String, Executor> executors = new LinkedHashMap<>();

    /** @return the Azure Resource Manager endpoint */
    public String getManagementEndpoint() {
        return managementEndpoint;
    }

    /** @param managementEndpoint the Azure Resource Manager endpoint */
    public void setManagementEndpoint(String managementEndpoint) {
        this.managementEndpoint = managementEndpoint;
    }

    /** @return the instance metadata endpoint, for the managed identity's token */
    public String getImdsEndpoint() {
        return imdsEndpoint;
    }

    /** @param imdsEndpoint the instance metadata endpoint */
    public void setImdsEndpoint(String imdsEndpoint) {
        this.imdsEndpoint = imdsEndpoint;
    }

    /** @return the client id of a user-assigned managed identity; blank for the system-assigned one */
    public String getManagedIdentityClientId() {
        return managedIdentityClientId;
    }

    /** @param managedIdentityClientId the client id of a user-assigned managed identity, or blank */
    public void setManagedIdentityClientId(String managedIdentityClientId) {
        this.managedIdentityClientId = managedIdentityClientId;
    }

    /** @return a file holding an Azure Resource Manager token of the Actor; blank uses the managed identity */
    public String getAccessTokenFile() {
        return accessTokenFile;
    }

    /** @param accessTokenFile a file holding a token, or blank */
    public void setAccessTokenFile(String accessTokenFile) {
        this.accessTokenFile = accessTokenFile;
    }

    /** @return the resources of each executor ({@code delete}, {@code archive}) */
    public Map<String, Executor> getExecutors() {
        return executors;
    }

    /** @param executors the resources of each executor */
    public void setExecutors(Map<String, Executor> executors) {
        this.executors = executors;
    }

    /** One executor's named resources, as ARM ids. */
    public static class Executor {
        private List<String> storageAccountIds = new ArrayList<>();
        private List<String> containerIds = new ArrayList<>();
        private boolean objectActions;

        /** @return the storage accounts the Actor was assigned on */
        public List<String> getStorageAccountIds() {
            return storageAccountIds;
        }

        /** @param storageAccountIds the storage account ARM ids */
        public void setStorageAccountIds(List<String> storageAccountIds) {
            this.storageAccountIds = storageAccountIds;
        }

        /** @return the containers the Actor was assigned on */
        public List<String> getContainerIds() {
            return containerIds;
        }

        /** @param containerIds the container ARM ids */
        public void setContainerIds(List<String> containerIds) {
            this.containerIds = containerIds;
        }

        /** @return whether the Actor was applied with object actions on */
        public boolean isObjectActions() {
            return objectActions;
        }

        /** @param objectActions whether the Actor was applied with object actions on */
        public void setObjectActions(boolean objectActions) {
            this.objectActions = objectActions;
        }
    }
}
