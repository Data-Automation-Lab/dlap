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

package com.dlap.adminservice.audit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the audit checkpoint signer and verifier, {@code dlap.audit.*}
 * (docs/specs/audit-log.md, section "Operating"). Every default lives here, so the service starts
 * without any {@code dlap.audit} key: checkpoints are then off and the verify API checks the
 * chain alone. Environment variables bind by Spring's relaxed rules, for example
 * {@code DLAP_AUDIT_CHECKPOINT_ENABLED} and {@code DLAP_AUDIT_SIGNING_PRIVATEKEYFILE}.
 */
@ConfigurationProperties(prefix = "dlap.audit")
public class AuditProperties {

    private final Datasource datasource = new Datasource();
    private final Checkpoint checkpoint = new Checkpoint();
    private final Signing signing = new Signing();
    private final Export export = new Export();

    /** @return the {@code dlap_common} connection settings */
    public Datasource getDatasource() {
        return datasource;
    }

    /** @return the checkpoint schedule */
    public Checkpoint getCheckpoint() {
        return checkpoint;
    }

    /** @return the signing and trusted keys */
    public Signing getSigning() {
        return signing;
    }

    /** @return where checkpoints are exported */
    public Export getExport() {
        return export;
    }

    /**
     * The connection to the shared {@code dlap_common} database. A blank URL is derived from
     * {@code spring.datasource.url} by replacing the database name with {@code dlap_common}; a
     * blank user name or password falls back to {@code spring.datasource.*}.
     */
    public static class Datasource {
        private String url = "";
        private String username = "";
        private String password = "";
        private int maximumPoolSize = 3;

        /** @return the JDBC URL, or blank to derive it */
        public String getUrl() {
            return url;
        }

        /** @param url the JDBC URL */
        public void setUrl(String url) {
            this.url = url;
        }

        /** @return the user name, or blank for the service user */
        public String getUsername() {
            return username;
        }

        /** @param username the user name */
        public void setUsername(String username) {
            this.username = username;
        }

        /** @return the password, or blank for the service password */
        public String getPassword() {
            return password;
        }

        /** @param password the password */
        public void setPassword(String password) {
            this.password = password;
        }

        /** @return the pool size */
        public int getMaximumPoolSize() {
            return maximumPoolSize;
        }

