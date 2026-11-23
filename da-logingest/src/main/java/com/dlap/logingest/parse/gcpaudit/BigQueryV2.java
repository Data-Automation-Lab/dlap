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
import com.dlap.logingest.parse.core.mapping.MappingDrivenParser;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.QuarantineReason;
import com.dlap.proto.log.v1.ResourceRelation;
import com.dlap.proto.log.v1.ResultStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * BigQuery v2 entries: {@code BigQueryAuditMetadata} in {@code protoPayload.metadata}
 * (googleapis {@code bigquery_audit_metadata.proto}; GCP report section 3).
 *
 * <p>The metadata holds exactly one event of the proto's {@code event} oneof, and the event, not
 * the method name, decides what happened:
 * <table>
 *   <caption>Events and what they become</caption>
 *   <tr><th>Event</th><th>Change type / category</th><th>Target</th></tr>
 *   <tr><td>{@code tableDataRead}</td><td>ACCESS / DATA_READ</td><td>the table ({@code resourceName})</td></tr>
 *   <tr><td>{@code jobChange} to {@code DONE}</td><td>UPDATE / MANAGEMENT, plus one ACCESS / DATA_READ per
 *       {@code queryStats.referencedTables} and {@code referencedViews} entry</td><td>the job; each read its table</td></tr>
 *   <tr><td>{@code jobInsertion}, {@code jobDeletion}</td><td>CREATE, DELETE / MANAGEMENT</td><td>the job</td></tr>
 *   <tr><td>{@code tableDataChange}</td><td>UPDATE / DATA_WRITE</td><td>the table</td></tr>
 *   <tr><td>{@code tableCreation}, {@code tableChange}, {@code tableDeletion}</td><td>CREATE, UPDATE, DELETE / MANAGEMENT</td><td>the table</td></tr>
 *   <tr><td>{@code datasetCreation}, {@code datasetChange}, {@code datasetDeletion}</td><td>CREATE, UPDATE, DELETE / MANAGEMENT;
 *       {@code SET_IAM_POLICY} is PERMISSION</td><td>the dataset</td></tr>
 *   <tr><td>model, routine, row access policy events, {@code unlinkDataset}</td><td>MANAGEMENT</td><td>the resource</td></tr>
 * </table>
 * No read is recorded for a job that hit the cache ({@code cacheHit}), failed, or is the parent of
 * a multi-statement script ({@code statementType SCRIPT}); its child jobs report their own reads.
 * A job that ended with an error ({@code jobStatus.errorResult}) is {@code FAILURE}, except on the
 * {@code CancelJob} entry, whose call succeeded. A read derived from the job takes the entry's own
 * result: {@code FAILURE} for a non-zero {@code status.code}, a denied authorization check, the
 * Policy Denied log or VPC Service Controls metadata (Q-393, D45).
 * Reads of anonymous result datasets ({@code _...}) are MANAGEMENT. A read derived from a job and
 * the {@code tableDataRead} entry of the same job carry the job name as {@code shared_event_id},
 * so usage counting sees one read per job and table. {@code google.iam.v1.IAMPolicy.SetIamPolicy}
 * is PERMISSION whatever its event. Metadata above 100K bytes is truncated by BigQuery; any
 * {@code ...Truncated: true} flag adds {@code PARSE_WARNING_PROVIDER_TRUNCATED}. Metadata without
 * a known event is {@code MISSING_REQUIRED_FIELD}.
 */
final class BigQueryV2 {

    /** The members of the {@code event} oneof, in proto order. */
    static final List<String> EVENTS = List.of(
            "jobInsertion", "jobChange", "jobDeletion", "datasetCreation", "datasetChange", "datasetDeletion",
            "tableCreation", "tableChange", "tableDeletion", "tableDataRead", "tableDataChange", "modelDeletion",
            "modelCreation", "modelMetadataChange", "modelDataChange", "modelDataRead", "routineCreation",
            "routineChange", "routineDeletion", "rowAccessPolicyCreation", "rowAccessPolicyChange",
            "rowAccessPolicyDeletion", "unlinkDataset");

    private static final String SET_IAM_POLICY = "SetIamPolicy";

    private BigQueryV2() {
    }

