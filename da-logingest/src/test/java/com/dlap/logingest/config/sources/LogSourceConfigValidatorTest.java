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

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.config.sources.ConfigProblem.Rule;
import com.dlap.logingest.parse.core.PipelineMode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * One test per design B2 rule and per check around them: each feeds a minimal bindings file that
 * breaks exactly that rule and asserts the rule, the severity and the binding it invalidates.
 */
class LogSourceConfigValidatorTest {

    private static String aws(String bindings) {
        return """
                dlap:
                  logingest:
                    sources:
                      - id: aws
                        kind: TEST_AWS
                        bindings:
                """ + bindings.indent(10);
    }

    private static final String GOOD_AWS = """
            - id: acme-prod
              scope: { account-id: "111111111111", regions: [us-east-1] }
              stream: { transport: LOOKUP_EVENTS }
              format: { envelope: lookup-events }
            """;

    private static LogSourceConfigValidator.Result run(String yaml) {
        return TestFamilies.validate(yaml);
    }

    private static ConfigProblem only(LogSourceConfigValidator.Result r, Rule rule) {
        List<ConfigProblem> hits = r.report().problems().stream().filter(p -> p.rule() == rule).toList();
        assertThat(hits).as("findings of %s in %s", rule, r.report().problems()).isNotEmpty();
        return hits.get(0);
    }

    @Test
    void aCleanFileHasNoFindings() {
        LogSourceConfigValidator.Result r = run(aws(GOOD_AWS));

        assertThat(r.report().problems()).isEmpty();
        assertThat(r.bindings()).singleElement().satisfies(b -> {
            assertThat(b.valid()).isTrue();
            assertThat(b.enabled()).isTrue();
            assertThat(b.configHash()).hasSize(64);
        });
    }

    @Test
    void duplicateBindingIdsAreRejected() {
        LogSourceConfigValidator.Result r = run(aws(GOOD_AWS + GOOD_AWS.replace("111111111111", "222222222222")));

        ConfigProblem p = only(r, Rule.DUPLICATE_ID);
        assertThat(p.isError()).isTrue();
        assertThat(p.bindingId()).isEqualTo("acme-prod");
        assertThat(r.report().invalidBindings()).contains("acme-prod");
    }

    @Test
    void duplicateSourceAndStreamIdsAreRejected() {
        String yaml = """
                dlap:
                  logingest:
                    sources:
                      - id: aws
                        kind: TEST_AWS
                        bindings:
                          - id: a1
                            scope: { account-id: "111111111111" }
                            streams:
                              - { id: s, transport: LOOKUP_EVENTS, format: { envelope: lookup-events }, dedup: cross-stream }
                              - { id: s, transport: LOOKUP_EVENTS, format: { envelope: lookup-events }, dedup: cross-stream }
                      - id: aws
                        kind: TEST_AWS
                        bindings: []
                """;

        LogSourceConfigValidator.Result r = run(yaml);

        assertThat(r.report().problems()).filteredOn(p -> p.rule() == Rule.DUPLICATE_ID).hasSize(2);
    }

    @Test
    void scopeSyntaxIsCheckedPerProvider() {
        String yaml = """
                dlap:
                  logingest:
                    sources:
                      - id: aws
                        kind: TEST_AWS
                        bindings:
                          - { id: a, scope: { account-id: "1234" }, stream: { transport: LOOKUP_EVENTS }, format: { envelope: lookup-events } }
                      - id: gcp
                        kind: TEST_GCP
                        bindings:
                          - { id: g, scope: { project-id: "Bad_Project" }, stream: { transport: LOGGING_API } }
                      - id: az
                        kind: TEST_AZURE
                        bindings:
                          - { id: z, scope: { subscription-id: "not-a-guid" }, stream: { transport: ACTIVITY_REST } }
                      - id: dbx
                        kind: TEST_DBX
                        bindings:
                          - { id: d, scope: { workspace-id: "ws-12" }, stream: { transport: SYSTEM_TABLES, location: { warehouse: w } } }
                          - { id: d2, stream: { transport: SYSTEM_TABLES, location: { warehouse: w } } }
                """;

        LogSourceConfigValidator.Result r = run(yaml);

        assertThat(r.report().problems()).filteredOn(p -> p.rule() == Rule.SCOPE_SYNTAX)
                .extracting(ConfigProblem::bindingId).containsExactlyInAnyOrder("a", "g", "z", "d");
        assertThat(only(r, Rule.SCOPE_MISSING).bindingId()).isEqualTo("d2");
        assertThat(r.bindings()).allSatisfy(b -> assertThat(b.valid()).isFalse());
    }

