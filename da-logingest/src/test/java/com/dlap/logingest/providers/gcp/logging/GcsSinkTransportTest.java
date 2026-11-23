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
import com.dlap.logingest.parse.gcpaudit.GcpAuditFormat;
import com.dlap.logingest.parse.gcpaudit.GcpAuditRegistryForTests;
import com.dlap.logingest.transport.CursorPosition;
import com.dlap.logingest.transport.FetchWindow;
import com.dlap.logingest.transport.ObjectReadException;
import com.dlap.logingest.transport.RawBatch;
import com.dlap.logingest.transport.RawBatchIterator;
import com.dlap.logingest.transport.StreamContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/**
 * The Cloud Storage sink transport against an in-memory bucket laid out the way a Log Router sink
 * writes it: hourly {@code _Sn} shards, late {@code _An:<unix seconds>} addenda, JSON arrays and
 * newline-delimited files, gzip, objects already processed.
 */
class GcsSinkTransportTest {

    private static final String DATA_ACCESS = "cloudaudit.googleapis.com/data_access";
    private static final FetchWindow WINDOW = new FetchWindow(Instant.parse("2026-09-01T10:00:00Z"),
            Instant.parse("2026-09-01T12:00:00Z"));

    /** An in-memory bucket. */
    static final class FakeBucket implements GcsSinkTransport.ShardStore {
        final Map<String, byte[]> objects = new LinkedHashMap<>();
        final List<String> listed = new ArrayList<>();
        final List<String> read = new ArrayList<>();

        FakeBucket put(String name, byte[] bytes) {
            objects.put(name, bytes);
            return this;
        }

