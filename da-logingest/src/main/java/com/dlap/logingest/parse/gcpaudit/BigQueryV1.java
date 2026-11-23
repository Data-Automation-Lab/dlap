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

package com.dlap.logingest.parse.gcpaudit;

import com.dlap.logingest.parse.core.CanonicalEvent;
import com.dlap.logingest.parse.core.CanonicalField;
import com.dlap.logingest.parse.core.Classification;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.QuarantineReason;
import com.dlap.proto.log.v1.ResourceRelation;
import com.dlap.proto.log.v1.ResultStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * BigQuery v1 entries: the legacy {@code AuditData} in {@code protoPayload.serviceData}
 * (googleapis {@code google/cloud/bigquery/logging/v1/audit_data.proto}; GCP report section 3).
 * In the {@code api-proto} envelope the service data arrives as {@code {typeUrl, base64}} and is
 * decoded first ({@link AuditDataDecoder}).
 *
 * <p>{@code jobservice.jobcompleted} fires once, when a job completed successfully or not:
 * <ul>
 *   <li>a successful job gives one ACCESS / DATA_READ event per table named in
 *       {@code jobStatistics.referencedTables}, {@code referencedViews}, the entry's
 *       {@code tableDataReadEvents}, a copy's {@code sourceTables} and an extract's
 *       {@code sourceTable}, and one UPDATE / DATA_WRITE event for a destination table the
 *       job wrote (a load, a copy, or a query whose {@code statementType} is DML or
 *       {@code CREATE_TABLE_AS_SELECT}); the first of them is the primary event and keeps the
 *       entry's native key; each carries the job as a related resource and the job name
 *       ({@code projects/P/jobs/J}) as {@code shared_event_id};</li>
 *   <li>a job with {@code jobStatus.error} (non-zero code), or one that read and wrote nothing,
 *       gives one UPDATE / MANAGEMENT event on the job.</li>
 * </ul>
 * Each read and write carries the entry's own result: a successful job on an entry with a non-zero
 * {@code status.code}, a denied authorization check, the Policy Denied log or VPC Service Controls
 * metadata is {@code FAILURE} (Q-393, D45), not {@code SUCCESS}.
 * Tables of anonymous result datasets ({@code _...}) are never data assets.
 * {@code tabledataservice.list} is ACCESS / DATA_READ on its table; every other method follows the
 * mapping's classification table.
 */
final class BigQueryV1 {