    @Test
    void validScopesOfEveryProviderPass() {
        String yaml = """
                dlap:
                  logingest:
                    sources:
                      - id: gcp
                        kind: TEST_GCP
                        bindings:
                          - { id: g, scope: { project-id: "my-project-2" }, stream: { transport: LOGGING_API } }
                      - id: az
                        kind: TEST_AZURE
                        bindings:
                          - { id: z, scope: { subscription-id: "0f4b2c9e-1a2b-4c3d-9e8f-001122334455" }, stream: { transport: ACTIVITY_REST } }
                      - id: dbx
                        kind: TEST_DBX
                        bindings:
                          - { id: d, scope: { workspace-id: "1234567890123456" }, stream: { transport: SYSTEM_TABLES, location: { warehouse: w } } }
                """;

        assertThat(run(yaml).report().errors()).isEmpty();
    }

    @Test
    void aPinNoParserReadsIsRejected() {
        LogSourceConfigValidator.Result r = run(aws(GOOD_AWS.replace("format: { envelope: lookup-events }",
                "format: { envelope: lookup-events, version: \"2.0\" }")));

        ConfigProblem p = only(r, Rule.PIN_NOT_READ);
        assertThat(p.isError()).isTrue();
        assertThat(p.message()).contains("2.0").contains("[1.0, 1.11]");
        assertThat(r.bindings().get(0).valid()).isFalse();
    }

    @Test
    void aPinBelowTheFloorOrAnUnknownNameIsRejected() {
        assertThat(run(aws(GOOD_AWS.replace("format: { envelope: lookup-events }",
                "format: { envelope: lookup-events, version: \"0.9\" }"))).report().rules()).contains(Rule.PIN_NOT_READ);
        String gcp = """
                dlap:
                  logingest:
                    sources:
                      - id: gcp
                        kind: TEST_GCP
                        bindings:
                          - { id: g, scope: { project-id: my-project-2 }, stream: { transport: LOGGING_API }, format: { version: bq-auditdata-legacy } }
                """;
        assertThat(run(gcp).report().rules()).contains(Rule.PIN_NOT_READ);
    }

    @Test
    void aPinAboveTheHighestKnownMinorIsOnlyAWarning() {
        LogSourceConfigValidator.Result r = run(aws(GOOD_AWS.replace("format: { envelope: lookup-events }",
                "format: { envelope: lookup-events, version: \"1.12\" }")));

        ConfigProblem p = only(r, Rule.PIN_NEWER_MINOR);
        assertThat(p.isError()).isFalse();
        assertThat(r.report().errors()).isEmpty();
        assertThat(r.bindings().get(0).valid()).isTrue();
    }

    @Test
    void aMalformedPinIsRejected() {
        LogSourceConfigValidator.Result r = run(aws(GOOD_AWS.replace("format: { envelope: lookup-events }",
                "format: { envelope: lookup-events, version: \"1 08\" }")));

        assertThat(only(r, Rule.VERSION_SYNTAX).isError()).isTrue();
    }

    @Test
    void overrideKeysMustBeCanonicalFieldsAndValuesPointers() {
        LogSourceConfigValidator.Result r = run(aws(GOOD_AWS + "  field-overrides: { principal.nickname: /x, "
                + "principal.display-name: \"not-a-pointer\", target.id: /requestParameters/bucketName }\n"));

        assertThat(only(r, Rule.FIELD_OVERRIDE_KEY).path()).endsWith("principal.nickname");
        assertThat(only(r, Rule.FIELD_OVERRIDE_POINTER).path()).endsWith("principal.display-name");
    }