    /**
     * The events of one v2 entry.
     *
     * @param r the record
     * @return the events or the quarantine
     */
    static GcpAuditParser.Outcome events(GcpAuditParser.Record r) {
        JsonNode metadata = r.entry().path("protoPayload").path("metadata");
        Optional<String> event = EVENTS.stream().filter(k -> metadata.path(k).isObject()).findFirst();
        if (event.isEmpty()) {
            return r.quarantine(QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD,
                    "protoPayload.metadata carries no BigQueryAuditMetadata event");
        }
        JsonNode ev = metadata.path(event.get());
        Set<ParseWarning> warnings = EnumSet.noneOf(ParseWarning.class);
        if (truncated(metadata, 0)) {
            warnings.add(ParseWarning.PARSE_WARNING_PROVIDER_TRUNCATED);
        }
        String resourceName = r.text(CanonicalField.TARGET_ID);
        String region = r.text(CanonicalField.TARGET_REGION);
        List<GcpAuditParser.Draft> drafts = switch (event.get()) {
            case "tableDataRead" -> List.of(tableDataRead(r, ev, resourceName, region));
            case "jobChange" -> jobChange(r, ev, resourceName, region);
            case "jobInsertion" -> List.of(job(r, GcpJson.textOr(ev, "job", "jobName"), resourceName, region,
                    ChangeType.CREATE, ev.path("job")));
            case "jobDeletion" -> List.of(job(r, GcpJson.textOr(ev, "jobName"), resourceName, region,
                    ChangeType.DELETE, ev));
            case "tableDataChange" -> List.of(tableDataChange(r, ev, resourceName, region));
            case "tableCreation" -> List.of(table(r, resourceName, region, ChangeType.CREATE,
                    MappingDrivenParser.time(ev.path("table").path("createTime")).orElse(null)));
            case "tableChange" -> List.of(table(r, resourceName, region, ChangeType.UPDATE, null));
            case "tableDeletion" -> List.of(table(r, resourceName, region, ChangeType.DELETE, null));
            case "datasetCreation" -> List.of(simple(r, resourceName, region, ChangeType.CREATE));
            case "datasetDeletion" -> List.of(simple(r, resourceName, region, ChangeType.DELETE));
            case "datasetChange" -> List.of("SET_IAM_POLICY".equals(GcpJson.textOr(ev, "reason"))
                    ? new GcpAuditParser.Draft(GcpAuditParser.Draft.of(ChangeType.PERMISSION, EventCategory.EVENT_CATEGORY_PERMISSION),
                            GcpResources.bigQuery(resourceName, region, r.hints()), List.of(), "", null)
                    : simple(r, resourceName, region, ChangeType.UPDATE));
            default -> List.of(simple(r, resourceName, region, changeTypeOf(event.get())));
        };
        List<GcpAuditParser.Draft> out = new ArrayList<>(drafts);
        GcpAuditParser.Draft primary = out.get(0);
        Optional<Classification> override = r.override();
        if (override.isPresent()) {
            out.set(0, withClassification(primary, override.get()));
        } else if (r.operation().endsWith(SET_IAM_POLICY)) {
            out.set(0, withClassification(primary,
                    GcpAuditParser.Draft.of(ChangeType.PERMISSION, EventCategory.EVENT_CATEGORY_PERMISSION)));
        }
        return new GcpAuditParser.Outcome(out, warnings, null);
    }

    private static GcpAuditParser.Draft tableDataRead(GcpAuditParser.Record r, JsonNode ev, String resourceName,
                                                      String region) {
        CanonicalEvent.Resource target = GcpResources.bigQuery(resourceName, region, r.hints());
        String jobName = GcpJson.textOr(ev, "jobName");
        String shared = !jobName.isEmpty() ? GcpResources.jobKey(jobName) : GcpJson.textOr(ev, "sessionName");
        List<CanonicalEvent.Resource> related = new ArrayList<>();
        if (!jobName.isEmpty()) {
            CanonicalEvent.Resource job = GcpResources.bigQuery(jobName, region, r.hints());
            if (!job.canonicalUri().isEmpty()) {
                related.add(GcpResources.related(job, ResourceRelation.RESOURCE_RELATION_OTHER));
            }
        }
        Classification c = GcpResources.anonymous(resourceName)
                ? GcpAuditParser.Draft.of(ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_MANAGEMENT)
                : GcpAuditParser.Draft.of(ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ);
        return new GcpAuditParser.Draft(c, target, related, shared, null);
    }