    private static final Set<String> WRITING_STATEMENTS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "CREATE_TABLE_AS_SELECT", "TRUNCATE_TABLE");

    private BigQueryV1() {
    }

    /**
     * The events of one v1 entry.
     *
     * @param r the record
     * @return the events or the quarantine
     */
    static GcpAuditParser.Outcome events(GcpAuditParser.Record r) {
        JsonNode payload = r.entry().path("protoPayload");
        JsonNode serviceData = payload.path("serviceData");
        if (GcpJson.present(serviceData, "typeUrl")) {
            Optional<? extends JsonNode> expanded = AuditDataDecoder.expand(serviceData);
            if (expanded.isEmpty()) {
                return r.quarantine(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                        "protoPayload.serviceData does not decode as google.cloud.bigquery.logging.v1.AuditData");
            }
            serviceData = expanded.get();
        }
        String resourceName = r.text(CanonicalField.TARGET_ID);
        String region = r.text(CanonicalField.TARGET_REGION);
        Optional<Classification> override = r.override();
        if ("jobservice.jobcompleted".equals(r.operation())) {
            JsonNode job = serviceData.path("jobCompletedEvent").path("job");
            if (!job.path("jobName").isObject()) {
                return r.quarantine(QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD,
                        "serviceData.jobCompletedEvent.job.jobName is missing");
            }
            List<GcpAuditParser.Draft> drafts = jobCompleted(r, serviceData, job, resourceName, region);
            if (override.isPresent()) {
                GcpAuditParser.Draft d = drafts.get(0);
                drafts.set(0, new GcpAuditParser.Draft(override.get(), d.target(), d.related(), d.sharedEventId(),
                        d.result()));
            }
            return new GcpAuditParser.Outcome(drafts, Set.of(), null);
        }
        CanonicalEvent.Resource target = GcpResources.bigQuery(resourceName, region, r.hints());
        Classification c;
        if (override.isPresent()) {
            c = override.get();
        } else if ("tabledataservice.list".equals(r.operation())) {
            c = GcpResources.anonymous(resourceName)
                    ? GcpAuditParser.Draft.of(ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_MANAGEMENT)
                    : GcpAuditParser.Draft.of(ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ);
        } else {
            c = r.classify();
        }
        return GcpAuditParser.Outcome.of(new GcpAuditParser.Draft(c, target, List.of(), "", null));
    }

    private static List<GcpAuditParser.Draft> jobCompleted(GcpAuditParser.Record r, JsonNode serviceData, JsonNode job,
                                                           String resourceName, String region) {
        CanonicalEvent.Resource jobResource = GcpResources.bigQueryJob(job.path("jobName"), r.hints())
                .orElseGet(() -> GcpResources.bigQuery(resourceName, region, r.hints()));
        String shared = jobResource.canonicalUri().isEmpty() ? ""
                : "projects/" + jobResource.project() + "/jobs/" + jobResource.name();
        boolean failed = GcpJson.integer(job, "jobStatus", "error", "code").map(c -> c != 0).orElse(false);
        List<GcpAuditParser.Draft> out = new ArrayList<>();
        if (!failed) {
            Map<String, CanonicalEvent.Resource> reads = new LinkedHashMap<>();
            List<JsonNode> names = new ArrayList<>(GcpJson.array(job, "jobStatistics", "referencedTables"));
            names.addAll(GcpJson.array(job, "jobStatistics", "referencedViews"));
            for (JsonNode e : GcpJson.array(serviceData, "tableDataReadEvents")) {
                names.add(e.path("tableName"));
            }
            // A copy reads its source tables and an extract its source table; no other v1 field names them.
            names.addAll(GcpJson.array(job, "jobConfiguration", "tableCopy", "sourceTables"));
            names.add(job.path("jobConfiguration").path("extract").path("sourceTable"));
            for (JsonNode n : names) {
                GcpResources.bigQueryTable(n, r.hints())
                        .filter(t -> !GcpResources.anonymous(t.id()))
                        .ifPresent(t -> reads.putIfAbsent(t.canonicalUri().isEmpty() ? t.id() : t.canonicalUri(), t));
            }
            List<CanonicalEvent.Resource> rel = jobResource.canonicalUri().isEmpty() ? List.of()
                    : List.of(GcpResources.related(jobResource, ResourceRelation.RESOURCE_RELATION_OTHER));
            for (CanonicalEvent.Resource t : reads.values()) {
                out.add(new GcpAuditParser.Draft(
                        GcpAuditParser.Draft.of(ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ), t, rel,
                        shared, r.derivedResult()));
            }
            destination(job.path("jobConfiguration"), r).ifPresent(t -> out.add(new GcpAuditParser.Draft(
                    GcpAuditParser.Draft.of(ChangeType.UPDATE, EventCategory.EVENT_CATEGORY_DATA_WRITE), t, rel, "",
                    r.derivedResult())));
        }
        if (out.isEmpty()) {
            out.add(new GcpAuditParser.Draft(
                    GcpAuditParser.Draft.of(ChangeType.UPDATE, EventCategory.EVENT_CATEGORY_MANAGEMENT), jobResource,
                    List.of(), "", failed ? ResultStatus.FAILURE : null));
        }
        return out;
    }

    /** The table a successful job wrote, if it wrote one outside an anonymous dataset. */
    private static Optional<CanonicalEvent.Resource> destination(JsonNode config, GcpAuditParser.Record r) {
        JsonNode table = null;
        if (config.path("load").isObject()) {
            table = config.path("load").path("destinationTable");
        } else if (config.path("tableCopy").isObject()) {
            table = config.path("tableCopy").path("destinationTable");
        } else if (config.path("query").isObject()
                && WRITING_STATEMENTS.contains(GcpJson.textOr(config, "query", "statementType"))) {
            table = config.path("query").path("destinationTable");
        }
        if (table == null) {
            return Optional.empty();
        }
        return GcpResources.bigQueryTable(table, r.hints()).filter(t -> !GcpResources.anonymous(t.id()));
    }
}
