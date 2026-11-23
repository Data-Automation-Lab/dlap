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

package com.dlap.logingest.providers.aws.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventSemantics;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.health.BindingStatusTopicPublisher;
import com.dlap.logingest.kafka.LogEventPublisher;
import com.dlap.logingest.quarantine.QuarantineTopicPublisher;
import com.dlap.logingest.repository.LogIngestPostgres;
import com.dlap.logingest.service.registry.RegistryIngestService;
import com.dlap.logingest.transport.StreamContext;
import com.dlap.logingest.transport.StreamPollScheduler;
import com.dlap.logingest.transport.StreamRunner;
import com.dlap.logingest.transport.TransportRegistry;
import com.dlap.logingest.transport.cursor.StreamCursorStore;
import com.dlap.logingest.transport.lock.AdvisoryLocks;
import com.dlap.logingest.transport.objects.ProcessedObjectStore;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.PrincipalType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * AWS-01 end to end on LocalStack S3 (community edition, {@code localstack/localstack:3.4} pinned by digest in
 * {@link LocalStackImage}, which also says how to pre-pull it) and
 * PostgreSQL (Testcontainers; requires Docker). The service starts with the registry pipeline, the
 * transport switch on and a bindings file that names an {@code S3_TRAIL_FILES} stream of
 * {@code AWS_CLOUDTRAIL} and an {@code S3_SERVER_ACCESS_LOGS} stream of
 * {@code AWS_S3_SERVER_ACCESS_LOG}, both with the LocalStack endpoint, under strict validation. The
 * transports take their credentials from the SDK default chain (the {@code aws.accessKeyId} system
 * properties here). Kafka is not started: the publisher is a mock whose arguments are the exact
 * key and wire map the Kafka publisher sends to {@code dlap.log.event.normalized}.
 *
 * <p>da-discovery routes a normalized event onto {@code dlap.discovery.asset.deleted} by its change
 * type {@code DELETE}, keyed by {@code LogEventSemantics.partitionKey}
 * ({@code DiscoveryLogEventProcessor.topicForChangeType}, pinned for these event shapes by
 * da-discovery's {@code ProviderLifecycleDeleteRoutingTest}); this test asserts those inputs on the
 * published events. The live hop through da-discovery needs the compose stack (integrator).
 */
// The bindings file is imported while the environment is prepared, before dynamic properties exist, so
// its path is a fixed one under the module build directory, written before the context starts.
@SpringBootTest(properties = "DLAP_LOGINGEST_SOURCES_FILE=" + AwsS3TransportsLocalStackTest.SOURCES_PATH)
class AwsS3TransportsLocalStackTest {

    /** Where the test writes its bindings file, relative to the module directory Gradle runs tests in. */
    static final String SOURCES_PATH = "build/aws01-localstack/log-sources.yml";

    private static final String ACCOUNT = "123456789012";
    private static final String TRAIL_BUCKET = "dlap-aws01-trail";
    private static final String LOG_BUCKET = "dlap-aws01-access-logs";
    private static final String CT_BINDING = "aws-ct-" + ACCOUNT;
    private static final String SAL_BINDING = "aws-sal-" + ACCOUNT;
    private static final DlapLogEventCodec CODEC = DlapLogEventCodec.defaults();

    @SuppressWarnings("resource")
    private static final GenericContainer<?> LOCALSTACK = new GenericContainer<>(LocalStackImage.name())
            .withEnv("SERVICES", "s3")
            .withExposedPorts(4566)
            .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566).forStatusCode(200));

    private static final DataSource DB = LogIngestPostgres.newDatabase();
    private static final S3Client S3 = start();
    private static final Path SOURCES = sourcesFile();

    private static S3Client start() {
        LOCALSTACK.start();
        // The transports read the SDK default chain; the system properties are one of its sources.
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
        S3Client s3 = S3Client.builder().endpointOverride(endpoint()).region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        s3.createBucket(b -> b.bucket(TRAIL_BUCKET));
        s3.createBucket(b -> b.bucket(LOG_BUCKET));
        return s3;
    }

    private static URI endpoint() {
        return URI.create("http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566));
    }

    private static Path sourcesFile() {
        try {
            Path file = Path.of(SOURCES_PATH).toAbsolutePath();
            Files.createDirectories(file.getParent());
            Files.writeString(file, """
                    dlap:
                      logingest:
                        sources:
                          - id: aws-cloudtrail
                            kind: AWS_CLOUDTRAIL
                            bindings:
                              - id: %1$s
                                scope: { account-id: "%2$s", regions: [us-east-1] }
                                poll-interval: PT1H
                                stream:
                                  transport: S3_TRAIL_FILES
                                  location: { bucket: %3$s, region: us-east-1, endpoint: "%5$s", path-style: "true" }
                          - id: aws-access-logs
                            kind: AWS_S3_SERVER_ACCESS_LOG
                            bindings:
                              - id: %4$s
                                scope: { account-id: "%2$s" }
                                poll-interval: PT1H
                                stream:
                                  transport: S3_SERVER_ACCESS_LOGS
                                  location: { bucket: %6$s, prefix: sal/, region: us-east-1, endpoint: "%5$s", path-style: "true" }
                    """.formatted(CT_BINDING, ACCOUNT, TRAIL_BUCKET, SAL_BINDING, endpoint(), LOG_BUCKET));
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> LogIngestPostgres.url(DB));
        r.add("spring.datasource.username", LogIngestPostgres::user);
        r.add("spring.datasource.password", LogIngestPostgres::password);
        r.add("dlap.logingest.pipeline", () -> "registry");
        r.add("dlap.logingest.config-validation", () -> "strict");
        r.add(AwsS3TransportsConfiguration.ENABLED, () -> "true");
        r.add("dlap.logingest.health.status-initial-delay", () -> "PT24H");
        r.add("dlap.logingest.metrics.snapshot-initial-delay", () -> "PT24H");
        r.add("KAFKA_BOOTSTRAP_SERVERS", () -> "localhost:1");
        r.add("KEYCLOAK_AUTH_SERVER_URL", () -> "http://localhost:1");
        r.add("logging.level.org.apache.kafka", () -> "ERROR");
    }

    @AfterAll
    static void stop() {
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
        S3.close();
        LOCALSTACK.stop();
    }

    @MockitoBean
    private LogEventPublisher publisher;

    @MockitoBean
    private QuarantineTopicPublisher quarantineTopic;

    @MockitoBean
    private BindingStatusTopicPublisher statusTopic;

    // The test polls the streams itself.
    @MockitoBean
    private StreamPollScheduler scheduler;

    @Autowired
    private EffectiveLogSources sources;

    @Autowired
    private StreamRunner runner;

    @Autowired
    private TransportRegistry transports;

    @Autowired
    private RegistryIngestService ingest;

    @Autowired
    private StreamCursorStore cursors;

    @Autowired
    private ProcessedObjectStore objects;

    @Autowired
    private AdvisoryLocks locks;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        clearInvocations(publisher, quarantineTopic, statusTopic);
    }

    private EffectiveStream stream(String bindingId) {
        return sources.binding(bindingId).orElseThrow().streams().get(0);
    }

    private static String trailKey(Instant delivered, String unique) {
        LocalDateTime t = LocalDateTime.ofInstant(delivered, ZoneOffset.UTC);
        return "AWSLogs/" + ACCOUNT + "/CloudTrail/us-east-1/" + DateTimeFormatter.ofPattern("yyyy/MM/dd/").format(t)
                + ACCOUNT + "_CloudTrail_us-east-1_" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'").format(t)
                + "_" + unique + ".json.gz";
    }

    private static String accessLogKey(Instant delivered, String unique) {
        return "sal/" + DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
                .format(LocalDateTime.ofInstant(delivered, ZoneOffset.UTC)) + "-" + unique;
    }

    private static void put(String bucket, String key, byte[] bytes) {
        S3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromBytes(bytes));
    }

    private static Instant minutesAgo(long minutes) {
        return Clock.systemUTC().instant().truncatedTo(ChronoUnit.MINUTES).minus(minutes, ChronoUnit.MINUTES);
    }

    /** The events the publisher was handed, decoded, with the Kafka key each was sent under. */
    private List<Map.Entry<String, DlapLogEvent>> published() {
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> wires = ArgumentCaptor.forClass(Map.class);
        verify(publisher, atLeast(0)).publish(keys.capture(), wires.capture());
        List<Map.Entry<String, DlapLogEvent>> out = new ArrayList<>();
        for (int i = 0; i < keys.getAllValues().size(); i++) {
            out.add(Map.entry(keys.getAllValues().get(i), CODEC.decode(wires.getAllValues().get(i))));
        }
        return out;
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void aLifecycleDeleteInATrailFileAndAnExpireLineInAnAccessLogBothPublishAsSystemDeletes() {
        put(TRAIL_BUCKET, trailKey(minutesAgo(20), "E2eTrail00000001"), AwsS3ForTests.gzip(AwsS3ForTests.trailFile(
                AwsS3ForTests.lifecycleDelete("0a5e0c11-1fe0-4e00-8000-0000aa010001", "2026-11-02T03:14:07Z",
                        "raw/2026/09/orders.parquet"))));
        put(LOG_BUCKET, accessLogKey(minutesAgo(20), "AWS01E2E00000001"), (S3AccessLogLineTest.EXPIRE.replace(
                "raw/2026/09/orders.parquet", "raw/2026/10/orders.parquet") + "\n").getBytes(StandardCharsets.UTF_8));

        StreamRunner.PollResult trail = runner.poll(stream(CT_BINDING));
        StreamRunner.PollResult accessLog = runner.poll(stream(SAL_BINDING));

        assertThat(trail.ran()).isTrue();
        assertThat(trail.failed()).isZero();
        assertThat(trail.records()).isEqualTo(1);
        assertThat(accessLog.ran()).isTrue();
        assertThat(accessLog.failed()).isZero();
        assertThat(accessLog.records()).isEqualTo(1);

        List<Map.Entry<String, DlapLogEvent>> events = published();
        assertThat(events).hasSize(2);
        assertThat(events).extracting(e -> e.getValue().getTargetResource().getCanonicalUri())
                .containsExactlyInAnyOrder("s3://dlap-aws-e2e/raw/2026/09/orders.parquet",
                        "s3://dlap-aws-e2e/raw/2026/10/orders.parquet");
        assertThat(events).allSatisfy(e -> {
            DlapLogEvent ev = e.getValue();
            // The inputs of da-discovery's route onto dlap.discovery.asset.deleted.
            assertThat(ev.getChangeType()).isEqualTo(ChangeType.DELETE);
            assertThat(e.getKey()).isEqualTo(LogEventSemantics.partitionKey(ev))
                    .isEqualTo(ev.getTargetResource().getCanonicalUri());
            assertThat(ev.getPrincipal().getType()).isEqualTo(PrincipalType.SYSTEM);
            assertThat(ev.getOrigin()).isEqualTo(Origin.ORIGIN_CLOUD_LOG);
        });
        assertThat(events).extracting(e -> e.getValue().getSourceFormat())
                .containsExactlyInAnyOrder("AWS_CLOUDTRAIL/s3-file", "AWS_S3_SERVER_ACCESS_LOG/cwl-json");
        assertThat(events).extracting(e -> e.getValue().getSourceService())
                .containsExactlyInAnyOrder("s3lifecycle.amazonaws.com", "s3.amazonaws.com");

        // Stored once each, under the configured bindings; both objects are checkpointed.
        assertThat(count("SELECT count(*) FROM dlap_logingest.log_events WHERE binding_id IN (?, ?) "
                + "AND change_type = 'DELETE' AND target_canonical_uri LIKE 's3://dlap-aws-e2e/raw/2026/%'",
                CT_BINDING, SAL_BINDING)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM dlap_logingest.processed_object WHERE binding_id IN (?, ?) "
                + "AND status = 'DONE' AND (object_key LIKE '%E2eTrail00000001%' OR object_key LIKE '%AWS01E2E00000001%')",
                CT_BINDING, SAL_BINDING)).isEqualTo(2);
    }

    @Test
    void aRestartNeitherSkipsNorRepeatsATrailFile() {
        String first = trailKey(minutesAgo(30), "Restart000000001");
        put(TRAIL_BUCKET, first, AwsS3ForTests.gzip(AwsS3ForTests.trailFile(AwsS3ForTests.lifecycleDelete(
                "0a5e0c11-1fe0-4e00-8000-0000aa020001", "2026-11-02T04:00:00Z", "raw/restart/one.parquet"))));
        assertThat(runner.poll(stream(CT_BINDING)).failed()).isZero();
        assertThat(published()).extracting(e -> e.getValue().getTargetResource().getCanonicalUri())
                .contains("s3://dlap-aws-e2e/raw/restart/one.parquet");
        Instant committed = jdbc.queryForObject("SELECT low_watermark FROM dlap_logingest.stream_cursor WHERE "
                + "binding_id = ? AND partition_key = 'us-east-1'", java.sql.Timestamp.class, CT_BINDING).toInstant();
        clearInvocations(publisher);

        // While the service is down, CloudTrail delivers a file named before the last one read (late) and a
        // new one.
        String late = trailKey(minutesAgo(35), "Restart000000002");
        String next = trailKey(minutesAgo(5), "Restart000000003");
        put(TRAIL_BUCKET, late, AwsS3ForTests.gzip(AwsS3ForTests.trailFile(AwsS3ForTests.lifecycleDelete(
                "0a5e0c11-1fe0-4e00-8000-0000aa020002", "2026-11-02T04:01:00Z", "raw/restart/two.parquet"))));
        put(TRAIL_BUCKET, next, AwsS3ForTests.gzip(AwsS3ForTests.trailFile(AwsS3ForTests.lifecycleDelete(
                "0a5e0c11-1fe0-4e00-8000-0000aa020003", "2026-11-02T04:02:00Z", "raw/restart/three.parquet"))));

        // A restarted service: new transport, client and runner; the database is all that carries over.
        List<String> reads = new CopyOnWriteArrayList<>();
        SdkS3LogStore sdk = new SdkS3LogStore();
        S3LogStore counting = new S3LogStore() {
            @Override
            public List<S3LogObject> list(StreamContext c, String b, String p, String s, String e) {
                return sdk.list(c, b, p, s, e);
            }

            @Override
            public List<String> folders(StreamContext c, String b, String p) {
                return sdk.folders(c, b, p);
            }

            @Override
            public byte[] read(StreamContext c, String b, S3LogObject o, long max) {
                reads.add(o.key());
                return sdk.read(c, b, o, max);
            }
        };
        StreamRunner restarted = new StreamRunner(sources, new TransportRegistry(List.of(
                new CloudTrailTrailFileTransport(counting), new S3ServerAccessLogTransport(counting))), ingest, cursors,
                objects, locks, Clock.systemUTC());
        try {
            StreamRunner.PollResult r = restarted.poll(stream(CT_BINDING));

            assertThat(r.failed()).isZero();
            assertThat(reads).as("the file read before the restart is not read again").doesNotContain(first)
                    .contains(late, next);
            assertThat(published()).extracting(e -> e.getValue().getTargetResource().getCanonicalUri())
                    .containsExactlyInAnyOrder("s3://dlap-aws-e2e/raw/restart/two.parquet",
                            "s3://dlap-aws-e2e/raw/restart/three.parquet");
            assertThat(count("SELECT count(*) FROM dlap_logingest.log_events WHERE binding_id = ? AND "
                    + "target_canonical_uri = 's3://dlap-aws-e2e/raw/restart/one.parquet'", CT_BINDING)).isEqualTo(1);
            Instant after = jdbc.queryForObject("SELECT low_watermark FROM dlap_logingest.stream_cursor WHERE "
                    + "binding_id = ? AND partition_key = 'us-east-1'", java.sql.Timestamp.class, CT_BINDING).toInstant();
            assertThat(after).isAfterOrEqualTo(committed);
        } finally {
            sdk.destroy();
        }
    }

    @Test
    void aMalformedObjectIsQuarantinedOnceAndNotRetried() {
        String key = trailKey(minutesAgo(15), "Malformed0000001");
        byte[] gz = AwsS3ForTests.gzip(AwsS3ForTests.trailFile(AwsS3ForTests.lifecycleDelete(
                "0a5e0c11-1fe0-4e00-8000-0000aa030001", "2026-11-02T05:00:00Z", "raw/bad.parquet")));
        put(TRAIL_BUCKET, key, java.util.Arrays.copyOf(gz, gz.length - 12));
        long before = count("SELECT count(*) FROM dlap_logingest.quarantine_record WHERE binding_id = ?", CT_BINDING);

        StreamRunner.PollResult r1 = runner.poll(stream(CT_BINDING));
        StreamRunner.PollResult r2 = runner.poll(stream(CT_BINDING));

        assertThat(r1.failed()).isZero();
        assertThat(r1.quarantined()).isEqualTo(1);
        assertThat(r2.failed()).isZero();
        assertThat(r2.quarantined()).as("the second poll skips the checkpointed object").isZero();
        assertThat(count("SELECT count(*) FROM dlap_logingest.quarantine_record WHERE binding_id = ?", CT_BINDING))
                .isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT reason_code FROM dlap_logingest.quarantine_record WHERE binding_id = ? "
                + "AND raw_ref::text LIKE ?", String.class, CT_BINDING, "%Malformed0000001%"))
                .contains("INVALID_ENCODING");
        assertThat(jdbc.queryForMap("SELECT status, records, quarantined FROM dlap_logingest.processed_object WHERE "
                + "binding_id = ? AND object_key = ?", CT_BINDING, key))
                .containsEntry("status", "DONE").containsEntry("quarantined", 1L);
    }

    @Test
    void theTransportsAreRegisteredAndTheBindingsValidated() {
        assertThat(transports.ids()).contains(CloudTrailTrailFileTransport.ID, S3ServerAccessLogTransport.ID);
        assertThat(sources.binding(CT_BINDING)).hasValueSatisfying(b -> assertThat(b.valid()).isTrue());
        assertThat(sources.binding(SAL_BINDING)).hasValueSatisfying(b -> assertThat(b.valid()).isTrue());
        assertThat(stream(SAL_BINDING).envelope()).isEqualTo("cwl-json");
        assertThat(stream(CT_BINDING).envelope()).isEqualTo("s3-file");
    }
}