        FakeBucket put(String name, String text) {
            return put(name, text.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public List<GcsSinkTransport.ShardObject> list(StreamContext context, String bucket, String prefix) {
            listed.add(bucket + "/" + prefix);
            return objects.entrySet().stream().filter(e -> e.getKey().startsWith(prefix))
                    .map(e -> new GcsSinkTransport.ShardObject(e.getKey(), "7", e.getValue().length)).toList();
        }

        @Override
        public byte[] read(StreamContext context, String bucket, GcsSinkTransport.ShardObject object) {
            read.add(object.name());
            return objects.get(object.name());
        }
    }

    private static String line(String insertId, String time) {
        return "{\"insertId\":\"" + insertId + "\",\"resource\":{\"type\":\"gcs_bucket\",\"labels\":{"
                + "\"bucket_name\":\"dlap-fixture-bucket\",\"project_id\":\"proj-0d1a0001\"}},"
                + "\"logName\":\"projects/proj-0d1a0001/logs/"
                + "cloudaudit.googleapis.com%2Fdata_access\",\"timestamp\":\"" + time + "\",\"protoPayload\":{"
                + "\"@type\":\"type.googleapis.com/google.cloud.audit.AuditLog\",\"serviceName\":\"storage.googleapis.com\","
                + "\"methodName\":\"storage.objects.get\",\"resourceName\":\"projects/_/buckets/dlap-fixture-bucket/objects/"
                + insertId + ".csv\",\"authenticationInfo\":{\"principalEmail\":\"analyst@example.com\"}}}";
    }

    private static StreamContext ctx(Map<String, String> location, Set<String> done) {
        Map<String, String> loc = new LinkedHashMap<>(location);
        loc.putIfAbsent("bucket", "dlap-fixture-sink");
        return GcpTransportsForTests.context(GcsSinkTransport.ID, GcpAuditFormat.JSON, "proj-0d1a0001", loc, done);
    }

    private static List<RawBatch> drain(RawBatchIterator it) {
        List<RawBatch> out = new ArrayList<>();
        while (it.hasNext()) {
            out.add(it.next());
        }
        return out;
    }

    @Test
    void shardNamesParseForRegularAndAddendumShards() {
        String folder = GcsSinkTransport.folder("org-sink", DATA_ACCESS);
        assertThat(folder).isEqualTo("org-sink/cloudaudit.googleapis.com/data_access/");
        Optional<GcsSinkTransport.Shard> s = GcsSinkTransport.parse(new GcsSinkTransport.ShardObject(
                folder + "2026/09/01/10:00:00_10:59:59_S0.json", "1", 10), folder);
        assertThat(s).hasValueSatisfying(x -> {
            assertThat(x.start()).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
            assertThat(x.end()).isEqualTo(Instant.parse("2026-09-01T10:59:59Z"));
            assertThat(x.addendum()).isFalse();
            assertThat(x.number()).isZero();
        });
        Optional<GcsSinkTransport.Shard> a = GcsSinkTransport.parse(new GcsSinkTransport.ShardObject(
                folder + "2026/09/01/10:00:00_10:59:59_A2:1788256800.json.gz", "1", 10), folder);
        assertThat(a).hasValueSatisfying(x -> {
            assertThat(x.addendum()).isTrue();
            assertThat(x.number()).isEqualTo(2);
            assertThat(x.writtenAt()).isEqualTo(1788256800L);
        });
        assertThat(GcsSinkTransport.parse(new GcsSinkTransport.ShardObject(folder + "2026/09/01/readme.txt", "1", 1),
                folder)).isEmpty();
        assertThat(GcsSinkTransport.parse(new GcsSinkTransport.ShardObject(
                "other/2026/09/01/10:00:00_10:59:59_S0.json", "1", 1), folder)).isEmpty();
        assertThat(GcsSinkTransport.folder("/", DATA_ACCESS)).isEqualTo(DATA_ACCESS + "/");
    }

    @Test
    void partitionsAreTheFourAuditLogsOrTheConfiguredLogIds() {
        GcsSinkTransport t = new GcsSinkTransport(new FakeBucket());
        assertThat(t.partitions(ctx(Map.of(), Set.of()))).containsExactly("cloudaudit.googleapis.com/activity",
                DATA_ACCESS, "cloudaudit.googleapis.com/system_event", "cloudaudit.googleapis.com/policy");
        assertThat(t.partitions(ctx(Map.of("log-ids", " cloudaudit.googleapis.com/activity , "), Set.of())))
                .containsExactly("cloudaudit.googleapis.com/activity");
    }

    @Test
    void arrayNdjsonAndGzipShardsAreReadOldestHourFirstAndAnAddendumAfterItsShard() throws IOException {
        String dir = DATA_ACCESS + "/2026/09/01/";
        FakeBucket bucket = new FakeBucket()
                .put(dir + "11:00:00_11:59:59_S0.json", line("n1", "2026-09-01T11:10:00Z") + "\n"
                        + line("n2", "2026-09-01T11:20:00Z") + "\n")
                .put(dir + "10:00:00_10:59:59_A0:1788260000.json", gzip("[" + line("late", "2026-09-01T10:30:00Z") + "]"))
                .put(dir + "10:00:00_10:59:59_S0.json", "[" + line("a1", "2026-09-01T10:01:00Z") + ","
                        + line("a2", "2026-09-01T10:02:00Z") + "]")
                .put(dir + "13:00:00_13:59:59_S0.json", "[" + line("after", "2026-09-01T13:01:00Z") + "]");
        GcsSinkTransport t = new GcsSinkTransport(bucket);
        List<RawBatch> batches = drain(t.fetch(ctx(Map.of(), Set.of()), DATA_ACCESS,
                new CursorPosition(null, null, Map.of()), WINDOW));

        assertThat(batches).hasSize(4);
        assertThat(batches.subList(0, 3)).extracting(b -> b.object().orElseThrow().key()).containsExactly(
                dir + "10:00:00_10:59:59_S0.json", dir + "10:00:00_10:59:59_A0:1788260000.json",
                dir + "11:00:00_11:59:59_S0.json");
        assertThat(batches.get(0).records()).hasSize(2);
        assertThat(batches.get(1).records()).hasSize(1);
        assertThat(batches.get(2).records()).hasSize(2);
        assertThat(batches.get(0).records()).allSatisfy(r -> {
            assertThat(r.envelope()).isEqualTo(GcpAuditFormat.JSON);
            assertThat(r.ref().uri()).isEqualTo("gs://dlap-fixture-sink/" + dir + "10:00:00_10:59:59_S0.json");
            assertThat(r.ref().cursor()).isEqualTo("7");
        });
        assertThat(batches.get(2).advanceTo().lowWatermark()).isEqualTo(Instant.parse("2026-09-01T11:59:59Z"));
        RawBatch last = batches.get(3);
        assertThat(last.records()).isEmpty();
        assertThat(last.object()).isEmpty();
        assertThat(last.advanceTo().lowWatermark()).isEqualTo(WINDOW.to());
        assertThat(bucket.read).doesNotContain(dir + "13:00:00_13:59:59_S0.json");

        List<String> outcomes = new ArrayList<>();
        for (RawBatch b : batches) {
            for (RawRecord r : b.records()) {
                outcomes.add(GcpAuditRegistryForTests.registry()
                        .parse(r, BindingConfig.auto("gcp-test", GcpAuditFormat.SOURCE_KIND)).outcomeCode());
            }
        }
        assertThat(outcomes).hasSize(5).containsOnly("PARSED");
    }

    @Test
    void theLateShardLookbackListsThePreviousDayForAddendaOfItsLastHours() {
        String yesterday = DATA_ACCESS + "/2026/08/31/";
        FakeBucket bucket = new FakeBucket()
                .put(yesterday + "23:00:00_23:59:59_A0:1788220000.json", "[" + line("late", "2026-08-31T23:30:00Z") + "]");
        FetchWindow midnight = new FetchWindow(Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T01:00:00Z"));

        List<RawBatch> defaults = drain(new GcsSinkTransport(bucket).fetch(ctx(Map.of(), Set.of()), DATA_ACCESS,
                new CursorPosition(null, null, Map.of()), midnight));
        assertThat(bucket.listed).contains("dlap-fixture-sink/" + yesterday);
        assertThat(defaults.get(0).records()).hasSize(1);

        FakeBucket other = new FakeBucket().put(yesterday + "23:00:00_23:59:59_A0:1788220000.json", "[]");
        drain(new GcsSinkTransport(other).fetch(ctx(Map.of("late-shard-lookback", "PT0S"), Set.of()), DATA_ACCESS,
                new CursorPosition(null, null, Map.of()), midnight));
        assertThat(other.listed).containsExactly("dlap-fixture-sink/" + DATA_ACCESS + "/2026/09/01/");
    }

    @Test
    void anObjectAlreadyProcessedInThisGenerationIsSkipped() {
        String dir = DATA_ACCESS + "/2026/09/01/";
        FakeBucket bucket = new FakeBucket()
                .put(dir + "10:00:00_10:59:59_S0.json", "[" + line("a1", "2026-09-01T10:01:00Z") + "]")
                .put(dir + "11:00:00_11:59:59_S0.json", "[" + line("b1", "2026-09-01T11:01:00Z") + "]");
        List<RawBatch> batches = drain(new GcsSinkTransport(bucket).fetch(
                ctx(Map.of(), Set.of(dir + "10:00:00_10:59:59_S0.json@7")), DATA_ACCESS,
                new CursorPosition(null, null, Map.of()), WINDOW));
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0).object().orElseThrow().key()).isEqualTo(dir + "11:00:00_11:59:59_S0.json");
        assertThat(bucket.read).containsExactly(dir + "11:00:00_11:59:59_S0.json");
    }

