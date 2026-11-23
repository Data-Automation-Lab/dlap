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

package com.dlap.common.constants;

/**
 * Common constants used across all DLAP microservices.
 * 
 * Contains string literals, error codes, configuration keys, and other constant values
 * that are shared between multiple services to ensure consistency.
 * 
 * @author DLAP Team
 * @version 1.0
 * @since 1.0
 */
public final class DlapConstants {

    // Prevent instantiation
    private DlapConstants() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    // ========== Asset Status Constants ==========
    public static final class AssetStatus {
        public static final String ACTIVE = "ACTIVE";
        public static final String ARCHIVED = "ARCHIVED";
        public static final String PENDING_DELETION = "PENDING_DELETION";
        public static final String DELETED = "DELETED";
        
        private AssetStatus() {}
    }

    // ========== Cloud Provider Constants ==========
    public static final class CloudProvider {
        public static final String AWS = "AWS";
        public static final String GCP = "GCP";
        public static final String AZURE = "AZURE";
        public static final String OCI = "OCI";
        /** Databricks Unity Catalog (crawler-only source; no cloud audit-log stream). */
        public static final String UNITY = "UNITY";
        /** Databricks account or workspace (system tables, audit delivery). Frozen kind list, wave 1. */
        public static final String DATABRICKS = "DATABRICKS";
        /** Snowflake account (ACCOUNT_USAGE views). Frozen kind list, wave 1. */
        public static final String SNOWFLAKE = "SNOWFLAKE";

        private CloudProvider() {}
    }

    // ========== Service Type Constants ==========
    public static final class ServiceType {
        // AWS Services
        public static final String S3_BUCKET = "S3_BUCKET";
        public static final String RDS_INSTANCE = "RDS_INSTANCE";
        public static final String REDSHIFT_CLUSTER = "REDSHIFT_CLUSTER";
        public static final String DYNAMODB_TABLE = "DYNAMODB_TABLE";
        
        // ---- Frozen kind list, wave 1 (ADR-0046 / ADR-0047) -----------------
        // Object, container and table kinds added for the canonical asset ID
        // (ID-01) and the dlp_assets CHECK constraints (CAT-01). Whether a kind
        // gets catalog rows is the asset-granularity rule of ADR-0046.
        public static final String S3_OBJECT = "S3_OBJECT";
        public static final String GLUE_DATABASE = "GLUE_DATABASE";
        public static final String GLUE_TABLE = "GLUE_TABLE";
        public static final String GCS_OBJECT = "GCS_OBJECT";
        public static final String BLOB_CONTAINER = "BLOB_CONTAINER";
        public static final String BLOB_OBJECT = "BLOB_OBJECT";
        public static final String UNITY_VOLUME = "UNITY_VOLUME";
        public static final String SNOWFLAKE_TABLE = "SNOWFLAKE_TABLE";

        // GCP Services
        public static final String GCS_BUCKET = "GCS_BUCKET";
        public static final String BIGQUERY_DATASET = "BIGQUERY_DATASET";
        public static final String BIGQUERY_TABLE = "BIGQUERY_TABLE";
        public static final String CLOUD_SQL_INSTANCE = "CLOUD_SQL_INSTANCE";
        
        // Azure Services
        public static final String BLOB_STORAGE = "BLOB_STORAGE";
        public static final String SQL_DATABASE = "SQL_DATABASE";
        public static final String COSMOS_DB = "COSMOS_DB";
        public static final String DATA_LAKE = "DATA_LAKE";

        // Unity Catalog hierarchy
        public static final String UNITY_CATALOG = "UNITY_CATALOG";
        public static final String UNITY_SCHEMA = "UNITY_SCHEMA";
        public static final String UNITY_TABLE = "UNITY_TABLE";

        // ---- Processing engines / jobs (C7) ---------------------------------
        // Cloud-neutral kinds for data-processing jobs and engines. A job is a
        // first-class asset so log-derived lineage edges can attach a real job
        // endpoint (read -> job -> write), not just table -> table. Providers map
        // their native job concept onto one of these (e.g. GCP BigQuery job ->
        // BIGQUERY_JOB, Dataflow -> DATAFLOW_JOB, a Databricks/Redshift SQL run ->
        // DATABRICKS_JOB / REDSHIFT_QUERY, an orchestrated Spark app -> SPARK_JOB,
        // a dbt model materialization -> DBT_MODEL, a standalone Python job ->
        // PYTHON_JOB). These are the {@code JOB_SERVICE_TYPES} set below.
        public static final String SPARK_JOB = "SPARK_JOB";
        public static final String DATAFLOW_JOB = "DATAFLOW_JOB";
        public static final String BIGQUERY_JOB = "BIGQUERY_JOB";
        public static final String DBT_MODEL = "DBT_MODEL";
        public static final String REDSHIFT_QUERY = "REDSHIFT_QUERY";
        public static final String DATABRICKS_JOB = "DATABRICKS_JOB";
        public static final String PYTHON_JOB = "PYTHON_JOB";

