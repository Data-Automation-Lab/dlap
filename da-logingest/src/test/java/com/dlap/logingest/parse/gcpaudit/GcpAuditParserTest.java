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

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalEvent;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.ResourceRelation;
import com.dlap.proto.log.v1.ResultStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Named GCP-01 acceptance checks on small hand-written entries, one behaviour each, next to the corpus
 * contract test: BigQuery reads from {@code tableDataRead} and {@code referencedTables} counted once per
 * job and table, no read for a cache hit, a failed job or a script parent, a script child's reads, v1
 * reads, the FAILURE rules (Policy Denied, VPC Service Controls, a denied check), masked actors, time
 * rules, anonymous and wildcard tables, and the native key across envelopes.
 */
class GcpAuditParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String P = "proj-0d1a0001";
    private static final String TABLE = "projects/" + P + "/datasets/sales/tables/orders";
    private static final String TABLE_2 = "projects/" + P + "/datasets/sales/tables/customers";
    private static final String JOB = "projects/" + P + "/jobs/bquxjob_0d1a0001";
    private static final String DATA_ACCESS = "projects/" + P + "/logs/cloudaudit.googleapis.com%2Fdata_access";
    private static final String POLICY = "projects/" + P + "/logs/cloudaudit.googleapis.com%2Fpolicy";
    private static final String V2 = GcpAuditFormat.BQ_AUDIT_METADATA_TYPE;

    private static ObjectNode entry(String insertId, String logName, String service, String method, String resource,
                                    String principal) {
        ObjectNode e = JSON.createObjectNode();
        e.put("insertId", insertId);
        e.put("logName", logName);
        e.put("timestamp", "2026-09-01T10:00:00.123456789Z");
        e.put("receiveTimestamp", "2026-09-01T10:00:01Z");
        e.put("severity", "INFO");
        ObjectNode r = e.putObject("resource");
        boolean bq = service.equals(GcpAuditFormat.BIGQUERY);
        r.put("type", bq ? "bigquery_dataset" : "gcs_bucket");
        r.putObject("labels").put("project_id", P).put(bq ? "dataset_id" : "bucket_name", bq ? "sales" : "dlap-fixture-bucket");
        ObjectNode pp = e.putObject("protoPayload");
        pp.put("@type", GcpAuditFormat.AUDIT_LOG_TYPE);
        pp.put("serviceName", service);
        pp.put("methodName", method);
        pp.put("resourceName", resource);
        pp.putObject("authenticationInfo").put("principalEmail", principal);
        pp.putObject("status");
        return e;
    }

    private static ObjectNode bq(String insertId, String method, String resource, String metadata) {
        ObjectNode e = entry(insertId, DATA_ACCESS, GcpAuditFormat.BIGQUERY, method, resource, "analyst@example.com");
        ObjectNode m = (ObjectNode) read("{\"@type\":\"" + V2 + "\"," + metadata + "}");
        ((ObjectNode) e.get("protoPayload")).set("metadata", m);
        return e;
    }

    private static ObjectNode gcsGet(String insertId) {
        return entry(insertId, DATA_ACCESS, GcpAuditFormat.STORAGE, "storage.objects.get",
                "projects/_/buckets/dlap-fixture-bucket/objects/exports/orders.csv", "analyst@example.com");
    }

    private static String jobDone(String extraConfig, String queryStats, String status) {
        return "\"jobChange\":{\"before\":\"RUNNING\",\"after\":\"DONE\",\"job\":{\"jobName\":\"" + JOB + "\","
                + "\"jobConfig\":{\"type\":\"QUERY\",\"queryConfig\":{\"query\":\"SELECT 1\"" + extraConfig + "}},"
                + "\"jobStatus\":{\"jobState\":\"DONE\"" + status + "},"
                + "\"jobStats\":{\"queryStats\":{" + queryStats + "}}}}";
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ParseOutcome parse(JsonNode entry, String envelope) {
        RawRecord r = RawRecord.builder().bindingId("gcp-test").sourceKind(GcpAuditFormat.SOURCE_KIND).envelope(envelope)
                .ref(RawRef.of("TEST", "test:entry", 0)).fetchedAt(Instant.parse("2026-09-02T00:00:00Z")).json(entry)
                .build();
        return GcpAuditRegistryForTests.registry().parse(r, BindingConfig.auto("gcp-test", GcpAuditFormat.SOURCE_KIND));
    }

    private static List<CanonicalEvent> events(JsonNode entry) {
        ParseOutcome o = parse(entry, GcpAuditFormat.JSON);
        assertThat(o.result()).as(o.outcomeCode()).isInstanceOf(ParseResult.Parsed.class);
        return ((ParseResult.Parsed) o.result()).events();
    }

    private static List<CanonicalEvent> reads(List<CanonicalEvent> events) {
        return events.stream().filter(e -> e.category() == EventCategory.EVENT_CATEGORY_DATA_READ).toList();
    }

    /** What downstream usage counts once: the job (shared event id) and the table read. */
    private static Set<String> usageKeys(List<CanonicalEvent> events) {
        return reads(events).stream().map(e -> e.sharedEventId() + " " + e.target().canonicalUri())
                .collect(Collectors.toSet());
    }

    @Test
    void aTableDataReadIsOneReadOfTheTableSharedByItsJob() {
        List<CanonicalEvent> events = events(bq("r1", "google.cloud.bigquery.v2.JobService.InsertJob", TABLE,
                "\"tableDataRead\":{\"reason\":\"JOB\",\"jobName\":\"" + JOB + "\",\"fields\":[\"id\",\"total\"]}"));
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.category()).isEqualTo(EventCategory.EVENT_CATEGORY_DATA_READ);
            assertThat(e.target().id()).isEqualTo(TABLE);
            assertThat(e.target().canonicalUri()).isNotEmpty();
            assertThat(e.sharedEventId()).isEqualTo(JOB);
            assertThat(e.related()).anySatisfy(r -> assertThat(r.id()).isEqualTo(JOB));
        });
    }

    @Test
    void aStorageReadSessionIsSharedByItsSessionName() {
        String session = "projects/" + P + "/locations/us/sessions/CAISDGRsYXAtZml4dHVyZQ";
        List<CanonicalEvent> events = events(bq("r2", "google.cloud.bigquery.storage.v1.BigQueryRead.CreateReadSession",
                TABLE, "\"tableDataRead\":{\"reason\":\"BIGQUERY_STORAGE_API\",\"sessionName\":\"" + session + "\"}"));
        assertThat(events).singleElement().satisfies(e -> assertThat(e.sharedEventId()).isEqualTo(session));
    }

    @Test
    void aFinishedQueryReadsEachReferencedTableOnceAndMatchesItsTableDataReads() {
        List<CanonicalEvent> job = events(bq("j1", "google.cloud.bigquery.v2.JobService.InsertJob", JOB,
                jobDone("", "\"referencedTables\":[\"" + TABLE + "\",\"" + TABLE_2 + "\",\"" + TABLE + "\"]", "")));
        assertThat(job.get(0).category()).isEqualTo(EventCategory.EVENT_CATEGORY_MANAGEMENT);
        assertThat(job.get(0).target().id()).isEqualTo(JOB);
        assertThat(job.get(0).related()).filteredOn(r -> r.relation() == ResourceRelation.RESOURCE_RELATION_SOURCE)
                .extracting(CanonicalEvent.Resource::id).containsExactly(TABLE, TABLE_2);
        assertThat(reads(job)).extracting(e -> e.target().id()).containsExactly(TABLE, TABLE_2);
        assertThat(reads(job)).allSatisfy(e -> {
            assertThat(e.sharedEventId()).isEqualTo(JOB);
            assertThat(e.result()).isEqualTo(ResultStatus.SUCCESS);
        });

        List<CanonicalEvent> tableRead = events(bq("r1", "google.cloud.bigquery.v2.JobService.InsertJob", TABLE,
                "\"tableDataRead\":{\"reason\":\"JOB\",\"jobName\":\"" + JOB + "\"}"));
        assertThat(usageKeys(job)).as("a tableDataRead of the same job and table is the same usage")
                .containsAll(usageKeys(tableRead));
        assertThat(job.get(1).nativeKey()).isNotEqualTo(tableRead.get(0).nativeKey());
    }

    @Test
    void aCacheHitAFailedJobAndAScriptParentReadNothing() {
        List<CanonicalEvent> cached = events(bq("j2", "google.cloud.bigquery.v2.JobService.InsertJob", JOB,
                jobDone("", "\"cacheHit\":true,\"referencedTables\":[\"" + TABLE + "\"]", "")));
        assertThat(cached).singleElement().satisfies(e ->
                assertThat(e.category()).isEqualTo(EventCategory.EVENT_CATEGORY_MANAGEMENT));

        List<CanonicalEvent> failed = events(bq("j3", "google.cloud.bigquery.v2.JobService.InsertJob", JOB,
                jobDone("", "\"referencedTables\":[\"" + TABLE + "\"]",
                        ",\"errorResult\":{\"code\":3,\"message\":\"Syntax error\"}")));
        assertThat(failed).singleElement().satisfies(e -> {
            assertThat(e.category()).isEqualTo(EventCategory.EVENT_CATEGORY_MANAGEMENT);
            assertThat(e.result()).isEqualTo(ResultStatus.FAILURE);
        });

        List<CanonicalEvent> script = events(bq("j4", "google.cloud.bigquery.v2.JobService.InsertJob", JOB,
                jobDone(",\"statementType\":\"SCRIPT\"", "\"referencedTables\":[\"" + TABLE + "\"]", "")));
        assertThat(reads(script)).isEmpty();
    }

    @Test
    void aScriptChildReadsItsTablesAndPointsToItsParent() {
        String child = "projects/" + P + "/jobs/script_job_0d1a0001_0";
        String meta = "\"jobChange\":{\"after\":\"DONE\",\"job\":{\"jobName\":\"" + child + "\","
                + "\"jobConfig\":{\"type\":\"QUERY\",\"queryConfig\":{\"query\":\"SELECT * FROM sales.orders\","
                + "\"statementType\":\"SELECT\"}},\"jobStatus\":{\"jobState\":\"DONE\"},"
                + "\"jobStats\":{\"parentJobName\":\"" + JOB + "\",\"queryStats\":{\"referencedTables\":[\"" + TABLE + "\"]}}}}";
        List<CanonicalEvent> events = events(bq("j5", "google.cloud.bigquery.v2.JobService.InsertJob", child, meta));
        assertThat(events.get(0).related()).anySatisfy(r -> {
            assertThat(r.id()).isEqualTo(JOB);
            assertThat(r.relation()).isEqualTo(ResourceRelation.RESOURCE_RELATION_PARENT);
        });
        assertThat(reads(events)).singleElement().satisfies(e -> {
            assertThat(e.target().id()).isEqualTo(TABLE);
            assertThat(e.sharedEventId()).isEqualTo(child);
        });
    }

    @Test
    void anAnonymousResultTableIsNeverADataAssetAndAWildcardReadLandsOnTheDataset() {
        String anonymous = "projects/" + P + "/datasets/_0d1a0001anon/tables/anon_result";
        List<CanonicalEvent> anon = events(bq("r3", "google.cloud.bigquery.v2.JobService.GetQueryResults", anonymous,
                "\"tableDataRead\":{\"reason\":\"JOB\",\"jobName\":\"" + JOB + "\"}"));
        assertThat(anon).singleElement().satisfies(e ->
                assertThat(e.category()).isEqualTo(EventCategory.EVENT_CATEGORY_MANAGEMENT));

        String wildcard = "projects/" + P + "/datasets/sales/tables/events_*";
        List<CanonicalEvent> job = events(bq("j6", "google.cloud.bigquery.v2.JobService.InsertJob", JOB,
                jobDone("", "\"referencedTables\":[\"" + wildcard + "\",\"" + anonymous + "\"]", "")));
        assertThat(reads(job)).singleElement().satisfies(e -> {
            assertThat(e.target().technicalMetadata()).containsEntry(GcpResources.WILDCARD_KEY, "events_*");
            assertThat(e.target().canonicalUri()).doesNotContain("events_");
        });
    }

    @Test
    void aV1CompletedQueryReadsItsReferencedTablesAndTableDataReadEventsOnce() {
        ObjectNode e = entry("v1-1", DATA_ACCESS, GcpAuditFormat.BIGQUERY, "jobservice.jobcompleted",
                "projects/" + P + "/jobs/bquxjob_0d1a00v1", "analyst@example.com");
        JsonNode sd = read("{\"@type\":\"" + GcpAuditFormat.BQ_AUDIT_DATA_TYPE + "\",\"jobCompletedEvent\":{"
                + "\"eventName\":\"query_job_completed\",\"job\":{\"jobName\":{\"projectId\":\"" + P + "\","
                + "\"jobId\":\"bquxjob_0d1a00v1\",\"location\":\"US\"},\"jobConfiguration\":{\"query\":{"
                + "\"query\":\"SELECT 1\",\"statementType\":\"SELECT\",\"destinationTable\":{\"projectId\":\"" + P + "\","
                + "\"datasetId\":\"_0d1a0001anon\",\"tableId\":\"anon_result\"}}},\"jobStatus\":{\"state\":\"DONE\"},"
                + "\"jobStatistics\":{\"referencedTables\":[{\"projectId\":\"" + P + "\",\"datasetId\":\"sales\","
                + "\"tableId\":\"orders\"}]}}},\"tableDataReadEvents\":[{\"tableName\":{\"projectId\":\"" + P + "\","
                + "\"datasetId\":\"sales\",\"tableId\":\"orders\"},\"referencedFields\":[\"id\"]},{\"tableName\":{"
                + "\"projectId\":\"" + P + "\",\"datasetId\":\"sales\",\"tableId\":\"customers\"}}]}");
        ((ObjectNode) e.get("protoPayload")).set("serviceData", sd);
        ParseOutcome o = parse(e, GcpAuditFormat.JSON);
        assertThat(o.detected().orElseThrow().schemaVersion()).isEqualTo(GcpAuditFormat.BQ_AUDIT_DATA_LEGACY);
        List<CanonicalEvent> events = ((ParseResult.Parsed) o.result()).events();
        assertThat(events).extracting(x -> x.target().id()).containsExactly(TABLE, TABLE_2);
        assertThat(events).allSatisfy(x -> {
            assertThat(x.category()).isEqualTo(EventCategory.EVENT_CATEGORY_DATA_READ);
            assertThat(x.sharedEventId()).isEqualTo("projects/" + P + "/jobs/bquxjob_0d1a00v1");
        });
        assertThat(events.get(1).nativeKey()).isEqualTo(events.get(0).nativeKey().split("#")[0] + "#"
                + events.get(1).target().canonicalUri());
    }

    // ------------------------------------------------------------------
    // Q-393 / D45: a read takes the entry's own failure
    // ------------------------------------------------------------------

    private static ObjectNode v1Completed(String insertId, String logName, String jobConfig) {
        ObjectNode e = entry(insertId, logName, GcpAuditFormat.BIGQUERY, "jobservice.jobcompleted",
                "projects/" + P + "/jobs/bquxjob_" + insertId, "analyst@example.com");
        JsonNode sd = read("{\"@type\":\"" + GcpAuditFormat.BQ_AUDIT_DATA_TYPE + "\",\"jobCompletedEvent\":{"
                + "\"eventName\":\"query_job_completed\",\"job\":{\"jobName\":{\"projectId\":\"" + P + "\","
                + "\"jobId\":\"bquxjob_" + insertId + "\",\"location\":\"US\"},\"jobConfiguration\":" + jobConfig
                + ",\"jobStatus\":{\"state\":\"DONE\"},\"jobStatistics\":{\"referencedTables\":[{\"projectId\":\""
                + P + "\",\"datasetId\":\"sales\",\"tableId\":\"orders\"}]}}}}");
        ((ObjectNode) e.get("protoPayload")).set("serviceData", sd);
        return e;
    }

    private static ObjectNode v1Query(String insertId, String logName) {
        return v1Completed(insertId, logName, "{\"query\":{\"query\":\"SELECT 1\",\"statementType\":\"SELECT\"}}");
    }

    private static ObjectNode v1Load(String insertId) {
        return v1Completed(insertId, DATA_ACCESS, "{\"load\":{\"destinationTable\":{\"projectId\":\"" + P
                + "\",\"datasetId\":\"sales\",\"tableId\":\"orders_staging\"}}}");
    }

    private static ObjectNode v2Query(String insertId) {
        return bq(insertId, "google.cloud.bigquery.v2.JobService.InsertJob", JOB,
                jobDone("", "\"referencedTables\":[\"" + TABLE + "\"]", ""));
    }

    private static void denyCheck(ObjectNode entry) {
        ((ObjectNode) entry.get("protoPayload")).putArray("authorizationInfo").addObject()
                .put("permission", "bigquery.tables.getData").put("resource", TABLE).put("granted", false);
    }

    private static void assertReadsAndWritesFail(List<CanonicalEvent> events, int expectedData) {
        List<CanonicalEvent> data = events.stream().filter(x ->
                x.category() == EventCategory.EVENT_CATEGORY_DATA_READ
                        || x.category() == EventCategory.EVENT_CATEGORY_DATA_WRITE).toList();
        assertThat(data).hasSize(expectedData);
        assertThat(data).allSatisfy(x -> assertThat(x.result()).isEqualTo(ResultStatus.FAILURE));
    }

    @Test
    void aV1CompletedQueryOnAnEntryWithStatusCode7IsAFailedRead() {
        ObjectNode ok = v1Query("q393a", DATA_ACCESS);
        assertThat(events(ok)).as("the control entry stays a successful read").singleElement().satisfies(x -> {
            assertThat(x.category()).isEqualTo(EventCategory.EVENT_CATEGORY_DATA_READ);
            assertThat(x.result()).isEqualTo(ResultStatus.SUCCESS);
        });

        ObjectNode denied = v1Query("q393b", DATA_ACCESS);
        ((ObjectNode) denied.get("protoPayload")).putObject("status").put("code", 7).put("message", "PERMISSION_DENIED");
        assertReadsAndWritesFail(events(denied), 1);
    }

    @Test
    void aV1CompletedLoadOnAnEntryWithStatusCode7IsAFailedWrite() {
        // The helper's statistics also reference the orders table, so the job reads it and writes the staging table.
        ObjectNode ok = v1Load("q393c");
        assertThat(events(ok)).extracting(CanonicalEvent::category).containsExactlyInAnyOrder(
                EventCategory.EVENT_CATEGORY_DATA_READ, EventCategory.EVENT_CATEGORY_DATA_WRITE);
        assertThat(events(ok)).allSatisfy(x -> assertThat(x.result()).isEqualTo(ResultStatus.SUCCESS));

        ObjectNode denied = v1Load("q393d");
        ((ObjectNode) denied.get("protoPayload")).putObject("status").put("code", 7);
        List<CanonicalEvent> events = events(denied);
        assertThat(events).extracting(CanonicalEvent::category).contains(EventCategory.EVENT_CATEGORY_DATA_WRITE);
        assertReadsAndWritesFail(events, 2);
    }

    @Test
    void aV1CompletedQueryWithADeniedAuthorizationCheckIsAFailedRead() {
        ObjectNode e = v1Query("q393e", DATA_ACCESS);
        denyCheck(e);
        assertReadsAndWritesFail(events(e), 1);
    }

    @Test
    void aV1CompletedQueryInThePolicyDeniedLogIsAFailedRead() {
        ObjectNode e = v1Query("q393f", POLICY);
        assertReadsAndWritesFail(events(e), 1);
    }

    @Test
    void aV1CompletedQueryWithVpcServiceControlsMetadataIsAFailedRead() {
        ObjectNode e = v1Query("q393g", DATA_ACCESS);
        ((ObjectNode) e.get("protoPayload")).set("metadata",
                read("{\"@type\":\"type.googleapis.com/google.cloud.audit.VpcServiceControlAuditMetadata\","
                        + "\"violationReason\":\"RESOURCES_NOT_IN_SAME_SERVICE_PERIMETER\"}"));
        assertReadsAndWritesFail(events(e), 1);
    }

    @Test
    void aV2FinishedQueryOnAnEntryWithStatusCode7GivesFailedReadsAndAFailedJob() {
        List<CanonicalEvent> ok = events(v2Query("q393h"));
        assertThat(reads(ok)).singleElement().satisfies(x -> assertThat(x.result()).isEqualTo(ResultStatus.SUCCESS));

        ObjectNode denied = v2Query("q393i");
        ((ObjectNode) denied.get("protoPayload")).putObject("status").put("code", 7);
        List<CanonicalEvent> events = events(denied);
        assertThat(events.get(0).category()).isEqualTo(EventCategory.EVENT_CATEGORY_MANAGEMENT);
        assertThat(events.get(0).result()).isEqualTo(ResultStatus.FAILURE);
        assertReadsAndWritesFail(events, 1);
    }

    @Test
    void aV2FinishedQueryWithADeniedCheckOrInThePolicyDeniedLogGivesFailedReads() {
        ObjectNode check = v2Query("q393j");
        denyCheck(check);
        assertReadsAndWritesFail(events(check), 1);

        ObjectNode policy = v2Query("q393k");
        policy.put("logName", POLICY);
        assertReadsAndWritesFail(events(policy), 1);
    }

    @Test
    void everyPolicyDeniedEntryIsAFailure() {
        ObjectNode e = gcsGet("pd-1");
        e.put("logName", POLICY);
        ((ObjectNode) e.get("protoPayload")).putObject("policyViolationInfo").putObject("orgPolicyViolationInfo");
        assertThat(events(e)).allSatisfy(x -> assertThat(x.result()).isEqualTo(ResultStatus.FAILURE));

        ObjectNode bare = gcsGet("pd-2");
        bare.put("logName", POLICY);
        assertThat(events(bare)).as("the policy log alone").allSatisfy(x ->
                assertThat(x.result()).isEqualTo(ResultStatus.FAILURE));
    }

    @Test
    void aVpcServiceControlsDenialIsAFailure() {
        ObjectNode e = gcsGet("vpc-1");
        e.put("logName", POLICY);
        ObjectNode pp = (ObjectNode) e.get("protoPayload");
        pp.putObject("status").put("code", 7).put("message", "Request is prohibited by organization's policy.");
        pp.set("metadata", read("{\"@type\":\"type.googleapis.com/google.cloud.audit.VpcServiceControlAuditMetadata\","
                + "\"violationReason\":\"RESOURCES_NOT_IN_SAME_SERVICE_PERIMETER\","
                + "\"securityPolicyInfo\":{\"organizationId\":\"999900000001\","
                + "\"servicePerimeterName\":\"accessPolicies/999900000003/servicePerimeters/dlap\"}}"));
        assertThat(events(e)).allSatisfy(x -> assertThat(x.result()).isEqualTo(ResultStatus.FAILURE));

        ObjectNode dataAccess = gcsGet("vpc-2");
        ((ObjectNode) dataAccess.get("protoPayload")).set("metadata",
                read("{\"@type\":\"type.googleapis.com/google.cloud.audit.VpcServiceControlAuditMetadata\"}"));
        assertThat(events(dataAccess)).as("the metadata type alone").allSatisfy(x ->
                assertThat(x.result()).isEqualTo(ResultStatus.FAILURE));
    }

    @Test
    void aPermissionCheckIsDeniedWhenGrantedIsFalseOrLeftOut() {
        ObjectNode granted = gcsGet("az-1");
        ((ObjectNode) granted.get("protoPayload")).putArray("authorizationInfo").addObject()
                .put("permission", "storage.objects.get").put("granted", true);
        assertThat(events(granted).get(0).result()).isEqualTo(ResultStatus.SUCCESS);

        ObjectNode explicit = gcsGet("az-2");
        ((ObjectNode) explicit.get("protoPayload")).putArray("authorizationInfo").addObject()
                .put("permission", "storage.objects.get").put("granted", false);
        assertThat(events(explicit).get(0).result()).isEqualTo(ResultStatus.FAILURE);

        ObjectNode omitted = gcsGet("az-3");
        ((ObjectNode) omitted.get("protoPayload")).putArray("authorizationInfo").addObject()
                .put("permission", "storage.objects.get").put("resource", "projects/_/buckets/dlap-fixture-bucket");
        assertThat(events(omitted).get(0).result()).as("proto3 JSON omits a false bool").isEqualTo(ResultStatus.FAILURE);

        ObjectNode empty = gcsGet("az-4");
        ((ObjectNode) empty.get("protoPayload")).putArray("authorizationInfo").addObject();
        assertThat(events(empty).get(0).result()).as("an empty entry is no check").isEqualTo(ResultStatus.SUCCESS);
    }

    @Test
    void aMaskedOrRedactedEmailIsAValidActorThatNamesNobody() {
        for (String masked : List.of("***@example.com", "...@example.com", "redacted")) {
            ObjectNode e = gcsGet("m-" + masked.length());
            ((ObjectNode) e.get("protoPayload")).putObject("authenticationInfo").put("principalEmail", masked);
            ParseOutcome o = parse(e, GcpAuditFormat.JSON);
            assertThat(o.outcomeCode()).as(masked).isEqualTo("PARSED");
            CanonicalEvent.Actor a = ((ParseResult.Parsed) o.result()).events().get(0).principal();
            assertThat(a.type()).as(masked).isEqualTo(PrincipalType.PRINCIPAL_TYPE_UNSPECIFIED);
            assertThat(a.id()).as(masked).isEmpty();
            assertThat(a.displayName()).as(masked).isEqualTo(masked);
        }
        assertThat(GcpPrincipals.of("sa-0d1a00e1@" + P + ".iam.gserviceaccount.com").type())
                .isEqualTo(PrincipalType.SERVICE_ACCOUNT);
        assertThat(GcpPrincipals.of("google-internal").type()).isEqualTo(PrincipalType.SYSTEM);
        assertThat(GcpPrincipals.of("principal://iam.googleapis.com/locations/global/workforcePools/p/subject/u").type())
                .isEqualTo(PrincipalType.USER);
    }

    @Test
    void theReceiveTimestampStandsInForAMissingTimestampAndNoTimeAtAllIsQuarantined() {
        ObjectNode late = gcsGet("t-1");
        late.remove("timestamp");
        assertThat(events(late).get(0).eventTime()).isEqualTo(Instant.parse("2026-09-01T10:00:01Z"));

        ObjectNode none = gcsGet("t-2");
        none.remove("timestamp");
        none.remove("receiveTimestamp");
        assertThat(parse(none, GcpAuditFormat.JSON).outcomeCode()).isEqualTo("QUARANTINED:MISSING_REQUIRED_FIELD");
    }

    @Test
    void aV2EntryWithoutAKnownMetadataEventIsQuarantined() {
        ObjectNode e = bq("u-1", "google.cloud.bigquery.v2.JobService.InsertJob", JOB, "\"futureEvent\":{\"x\":1}");
        assertThat(parse(e, GcpAuditFormat.JSON).outcomeCode()).isEqualTo("QUARANTINED:MISSING_REQUIRED_FIELD");
    }

    @Test
    void theSameEntryHasOneNativeKeyInTheJsonAndApiProtoEnvelopes() {
        ObjectNode e = gcsGet("k-1");
        String viaJson = events(e).get(0).nativeKey();
        ParseOutcome viaApi = parse(e, GcpAuditFormat.API_PROTO);
        assertThat(((ParseResult.Parsed) viaApi.result()).events().get(0).nativeKey()).isEqualTo(viaJson)
                .isEqualTo(DATA_ACCESS + "|2026-09-01T10:00:00.123456789Z|k-1");
    }

    /**
     * A BigQuery sink stores microseconds, so an entry whose timestamp has nanoseconds gets another
     * native key there than through the API or a Cloud Storage sink (pinned limitation; read a project
     * through one transport). With microsecond timestamps the keys agree.
     */
    @Test
    void aBigQuerySinkRowMatchesTheOtherEnvelopesOnlyToTheMicrosecond() {
        ObjectNode micro = gcsGet("k-2");
        micro.put("timestamp", "2026-09-01T10:00:00.123456Z");
        String viaJson = events(micro).get(0).nativeKey();
        ObjectNode row = sinkRow(micro);
        ParseOutcome viaRow = parse(row, GcpAuditFormat.BQ_SINK_ROW);
        assertThat(viaRow.outcomeCode()).isEqualTo("PARSED");
        assertThat(((ParseResult.Parsed) viaRow.result()).events().get(0).nativeKey()).isEqualTo(viaJson);

        ObjectNode nano = gcsGet("k-3");
        String nanoKey = events(nano).get(0).nativeKey();
        ObjectNode nanoRow = sinkRow(nano);
        nanoRow.put("timestamp", "2026-09-01T10:00:00.123456Z");
        String rowKey = ((ParseResult.Parsed) parse(nanoRow, GcpAuditFormat.BQ_SINK_ROW).result()).events().get(0)
                .nativeKey();
        assertThat(rowKey).isNotEqualTo(nanoKey);
    }

    /** The sink row of an entry: the payload under protopayload_auditlog, other columns as they are. */
    private static ObjectNode sinkRow(ObjectNode entry) {
        ObjectNode row = entry.deepCopy();
        ObjectNode pp = (ObjectNode) row.remove("protoPayload");
        pp.remove("@type");
        row.set("protopayload_auditlog", pp);
        return row;
    }
}