    @Test
    void anObjectAboveTheSizeLimitIsSkippedAndAGzipBombIsRefused() throws IOException {
        String dir = DATA_ACCESS + "/2026/09/01/";
        FakeBucket big = new FakeBucket().put(dir + "10:00:00_10:59:59_S0.json", "[" + line("a1", "2026-09-01T10:01:00Z") + "]");
        List<RawBatch> skipped = drain(new GcsSinkTransport(big).fetch(ctx(Map.of("max-object-bytes", "10"), Set.of()),
                DATA_ACCESS, new CursorPosition(null, null, Map.of()), WINDOW));
        assertThat(skipped).singleElement().satisfies(b -> assertThat(b.records()).isEmpty());
        assertThat(big.read).isEmpty();

        FakeBucket bomb = new FakeBucket().put(dir + "10:00:00_10:59:59_S0.json.gz", gzip(" ".repeat(10_000)));
        RawBatchIterator it = new GcsSinkTransport(bomb).fetch(ctx(Map.of("max-object-bytes", "1000"), Set.of()),
                DATA_ACCESS, new CursorPosition(null, null, Map.of()), WINDOW);
        assertThatThrownBy(it::next).isInstanceOf(ObjectReadException.class)
                .hasMessageContaining("unpacks to more than max-object-bytes");
    }