    @Test
    void operationOverridesMustBeWellFormed() {
        LogSourceConfigValidator.Result r = run(aws(GOOD_AWS + "  operation-overrides:\n"
                + "    - { service: s3.amazonaws.com, operation: PutObject, change-type: UPDATE, category: DATA_WRITE }\n"
                + "    - { service: s3.amazonaws.com, operation: GetObject, operation-prefix: Get, change-type: ACCESS, "
                + "category: DATA_READ }\n"
                + "    - { service: s3.amazonaws.com, operation: Odd, change-type: FROB, category: DATA_READ }\n"));

        assertThat(r.report().problems()).filteredOn(p -> p.rule() == Rule.OPERATION_OVERRIDE).hasSize(2);
    }

    @Test
    void eachTransportMustBeRegisteredAndGetItsLocation() {
        String yaml = aws("""
                - id: a
                  scope: { account-id: "111111111111" }
                  streams:
                    - { transport: S3_TRAIL_FILES, format: { envelope: s3-file }, location: { prefix: p, colour: blue } }
                - id: b
                  scope: { account-id: "222222222222" }
                  stream: { transport: KINESIS_FIREHOSE, format: { envelope: s3-file } }
                """);

        LogSourceConfigValidator.Result r = run(yaml);

        assertThat(only(r, Rule.MISSING_LOCATION).path()).endsWith("location.bucket");
        assertThat(only(r, Rule.UNKNOWN_LOCATION_KEY).isError()).isFalse();
        assertThat(only(r, Rule.UNKNOWN_TRANSPORT).bindingId()).isEqualTo("b");
    }

    @Test
    void credentialsRefMustNameAnExistingVariableOrARoleArnAndIsNeverEchoed() {
        String secretLooking = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";
        String yaml = aws("""
                - { id: a, scope: { account-id: "111111111111" }, stream: { transport: LOOKUP_EVENTS }, format: { envelope: lookup-events }, credentials-ref: AWS_ROLE }
                - { id: b, scope: { account-id: "222222222222" }, stream: { transport: LOOKUP_EVENTS }, format: { envelope: lookup-events }, credentials-ref: "env:MISSING_VAR" }
                - { id: c, scope: { account-id: "333333333333" }, stream: { transport: LOOKUP_EVENTS }, format: { envelope: lookup-events }, credentials-ref: "%s" }
                - { id: d, scope: { account-id: "444444444444" }, stream: { transport: LOOKUP_EVENTS }, format: { envelope: lookup-events }, credentials-ref: "arn:aws:iam::444444444444:role/dlap-reader" }
                """.formatted(secretLooking));

        LogSourceConfigValidator.Result r = run(yaml);

        assertThat(only(r, Rule.CREDENTIALS_REF_MISSING).bindingId()).isEqualTo("b");
        assertThat(only(r, Rule.CREDENTIALS_REF_MISSING).message()).contains("MISSING_VAR");
        ConfigProblem secret = only(r, Rule.CREDENTIALS_REF_SYNTAX);
        assertThat(secret.bindingId()).isEqualTo("c");
        assertThat(r.report().problems()).allSatisfy(p -> assertThat(p.toString()).doesNotContain(secretLooking));
        assertThat(r.report().invalidBindings()).containsExactlyInAnyOrder("b", "c");
    }

    @Test
    void twoStreamsOnOneScopeNeedCrossStreamDedup() {
        String overlapping = aws("""
                - id: org-trail
                  scope: { account-id: "111111111111" }
                  stream: { transport: S3_TRAIL_FILES, format: { envelope: s3-file }, location: { bucket: org } }
                - id: account-trail
                  scope: { account-id: "111111111111", regions: [us-east-1] }
                  stream: { transport: LOOKUP_EVENTS, format: { envelope: lookup-events } }
                """);

        LogSourceConfigValidator.Result r = run(overlapping);

        assertThat(r.report().problems()).filteredOn(p -> p.rule() == Rule.OVERLAPPING_STREAMS)
                .extracting(ConfigProblem::bindingId).containsExactlyInAnyOrder("org-trail", "account-trail");

        LogSourceConfigValidator.Result declared = run(overlapping.replace(
                "format: { envelope: lookup-events } }", "format: { envelope: lookup-events }, dedup: cross-stream }"));
        assertThat(declared.report().rules()).doesNotContain(Rule.OVERLAPPING_STREAMS);

        LogSourceConfigValidator.Result disjoint = run(overlapping.replace(
                "scope: { account-id: \"111111111111\" }", "scope: { account-id: \"111111111111\", regions: [eu-west-1] }"));
        assertThat(disjoint.report().rules()).as("disjoint regions do not overlap").doesNotContain(Rule.OVERLAPPING_STREAMS);
    }