        /**
         * The processing-job / engine service types (C7). A membership test lets
         * consumers treat any job kind uniformly (e.g. render it as a job node in
         * lineage) without enumerating each constant.
         */
        public static final java.util.Set<String> JOB_SERVICE_TYPES = java.util.Set.of(
                SPARK_JOB, DATAFLOW_JOB, BIGQUERY_JOB, DBT_MODEL,
                REDSHIFT_QUERY, DATABRICKS_JOB, PYTHON_JOB);

        /** True when {@code serviceType} names a processing job/engine kind. */
        public static boolean isJob(String serviceType) {
            return serviceType != null && JOB_SERVICE_TYPES.contains(serviceType);
        }

        private ServiceType() {}
    }

    // ========== Label Category Constants ==========
    public static final class LabelCategory {
        public static final String SENSITIVITY = "Sensitivity";
        public static final String COMPLIANCE = "Compliance";
        public static final String DATA_TYPE = "Data Type";
        public static final String BUSINESS_DOMAIN = "Business Domain";
        public static final String GOVERNANCE = "Governance";
        public static final String CLASSIFICATION = "Classification";
        
        private LabelCategory() {}
    }

    // ========== Assignment Source Constants ==========
    public static final class AssignmentSource {
        public static final String USER = "USER";
        public static final String SYSTEM = "SYSTEM";
        public static final String SMART_LABEL_ML = "SMART_LABEL_ML";
        public static final String SMART_LABEL_RULE = "SMART_LABEL_RULE";
        public static final String POLICY_ENGINE = "POLICY_ENGINE";
        
        private AssignmentSource() {}
    }

    // ========== User Role Constants ==========
    // The 10 canonical roles of the DLAP Access Model (ADR-0032), grouped by
    // tier below. Keycloak is the single source of truth for these names; the
    // legacy phantom VP is removed (superseded by DIRECTOR).
    public static final class UserRole {
        // Executive tier
        public static final String ADMIN = "ADMIN";
        public static final String CDO = "CDO";
        // Leadership tier
        public static final String DIRECTOR = "DIRECTOR";
        public static final String POLICY_MANAGER = "POLICY_MANAGER";
        // Practitioner tier
        public static final String DATA_ENGINEER = "DATA_ENGINEER";
        public static final String DATA_SCIENTIST = "DATA_SCIENTIST";
        // Reader tier
        public static final String DATA_ANALYST = "DATA_ANALYST";
        public static final String AUDITOR = "AUDITOR";
        public static final String VIEWER = "VIEWER";

        // Machine principal: NOT a human persona and NOT part of the 4-tier model.
        // Carried by service-account (client-credentials) tokens for inter-service
        // (M2M) calls. Endpoints that are legitimately reachable service-to-service
        // add this to their @PreAuthorize; it is granted nothing else (least privilege).
        public static final String SERVICE_ACCOUNT = "SERVICE_ACCOUNT";

        private UserRole() {}
    }

    // ========== Access-Model Tiers (ADR-0032) ==========
    // The normative 4-tier persona model. Each tier names the roles it contains;
    // authorization widens from Reader (narrowest) to Executive (broadest).
    public static final class RoleTier {
        public static final java.util.Set<String> EXECUTIVE =
                java.util.Set.of(UserRole.ADMIN, UserRole.CDO);
        public static final java.util.Set<String> LEADERSHIP =
                java.util.Set.of(UserRole.DIRECTOR, UserRole.POLICY_MANAGER);
        public static final java.util.Set<String> PRACTITIONER =
                java.util.Set.of(UserRole.DATA_ENGINEER, UserRole.DATA_SCIENTIST);
        public static final java.util.Set<String> READER =
                java.util.Set.of(UserRole.DATA_ANALYST, UserRole.AUDITOR, UserRole.VIEWER);

        private RoleTier() {}
    }