    @Test
    void aShardThatCannotBeReadNamesItsObjectAndTheIteratorIsPositionedPastIt() throws IOException {
        String dir = DATA_ACCESS + "/2026/09/01/";
        String badName = dir + "10:00:00_10:59:59_S0.json.gz";
        FakeBucket bucket = new FakeBucket()
                .put(badName, gzip(" ".repeat(10_000)))
                .put(dir + "11:00:00_11:59:59_S0.json", "[" + line("b1", "2026-09-01T11:01:00Z") + "]");
        RawBatchIterator it = new GcsSinkTransport(bucket).fetch(ctx(Map.of("max-object-bytes", "1000"), Set.of()),
                DATA_ACCESS, new CursorPosition(null, null, Map.of()), WINDOW);

        assertThat(it.hasNext()).isTrue();
        assertThatThrownBy(it::next).isInstanceOfSatisfying(ObjectReadException.class, e -> {
            assertThat(e.object().key()).isEqualTo(badName);
            assertThat(e.object().etag()).isEqualTo("7");
            assertThat(e.uri()).isEqualTo("gs://dlap-fixture-sink/" + badName);
        });
        RawBatch next = it.next();
        assertThat(next.object().orElseThrow().key()).isEqualTo(dir + "11:00:00_11:59:59_S0.json");
        assertThat(next.records()).hasSize(1);
    }

    @Test
    void theBucketIsRequiredAndBadNumbersFallBack() {
        StreamContext noBucket = GcpTransportsForTests.context(GcsSinkTransport.ID, GcpAuditFormat.JSON, "proj-0d1a0001",
                Map.of(), Set.of());
        assertThatThrownBy(() -> new GcsSinkTransport(new FakeBucket()).fetch(noBucket, DATA_ACCESS,
                new CursorPosition(null, null, Map.of()), WINDOW)).hasMessageContaining("bucket");
        assertThat(GcsSinkTransport.duration("P2D", Duration.ZERO)).isEqualTo(Duration.ofDays(2));
        assertThat(GcsSinkTransport.duration("-PT1H", Duration.ofDays(1))).isEqualTo(Duration.ofDays(1));
        assertThat(GcsSinkTransport.duration("tomorrow", Duration.ofDays(1))).isEqualTo(Duration.ofDays(1));
        assertThat(GcsSinkTransport.bytes("0", 5)).isEqualTo(5);
        assertThat(GcsSinkTransport.bytes("42", 5)).isEqualTo(42);
    }

    /** Every record of every positive json fixture case, read through a sink shard, parses to the same events as its line. */
    @Test
    void everyJsonFixtureCaseReadThroughAShardParsesLikeTheCaseItself() {
        String dir = DATA_ACCESS + "/2026/09/01/";
        int n = 0;
        int records = 0;
        for (FixtureCase c : FixtureCatalog.fromClasspath().cases(GcpAuditFormat.FAMILY)) {
            if (c.negative() || !c.envelope().equals(GcpAuditFormat.JSON)) {
                continue;
            }
            List<String> lines = GcpTransportsForTests.records(c);
            String array = "[" + String.join(",", lines) + "]";
            FakeBucket bucket = new FakeBucket().put(dir + "10:00:00_10:59:59_S" + n++ + ".json", array);
            List<RawBatch> batches = drain(new GcsSinkTransport(bucket).fetch(ctx(Map.of(), Set.of()), DATA_ACCESS,
                    new CursorPosition(null, null, Map.of()), WINDOW));
            List<RawRecord> read = batches.get(0).records();
            assertThat(read).as(c.id()).hasSize(lines.size());
            BindingConfig binding = BindingConfig.auto("gcp-test", GcpAuditFormat.SOURCE_KIND);
            for (int i = 0; i < read.size(); i++) {
                RawRecord direct = RawRecord.builder().bindingId("gcp-test").sourceKind(GcpAuditFormat.SOURCE_KIND)
                        .envelope(GcpAuditFormat.JSON).ref(read.get(i).ref()).fetchedAt(GcpTransportsForTests.NOW)
                        .text(lines.get(i)).build();
                ParseOutcome viaShard = GcpAuditRegistryForTests.registry().parse(read.get(i), binding);
                ParseOutcome viaLine = GcpAuditRegistryForTests.registry().parse(direct, binding);
                assertThat(viaShard.outcomeCode()).as(c.id()).isEqualTo(viaLine.outcomeCode()).doesNotStartWith("QUARANTINED");
                if (viaLine.result() instanceof ParseResult.Parsed p) {
                    assertThat(((ParseResult.Parsed) viaShard.result()).events()).as(c.id())
                            .usingRecursiveFieldByFieldElementComparatorIgnoringFields("raw").isEqualTo(p.events());
                }
                records++;
            }
        }
        assertThat(records).as("json records read through a shard").isGreaterThan(100);
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream z = new GZIPOutputStream(out)) {
            z.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }
}
