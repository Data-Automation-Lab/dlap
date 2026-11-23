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

package com.dlap.discovery.coverage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the read-coverage collectors ({@code dlap.discovery.coverage.*}, COV-01). Everything
 * is off by default: {@code dlap.discovery.coverage.enabled=true} starts the job, and each cloud
 * needs its own {@code <cloud>.enabled=true}. As environment variables:
 * {@code DLAP_DISCOVERY_COVERAGE_ENABLED}, {@code DLAP_DISCOVERY_COVERAGE_GCP_ENABLED},
 * {@code DLAP_DISCOVERY_COVERAGE_GCP_PROJECTS} (comma-separated) and so on.
 *
 * <p>The schedule is {@code dlap.discovery.coverage.cron} (default {@code 0 41 0/6 * * *}, every six
 * hours), read by the job's annotation.
 */
@ConfigurationProperties(prefix = "dlap.discovery.coverage")
public class ReadCoverageProperties {

    private boolean enabled;
    private Duration lockAtMostFor = Duration.ofHours(1);
    private Duration lockAtLeastFor = Duration.ofMinutes(1);
    private Duration sendTimeout = Duration.ofSeconds(60);
    private final Aws aws = new Aws();
    private final Gcp gcp = new Gcp();
    private final Azure azure = new Azure();

    /** @return whether the collection job runs */
    public boolean isEnabled() {
        return enabled;
    }

    /** @param enabled whether the collection job runs */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** @return the longest time a run holds the cluster-wide lock */
    public Duration getLockAtMostFor() {
        return lockAtMostFor;
    }

    /** @param lockAtMostFor the longest time a run holds the lock; at least a minute */
    public void setLockAtMostFor(Duration lockAtMostFor) {
        this.lockAtMostFor = lockAtMostFor;
    }

    /** @return the shortest time a run holds the lock, so replicas with skewed schedules do not repeat it */
    public Duration getLockAtLeastFor() {
        return lockAtLeastFor;
    }

    /** @param lockAtLeastFor the shortest time a run holds the lock */
    public void setLockAtLeastFor(Duration lockAtLeastFor) {
        this.lockAtLeastFor = lockAtLeastFor;
    }

    /** @return how long a run waits for the broker to acknowledge its observations */
    public Duration getSendTimeout() {
        return sendTimeout;
    }

    /** @param sendTimeout how long a run waits for acknowledgements */
    public void setSendTimeout(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }

    /** @return the AWS settings */
    public Aws getAws() {
        return aws;
    }

    /** @return the GCP settings */
    public Gcp getGcp() {
        return gcp;
    }

    /** @return the Azure settings */
    public Azure getAzure() {
        return azure;
    }

    /** AWS: CloudTrail trails and event selectors for the account's S3 buckets. */
    public static class Aws {

        private boolean enabled;
        private String cloudtrailEndpoint = "";
        private String defaultRegion = "us-east-1";

        /** @return whether the AWS collector runs (it also needs {@code cloud.provider.aws.enabled=true}) */
        public boolean isEnabled() {
            return enabled;
        }

        /** @param enabled whether the AWS collector runs */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * A CloudTrail endpoint that replaces {@code https://cloudtrail.<region>.amazonaws.com}, for
         * an emulator. Empty in real deployments.
         *
         * @return the override, or empty
         */
        public String getCloudtrailEndpoint() {
            return cloudtrailEndpoint;
        }

        /** @param cloudtrailEndpoint the endpoint override */
        public void setCloudtrailEndpoint(String cloudtrailEndpoint) {
            this.cloudtrailEndpoint = cloudtrailEndpoint == null ? "" : cloudtrailEndpoint;
        }

        /** @return the region for a bucket whose location reads as empty ({@code us-east-1} by S3's own rule) */
        public String getDefaultRegion() {
            return defaultRegion;
        }

        /** @param defaultRegion the region of a bucket with an empty location constraint */
        public void setDefaultRegion(String defaultRegion) {
            this.defaultRegion = defaultRegion;
        }
    }

    /** GCP: IAM-policy audit configs of each project and its ancestors, and bucket access settings. */
    public static class Gcp {

        private boolean enabled;
        private List<String> projects = new ArrayList<>();
        private boolean bigqueryCrossProjectReadsVerified;
        private String resourceManagerEndpoint = "https://cloudresourcemanager.googleapis.com";
        private String storageEndpoint = "https://storage.googleapis.com";

        /** @return whether the GCP collector runs */
        public boolean isEnabled() {
            return enabled;
        }

        /** @param enabled whether the GCP collector runs */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** @return the project ids to observe; empty means the discovery project ({@code gcp.project-id}) */
        public List<String> getProjects() {
            return projects;
        }

        /** @param projects the project ids */
        public void setProjects(List<String> projects) {
            this.projects = projects == null ? new ArrayList<>() : new ArrayList<>(projects);
        }

        /**
         * Whether an operator has confirmed on a live project that BigQuery records reads by jobs
         * that run in other projects in the table's project (open question for the LIVE-00
         * capture). While false, BigQuery coverage is at most {@code PARTIAL}.
         *
         * @return the confirmation
         */
        public boolean isBigqueryCrossProjectReadsVerified() {
            return bigqueryCrossProjectReadsVerified;
        }

        /** @param verified the confirmation */
        public void setBigqueryCrossProjectReadsVerified(boolean verified) {
            this.bigqueryCrossProjectReadsVerified = verified;
        }

        /** @return the Resource Manager API base URL */
        public String getResourceManagerEndpoint() {
            return resourceManagerEndpoint;
        }

        /** @param endpoint the Resource Manager API base URL */
        public void setResourceManagerEndpoint(String endpoint) {
            this.resourceManagerEndpoint = endpoint;
        }

        /** @return the Cloud Storage JSON API base URL */
        public String getStorageEndpoint() {
            return storageEndpoint;
        }

        /** @param endpoint the Cloud Storage JSON API base URL */
        public void setStorageEndpoint(String endpoint) {
            this.storageEndpoint = endpoint;
        }
    }

    /** Azure: blob-service diagnostic settings of each storage account. */
    public static class Azure {

        private boolean enabled;
        private List<String> subscriptions = new ArrayList<>();
        private String managementEndpoint = "https://management.azure.com";

        /** @return whether the Azure collector runs (it also needs {@code cloud.provider.azure.enabled=true}) */
        public boolean isEnabled() {
            return enabled;
        }

        /** @param enabled whether the Azure collector runs */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** @return the subscription ids; empty means the discovery subscription ({@code azure.subscription-id}) */
        public List<String> getSubscriptions() {
            return subscriptions;
        }

        /** @param subscriptions the subscription ids */
        public void setSubscriptions(List<String> subscriptions) {
            this.subscriptions = subscriptions == null ? new ArrayList<>() : new ArrayList<>(subscriptions);
        }

        /** @return the Azure Resource Manager base URL */
        public String getManagementEndpoint() {
            return managementEndpoint;
        }

        /** @param endpoint the Azure Resource Manager base URL */
        public void setManagementEndpoint(String endpoint) {
            this.managementEndpoint = endpoint;
        }
    }
}