    // ========== Schema Names ==========
    public static final class Schema {
        public static final String COMMON = "dlap_common";
        public static final String CATALOG = "dlap_catalog";
        public static final String DISCOVERY = "dlap_discovery";
        public static final String POLICY_ENGINE = "dlap_policyengine";
        public static final String REPORTING = "dlap_reporting";
        public static final String AUTOLABEL = "dlap_autolabel";
        public static final String AUTOARCHIVAL = "dlap_autoarchival";
        public static final String AUTODELETE = "dlap_autodelete";
        public static final String AUTOCOMPLIANCE = "dlap_autocompliance";
        public static final String ADMIN = "dlap_admin";
        /** da-logingest schema, added in M2 / R-P1-10 / ADR-0010. */
        public static final String LOGINGEST = "dlap_logingest";

        private Schema() {}
    }

    // ========== Kafka Topic Constants ==========
    public static final class KafkaTopic {
        public static final String ASSET_CHANGE_EVENT = "dlap.asset.change.event";
        public static final String LABEL_ASSIGNMENT_CHANGED = "dlap.asset.label.assignment.changed";
        // Canonical policy-action topic. da-policyengine PRODUCES here (config
        // dlap.kafka.topics.policy-actions / app.kafka.topic.policy-action-event)
        // and da-autoarchival/da-autodelete/da-reporting CONSUME it. The value must
        // match the producer's configured topic, hence dlap.policies.actions.
        public static final String POLICY_ACTION_EVENT = "dlap.policies.actions";
        public static final String DISCOVERY_SCAN_REQUESTED = "dlap.discovery.scan.requested";
        public static final String DISCOVERY_COMPLETION_EVENT = "dlap.discovery.completion.event";
        /**
         * Per ADR-0021: typed asset-lifecycle topics. {@code DiscoveryLogEventProcessor}
         * (in da-discovery) consumes {@link #LOG_EVENT_NORMALIZED} and routes each
         * event onto exactly one of the topics below based on {@code DlapLogEvent.changeType}.
         * Downstream services subscribe to the topics relevant to their role:
         * <ul>
         *   <li>{@code da-catalog}: all six (state of record).</li>
         *   <li>{@code da-autolabel}: DISCOVERED, MODIFIED (label revisit).</li>
         *   <li>{@code da-autoarchival}: ACCESSED (last-accessed math).</li>
         *   <li>{@code da-autodelete}: DELETED (reconciliation).</li>
         *   <li>{@code da-autocompliance}: PERMISSION_CHANGED (IAM drift).</li>
         *   <li>{@code da-policyengine}: all six (policy match against any).</li>
         * </ul>
         */
        public static final String DISCOVERY_ASSET_DISCOVERED = "dlap.discovery.asset.discovered";
        public static final String DISCOVERY_ASSET_MODIFIED = "dlap.discovery.asset.modified";
        public static final String DISCOVERY_ASSET_DELETED = "dlap.discovery.asset.deleted";
        public static final String DISCOVERY_ASSET_ACCESSED = "dlap.discovery.asset.accessed";
        public static final String DISCOVERY_ASSET_PERMISSION_CHANGED = "dlap.discovery.asset.permission-changed";
        public static final String DISCOVERY_ASSET_ARCHIVED = "dlap.discovery.asset.archived";
        public static final String LABELING_REQUEST_EVENT = "dlap.labeling.request.event";
        public static final String ARCHIVAL_REQUEST_EVENT = "dlap.archival.request.event";
        public static final String DELETION_REQUEST_EVENT = "dlap.deletion.request.event";
        public static final String COMPLIANCE_VIOLATION_EVENT = "dlap.compliance.violation.event";
        /**
         * Canonical normalized log-event topic produced by da-logingest
         * (M2 / R-P1-9 / R-P1-10). Every per-cloud audit-log adapter
         * publishes a {@code dlap.log.v1.DlapLogEvent} here, and the
         * da-discovery crawler bridge adds crawler observations;
         * downstream services (da-discovery, da-catalog, da-policyengine,
         * da-autocompliance) subscribe instead of touching cloud SDKs.
         * The wire form is single-encoded proto3 JSON written and read with
         * {@code com.dlap.common.logevent.DlapLogEventCodec} (ADR-0045).
         * Spec: docs/specs/log-event.md.
         */
        public static final String LOG_EVENT_NORMALIZED = "dlap.log.event.normalized";
        /**
         * Catalog asset-upserted event (M3 / M4 prerequisite). Published by
         * {@code da-catalog} every time an asset is created or updated.
         * Subscribed by {@code da-autolabel} to drive the labeling
         * pipeline, and by {@code da-policyengine} to evaluate policies on
         * fresh asset state. Carries the catalog asset id; consumers
         * fetch full state via the catalog REST API.
         */
        public static final String CATALOG_ASSET_UPSERTED = "dlap.catalog.asset.upserted";
        /**
         * Consumed-stage access-decision log (ADR-0040). {@code da-policyengine}'s
         * AuthZEN PDP publishes one event here for every access evaluation (allow
         * or deny). This is the single replayable audit trail for the Consumed
         * stage; enforcement-point access logs ingested by {@code da-logingest} are
         * joined against it to detect drift between verdicts and actual reads.
         * Schema: docs/specs/enforcement-contract.md §6.
         */
        public static final String ACCESS_DECISION = "dlap.access.decision";
        /**
         * Quarantined log records (ADR-0045). da-logingest publishes one
         * {@code dlap.log.v1.LogQuarantineRecord} (proto3 JSON) per source record
         * it could not parse, keyed by {@code binding_id}. Spec:
         * docs/specs/log-event.md, "Quarantine topic".
         */
        public static final String LOG_EVENT_QUARANTINE = "dlap.log.event.quarantine";
        /**
         * Per-binding ingest health (ADR-0045). da-logingest publishes a
         * {@code dlap.log.v1.LogBindingStatus} (proto3 JSON) keyed by
         * {@code binding_id}; the latest message per key is the current state, so
         * the topic may be compacted. Read by da-catalog to tell "not read" from
         * "read logging not captured". Spec: docs/specs/log-event.md.
         */
        public static final String LOGINGEST_BINDING_STATUS = "dlap.logingest.binding.status";
        /**
         * Retention transitions (ADR-0046). da-catalog publishes one event when an
         * asset's retention status changes (for example to EXPIRED), keyed by the
         * asset's canonical URI. The payload is specified with the retention
         * model in docs/specs/asset-descriptor.md.
         */
        public static final String CATALOG_ASSET_RETENTION_DUE = "dlap.catalog.asset.retention-due";
        /**
         * Approval requests (APPR-01). da-policyengine publishes one {@code ApprovalEvent} v1
         * ({@code eventType: REQUESTED}) when a destructive or approval-gated action starts to
         * wait for people, keyed by the request id. Spec: docs/specs/approvals.md.
         */
        public static final String APPROVAL_REQUESTED = "dlap.approvals.requested";
        /**
         * Approval outcomes (APPR-01). da-policyengine publishes one {@code ApprovalEvent} v1
         * ({@code eventType: DECIDED}) when a request reaches a terminal state: APPROVED,
         * APPROVED_BREAK_GLASS, REJECTED or EXPIRED, keyed by the request id. Executors of
         * API-created requests (for example a hard delete) act on this topic. Spec:
         * docs/specs/approvals.md.
         */
        public static final String APPROVAL_DECIDED = "dlap.approvals.decided";
        /**
         * Break-glass alerts (APPR-01). da-policyengine publishes one {@code ApprovalEvent} v1
         * ({@code eventType: BREAK_GLASS}) each time an administrator releases a request without
         * the quorum, with the justification, keyed by the request id. Alerting subscribes here.
         * Spec: docs/specs/approvals.md.
         */
        public static final String APPROVAL_BREAK_GLASS = "dlap.approvals.break-glass";