        /** @param maximumPoolSize the pool size, at least 2 */
        public void setMaximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
        }
    }

    /** The checkpoint schedule. */
    public static class Checkpoint {
        private boolean enabled = false;
        private Duration interval = Duration.ofHours(1);
        private Duration initialDelay = Duration.ofMinutes(2);

        /** @return whether the scheduled signer runs */
        public boolean isEnabled() {
            return enabled;
        }

        /** @param enabled whether the scheduled signer runs */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /** @return the delay between two runs */
        public Duration getInterval() {
            return interval;
        }

        /** @param interval the delay between two runs */
        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        /** @return the delay before the first run */
        public Duration getInitialDelay() {
            return initialDelay;
        }

        /** @param initialDelay the delay before the first run */
        public void setInitialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
        }
    }

    /** The checkpoint key and the keys a verifier trusts. */
    public static class Signing {
        private String privateKeyFile = "";
        private String publicKeyFile = "";
        private List<String> trustedPublicKeyFiles = new ArrayList<>();

        /** @return the PKCS#8 PEM file of the Ed25519 signing key, or blank for none */
        public String getPrivateKeyFile() {
            return privateKeyFile;
        }

        /** @param privateKeyFile the PKCS#8 PEM file */
        public void setPrivateKeyFile(String privateKeyFile) {
            this.privateKeyFile = privateKeyFile;
        }

        /** @return the X.509 PEM file of the matching public key */
        public String getPublicKeyFile() {
            return publicKeyFile;
        }

        /** @param publicKeyFile the X.509 PEM file */
        public void setPublicKeyFile(String publicKeyFile) {
            this.publicKeyFile = publicKeyFile;
        }

        /** @return further public keys to trust, for checkpoints signed before a key rotation */
        public List<String> getTrustedPublicKeyFiles() {
            return trustedPublicKeyFiles;
        }

        /** @param trustedPublicKeyFiles further public key PEM files */
        public void setTrustedPublicKeyFiles(List<String> trustedPublicKeyFiles) {
            this.trustedPublicKeyFiles = trustedPublicKeyFiles;
        }
    }

    /** The kinds of export sink. */
    public enum SinkType {
        /** No export. Allowed only while checkpoints are off. */
        NONE,
        /** A local directory: development and tests; not write-once storage. */
        FILESYSTEM,
        /** Amazon S3 with Object Lock. */
        S3,
        /** Google Cloud Storage with a locked bucket retention policy. */
        GCS,
        /** Azure Blob Storage with version-level immutability. */
        AZURE
    }

    /** Where checkpoints go. */
    public static class Export {
        private SinkType sink = SinkType.NONE;
        private String prefix = "dlap-audit";
        private Duration timeout = Duration.ofSeconds(30);
        private final FileSystem filesystem = new FileSystem();
        private final S3 s3 = new S3();
        private final Gcs gcs = new Gcs();
        private final Azure azure = new Azure();

        /** @return the sink */
        public SinkType getSink() {
            return sink;
        }

        /** @param sink the sink */
        public void setSink(SinkType sink) {
            this.sink = sink;
        }

        /** @return the object name prefix inside the bucket or container */
        public String getPrefix() {
            return prefix;
        }

        /** @param prefix the object name prefix */
        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        /** @return the timeout of one HTTP call */
        public Duration getTimeout() {
            return timeout;
        }

        /** @param timeout the timeout of one HTTP call */
        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        /** @return the filesystem sink settings */
        public FileSystem getFilesystem() {
            return filesystem;
        }

        /** @return the S3 sink settings */
        public S3 getS3() {
            return s3;
        }

        /** @return the GCS sink settings */
        public Gcs getGcs() {
            return gcs;
        }

        /** @return the Azure sink settings */
        public Azure getAzure() {
            return azure;
        }
    }

    /** The filesystem sink. */
    public static class FileSystem {
        private String directory = "";

        /** @return the target directory */
        public String getDirectory() {
            return directory;
        }

        /** @param directory the target directory */
        public void setDirectory(String directory) {
            this.directory = directory;
        }
    }

    /** The S3 Object Lock sink. */
    public static class S3 {
        private String bucket = "";
        private String region = "";
        private String endpoint = "";
        private boolean pathStyle = false;
        private int retentionDays = 0;
        private String mode = "COMPLIANCE";
        private String imdsEndpoint = "http://169.254.169.254";

        /** @return the bucket, created with Object Lock enabled */
        public String getBucket() {
            return bucket;
        }

        /** @param bucket the bucket */
        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        /** @return the region, for example {@code eu-west-1} */
        public String getRegion() {
            return region;
        }

        /** @param region the region */
        public void setRegion(String region) {
            this.region = region;
        }

        /** @return an endpoint override for S3-compatible stores and tests, or blank */
        public String getEndpoint() {
            return endpoint;
        }

        /** @param endpoint the endpoint override */
        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        /** @return whether to address the bucket in the path instead of the host name */
        public boolean isPathStyle() {
            return pathStyle;
        }

        /** @param pathStyle whether to use path-style addressing */
        public void setPathStyle(boolean pathStyle) {
            this.pathStyle = pathStyle;
        }

        /** @return days each checkpoint object is locked; required, at least 1 */
        public int getRetentionDays() {
            return retentionDays;
        }

        /** @param retentionDays the lock period in days */
        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }

        /** @return {@code COMPLIANCE} (default) or {@code GOVERNANCE} */
        public String getMode() {
            return mode;
        }

        /** @param mode the Object Lock mode */
        public void setMode(String mode) {
            this.mode = mode;
        }

        /** @return the EC2 instance metadata endpoint for instance-profile credentials */
        public String getImdsEndpoint() {
            return imdsEndpoint;
        }

        /** @param imdsEndpoint the instance metadata endpoint */
        public void setImdsEndpoint(String imdsEndpoint) {
            this.imdsEndpoint = imdsEndpoint;
        }
    }

    /** The GCS bucket-lock sink. */
    public static class Gcs {
        private String bucket = "";
        private String endpoint = "https://storage.googleapis.com";
        private String accessTokenFile = "";
        private String metadataEndpoint = "http://metadata.google.internal";
        private int minRetentionDays = 1;

        /** @return the bucket, with a locked retention policy */
        public String getBucket() {
            return bucket;
        }

        /** @param bucket the bucket */
        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        /** @return the JSON API endpoint */
        public String getEndpoint() {
            return endpoint;
        }

        /** @param endpoint the JSON API endpoint */
        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        /** @return a file holding an OAuth access token, or blank to ask the metadata server */
        public String getAccessTokenFile() {
            return accessTokenFile;
        }

        /** @param accessTokenFile the token file */
        public void setAccessTokenFile(String accessTokenFile) {
            this.accessTokenFile = accessTokenFile;
        }

        /** @return the metadata server for the attached service account's token */
        public String getMetadataEndpoint() {
            return metadataEndpoint;
        }

        /** @param metadataEndpoint the metadata server */
        public void setMetadataEndpoint(String metadataEndpoint) {
            this.metadataEndpoint = metadataEndpoint;
        }

        /** @return the shortest locked retention period the bucket must have, in days */
        public int getMinRetentionDays() {
            return minRetentionDays;
        }

        /** @param minRetentionDays the shortest acceptable retention period in days */
        public void setMinRetentionDays(int minRetentionDays) {
            this.minRetentionDays = minRetentionDays;
        }
    }

    /** The Azure immutable-blob sink. */
    public static class Azure {
        private String accountUrl = "";
        private String container = "";
        private int retentionDays = 0;
        private String accessTokenFile = "";
        private String imdsEndpoint = "http://169.254.169.254";
        private String managedIdentityClientId = "";

        /** @return the blob service URL, for example {@code https://acct.blob.core.windows.net} */
        public String getAccountUrl() {
            return accountUrl;
        }

        /** @param accountUrl the blob service URL */
        public void setAccountUrl(String accountUrl) {
            this.accountUrl = accountUrl;
        }

        /** @return the container, with version-level immutability enabled */
        public String getContainer() {
            return container;
        }

        /** @param container the container */
        public void setContainer(String container) {
            this.container = container;
        }

        /** @return days each checkpoint blob is locked; required, at least 1 */
        public int getRetentionDays() {
            return retentionDays;
        }

        /** @param retentionDays the lock period in days */
        public void setRetentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
        }

        /** @return a file holding an OAuth access token, or blank to ask the instance metadata service */
        public String getAccessTokenFile() {
            return accessTokenFile;
        }

        /** @param accessTokenFile the token file */
        public void setAccessTokenFile(String accessTokenFile) {
            this.accessTokenFile = accessTokenFile;
        }

        /** @return the instance metadata endpoint for managed-identity tokens */
        public String getImdsEndpoint() {
            return imdsEndpoint;
        }

        /** @param imdsEndpoint the instance metadata endpoint */
        public void setImdsEndpoint(String imdsEndpoint) {
            this.imdsEndpoint = imdsEndpoint;
        }

        /** @return the client id of a user-assigned managed identity, or blank for the system identity */
        public String getManagedIdentityClientId() {
            return managedIdentityClientId;
        }

        /** @param managedIdentityClientId the client id of a user-assigned identity */
        public void setManagedIdentityClientId(String managedIdentityClientId) {
            this.managedIdentityClientId = managedIdentityClientId;
        }
    }
}