    @Test
    void unknownSourceKindsAndReservedNamesAreRejected() {
        String yaml = """
                dlap:
                  logingest:
                    sources:
                      - id: snow
                        kind: SNOWFLAKE_ACCESS_HISTORY
                        bindings:
                          - { id: s, scope: { account-id: acme }, stream: { transport: LOOKUP_EVENTS } }
                      - id: own
                        kind: DLAP_NORMALIZED_AWS
                        bindings:
                          - { id: dlap-rest-aws, scope: { account-id: "111111111111" }, stream: { transport: REST } }
                """;

        LogSourceConfigValidator.Result r = run(yaml);

        assertThat(only(r, Rule.UNKNOWN_SOURCE_KIND).bindingId()).isEqualTo("s");
        assertThat(r.report().problems()).filteredOn(p -> p.rule() == Rule.RESERVED_ID).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void anAmbiguousEnvelopeMustBeNamedAndAnUnknownOneIsRejected() {
        String yaml = aws("""
                - { id: a, scope: { account-id: "111111111111" }, stream: { transport: LOOKUP_EVENTS } }
                - { id: b, scope: { account-id: "222222222222" }, stream: { transport: LOOKUP_EVENTS }, format: { envelope: cloudwatch-line } }
                """);

        LogSourceConfigValidator.Result r = run(yaml);

        assertThat(only(r, Rule.ENVELOPE_UNRESOLVED).bindingId()).isEqualTo("a");
        assertThat(only(r, Rule.UNKNOWN_ENVELOPE).bindingId()).isEqualTo("b");
    }

    @Test
    void aSingleEnvelopeFamilyNeedsNoEnvelopeKey() {
        String yaml = """
                dlap:
                  logingest:
                    sources:
                      - id: gcp
                        kind: TEST_GCP
                        bindings:
                          - { id: g, scope: { project-id: my-project-2 }, stream: { transport: LOGGING_API } }
                """;

        LogSourceConfigValidator.Result r = run(yaml);

        assertThat(r.report().errors()).isEmpty();
        assertThat(r.bindings().get(0).streams().get(0).envelope()).isEqualTo("api-json");
    }

    @Test
    void invalidValuesAreReportedWithTheirPath() {
        String yaml = aws(GOOD_AWS + "  on-mismatch: SOMETIMES\n  persist-raw: EVERYTHING\n  overlap: twenty\n"
                + "  poll-interval: PT0S\n  max-record-bytes: 0\n  dedup: maybe\n  rate-limit: { requests-per-second: -1 }\n");

        LogSourceConfigValidator.Result r = run(yaml);

        assertThat(r.report().problems()).filteredOn(p -> p.rule() == Rule.INVALID_VALUE)
                .extracting(ConfigProblem::path)
                .anySatisfy(p -> assertThat(p).endsWith(".on-mismatch"))
                .anySatisfy(p -> assertThat(p).endsWith(".persist-raw"))
                .anySatisfy(p -> assertThat(p).endsWith(".overlap"))
                .anySatisfy(p -> assertThat(p).endsWith(".poll-interval"))
                .anySatisfy(p -> assertThat(p).endsWith(".max-record-bytes"))
                .anySatisfy(p -> assertThat(p).endsWith(".dedup"))
                .anySatisfy(p -> assertThat(p).endsWith(".requests-per-second"));
    }

    @Test
    void theUnknownMajorRuleCannotBeRelaxed() {
        String yaml = """
                dlap:
                  logingest:
                    defaults: { on-unknown-major: PARSE_WITH_DETECTED }
                    sources: []
                """;

        assertThat(only(run(yaml), Rule.UNKNOWN_MAJOR_POLICY).isError()).isTrue();
    }

    @Test
    void theDatabaseConfigModeIsReserved() {
        String yaml = """
                dlap:
                  logingest:
                    config-mode: FILE_SEEDS_DB
                """;

        assertThat(only(run(yaml), Rule.CONFIG_MODE).isError()).isTrue();
    }

    @Test
    void twoStreamsOfOneEnvelopeMustParseTheSameWay() {
        String yaml = aws("""
                - id: a
                  scope: { account-id: "111111111111" }
                  dedup: cross-stream
                  streams:
                    - { id: one, transport: LOOKUP_EVENTS, format: { envelope: lookup-events, version: "1.08" } }
                    - { id: two, transport: LOOKUP_EVENTS, format: { envelope: lookup-events } }
                """);

        assertThat(only(run(yaml), Rule.PARSE_SETTINGS_CONFLICT).bindingId()).isEqualTo("a");
    }

    @Test
    void legacySourcesCannotRunBesideTheRegistry() {
        LogSourceConfigValidator v = new LogSourceConfigValidator(TestFamilies.context(PipelineMode.REGISTRY,
                List.of("dlap.logingest.aws.enabled"), n -> null));

        LogSourceConfigValidator.Result r = v.validate(TestFamilies.bind(aws(GOOD_AWS)));

        assertThat(only(r, Rule.LEGACY_SOURCE_ON_REGISTRY).isError()).isTrue();
    }

    @Test
    void onTheLegacyPipelineConfiguredBindingsAreReportedInactive() {
        LogSourceConfigValidator.Result r = TestFamilies.validate(aws(GOOD_AWS), PipelineMode.LEGACY);

        assertThat(only(r, Rule.INACTIVE_ON_LEGACY).isError()).isFalse();
        assertThat(r.bindings().get(0).state(PipelineMode.LEGACY).name()).isEqualTo("INACTIVE");
    }

    @Test
    void inheritanceTakesTheNearestLevelAndDisablingCascades() {
        String yaml = """
                dlap:
                  logingest:
                    defaults: { overlap: PT20M, persist-raw: ALL, field-overrides: { request.caller-ip: /ip } }
                    sources:
                      - id: aws
                        kind: TEST_AWS
                        overlap: PT10M
                        field-overrides: { request.caller-ip: /sourceIPAddress }
                        bindings:
                          - id: near
                            scope: { account-id: "111111111111" }
                            stream: { transport: LOOKUP_EVENTS, overlap: PT5M, format: { envelope: lookup-events } }
                          - id: off
                            enabled: false
                            scope: { account-id: "222222222222" }
                            stream: { transport: LOOKUP_EVENTS, enabled: true, format: { envelope: lookup-events } }
                """;

        LogSourceConfigValidator.Result r = run(yaml);

        assertThat(r.report().errors()).isEmpty();
        EffectiveStream near = r.bindings().get(0).streams().get(0);
        assertThat(near.overlap()).hasMinutes(5);
        assertThat(near.parse().persistRaw().configured().name()).isEqualTo("ALL");
        assertThat(near.parse().fieldOverrides().values()).singleElement()
                .satisfies(paths -> assertThat(paths.get(0).pointer()).isEqualTo("/sourceIPAddress"));
        assertThat(r.bindings().get(1).enabled()).isFalse();
    }

    @Test
    void theHashFollowsMeaningNotOrder() {
        String a = aws(GOOD_AWS + "  field-overrides: { request.caller-ip: /a, request.user-agent: /b }\n");
        String b = aws(GOOD_AWS + "  field-overrides: { request.user-agent: /b, request.caller-ip: /a }\n");
        String c = aws(GOOD_AWS + "  field-overrides: { request.user-agent: /b, request.caller-ip: /c }\n");

        String ha = run(a).bindings().get(0).configHash();
        assertThat(run(b).bindings().get(0).configHash()).isEqualTo(ha);
        assertThat(run(c).bindings().get(0).configHash()).isNotEqualTo(ha);
    }
}