    private static GcpAuditParser.Draft tableDataChange(GcpAuditParser.Record r, JsonNode ev, String resourceName,
                                                        String region) {
        CanonicalEvent.Resource target = GcpResources.bigQuery(resourceName, region, r.hints());
        List<CanonicalEvent.Resource> related = new ArrayList<>();
        String jobName = GcpJson.textOr(ev, "jobName");
        if (!jobName.isEmpty()) {
            CanonicalEvent.Resource job = GcpResources.bigQuery(jobName, region, r.hints());
            if (!job.canonicalUri().isEmpty()) {
                related.add(GcpResources.related(job, ResourceRelation.RESOURCE_RELATION_OTHER));
            }
        }
        Classification c = GcpResources.anonymous(resourceName)
                ? GcpAuditParser.Draft.of(ChangeType.UPDATE, EventCategory.EVENT_CATEGORY_MANAGEMENT)
                : GcpAuditParser.Draft.of(ChangeType.UPDATE, EventCategory.EVENT_CATEGORY_DATA_WRITE);
        return new GcpAuditParser.Draft(c, target, related, "", null);
    }

    private static List<GcpAuditParser.Draft> jobChange(GcpAuditParser.Record r, JsonNode ev, String resourceName,
                                                        String region) {
        JsonNode job = ev.path("job");
        String jobName = GcpJson.text(job, "jobName").orElse(resourceName);
        boolean failed = failed(job);
        GcpAuditParser.Draft primary = job(r, jobName, resourceName, region, ChangeType.UPDATE, job);
        // A cancelled job ends with an error result, but the CancelJob call itself succeeded.
        if (failed && !r.operation().endsWith("CancelJob")) {
            primary = new GcpAuditParser.Draft(primary.classification(), primary.target(), primary.related(), "",
                    ResultStatus.FAILURE);
        }
        List<GcpAuditParser.Draft> out = new ArrayList<>();
        JsonNode stats = job.path("jobStats");
        JsonNode query = stats.path("queryStats");
        boolean done = "DONE".equals(GcpJson.textOr(ev, "after"))
                || "DONE".equals(GcpJson.textOr(job, "jobStatus", "jobState"));
        boolean script = "SCRIPT".equals(GcpJson.textOr(job, "jobConfig", "queryConfig", "statementType"));
        boolean cacheHit = GcpJson.isTrue(query, "cacheHit");
        List<CanonicalEvent.Resource> related = new ArrayList<>(primary.related());
        Map<String, CanonicalEvent.Resource> reads = new LinkedHashMap<>();
        if (done && !failed && !script && !cacheHit && query.isObject()) {
            List<JsonNode> refs = new ArrayList<>(GcpJson.array(query, "referencedTables"));
            refs.addAll(GcpJson.array(query, "referencedViews"));
            for (JsonNode ref : refs) {
                if (!ref.isTextual() || ref.textValue().isBlank()) {
                    continue;
                }
                String name = ref.textValue().strip();
                if (GcpResources.isTable(name) && GcpResources.anonymous(name)) {
                    continue;
                }
                CanonicalEvent.Resource table = GcpResources.isTable(name)
                        ? GcpResources.bigQuery(name, region, r.hints())
                        : CanonicalEvent.Resource.builder().kind(GcpResources.BQ_TABLE).id(name).name(name)
                                .resourcePath(name)
                                .canonicalFrom(name, r.hints().withKind(com.dlap.common.assetid.AssetKind.BIGQUERY_TABLE))
                                .build();
                reads.putIfAbsent(table.canonicalUri().isEmpty() ? name : table.canonicalUri(), table);
            }
        }
        String destination = GcpJson.textOr(job, "jobConfig", "queryConfig", "destinationTable");
        for (CanonicalEvent.Resource t : reads.values()) {
            related.add(GcpResources.related(t, ResourceRelation.RESOURCE_RELATION_SOURCE));
        }
        if (!destination.isEmpty() && !GcpResources.anonymous(destination)) {
            CanonicalEvent.Resource d = GcpResources.bigQuery(destination, region, r.hints());
            if (!d.canonicalUri().isEmpty()) {
                related.add(GcpResources.related(d, ResourceRelation.RESOURCE_RELATION_DESTINATION));
            }
        }
        out.add(new GcpAuditParser.Draft(primary.classification(), primary.target(), related, "", primary.result()));
        CanonicalEvent.Resource jobResource = primary.target();
        String shared = GcpResources.jobKey(jobName);
        for (CanonicalEvent.Resource t : reads.values()) {
            List<CanonicalEvent.Resource> rel = jobResource.canonicalUri().isEmpty() ? List.of()
                    : List.of(GcpResources.related(jobResource, ResourceRelation.RESOURCE_RELATION_OTHER));
            out.add(new GcpAuditParser.Draft(
                    GcpAuditParser.Draft.of(ChangeType.ACCESS, EventCategory.EVENT_CATEGORY_DATA_READ), t, rel, shared,
                    r.derivedResult()));
        }
        return out;
    }

