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

package com.dlap.discovery.client.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank; // Assuming JSR 380 annotations are available
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

// Main DTO for the POST /discovery/scans request
public class DiscoveryScanRequest {

    @NotBlank(message = "Cloud Connection ID is required")
    private String cloudConnectionId;

    @NotNull(message = "Scan type is required")
    private ScanType scanType;

    @NotNull(message = "Scope is required")
    @Valid
    private Scope scope;

    private String scanName; // Optional scan name from request

    @Valid
    private ScanConfiguration configuration; // Optional scan configuration

    // Getters and Setters
    public String getCloudConnectionId() {
        return cloudConnectionId;
    }

    public void setCloudConnectionId(String cloudConnectionId) {
        this.cloudConnectionId = cloudConnectionId;
    }

    public ScanType getScanType() {
        return scanType;
    }

    public void setScanType(ScanType scanType) {
        this.scanType = scanType;
    }

    public Scope getScope() {
        return scope;
    }

    public void setScope(Scope scope) {
        this.scope = scope;
    }

    public String getScanName() {
        return scanName;
    }

    public void setScanName(String scanName) {
        this.scanName = scanName;
    }

    public ScanConfiguration getConfiguration() {
        return configuration;
    }

    public void setConfiguration(ScanConfiguration configuration) {
        this.configuration = configuration;
    }

    // Enum for ScanType
    public enum ScanType {
        // GCP
        GCP_PROJECTS, // Discover resources within specified GCP projects
        GCP_ORGANIZATION, // Discover all projects and resources within a GCP organization
        GCP_BIGQUERY_DATASETS, // Specifically discover BigQuery datasets (could be part of GCP_PROJECTS)

        // AWS
        AWS_ACCOUNT_FULL, // Discover all supported resources in an AWS account (potentially across regions)
        AWS_S3_BUCKETS, // Specifically discover S3 buckets
        AWS_EC2_INSTANCES, // Specifically discover EC2 instances
        // Add other specific AWS service scans if needed

        // Azure
        AZURE_SUBSCRIPTION, // Discover resources within an Azure subscription
        AZURE_RESOURCE_GROUP, // Discover resources within a specific Azure resource group
        // Add other specific Azure service scans if needed

        // OCI (Oracle Cloud Infrastructure)
        OCI_TENANCY, // Discover resources within an OCI tenancy
        OCI_COMPARTMENT, // Discover resources within a specific OCI compartment
        // Add other specific OCI service scans if needed

        // Generic/Fallback (less preferred, aim for specific types)
        FULL, // A full scan, provider determined by connectionId
        INCREMENTAL, // An incremental scan, provider determined by connectionId
    }

    // Nested DTO for Scope
    public static class Scope {

        private GcpScope gcp;
        private AwsScope aws;
        private AzureScope azure;
        private OciScope oci;

        // Getters and Setters
        public GcpScope getGcp() {
            return gcp;
        }

        public void setGcp(GcpScope gcp) {
            this.gcp = gcp;
        }

        public AwsScope getAws() {
            return aws;
        }

        public void setAws(AwsScope aws) {
            this.aws = aws;
        }

        public AzureScope getAzure() {
            return azure;
        }

        public void setAzure(AzureScope azure) {
            this.azure = azure;
        }

        public OciScope getOci() {
            return oci;
        }

        public void setOci(OciScope oci) {
            this.oci = oci;
        }
    }

    // Nested DTO for ScanConfiguration (mirrors structure in DiscoveryScanDetail.ScanConfiguration)
    public static class ScanConfiguration {

        private String mode; // "MANUAL", "SCHEDULED"
        private String schedule; // cron expression if mode is SCHEDULED
        private Map<String, Object> overrides; // Specific config overrides, e.g., {"enableFullScan": true}
        private Integer maxDepth; // for hierarchical discovery
        private Boolean incremental; // for incremental scans

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public String getSchedule() {
            return schedule;
        }

        public void setSchedule(String schedule) {
            this.schedule = schedule;
        }

        public Map<String, Object> getOverrides() {
            return overrides;
        }

        public void setOverrides(Map<String, Object> overrides) {
            this.overrides = overrides;
        }

        public Integer getMaxDepth() {
            return maxDepth;
        }

        public void setMaxDepth(Integer maxDepth) {
            this.maxDepth = maxDepth;
        }

        public Boolean getIncremental() {
            return incremental;
        }

