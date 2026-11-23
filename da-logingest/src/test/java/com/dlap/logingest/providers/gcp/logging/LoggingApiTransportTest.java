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

package com.dlap.logingest.providers.gcp.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.fixtures.FixtureCase;
import com.dlap.fixtures.FixtureCatalog;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.parse.gcpaudit.GcpAuditFormat;
import com.dlap.logingest.parse.gcpaudit.GcpAuditRegistryForTests;
import com.dlap.logingest.transport.CursorPosition;
import com.dlap.logingest.transport.FetchWindow;
import com.dlap.logingest.transport.RawBatch;
import com.dlap.logingest.transport.RawBatchIterator;
import com.dlap.logingest.transport.StreamContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.audit.AuditLog;
import com.google.logging.v2.LogEntry;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import com.google.rpc.ErrorDetailsProto;
import com.google.rpc.Status;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The Cloud Logging API transport: the window filter, paging and cursor, the per-project limiter, and
 * the section 6 printing of the API's {@code LogEntry} into the {@code api-proto} envelope, checked
 * against every positive api-proto fixture record.
 */
class LoggingApiTransportTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final FetchWindow WINDOW = new FetchWindow(Instant.parse("2026-09-01T10:00:00Z"),
            Instant.parse("2026-09-01T11:00:00Z"));
    private static final JsonFormat.TypeRegistry TYPES = JsonFormat.TypeRegistry.newBuilder()
            .add(AuditLog.getDescriptor()).add(ErrorDetailsProto.getDescriptor().getMessageTypes()).build();

    /** A pager that serves fixed pages and records each request. */
    static final class FakePager implements LoggingApiTransport.EntriesPager {
        final List<LoggingApiTransport.Page> pages;
        final List<String> requests = new ArrayList<>();

        FakePager(List<LoggingApiTransport.Page> pages) {
            this.pages = pages;
        }

        @Override
        public LoggingApiTransport.Page page(StreamContext context, String project, String filter, String pageToken,
                                             int pageSize) {
            requests.add(project + "|" + filter + "|" + pageToken + "|" + pageSize);
            return pages.get(requests.size() - 1);
        }
    }

    private static LogEntry entry(String insertId, Instant at) {
        AuditLog audit = AuditLog.newBuilder().setServiceName("storage.googleapis.com")
                .setMethodName("storage.objects.get")
                .setResourceName("projects/_/buckets/dlap-fixture-bucket/objects/a.csv").build();
        return LogEntry.newBuilder().setInsertId(insertId)
                .setLogName("projects/proj-0d1a0001/logs/cloudaudit.googleapis.com%2Fdata_access")
                .setTimestamp(Timestamp.newBuilder().setSeconds(at.getEpochSecond()).setNanos(at.getNano()))
                .setProtoPayload(Any.pack(audit)).build();
    }

    @Test
    void theFilterReadsTheAuditLogsOfTheWindowPlusTheStreamClause() {
        assertThat(LoggingApiTransport.filter(ctx(Map.of()), WINDOW)).isEqualTo("logName:\"cloudaudit.googleapis.com\""
                + " AND timestamp>=\"2026-09-01T10:00:00Z\" AND timestamp<\"2026-09-01T11:00:00Z\"");
        assertThat(LoggingApiTransport.filter(ctx(Map.of("filter", "protoPayload.serviceName=\"bigquery.googleapis.com\"")),
                WINDOW)).endsWith(" AND (protoPayload.serviceName=\"bigquery.googleapis.com\")");
    }

    @Test
    void theProjectComesFromTheLocationOrTheBindingScopeAndIsRequired() {
        assertThat(LoggingApiTransport.project(ctx(Map.of()))).isEqualTo("proj-0d1a0001");
        assertThat(LoggingApiTransport.project(ctx(Map.of("project", "proj-0d1a0002")))).isEqualTo("proj-0d1a0002");
        assertThatThrownBy(() -> LoggingApiTransport.project(GcpTransportsForTests.context(LoggingApiTransport.ID,
                GcpAuditFormat.API_PROTO, null, Map.of(), Set.of()))).hasMessageContaining("has no project");
    }

    @Test
    void thePageSizeDefaultsAndIsClamped() {
        assertThat(LoggingApiTransport.pageSize(ctx(Map.of()))).isEqualTo(1000);
        assertThat(LoggingApiTransport.pageSize(ctx(Map.of("page-size", "50000")))).isEqualTo(10_000);
        assertThat(LoggingApiTransport.pageSize(ctx(Map.of("page-size", "0")))).isEqualTo(1);
        assertThat(LoggingApiTransport.pageSize(ctx(Map.of("page-size", "many")))).isEqualTo(1000);
    }

    @Test
    void eachPageIsOneBatchAndTheLastMovesTheLowWatermarkToTheWindowEnd() {
        Instant t1 = Instant.parse("2026-09-01T10:05:00.123456789Z");
        Instant t2 = Instant.parse("2026-09-01T10:20:00Z");
        FakePager pager = new FakePager(List.of(
                new LoggingApiTransport.Page(List.of(entry("e1", t1), entry("e2", t2)), "tok-2"),
                new LoggingApiTransport.Page(List.of(entry("e3", t2)), "")));
        List<Duration> sleeps = new ArrayList<>();
        RequestRateLimiterTest.TestClock clock = new RequestRateLimiterTest.TestClock();
        RequestRateLimiter oneAMinute = new RequestRateLimiter(1, Duration.ofMinutes(1), clock, d -> {
            sleeps.add(d);
            clock.advance(d);
        });
        LoggingApiTransport transport = new LoggingApiTransport(pager, oneAMinute);
        RawBatchIterator it = transport.fetch(ctx(Map.of("page-size", "2")), "default",
                new CursorPosition(null, null, Map.of()), WINDOW);

        RawBatch first = it.next();
        assertThat(first.records()).hasSize(2);
        assertThat(first.records()).extracting(RawRecord::ref).extracting(RawRef::cursor).containsOnly("");
        assertThat(first.advanceTo().lowWatermark()).isEqualTo(t2);
        assertThat(first.advanceTo().state()).containsEntry("pageToken", "tok-2");
        assertThat(it.hasNext()).isTrue();

        RawBatch second = it.next();
        assertThat(second.records()).singleElement().satisfies(r -> {
            assertThat(r.ref().cursor()).isEqualTo("tok-2");
            assertThat(r.ref().recordIndex()).isEqualTo(2);
            assertThat(r.envelope()).isEqualTo(GcpAuditFormat.API_PROTO);
        });
        assertThat(second.advanceTo().lowWatermark()).isEqualTo(WINDOW.to());
        assertThat(second.advanceTo().highWatermark()).isEqualTo(t2);
        assertThat(it.hasNext()).isFalse();

        assertThat(pager.requests).extracting(r -> r.split("\\|", -1)[2]).containsExactly("", "tok-2");
        assertThat(pager.requests).allSatisfy(r -> assertThat(r).startsWith("proj-0d1a0001|").endsWith("|2"));
        assertThat(sleeps).as("the second request waited for the project's quota").containsExactly(Duration.ofMinutes(1));
    }

    @Test
    void anEmptyWindowEndsInOneEmptyBatchAtTheWindowEnd() {
        LoggingApiTransport transport = new LoggingApiTransport(
                new FakePager(List.of(new LoggingApiTransport.Page(List.of(), ""))),
                new RequestRateLimiter(60, Duration.ofMinutes(1), new RequestRateLimiterTest.TestClock(), d -> { }));
        RawBatchIterator it = transport.fetch(ctx(Map.of()), "default", new CursorPosition(null, null, Map.of()), WINDOW);
        RawBatch only = it.next();
        assertThat(only.records()).isEmpty();
        assertThat(only.advanceTo().lowWatermark()).isEqualTo(WINDOW.to());
        assertThat(it.hasNext()).isFalse();
    }

    @Test
    void aPayloadThatIsNotAnAuditLogIsKeptOpaqueSoTheParserQuarantinesIt() throws IOException {
        LogEntry e = entry("e1", Instant.parse("2026-09-01T10:05:00Z")).toBuilder()
                .setProtoPayload(Any.pack(Status.newBuilder().setCode(7).build())).build();
        JsonNode printed = JSON.readTree(LogEntryJson.print(e));
        assertThat(printed.path("protoPayload").path("typeUrl").asText()).isEqualTo("type.googleapis.com/google.rpc.Status");
        assertThat(printed.path("protoPayload").has("base64")).isTrue();
        assertThat(parse(printed).outcomeCode()).startsWith("QUARANTINED:");
    }

    /**
     * Every positive api-proto fixture record, rebuilt as the {@code LogEntry} the API returns and printed
     * by the transport, parses to the same events as the fixture record itself.
     */
    @Test
    void theTransportPrintsEveryApiProtoFixtureRecordSoItParsesToTheSameEvents() throws IOException {
        int records = 0;
        int withServiceData = 0;
        for (FixtureCase c : FixtureCatalog.fromClasspath().cases(GcpAuditFormat.FAMILY)) {
            if (c.negative() || !c.envelope().equals(GcpAuditFormat.API_PROTO) || c.id().contains("unknown-fields")) {
                continue;
            }
            for (String line : GcpTransportsForTests.records(c)) {
                JsonNode fixture = JSON.readTree(line);
                LogEntry entry = toApiEntry((ObjectNode) fixture.deepCopy());
                withServiceData += entry.getProtoPayload().unpack(AuditLog.class).hasServiceData() ? 1 : 0;
                JsonNode printed = JSON.readTree(LogEntryJson.print(entry));
                ParseOutcome expected = parse(fixture);
                ParseOutcome actual = parse(printed);
                assertThat(actual.outcomeCode()).as(c.id()).isEqualTo(expected.outcomeCode());
                assertThat(actual.detected()).as(c.id()).isEqualTo(expected.detected());
                if (expected.result() instanceof ParseResult.Parsed p) {
                    assertThat(((ParseResult.Parsed) actual.result()).events()).as(c.id())
                            .usingRecursiveFieldByFieldElementComparatorIgnoringFields("raw")
                            .isEqualTo(p.events());
                }
                records++;
            }
        }
        assertThat(records).as("api-proto records checked").isGreaterThan(100);
        assertThat(withServiceData).as("records with a legacy serviceData Any").isGreaterThan(5);
    }

    /** Rebuilds the API's message from a fixture record: the opaque Any values go back to their bytes. */
    private static LogEntry toApiEntry(ObjectNode record) throws IOException {
        ObjectNode pp = (ObjectNode) record.get("protoPayload");
        if (!pp.has("@type")) {
            // The API always types the payload; a fixture without @type (the google-cloudevents shape) is an AuditLog.
            ObjectNode typed = JSON.createObjectNode().put("@type", "type.googleapis.com/google.cloud.audit.AuditLog");
            typed.setAll(pp);
            record.set("protoPayload", typed);
            pp = typed;
        }
        Any serviceData = null;
        List<Any> opaqueDetails = new ArrayList<>();
        if (pp.path("serviceData").has("typeUrl")) {
            serviceData = any(pp.get("serviceData"));
            pp.remove("serviceData");
        }
        if (pp.path("status").path("details") instanceof ArrayNode details) {
            ArrayNode kept = JSON.createArrayNode();
            for (JsonNode d : details) {
                if (d.has("typeUrl")) {
                    opaqueDetails.add(any(d));
                } else {
                    kept.add(d);
                }
            }
            ((ObjectNode) pp.get("status")).set("details", kept);
        }
        LogEntry.Builder b = LogEntry.newBuilder();
        JsonFormat.parser().usingTypeRegistry(TYPES).merge(JSON.writeValueAsString(record), b);
        if (serviceData != null || !opaqueDetails.isEmpty()) {
            AuditLog.Builder audit = b.getProtoPayload().unpack(AuditLog.class).toBuilder();
            if (serviceData != null) {
                audit.setServiceData(serviceData);
            }
            opaqueDetails.forEach(d -> audit.getStatusBuilder().addDetails(d));
            b.setProtoPayload(Any.pack(audit.build()));
        }
        return b.build();
    }

    private static Any any(JsonNode opaque) {
        return Any.newBuilder().setTypeUrl(opaque.path("typeUrl").asText())
                .setValue(ByteString.copyFrom(Base64.getDecoder().decode(opaque.path("base64").asText()))).build();
    }

    private static ParseOutcome parse(JsonNode json) {
        BindingConfig binding = BindingConfig.auto("gcp-test", GcpAuditFormat.SOURCE_KIND);
        RawRecord r = RawRecord.builder().bindingId("gcp-test").sourceKind(GcpAuditFormat.SOURCE_KIND)
                .envelope(GcpAuditFormat.API_PROTO).ref(RawRef.of(LoggingApiTransport.ID, "logging://test", 0))
                .fetchedAt(GcpTransportsForTests.NOW).json(json).build();
        return GcpAuditRegistryForTests.registry().parse(r, binding);
    }

    private static StreamContext ctx(Map<String, String> location) {
        return GcpTransportsForTests.context(LoggingApiTransport.ID, GcpAuditFormat.API_PROTO, "proj-0d1a0001",
                location, Set.of());
    }
}
