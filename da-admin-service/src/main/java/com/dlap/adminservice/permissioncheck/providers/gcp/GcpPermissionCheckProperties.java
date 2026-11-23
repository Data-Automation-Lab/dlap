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

package com.dlap.adminservice.permissioncheck.providers.gcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The GCP block of the permission self-check, {@code dlap.permission-check.gcp.*}.
 * {@code testIamPermissions} answers for the caller, so the token must belong to the Actor service
 * account: run da-admin-service as that account, or point {@code access-token-file} at a token of
 * it (for example one minted by {@code gcloud auth print-access-token --impersonate-service-account}).
 */
@ConfigurationProperties(prefix = "dlap.permission-check.gcp")
public class GcpPermissionCheckProperties {

    private String storageEndpoint = "https://storage.googleapis.com";
    private String bigqueryEndpoint = "https://bigquery.googleapis.com";
    private String metadataEndpoint = "http://metadata.google.internal";
    private String accessTokenFile = "";
    private Map<String, Executor> executors = new LinkedHashMap<>();

    /** @return the Cloud Storage JSON API endpoint */
    public String getStorageEndpoint() {
        return storageEndpoint;
    }

    /** @param storageEndpoint the Cloud Storage JSON API endpoint */
    public void setStorageEndpoint(String storageEndpoint) {
        this.storageEndpoint = storageEndpoint;
    }

    /** @return the BigQuery API endpoint */
    public String getBigqueryEndpoint() {
        return bigqueryEndpoint;
    }

    /** @param bigqueryEndpoint the BigQuery API endpoint */
    public void setBigqueryEndpoint(String bigqueryEndpoint) {
        this.bigqueryEndpoint = bigqueryEndpoint;
    }

    /** @return the metadata server, for the attached service account's token */
    public String getMetadataEndpoint() {
        return metadataEndpoint;
    }

    /** @param metadataEndpoint the metadata server */
    public void setMetadataEndpoint(String metadataEndpoint) {
        this.metadataEndpoint = metadataEndpoint;
    }

    /** @return a file holding an access token of the Actor account; blank uses the metadata server */
    public String getAccessTokenFile() {
        return accessTokenFile;
    }

    /** @param accessTokenFile a file holding an access token, or blank */
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

    /** One executor's named resources. */
    public static class Executor {
        private List<String> buckets = new ArrayList<>();
        private boolean objectActions;
        private List<String> bigqueryTables = new ArrayList<>();

        /** @return the bucket names the Actor was granted on */
        public List<String> getBuckets() {
            return buckets;
        }

        /** @param buckets the bucket names */
        public void setBuckets(List<String> buckets) {
            this.buckets = buckets;
        }

        /** @return whether the Actor was applied with object actions on */
        public boolean isObjectActions() {
            return objectActions;
        }

        /** @param objectActions whether the Actor was applied with object actions on */
        public void setObjectActions(boolean objectActions) {
            this.objectActions = objectActions;
        }

        /**
         * @return one table per named dataset, as {@code project.dataset.table}: BigQuery tests
         *         permissions on a table, which inherits the dataset grant
         */
        public List<String> getBigqueryTables() {
            return bigqueryTables;
        }

        /** @param bigqueryTables tables as {@code project.dataset.table} */
        public void setBigqueryTables(List<String> bigqueryTables) {
            this.bigqueryTables = bigqueryTables;
        }
    }
}