        public void setIncremental(Boolean incremental) {
            this.incremental = incremental;
        }
    }

    // Nested DTO for GCP Scope
    public static class GcpScope {

        private List<String> projectIds;
        private List<String> includeServices; // e.g., "BIGQUERY", "GCS"
        private Map<String, Object> serviceSpecificRefinements; // For future flexibility

        // Getters and Setters
        public List<String> getProjectIds() {
            return projectIds;
        }

        public void setProjectIds(List<String> projectIds) {
            this.projectIds = projectIds;
        }

        public List<String> getIncludeServices() {
            return includeServices;
        }

        public void setIncludeServices(List<String> includeServices) {
            this.includeServices = includeServices;
        }

        public Map<String, Object> getServiceSpecificRefinements() {
            return serviceSpecificRefinements;
        }

        public void setServiceSpecificRefinements(Map<String, Object> serviceSpecificRefinements) {
            this.serviceSpecificRefinements = serviceSpecificRefinements;
        }
    }

    // Nested DTO for AWS Scope
    public static class AwsScope {

        private List<String> accountIds;
        private List<String> regions;
        private List<String> includeServices; // e.g., "S3", "EMR", "REDSHIFT"
        private Map<String, Object> serviceSpecificRefinements;

        // Getters and Setters
        public List<String> getAccountIds() {
            return accountIds;
        }

        public void setAccountIds(List<String> accountIds) {
            this.accountIds = accountIds;
        }

        public List<String> getRegions() {
            return regions;
        }

        public void setRegions(List<String> regions) {
            this.regions = regions;
        }

        public List<String> getIncludeServices() {
            return includeServices;
        }

        public void setIncludeServices(List<String> includeServices) {
            this.includeServices = includeServices;
        }

        public Map<String, Object> getServiceSpecificRefinements() {
            return serviceSpecificRefinements;
        }

        public void setServiceSpecificRefinements(Map<String, Object> serviceSpecificRefinements) {
            this.serviceSpecificRefinements = serviceSpecificRefinements;
        }
    }

    // Nested DTO for Azure Scope
    public static class AzureScope {

        private List<String> subscriptionIds;
        private List<String> resourceGroups; // Optional filter
        private List<String> includeServices; // e.g., "BLOB_STORAGE", "SQL_DATABASE"
        private Map<String, Object> serviceSpecificRefinements;

        // Getters and Setters
        public List<String> getSubscriptionIds() {
            return subscriptionIds;
        }

        public void setSubscriptionIds(List<String> subscriptionIds) {
            this.subscriptionIds = subscriptionIds;
        }

        public List<String> getResourceGroups() {
            return resourceGroups;
        }

        public void setResourceGroups(List<String> resourceGroups) {
            this.resourceGroups = resourceGroups;
        }

        public List<String> getIncludeServices() {
            return includeServices;
        }

        public void setIncludeServices(List<String> includeServices) {
            this.includeServices = includeServices;
        }

        public Map<String, Object> getServiceSpecificRefinements() {
            return serviceSpecificRefinements;
        }

        public void setServiceSpecificRefinements(Map<String, Object> serviceSpecificRefinements) {
            this.serviceSpecificRefinements = serviceSpecificRefinements;
        }
    }

    // Nested DTO for OCI Scope
    public static class OciScope {

        private List<String> compartmentIds;
        private List<String> regions; // Optional filter
        private List<String> includeServices; // e.g., "OBJECT_STORAGE", "AUTONOMOUS_DATABASE"
        private Map<String, Object> serviceSpecificRefinements;

        // Getters and Setters
        public List<String> getCompartmentIds() {
            return compartmentIds;
        }

        public void setCompartmentIds(List<String> compartmentIds) {
            this.compartmentIds = compartmentIds;
        }

        public List<String> getRegions() {
            return regions;
        }

        public void setRegions(List<String> regions) {
            this.regions = regions;
        }

        public List<String> getIncludeServices() {
            return includeServices;
        }

        public void setIncludeServices(List<String> includeServices) {
            this.includeServices = includeServices;
        }

        public Map<String, Object> getServiceSpecificRefinements() {
            return serviceSpecificRefinements;
        }

        public void setServiceSpecificRefinements(Map<String, Object> serviceSpecificRefinements) {
            this.serviceSpecificRefinements = serviceSpecificRefinements;
        }
    }
}
