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

package com.dlap.logingest.parse.bqjobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalEventMapper;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.parse.core.contract.AbstractParserContractTest;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.QuarantineReason;
import com.dlap.proto.log.v1.ResourceRelation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the JOBS parser that the fixture corpus cannot say with a field list: ids, fan-out,
 * related resources, the scan's exclusion rules, principal typing, and what is never stored.
 */
class BqJobsParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final MappingSpec MAPPING = AbstractParserContractTest.classpathMapping(BqJobsFormat.FAMILY);
    private static final DefaultParserRegistry REGISTRY =
            AbstractParserContractTest.registryOf(MAPPING, List.of(new BqJobsParser(MAPPING)));
    private static final BindingConfig BINDING = MAPPING.defaultBinding("bq-test");
    private static final CanonicalEventMapper MAPPER = new CanonicalEventMapper(
            Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC));
    private static final String P = "proj-x";

    // ------------------------------------------------------------------
    // Row construction
    // ------------------------------------------------------------------

    private static ObjectNode table(String dataset, String table) {
        ObjectNode t = JSON.createObjectNode();
        t.put("project_id", P);
        t.put("dataset_id", dataset);
        t.put("table_id", table);
        return t;
    }

    /** A finished SELECT by a user reading no tables; tests add what they need. */
    private static ObjectNode row(String jobId) {
        ObjectNode r = JSON.createObjectNode();
        r.put("location", "US");
        r.put("project_id", P);
        r.put("job_id", jobId);
        r.put("creation_time", "2026-09-01T10:00:00Z");
        r.put("end_time", "2026-09-01T10:00:05.5Z");
        r.put("user_email", "analyst@example.com");
        r.putNull("principal_subject");
        r.put("job_type", "QUERY");
        r.put("statement_type", "SELECT");
        r.put("state", "DONE");
        r.putNull("error_result");
        r.put("cache_hit", false);
        r.putNull("parent_job_id");
        r.putNull("destination_table");
        r.putArray("referenced_tables");
        r.putArray("referenced_logical_views");
        return r;
    }

    private static ObjectNode withTables(ObjectNode r, ObjectNode... tables) {
        ArrayNode a = r.putArray("referenced_tables");
        for (ObjectNode t : tables) {
            a.add(t);
        }
        return r;
    }

    private static ParseOutcome parse(ObjectNode row) {
        RawRecord record = RawRecord.builder().bindingId(BINDING.bindingId()).sourceKind(BqJobsFormat.SOURCE_KIND)
                .envelope(BqJobsFormat.ENVELOPE).ref(RawRef.of("TEST", "test:" + row.path("job_id").asText(), 0))
                .mode(IngestMode.STREAM).fetchedAt(Instant.parse("2030-01-01T00:00:00Z")).json(row).build();
        return REGISTRY.parse(record, BINDING);
    }

    private static List<DlapLogEvent> events(ObjectNode row) {
        return MAPPER.events(parse(row));
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    @Test
    void theFirstEventKeepsTheJobKeyAndEveryOtherEventAppendsItsTable() {
        ObjectNode r = withTables(row("job_a"), table("sales", "orders"), table("sales", "customers"));
        List<DlapLogEvent> events = events(r);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).getNativeEventId()).isEqualTo("GCP_BQ_JOBS_VIEW|proj-x|US|job_a");
        assertThat(events.get(0).getTargetResource().getCanonicalUri()).isEqualTo("bq://proj-x/sales/customers");
        assertThat(events.get(1).getNativeEventId())
                .isEqualTo("GCP_BQ_JOBS_VIEW|proj-x|US|job_a#bq://proj-x/sales/orders");
        assertThat(events.stream().map(DlapLogEvent::getEventId).distinct()).hasSize(2);
    }

    @Test
    void theSameRowOnTwoPollsGivesTheSameEventIds() {
        ObjectNode r = withTables(row("job_b"), table("sales", "orders"), table("sales", "items"));
        assertThat(events(r).stream().map(DlapLogEvent::getEventId).toList())
                .isEqualTo(events(r.deepCopy()).stream().map(DlapLogEvent::getEventId).toList());
    }

    @Test
    void theWriteIsThePrimaryEventWhenThereIsOne() {
        ObjectNode r = withTables(row("job_c"), table("sales", "orders"));
        r.put("statement_type", "INSERT");
        r.set("destination_table", table("staging", "landing"));
        List<DlapLogEvent> events = events(r);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).getNativeEventId()).isEqualTo("GCP_BQ_JOBS_VIEW|proj-x|US|job_c");
        assertThat(events.get(0).getEventCategory()).isEqualTo(EventCategory.EVENT_CATEGORY_DATA_WRITE);
        assertThat(events.get(1).getEventCategory()).isEqualTo(EventCategory.EVENT_CATEGORY_DATA_READ);
    }

    @Test
    void theLocationIsPartOfTheKeyBecauseAJobIdIsOnlyUniqueWithIt() {
        ObjectNode us = withTables(row("job_d"), table("sales", "orders"));
        ObjectNode eu = us.deepCopy();
        eu.put("location", "EU");
        assertThat(events(us).get(0).getEventId()).isNotEqualTo(events(eu).get(0).getEventId());
    }

    // ------------------------------------------------------------------
    // Reads and writes: the scan's rules
    // ------------------------------------------------------------------

    @Test
    void aTableTheJobWritesIsNotAlsoARead() {
        ObjectNode r = withTables(row("job_e"), table("sales", "orders"), table("sales", "audit"));
        r.put("statement_type", "MERGE");
        r.set("destination_table", table("sales", "orders"));
        List<DlapLogEvent> events = events(r);

        assertThat(events).extracting(e -> e.getTargetResource().getName() + ":" + e.getEventCategory().name())
                .containsExactly("orders:EVENT_CATEGORY_DATA_WRITE", "audit:EVENT_CATEGORY_DATA_READ");
    }

    @Test
    void aLogicalViewIsARead() {
        ObjectNode r = row("job_f");
        r.putArray("referenced_logical_views").add(table("analytics", "v_orders"));
        List<DlapLogEvent> events = events(r);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getTargetResource().getCanonicalUri()).isEqualTo("bq://proj-x/analytics/v_orders");
        assertThat(events.get(0).getEventCategory()).isEqualTo(EventCategory.EVENT_CATEGORY_DATA_READ);
    }

    @Test
    void aTableListedAsTableAndAsViewIsReadOnce() {
        ObjectNode r = withTables(row("job_g"), table("analytics", "v_orders"));
        r.putArray("referenced_logical_views").add(table("analytics", "v_orders"));
        assertThat(events(r)).hasSize(1);
    }

    @Test
    void aPartitionDecoratorDoesNotMakeAnotherTable() {
        ObjectNode r = withTables(row("job_h"), table("sales", "events"), table("sales", "events$20260901"));
        assertThat(events(r)).hasSize(1);
        assertThat(events(r).get(0).getTargetResource().getCanonicalUri()).isEqualTo("bq://proj-x/sales/events");
    }

    @Test
    void informationSchemaReferencesAreNotReads() {
        ObjectNode r = withTables(row("job_i"), table("region-us", "x"));
        ((ObjectNode) r.get("referenced_tables").get(0)).put("dataset_id", "INFORMATION_SCHEMA");
        ParseOutcome outcome = parse(r);
        assertThat(outcome.result()).isInstanceOf(ParseResult.Skipped.class);
    }

    @Test
    void aStatementOnTheNoReadListReadsNothingEvenWhenItListsTables() {
        for (String statement : BqJobsParser.NO_READ_STATEMENTS) {
            ObjectNode r = withTables(row("job_j_" + statement), table("sales", "orders"));
            r.put("statement_type", statement);
            r.set("destination_table", table("analytics", "result"));
            List<DlapLogEvent> events = events(r);
            assertThat(events).as(statement).noneMatch(e -> e.getEventCategory() == EventCategory.EVENT_CATEGORY_DATA_READ);
        }
    }

    @Test
    void anAnonymousResultTableIsNeverAWriteTarget() {
        ObjectNode r = withTables(row("job_k"), table("sales", "orders"));
        r.set("destination_table", table("_9ab01c", "anon1234"));
        assertThat(events(r)).extracting(e -> e.getEventCategory().name())
                .containsExactly("EVENT_CATEGORY_DATA_READ");
    }

    @Test
    void aDropNamesItsTableInTheSingleReferencedTableWhenThereIsNoDestination() {
        ObjectNode r = withTables(row("job_l"), table("staging", "old"));
        r.put("statement_type", "DROP_TABLE");
        List<DlapLogEvent> events = events(r);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventCategory()).isEqualTo(EventCategory.EVENT_CATEGORY_MANAGEMENT);
        assertThat(events.get(0).getChangeType().name()).isEqualTo("DELETE");
        assertThat(events.get(0).getTargetResource().getCanonicalUri()).isEqualTo("bq://proj-x/staging/old");
    }

    @Test
    void aCreateViewWithoutADestinationDoesNotWriteItsSourceTable() {
        ObjectNode r = withTables(row("job_cv"), table("sales", "orders"));
        r.put("statement_type", "CREATE_VIEW");
        assertThat(parse(r).result()).as("the source of a view definition is neither read nor written")
                .isInstanceOf(ParseResult.Skipped.class);
    }

    @Test
    void aDdlWithoutAnyTableIsNotAnEvent() {
        ObjectNode r = row("job_m");
        r.put("statement_type", "DROP_TABLE");
        assertThat(parse(r).result()).isInstanceOf(ParseResult.Skipped.class);
    }

    @Test
    void anUnknownStatementTypeReadsItsTablesAndIsFlaggedAsAGuess() {
        ObjectNode r = withTables(row("job_n"), table("sales", "orders"));
        r.put("statement_type", "ASSERT");
        List<DlapLogEvent> events = events(r);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventCategory()).isEqualTo(EventCategory.EVENT_CATEGORY_DATA_READ);
        assertThat(events.get(0).getParseWarningsList()).contains(ParseWarning.PARSE_WARNING_HEURISTIC_CLASSIFICATION);
    }

    @Test
    void aCopyJobWritesItsDestinationAndTheCopiedSourceIsNotKnown() {
        ObjectNode r = row("job_o");
        r.put("job_type", "COPY");
        r.putNull("statement_type");
        r.set("destination_table", table("analytics", "copy"));
        List<DlapLogEvent> events = events(r);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).getOperation()).isEqualTo("COPY");
        assertThat(events.get(0).getEventCategory()).isEqualTo(EventCategory.EVENT_CATEGORY_DATA_WRITE);
    }

    @Test
    void aLoadJobIgnoresReferencedTables() {
        ObjectNode r = withTables(row("job_p"), table("sales", "orders"));
        r.put("job_type", "LOAD");
        r.putNull("statement_type");
        r.set("destination_table", table("staging", "landing"));
        assertThat(events(r)).extracting(e -> e.getEventCategory().name()).containsExactly("EVENT_CATEGORY_DATA_WRITE");
    }

    @Test
    void aFailedJobGivesFailureEventsThatMoveNothing() {
        ObjectNode r = withTables(row("job_q"), table("sales", "orders"));
        r.putObject("error_result").put("reason", "accessDenied");
        assertThat(events(r)).hasSize(1).allSatisfy(e -> assertThat(e.getResultStatus().name()).isEqualTo("FAILURE"));
    }

    @Test
    void theEventTimeIsTheJobsEndTime() {
        ObjectNode r = withTables(row("job_r"), table("sales", "orders"));
        assertThat(events(r).get(0).getEventTime().getSeconds())
                .isEqualTo(Instant.parse("2026-09-01T10:00:05.5Z").getEpochSecond());
        assertThat(events(r).get(0).getEventTime().getNanos()).isEqualTo(500_000_000);
    }

    @Test
    void aRowWithoutAnEndTimeIsSkippedNotStampedWithTheClock() {
        ObjectNode r = withTables(row("job_s"), table("sales", "orders"));
        r.putNull("end_time");
        ParseResult result = parse(r).result();
        assertThat(result).isInstanceOf(ParseResult.Skipped.class);
        assertThat(((ParseResult.Skipped) result).reason().name()).isEqualTo("MISSING_EVENT_TIME");
    }

    // ------------------------------------------------------------------
    // Related resources and metadata
    // ------------------------------------------------------------------

    @Test
    void theJobAndTheOtherSideOfTheLineageAreRelatedResources() {
        ObjectNode r = withTables(row("job_t"), table("sales", "orders"), table("sales", "items"));
        r.put("statement_type", "CREATE_TABLE_AS_SELECT");
        r.set("destination_table", table("analytics", "summary"));
        List<DlapLogEvent> events = events(r);

        DlapLogEvent write = events.get(0);
        assertThat(write.getRelatedResourcesList()).extracting(x -> x.getRelation() + " " + x.getCanonicalUri())
                .contains("RESOURCE_RELATION_OTHER bqjob://proj-x/job_t",
                        "RESOURCE_RELATION_SOURCE bq://proj-x/sales/items",
                        "RESOURCE_RELATION_SOURCE bq://proj-x/sales/orders");
        DlapLogEvent read = events.get(1);
        assertThat(read.getRelatedResourcesList()).extracting(x -> x.getRelation() + " " + x.getCanonicalUri())
                .contains("RESOURCE_RELATION_DESTINATION bq://proj-x/analytics/summary");
        assertThat(read.getRequestMetadata().getRequestId()).isEqualTo("job_t");
        assertThat(write.getRelatedResourcesList()).noneMatch(x -> x.getRelation() == ResourceRelation.RESOURCE_RELATION_UNSPECIFIED);
    }

    @Test
    void aListAtTheProviderLimitIsFlaggedBecauseItMayBeCut() {
        ObjectNode r = row("job_u");
        ArrayNode refs = r.putArray("referenced_tables");
        for (int i = 0; i < BqJobsParser.PROVIDER_REFERENCE_LIMIT; i++) {
            refs.add(table("shards", "t" + i));
        }
        assertThat(events(r)).hasSize(BqJobsParser.PROVIDER_REFERENCE_LIMIT)
                .allSatisfy(e -> assertThat(e.getParseWarningsList()).contains(ParseWarning.PARSE_WARNING_PROVIDER_TRUNCATED));
    }

    // ------------------------------------------------------------------
    // Principals: the scan's typing
    // ------------------------------------------------------------------

    @Test
    void principalsAreTypedAsTheScanTypesThem() {
        assertThat(BqJobsParser.principalType("a@example.com", "")).isEqualTo(PrincipalType.USER);
        assertThat(BqJobsParser.principalType("etl@p.iam.gserviceaccount.com", "")).isEqualTo(PrincipalType.SERVICE_ACCOUNT);
        assertThat(BqJobsParser.principalType("", "serviceAccount:etl@p.iam.gserviceaccount.com"))
                .isEqualTo(PrincipalType.SERVICE_ACCOUNT);
        assertThat(BqJobsParser.principalType("", "principal://iam.googleapis.com/projects/1/locations/global/workloadIdentityPools/w/subject/s"))
                .isEqualTo(PrincipalType.SERVICE_ACCOUNT);
        assertThat(BqJobsParser.principalType("", "principal://iam.googleapis.com/locations/global/workforcePools/w/subject/s"))
                .isEqualTo(PrincipalType.USER);
        assertThat(BqJobsParser.principalType("", "user:a@example.com")).isEqualTo(PrincipalType.USER);
        assertThat(BqJobsParser.principalType("service-1@gcp-sa-bigquerydatatransfer.iam.gserviceaccount.com", ""))
                .isEqualTo(PrincipalType.SYSTEM);
        assertThat(BqJobsParser.principalType("1234-compute@cloudservices.gserviceaccount.com", ""))
                .isEqualTo(PrincipalType.SYSTEM);
        assertThat(BqJobsParser.principalType("", "")).isEqualTo(PrincipalType.PRINCIPAL_TYPE_UNSPECIFIED);
    }

    @Test
    void userEmailWinsOverPrincipalSubject() {
        ObjectNode r = withTables(row("job_v"), table("sales", "orders"));
        r.put("principal_subject", "user:someone-else@example.com");
        assertThat(events(r).get(0).getPrincipal().getId()).isEqualTo("analyst@example.com");
    }

    // ------------------------------------------------------------------
    // What is never stored
    // ------------------------------------------------------------------

    @Test
    void queryTextIsNeverStored() throws Exception {
        String secret = "customer_secret_marker";
        ObjectNode r = withTables(row("job_w"), table("sales", "orders"));
        r.put("query", "SELECT " + secret + " FROM sales.orders");
        r.set("job_stages", JSON.createArrayNode().add(JSON.createObjectNode().put("name", secret)));
        r.putObject("timeline").put("note", secret);

        ParseOutcome outcome = parse(r);
        List<DlapLogEvent> events = MAPPER.events(outcome);
        assertThat(events).isNotEmpty();
        DlapLogEventCodec codec = DlapLogEventCodec.defaults();
        for (DlapLogEvent e : events) {
            assertThat(codec.encode(e)).as("the encoded event").doesNotContain(secret);
            assertThat(e.toString()).doesNotContain(secret);
            assertThat(e.getRawEvent().getFieldsMap()).doesNotContainKeys("query", "job_stages", "timeline");
        }
    }

    @Test
    void rawEventKeepsTheKnownColumnsAndDropsTheUnknownOnes() {
        ObjectNode r = withTables(row("job_x"), table("sales", "orders"));
        r.put("future_flag", true);
        List<DlapLogEvent> events = events(r);

        assertThat(events.get(0).getRawEvent().getFieldsMap()).containsKeys("job_id", "location", "referenced_tables")
                .doesNotContainKey("future_flag");
        assertThat(events.get(0).getParseWarningsList()).contains(ParseWarning.PARSE_WARNING_UNKNOWN_FIELDS);
    }

    // ------------------------------------------------------------------
    // Quarantine
    // ------------------------------------------------------------------

    @Test
    void aMissingRequiredColumnIsColumnDriftAndNamesTheColumn() {
        ObjectNode r = row("job_y");
        r.remove("user_email");
        ParseResult result = parse(r).result();
        assertThat(result).isInstanceOf(ParseResult.Quarantined.class);
        ParseResult.Quarantined q = (ParseResult.Quarantined) result;
        assertThat(q.reason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD);
        assertThat(q.detail()).contains("column drift").contains("user_email").doesNotContain("job_y");
    }

    @Test
    void aNullRequiredColumnIsNotDriftBecauseTheColumnIsThere() {
        ObjectNode r = withTables(row("job_z"), table("sales", "orders"));
        r.putNull("user_email");
        r.put("principal_subject", "user:a@example.com");
        assertThat(events(r)).hasSize(1);
    }

    @Test
    void aRowWithoutALocationIsQuarantinedBecauseItsKeyNeedsOne() {
        ObjectNode r = withTables(row("job_aa"), table("sales", "orders"));
        r.remove("location");
        ParseResult result = parse(r).result();
        assertThat(result).isInstanceOf(ParseResult.Quarantined.class);
        assertThat(((ParseResult.Quarantined) result).reason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD);
    }

    @Test
    void aMalformedTableListIsATypeMismatch() {
        ObjectNode r = row("job_bb");
        r.put("referenced_tables", "not an array");
        ParseResult result = parse(r).result();
        assertThat(((ParseResult.Quarantined) result).reason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH);
    }

    @Test
    void aRunProblemMarkerIsQuarantinedAsAnUnknownEnvelopeAndStaysStable() {
        ObjectNode marker = BqJobsRunProblem.columnDrift("US", List.of("referenced_tables"), "a".repeat(64), null);
        ParseOutcome first = parse(marker);
        ParseOutcome second = parse(marker.deepCopy());

        assertThat(first.result()).isInstanceOf(ParseResult.Quarantined.class);
        assertThat(((ParseResult.Quarantined) first.result()).reason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_UNKNOWN_ENVELOPE);
        assertThat(BqJobsRunProblem.isMarker(marker)).isTrue();
        assertThat(MAPPER.quarantine(first).orElseThrow().getQuarantineId())
                .isEqualTo(MAPPER.quarantine(second).orElseThrow().getQuarantineId());
        assertThat(MAPPER.quarantine(first).orElseThrow().getRawExcerpt()).contains("COLUMN_DRIFT").contains("referenced_tables");
    }

    @Test
    void aColumnLostMarkerIsQuarantinedAsAnUnknownEnvelopeAndNamesTheLostColumns() {
        ObjectNode marker = BqJobsRunProblem.columnLost("US", List.of("cache_hit", "error_result"), "a".repeat(64), "b".repeat(64));
        ParseOutcome first = parse(marker);
        ParseOutcome second = parse(marker.deepCopy());

        assertThat(BqJobsRunProblem.isMarker(marker)).isTrue();
        assertThat(((ParseResult.Quarantined) first.result()).reason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_UNKNOWN_ENVELOPE);
        assertThat(MAPPER.quarantine(first).orElseThrow().getQuarantineId())
                .isEqualTo(MAPPER.quarantine(second).orElseThrow().getQuarantineId());
        assertThat(MAPPER.quarantine(first).orElseThrow().getRawExcerpt()).contains("COLUMN_LOST").contains("cache_hit")
                .contains("error_result");
    }

    @Test
    void theLoadBearingOptionalColumnsAreKeptNotRequiredAndLossIsCountedOnlyAgainstWhatWasSeen() {
        assertThat(BqJobsFormat.KEPT_COLUMNS).containsAll(BqJobsFormat.LOAD_BEARING_OPTIONAL_COLUMNS);
        assertThat(BqJobsFormat.REQUIRED_COLUMNS).doesNotContainAnyElementsOf(BqJobsFormat.LOAD_BEARING_OPTIONAL_COLUMNS);
        assertThat(BqJobsFormat.LOAD_BEARING_OPTIONAL_COLUMNS).containsExactlyInAnyOrder("cache_hit", "statement_type",
                "error_result", "destination_table", "parent_job_id");
        assertThat(BqJobsFormat.presentLoadBearing(List.of("JOB_ID", "Cache_Hit", "query"))).containsExactly("cache_hit");
        assertThat(BqJobsFormat.lostLoadBearing(List.of("cache_hit", "statement_type"), List.of("job_id", "STATEMENT_TYPE")))
                .containsExactly("cache_hit");
        assertThat(BqJobsFormat.lostLoadBearing(List.of(), List.of("job_id"))).isEmpty();
    }

    // ------------------------------------------------------------------
    // The mapping and the constants agree
    // ------------------------------------------------------------------

    @Test
    void theRequiredColumnsAreTheCoverageSpecsAndAllAreKnownColumns() {
        assertThat(BqJobsFormat.REQUIRED_COLUMNS).containsExactly("job_id", "project_id", "creation_time", "end_time",
                "user_email", "job_type", "state", "referenced_tables");
        assertThat(BqJobsFormat.KEPT_COLUMNS).containsAll(BqJobsFormat.REQUIRED_COLUMNS);
        assertThat(BqJobsFormat.KEPT_COLUMNS).doesNotContainAnyElementsOf(BqJobsFormat.DROPPED_COLUMNS);
        assertThat(BqJobsFormat.IGNORED_COLUMNS).doesNotContainAnyElementsOf(BqJobsFormat.KEPT_COLUMNS);
    }

    @Test
    void columnSetHashIgnoresOrderAndCaseAndSeesAnAddedColumn() {
        List<String> a = List.of("job_id", "project_id", "Query");
        List<String> b = List.of("query", "JOB_ID", "project_id");
        List<String> added = List.of("job_id", "project_id", "Query", "edition");
        assertThat(BqJobsFormat.columnSetHash(a)).isEqualTo(BqJobsFormat.columnSetHash(b));
        assertThat(BqJobsFormat.columnSetHash(a)).isNotEqualTo(BqJobsFormat.columnSetHash(added));
        assertThat(BqJobsFormat.missingRequired(List.of("job_id", "project_id"))).contains("end_time", "referenced_tables")
                .doesNotContain("job_id");
    }

    @Test
    void everyPathTheMappingReadsIsAColumnTheParserKnows() {
        var effective = MAPPING.envelope(BqJobsFormat.ENVELOPE).orElseThrow().effective(BqJobsFormat.VERSION).orElseThrow();
        List<String> known = BqJobsParser.knownColumns().stream().map(p -> p.pointer()).toList();
        assertThat(effective.declaredPaths()).isNotEmpty();
        for (var path : effective.declaredPaths()) {
            assertThat(known).as("mapping.yaml reads %s", path.pointer()).contains(path.pointer());
        }
    }
}