        private KafkaTopic() {}
    }

    // ========== Service Port Registry ==========
    /**
     * One source of truth for service ports. Keeps docker-compose,
     * Terraform, and Kubernetes manifests aligned with the Spring
     * Boot {@code server.port} property in each service.
     */
    public static final class ServicePort {
        public static final int DA_DISCOVERY = 8081;
        public static final int DA_CATALOG = 8082;
        public static final int DA_POLICYENGINE = 8083;
        public static final int DA_REPORTING = 8084;
        public static final int DA_AUTOLABEL = 8085;
        public static final int DA_AUTOARCHIVAL = 8086;
        public static final int DA_AUTODELETE = 8087;
        public static final int DA_AUTOCOMPLIANCE = 8088;
        public static final int DA_ADMIN_SERVICE = 8089;
        /**
         * da-logingest landed in M2 (ADR-0010: new service, not a
         * module inside da-discovery). 8090 is the next free slot above
         * the existing 8081-8089 range.
         */
        public static final int DA_LOGINGEST = 8090;

        private ServicePort() {}
    }

    // ========== API Version Constants ==========
    public static final class ApiVersion {
        public static final String V1 = "v1";
        public static final String BASE_PATH = "/api/" + V1;
        
        private ApiVersion() {}
    }

    // ========== Configuration Keys ==========
    public static final class ConfigKey {
        public static final String GLOBAL_CONFIG_ID = "GLOBAL";
        public static final String DEFAULT_PAGE_SIZE = "20";
        public static final String MAX_PAGE_SIZE = "100";
        
        private ConfigKey() {}
    }
}