    private static GcpAuditParser.Draft job(GcpAuditParser.Record r, String jobName, String resourceName, String region,
                                            ChangeType changeType, JsonNode job) {
        String name = jobName.isEmpty() ? resourceName : jobName;
        CanonicalEvent.Resource target = GcpResources.bigQuery(name, region, r.hints());
        List<CanonicalEvent.Resource> related = new ArrayList<>();
        String parent = GcpJson.textOr(job, "jobStats", "parentJobName");
        if (!parent.isEmpty()) {
            CanonicalEvent.Resource p = GcpResources.bigQuery(parent, region, r.hints());
            if (!p.canonicalUri().isEmpty()) {
                related.add(GcpResources.related(p, ResourceRelation.RESOURCE_RELATION_PARENT));
            }
        }
        return new GcpAuditParser.Draft(GcpAuditParser.Draft.of(changeType, EventCategory.EVENT_CATEGORY_MANAGEMENT),
                target, related, "", null);
    }

    private static GcpAuditParser.Draft table(GcpAuditParser.Record r, String resourceName, String region,
                                              ChangeType changeType, java.time.Instant created) {
        CanonicalEvent.Resource t = GcpResources.bigQuery(resourceName, region, r.hints());
        if (created != null) {
            t = new CanonicalEvent.Resource(t.kind(), t.id(), t.name(), t.project(), t.region(), t.resourcePath(),
                    t.labels(), t.canonicalUri(), created, t.sizeBytes(), t.technicalMetadata(), t.relation());
        }
        return new GcpAuditParser.Draft(GcpAuditParser.Draft.of(changeType, EventCategory.EVENT_CATEGORY_MANAGEMENT),
                t, List.of(), "", null);
    }

    private static GcpAuditParser.Draft simple(GcpAuditParser.Record r, String resourceName, String region,
                                               ChangeType changeType) {
        return new GcpAuditParser.Draft(GcpAuditParser.Draft.of(changeType, EventCategory.EVENT_CATEGORY_MANAGEMENT),
                GcpResources.bigQuery(resourceName, region, r.hints()), List.of(), "", null);
    }

    private static ChangeType changeTypeOf(String event) {
        if (event.endsWith("Creation")) {
            return ChangeType.CREATE;
        }
        if (event.endsWith("Deletion") || "unlinkDataset".equals(event)) {
            return ChangeType.DELETE;
        }
        if (event.endsWith("Read")) {
            return ChangeType.ACCESS;
        }
        return ChangeType.UPDATE;
    }

    /** A job that ended with an error: {@code jobStatus.errorResult} with a non-zero code or a message. */
    private static boolean failed(JsonNode job) {
        JsonNode error = job.path("jobStatus").path("errorResult");
        if (!error.isObject() || error.isEmpty()) {
            return false;
        }
        Optional<Long> code = GcpJson.integer(error, "code");
        return code.map(c -> c != 0).orElse(GcpJson.present(error, "message"));
    }

    /** Any {@code ...Truncated: true} flag in the metadata, to a bounded depth. */
    private static boolean truncated(JsonNode node, int depth) {
        if (depth > 12 || node == null) {
            return false;
        }
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().endsWith("Truncated") && GcpJson.isTrue(e.getValue())) {
                    return true;
                }
                if (truncated(e.getValue(), depth + 1)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode n : node) {
                if (truncated(n, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static GcpAuditParser.Draft withClassification(GcpAuditParser.Draft d, Classification c) {
        return new GcpAuditParser.Draft(c, d.target(), d.related(), d.sharedEventId(), d.result());
    }
}
