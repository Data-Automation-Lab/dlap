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

package com.dlap.logingest.config.sources;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The bindings file of the configuration-driven log layer (design B2, ADR-0045 decision 8).
 *
 * <p>The file is loaded with
 * {@code spring.config.import=optional:file:${DLAP_LOGINGEST_SOURCES_FILE:/etc/dlap/log-sources.yml}}
 * and binds under {@value #PREFIX}. Hierarchy: deployment {@code defaults}, then {@code sources}
 * (one source kind each), then {@code bindings} (one scope: an AWS account, a GCP project, an
 * Azure subscription, a Databricks workspace), then {@code streams} (one transport and location).
 * Every level can set the keys of {@link Settings}; the nearest level wins, then the family's
 * mapping YAML {@code settings}, then the contract defaults.
 *
 * <p>Enumerations and durations bind as text and are parsed by {@link LogSourceConfigValidator},
 * so a bad value is reported with its path and rule, and a profile that only warns (design B2:
 * dev) can still start with the rest of the configuration. Never put a secret here:
 * {@code credentials-ref} names an environment variable or a role ARN.
 */
@ConfigurationProperties(prefix = LogSourcesProperties.PREFIX)
@Getter
@Setter
public class LogSourcesProperties {

    /** Property prefix of the bindings file. */
    public static final String PREFIX = "dlap.logingest";

    /** {@code FILE} (0.x). {@code FILE_SEEDS_DB} is reserved for a database-authored mode. */
    private String configMode = "FILE";

    /**
     * {@code auto}: fail startup on an error under the {@code prod} profile, warn otherwise.
     * {@code strict}: always fail. {@code warn}: never fail; invalid bindings do not run.
     */
    private String configValidation = "auto";

    /** Deployment-wide defaults. */
    private Settings defaults = new Settings();

    /** The sources. */
    private List<Source> sources = new ArrayList<>();

    /** Keys every level of the hierarchy may set. Absent keys inherit. */
    @Getter
    @Setter
    public static class Settings {

        /** {@code false} disables this level and everything below it. */
        private Boolean enabled;

        /** Envelope, version pin and mismatch policy. */
        private Format format = new Format();

        /** Mismatch policy when {@code format.on-mismatch} is absent at the same level. */
        private String onMismatch;

        /** Only {@code QUARANTINE} is accepted: an unknown major is always quarantined (ADR-0054). */
        private String onUnknownMajor;

        /** Service and operation filters; matching events are skipped as {@code FILTERED_BY_BINDING}. */
        private Filters filters = new Filters();

        /** Canonical field (mapping-YAML spelling) to a JSON pointer, tried before the mapping. */
        private Map<String, String> fieldOverrides = new LinkedHashMap<>();

        /** Classification rows that win over the family's table. */
        private List<OperationOverride> operationOverrides = new ArrayList<>();

        /** Re-read window of time-cursored transports, ISO-8601 ({@code PT20M}). */
        private String overlap;

        /** Polling period of streaming transports, ISO-8601 ({@code PT1M}). */
        private String pollInterval;

        /** Request rate limit. */
        private RateLimit rateLimit = new RateLimit();

        /** Name of an environment variable, or a role ARN; never a secret value. */
        private String credentialsRef;

        /** Backfill permission. */
        private Backfill backfill = new Backfill();

        /** {@code ALL}, {@code QUARANTINE_ONLY} or {@code SAMPLED} ({@code PersistRaw}). */
        private String persistRaw;

        /** Sampling rate of {@code SAMPLED}: keep one raw record in this many. */
        private Integer persistRawSampleOneIn;

        /** Records above this many bytes are quarantined as {@code OVERSIZE_RECORD}. */
        private Long maxRecordBytes;

        /** {@code none} or {@code cross-stream}: allows two streams that read the same scope. */
        private String dedup;
    }

    /** Envelope and version of a level. */
    @Getter
    @Setter
    public static class Format {

        /** The envelope id, for example {@code s3-file}. */
        private String envelope;

        /** {@code auto} or a pinned version, for example {@code "1.08"}. */
        private String version;

        /** {@code QUARANTINE}, {@code PARSE_WITH_PIN} or {@code PARSE_WITH_DETECTED}. */
        private String onMismatch;
    }

    /** Service and operation filters. A null list inherits; an empty list clears. */
    @Getter
    @Setter
    public static class Filters {

        /** When set, only events of these services pass. */
        private List<String> includeServices;

        /** Events of these services are skipped. */
        private List<String> excludeServices;

        /** When set, only these operations pass. */
        private List<String> includeOperations;

        /** These operations are skipped. */
        private List<String> excludeOperations;
    }

    /** One classification row of {@code operation-overrides}. */
    @Getter
    @Setter
    public static class OperationOverride {

        /** Provider service, exact. */
        private String service;

        /** Exact operation; exactly one of operation and operation-prefix. */
        private String operation;

        /** Operation prefix. */
        private String operationPrefix;

        /** {@code CREATE}, {@code UPDATE}, {@code DELETE}, {@code ACCESS}, {@code PERMISSION}, {@code ARCHIVE}. */
        private String changeType;

        /** {@code MANAGEMENT}, {@code DATA_READ}, {@code DATA_WRITE}, {@code PERMISSION}, {@code DATA_READ_OBSERVED}. */
        private String category;

        /** Optional asset kind of the target ({@code AssetKind}). */
        private String assetKind;

        /** Optional resource extractor of the family. */
        private String extractor;
    }

    /** Request rate limit of a stream. */
    @Getter
    @Setter
    public static class RateLimit {

        /** Requests per second against the provider API. */
        private Double requestsPerSecond;
    }

    /** Backfill permission. */
    @Getter
    @Setter
    public static class Backfill {

        /** Whether bounded history reads may run against the binding. */
        private Boolean allowed;
    }

    /** One source: a source kind and its bindings. */
    @Getter
    @Setter
    public static class Source extends Settings {

        /** The source id, unique in the file. */
        private String id;

        /** The source kind, upper snake case ({@code AWS_CLOUDTRAIL}). */
        private String kind;

        /** The bindings. */
        private List<Binding> bindings = new ArrayList<>();
    }

    /** One binding: a scope of the source and its streams. */
    @Getter
    @Setter
    public static class Binding extends Settings {

        /** The binding id, unique across the file. */
        private String id;

        /** The scope. */
        private Scope scope = new Scope();

        /** One stream, shorthand for a one-element {@code streams}. */
        private Stream stream;

        /** The streams. */
        private List<Stream> streams = new ArrayList<>();
    }

    /** The scope of a binding. Which key is required depends on the provider. */
    @Getter
    @Setter
    public static class Scope {

        /** AWS account id (12 digits), Databricks account id (UUID) or Snowflake account. */
        private String accountId;

        /** GCP project id. */
        private String projectId;

        /** Azure subscription id (GUID). */
        private String subscriptionId;

        /** Azure resource group. */
        private String resourceGroup;

        /** Azure storage account. */
        private String storageAccount;

        /** Databricks workspace id (numeric). */
        private String workspaceId;

        /** Unity Catalog metastore id. */
        private String metastoreId;

        /** AWS regions; empty means every region the transport reads. */
        private List<String> regions = new ArrayList<>();
    }

    /** One stream: a transport and where it reads. */
    @Getter
    @Setter
    public static class Stream extends Settings {

        /** The stream id, unique in the binding; defaults to the transport in kebab case. */
        private String id;

        /** The transport id, upper snake case ({@code S3_TRAIL_FILES}). */
        private String transport;

        /** Transport location keys ({@code bucket}, {@code prefix}, {@code dataset}). */
        private Map<String, String> location = new LinkedHashMap<>();
    }
}
