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

package com.dlap.fixtures.anon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.fixtures.FixtureCatalog;
import com.dlap.fixtures.FixtureCase;
import com.dlap.fixtures.ProvenanceVerifier;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the importer end to end on a small invented repository checkout: the generated cases must pass
 * the provenance gate of the corpus, be byte for byte reproducible, carry no source identifier, and
 * be re-checkable.
 */
class SeedImporterTest {

    private static final String EVENT_1 = "11111111-2222-4333-8444-555555555555";
    private static final String EVENT_2 = "66666666-7777-4888-8999-000000000000";
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path tmp;

    private Path checkout;
    private Path corpus;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;

    @BeforeEach
    void setUp() throws IOException {
        checkout = tmp.resolve("checkout");
        corpus = tmp.resolve("corpus");
        Files.createDirectories(checkout.resolve("CloudTrail"));
        Files.writeString(checkout.resolve("CloudTrail/731926485107_CloudTrail_us-east-1_demo.json"), """
                {"Records":[
                  %s,
                  %s
                ]}
                """.formatted(record(EVENT_1, "CreateBucket", "1.08"), record(EVENT_2, "DeleteBucket", "1.08")));
        Files.writeString(checkout.resolve("CloudTrail/731926485107_CloudTrail_us-east-1_old.json"), """
                {"Records":[%s]}
                """.formatted(record("77777777-8888-4999-8aaa-bbbbbbbbbbbb", "ListBuckets", "1.05")));
        Files.createDirectories(corpus.resolve("cloudtrail"));
        Files.writeString(corpus.resolve("cloudtrail/seeds.yaml"), manifest(""));
        Files.writeString(corpus.resolve("cloudtrail/catalog.yaml"), """
                family: cloudtrail
                envelopes:
                  - id: s3-file
                    versions:
                      - id: "1.08"
                        supported: false
                        operations: [s3.amazonaws.com:CreateBucket, s3.amazonaws.com:DeleteBucket]
                        variants: [uid.iam-user, account.recipient-account-id, field.tls-details]
                """);
        Files.writeString(corpus.resolve("cloudtrail/NOTICE.md"), "Cases come from example-org/cloudtrail-demo (MIT).\n");
        Files.writeString(corpus.resolve("README.md"), "marker\n");
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
    }

    private static String record(String id, String name, String version) {
        return """
                {"eventVersion":"%s","userIdentity":{"type":"IAMUser","principalId":"AIDAZ7Q2M5X8C4V1B6N3K",
                  "arn":"arn:aws:iam::731926485107:user/jane-doe","accountId":"731926485107",
                  "accessKeyId":"AKIAZ7Q2M5X8C4V1B6N3","userName":"jane-doe"},
                 "eventTime":"2023-07-10T11:42:03Z","eventSource":"s3.amazonaws.com","eventName":"%s",
                 "awsRegion":"us-east-1","sourceIPAddress":"8.8.4.4","userAgent":"jane-doe laptop",
                 "requestParameters":{"bucketName":"b1"},"responseElements":null,"requestID":"R1","eventID":"%s",
                 "readOnly":false,"eventType":"AwsApiCall","managementEvent":true,"recipientAccountId":"731926485107",
                 "eventCategory":"Management","tlsDetails":{"tlsVersion":"TLSv1.3"}}"""
                .formatted(version, name, id);
    }

    private static String manifest(String extraCases) {
        return """
                spec: dlap-seed-manifest/1
                family: cloudtrail
                sources:
                  demo:
                    repo: example-org/cloudtrail-demo
                    commit: "%s"
                    commit_date: "2023-07-12"
                    license: MIT
                    kind: cloudtrail-files
                    root: CloudTrail
                    url: https://github.com/example-org/cloudtrail-demo/blob/%s/CloudTrail
                    retrieved: "2026-10-19"
                    note: Invented demo data.
                cases:
                  - name: s3-create-bucket
                    source: demo
                    file: "*_CloudTrail_us-east-1_demo.json"
                    select:
                      event_ids: ["%s"]
                  - name: s3-two-records
                    source: demo
                    file: "*_CloudTrail_us-east-1_demo.json"
                    gzip: true
                    notes: Both records of the file.
                %s""".formatted(COMMIT, COMMIT, EVENT_1, extraCases);
    }

    private int run(String... args) {
        return SeedImporter.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private int runImport(String command) {
        return run(command, "--corpus", corpus.toString(), "--source", "demo=" + checkout);
    }

    private String errText() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private static String gunzip(Path file) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void importWritesCasesThatPassTheProvenanceGate() throws IOException {
        assertThat(runImport("import")).as(errText()).isZero();

        FixtureCatalog catalog = FixtureCatalog.load(corpus);
        assertThat(catalog.cases("cloudtrail")).extracting(FixtureCase::id).containsExactly(
                "cloudtrail/s3-file/1.08/s3-create-bucket", "cloudtrail/s3-file/1.08/s3-two-records");
        assertThat(ProvenanceVerifier.verify(corpus).violations()).isEmpty();

        FixtureCase one = catalog.cases("cloudtrail").get(0);
        assertThat(one.meta().origin()).isEqualTo(FixtureCase.Origin.PUBLIC_DATASET);
        assertThat(one.meta().repo()).isEqualTo("example-org/cloudtrail-demo");
        assertThat(one.meta().commit()).isEqualTo(COMMIT);
        assertThat(one.meta().license()).isEqualTo("MIT");
        assertThat(one.meta().docDate()).hasToString("2023-07-12");
        assertThat(one.meta().anonymised()).isTrue();
        assertThat(one.meta().covers()).containsExactly("s3.amazonaws.com:CreateBucket");
        assertThat(one.meta().variantIds()).containsExactly("uid.iam-user", "account.recipient-account-id", "field.tls-details");
        assertThat(one.meta().expectedOutcome()).isEqualTo("PARSED");
        assertThat(one.meta().sourceUrl()).endsWith("/CloudTrail");
        assertThat(one.meta().notes()).contains("example-org/cloudtrail-demo").contains(EVENT_1);

        assertThat(one.expectedTree().get("outcome").asText()).isEqualTo("PARSED");
        assertThat(one.expectedTree().get("events")).hasSize(1);
        assertThat(one.expectedTree().at("/events/0/nativeEventId").asText()).isEqualTo(EVENT_1);
        assertThat(one.expectedTree().at("/events/0/eventCategory").asText()).isEqualTo("EVENT_CATEGORY_MANAGEMENT");

        FixtureCase two = catalog.cases("cloudtrail").get(1);
        assertThat(two.primaryInput().getFileName()).hasToString("input.json.gz");
        assertThat(two.inputText()).contains(EVENT_1).contains(EVENT_2);
        assertThat(two.meta().covers()).containsExactly("s3.amazonaws.com:CreateBucket", "s3.amazonaws.com:DeleteBucket");
        assertThat(two.expectedTree().get("events")).hasSize(2);
        assertThat(gunzip(two.primaryInput())).isEqualTo(two.inputText());
    }

    @Test
    void noSourceIdentifierSurvivesAndTheScannerAgrees() throws IOException {
        assertThat(runImport("import")).isZero();
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(corpus.resolve("cloudtrail"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String name = f.getFileName().toString();
                all.append(name.endsWith(".gz") ? gunzip(f) : Files.readString(f)).append('\n');
            }
        }
        assertThat(all.toString()).doesNotContain("731926485107").doesNotContain("jane-doe").doesNotContain("8.8.4.4")
                .doesNotContain("AKIAZ7Q2M5X8C4V1B6N3").doesNotContain("AIDAZ7Q2M5X8C4V1B6N3K");
        assertThat(LeakScanner.scanTree(corpus)).isEmpty();
        assertThat(run("scan", "--root", corpus.toString())).isZero();
    }

    @Test
    void theImportIsReproducibleByteForByte() throws IOException {
        assertThat(runImport("import")).isZero();
        Path second = tmp.resolve("second");
        Files.createDirectories(second.resolve("cloudtrail"));
        Files.copy(corpus.resolve("cloudtrail/seeds.yaml"), second.resolve("cloudtrail/seeds.yaml"));
        assertThat(run("import", "--corpus", second.toString(), "--source", "demo=" + checkout)).isZero();

        try (Stream<Path> files = Files.walk(second.resolve("cloudtrail"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Path other = corpus.resolve(second.relativize(f));
                if (f.getFileName().toString().endsWith(".gz")) {
                    assertThat(gunzip(f)).isEqualTo(gunzip(other));
                } else {
                    assertThat(Files.readString(f)).as(f.toString()).isEqualTo(Files.readString(other));
                }
            }
        }
    }

    @Test
    void checkPassesOnAFreshImportAndFailsOnAnyEdit() throws IOException {
        assertThat(runImport("import")).isZero();
        assertThat(runImport("check")).as(errText()).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("exactly what the import produces");

        Path expected = corpus.resolve("cloudtrail/s3-file/1.08/s3-create-bucket/expected.json");
        Files.writeString(expected, Files.readString(expected).replace("SUCCESS", "FAILURE"));
        assertThat(runImport("check")).isEqualTo(1);
        assertThat(errText()).contains("differs: cloudtrail/s3-file/1.08/s3-create-bucket/expected.json");

        Files.delete(corpus.resolve("cloudtrail/s3-file/1.08/s3-two-records/meta.yaml"));
        err.reset();
        assertThat(runImport("check")).isEqualTo(1);
        assertThat(errText()).contains("s3-two-records/meta.yaml (missing)");
    }

    @Test
    void aMissingCheckoutIsReportedWithWhatToPass() {
        assertThat(run("import", "--corpus", corpus.toString())).isEqualTo(1);
        assertThat(errText()).contains("pass --source demo=<checkout of example-org/cloudtrail-demo at " + COMMIT + ">");
    }

    @Test
    void aGlobMustMatchOneFileAndACaseCannotSpanVersions() throws IOException {
        Files.writeString(corpus.resolve("cloudtrail/seeds.yaml"), manifest("""
                  - name: ambiguous
                    source: demo
                    file: "731926485107_CloudTrail_us-east-1_*.json"
                """));
        assertThat(runImport("import")).isEqualTo(1);
        assertThat(errText()).contains("matches 2 files");

        Files.writeString(checkout.resolve("CloudTrail/731926485107_CloudTrail_us-east-1_mixed.json"), """
                {"Records":[%s,%s]}
                """.formatted(record("99999999-aaaa-4bbb-8ccc-dddddddddddd", "CreateBucket", "1.08"),
                record("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", "CreateBucket", "1.05")));
        Files.writeString(corpus.resolve("cloudtrail/seeds.yaml"), manifest("""
                  - name: mixed
                    source: demo
                    file: "*_CloudTrail_us-east-1_mixed.json"
                """));
        err.reset();
        assertThat(runImport("import")).isEqualTo(1);
        assertThat(errText()).contains("records span versions");
    }

    @Test
    void anEventIdThatIsNotInTheFileIsAnError() throws IOException {
        Files.writeString(corpus.resolve("cloudtrail/seeds.yaml"), manifest("""
                  - name: missing-record
                    source: demo
                    file: "*_CloudTrail_us-east-1_demo.json"
                    select:
                      event_ids: ["00000000-0000-4000-8000-000000000000"]
                """));
        assertThat(runImport("import")).isEqualTo(1);
        assertThat(errText()).contains("no record with eventID");
    }

    @Test
    void aNegativeCaseGoesUnderNegativeWithTheOutcomeAndNoEvents() throws IOException {
        Files.writeString(corpus.resolve("cloudtrail/seeds.yaml"), manifest("""
                  - name: unknown-major
                    source: demo
                    file: "*_CloudTrail_us-east-1_old.json"
                    negative: true
                    cell_version: "1.08"
                    outcome: QUARANTINED:UNKNOWN_MAJOR_VERSION
                    variants: [version.unknown-major]
                """));
        assertThat(runImport("import")).as(errText()).isZero();
        Path dir = corpus.resolve("cloudtrail/negative/s3-file/1.08/unknown-major");
        assertThat(Files.readString(dir.resolve("expected.json")))
                .isEqualTo("{\n  \"outcome\": \"QUARANTINED\",\n  \"reason\": \"UNKNOWN_MAJOR_VERSION\",\n  \"events\": []\n}\n");
        assertThat(Files.readString(dir.resolve("meta.yaml")))
                .contains("expected_outcome: QUARANTINED:UNKNOWN_MAJOR_VERSION").contains("covers: []")
                .contains("  - version.unknown-major");
        assertThat(ProvenanceVerifier.verify(corpus).violations()).isEmpty();
    }

    @Test
    void anIdentifierTheAnonymiserCannotRewriteStopsTheImport() throws IOException {
        // Upper case project ids are not matched by the project path rule, so the scanner must catch them.
        Files.writeString(checkout.resolve("CloudTrail/731926485107_CloudTrail_us-east-1_leak.json"), """
                {"Records":[%s]}
                """.formatted(record("88888888-9999-4aaa-8bbb-cccccccccccc", "CreateBucket", "1.08")
                .replace("\"userAgent\":\"jane-doe laptop\"", "\"userAgent\":\"see projects/Acme-Sample-4821/x\"")));
        Files.writeString(corpus.resolve("cloudtrail/seeds.yaml"), manifest("""
                  - name: leaky
                    source: demo
                    file: "*_CloudTrail_us-east-1_leak.json"
                """));
        assertThat(runImport("import")).isEqualTo(1);
        assertThat(errText()).contains("still carries identifiers").contains("PROJECT_ID");
        assertThat(Files.exists(corpus.resolve("cloudtrail/s3-file/1.08/leaky"))).isFalse();
    }

    @Test
    void credentialsInASourceRecordAreReplacedByPlaceholdersAndTheImportStaysReproducible() throws IOException {
        String token = TestCredentials.sessionToken(800, 21);
        String secret = TestCredentials.secretKey(22);
        String credentials = "\"responseElements\":{\"credentials\":{\"accessKeyId\":\"ASIAZ7Q2M5X8C4V1B6N4\","
                + "\"secretAccessKey\":\"" + secret + "\",\"sessionToken\":\"" + token + "\"}}";
        Files.writeString(checkout.resolve("CloudTrail/731926485107_CloudTrail_us-east-1_creds.json"), """
                {"Records":[%s]}
                """.formatted(record("12121212-3434-4565-8787-909090909090", "CreateBucket", "1.08")
                .replace("\"responseElements\":null", credentials)));
        Files.writeString(corpus.resolve("cloudtrail/seeds.yaml"), manifest("""
                  - name: with-credentials
                    source: demo
                    file: "*_CloudTrail_us-east-1_creds.json"
                """));
        assertThat(runImport("import")).as(errText()).isZero();

        Path input = corpus.resolve("cloudtrail/s3-file/1.08/with-credentials/input.json");
        String text = Files.readString(input);
        assertThat(text).doesNotContain(token.substring(20, 60)).doesNotContain(secret).doesNotContain("ASIAZ7Q2M5X8C4V1B6N4")
                .contains("\"sessionToken\": \"" + TestCredentials.STS_HEAD + "EXAMPLE").contains("\"secretAccessKey\": \"EXAMPLE")
                .contains("\"accessKeyId\": \"ASIAEXAMPLE");
        assertThat(LeakScanner.scanTree(corpus)).isEmpty();
        assertThat(runImport("check")).as(errText()).isZero();
    }

    @Test
    void distinctEventIdsGiveEachRecordItsOwnStableIdAndSaySo() throws IOException {
        // Q-642: a sample that reuses one eventID for two different records.
        Files.writeString(checkout.resolve("CloudTrail/731926485107_CloudTrail_us-east-1_dup.json"), """
                {"Records":[%s,%s]}
                """.formatted(record(EVENT_1, "CreateBucket", "1.08"), record(EVENT_1, "DeleteBucket", "1.08")));
        Files.writeString(corpus.resolve("cloudtrail/seeds.yaml"), manifest("""
                  - name: reused-id
                    source: demo
                    file: "*_CloudTrail_us-east-1_dup.json"
                    distinct_event_ids: true
                """));
        assertThat(runImport("import")).as(errText()).isZero();

        FixtureCase c = FixtureCatalog.load(corpus).cases("cloudtrail").stream()
                .filter(x -> x.name().equals("reused-id")).findFirst().orElseThrow();
        String first = UUID.nameUUIDFromBytes("cloudtrail/reused-id|0".getBytes(StandardCharsets.UTF_8)).toString();
        String second = UUID.nameUUIDFromBytes("cloudtrail/reused-id|1".getBytes(StandardCharsets.UTF_8)).toString();
        assertThat(c.expectedTree().at("/events/0/nativeEventId").asText()).isEqualTo(first);
        assertThat(c.expectedTree().at("/events/1/nativeEventId").asText()).isEqualTo(second);
        assertThat(c.inputText()).contains(first).contains(second).doesNotContain(EVENT_1);
        assertThat(c.meta().notes()).contains("eventID replaced by a name-based UUID").contains("Q-642");
        assertThat(ProvenanceVerifier.verify(corpus).violations()).isEmpty();
        assertThat(runImport("check")).as("a second import gives the same ids").isZero();

        FixtureCase plain = FixtureCatalog.load(corpus).cases("cloudtrail").stream()
                .filter(x -> x.name().equals("s3-create-bucket")).findFirst().orElseThrow();
        assertThat(plain.inputText()).as("cases without the flag keep their ids").contains(EVENT_1);
        assertThat(plain.meta().notes()).doesNotContain("Q-642");
    }

    @Test
    void distinctEventIdsMustBeABoolean() throws IOException {
        Files.writeString(corpus.resolve("cloudtrail/seeds.yaml"), manifest("""
                  - name: reused-id
                    source: demo
                    file: "*_CloudTrail_us-east-1_demo.json"
                    distinct_event_ids: "yes"
                """));
        assertThat(runImport("import")).isEqualTo(1);
        assertThat(errText()).contains("distinct_event_ids must be true or false");
    }

    @Test
    void aFamilyWithoutEventIdsRefusesDistinctEventIds() {
        FamilyRules gcp = FamilyRules.of("gcpaudit");
        assertThatThrownBy(() -> gcp.makeEventIdsDistinct(java.util.List.of(), "gcpaudit/x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("distinct_event_ids");
    }

    @Test
    void usageErrorsExitWithTwo() {
        assertThat(run()).isEqualTo(2);
        assertThat(run("frobnicate")).isEqualTo(2);
        assertThat(run("scan")).isEqualTo(2);
        assertThat(run("import")).isEqualTo(2);
        assertThat(run("import", "--corpus", corpus.toString(), "--source", "no-equals")).isEqualTo(2);
    }

    @Test
    void theManifestIsValidatedStrictly() throws IOException {
        Files.writeString(corpus.resolve("cloudtrail/seeds.yaml"), manifest("").replace("kind: cloudtrail-files", "kind: made-up")
                .replace("license: MIT", "license: GPL-3.0").replace("spec: dlap-seed-manifest/1", "spec: other/9"));
        assertThat(runImport("import")).isEqualTo(1);
        assertThat(errText()).contains("kind must be one of").contains("license must be one of").contains("spec must be");
    }
}
