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

package com.dlap.logingest.parse.core;

import static com.dlap.logingest.parse.core.Stubs.KEY;
import static com.dlap.logingest.parse.core.Stubs.binding;
import static com.dlap.logingest.parse.core.Stubs.event;
import static com.dlap.logingest.parse.core.Stubs.record;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.QuarantineReason;
import com.dlap.proto.log.v1.SourceProvider;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Registry contract tests (LOG-02a acceptance): resolution by (source kind, envelope, version),
 * the frozen version rules, the outcome of every failure mode, and fail-fast registration.
 */
class DefaultParserRegistryTest {

    private static DefaultParserRegistry registry(Stubs.Parser... parsers) {
        return new DefaultParserRegistry(List.of(parsers), List.of(new Stubs.Detector()));
    }

    private static String code(ParseOutcome o) {
        return o.outcomeCode();
    }

    private static QuarantineReason reason(ParseOutcome o) {
        return ((ParseResult.Quarantined) o.result()).reason();
    }

    @Nested
    class Versions {

        @Test
        void aSupportedVersionIsParsedAsDetected() {
            Stubs.Parser p = new Stubs.Parser();
            ParseOutcome o = registry(p).parse(record("{\"v\":\"1.08\",\"id\":\"e1\"}"), binding());
            assertThat(code(o)).isEqualTo("PARSED");
            assertThat(p.seen).containsExactly(KEY.at("1.08"));
            assertThat(o.detected()).contains(KEY.at("1.08"));
            assertThat(o.effective()).contains(KEY.at("1.08"));
            assertThat(o.parserVersion()).contains("stub-parser@1.0.0");
            assertThat(o.sourceProvider()).contains(SourceProvider.AWS);
        }

        @Test
        void aNewerMinorIsParsedAsTheHighestKnownWithAWarning() {
            Stubs.Parser p = new Stubs.Parser();
            ParseOutcome o = registry(p).parse(record("{\"v\":\"1.13\"}"), binding());
            assertThat(code(o)).isEqualTo("PARSED_WITH_WARNINGS");
            assertThat(p.seen).containsExactly(KEY.at("1.11"));
            assertThat(o.detected()).contains(KEY.at("1.13"));
            assertThat(o.effective()).contains(KEY.at("1.11"));
            assertThat(((ParseResult.Parsed) o.result()).warnings()).containsExactly(ParseWarning.PARSE_WARNING_NEWER_MINOR);
        }

        @Test
        void anUnknownMajorIsQuarantinedAndNoParserRuns() {
            Stubs.Parser p = new Stubs.Parser();
            ParseOutcome o = registry(p).parse(record("{\"v\":\"2.0\"}"), binding());
            assertThat(code(o)).isEqualTo("QUARANTINED:UNKNOWN_MAJOR_VERSION");
            assertThat(p.seen).isEmpty();
            assertThat(o.detected()).contains(KEY.at("2.0"));
            assertThat(o.effective()).isEmpty();
        }

        @Test
        void anUnknownMajorIsQuarantinedWhateverThePinPolicy() {
            for (OnMismatch policy : OnMismatch.values()) {
                Stubs.Parser p = new Stubs.Parser();
                ParseOutcome o = registry(p).parse(record("{\"v\":\"2.0\"}"), binding().pinned("1.08", policy));
                assertThat(code(o)).as(policy.name()).isEqualTo("QUARANTINED:UNKNOWN_MAJOR_VERSION");
                assertThat(p.seen).isEmpty();
            }
        }

        @Test
        void aMinorBelowTheFloorIsUnknown() {
            Stubs.Parser p = new Stubs.Parser().range(VersionRange.majorMinor("1.05", "1.11"));
            assertThat(code(registry(p).parse(record("{\"v\":\"1.02\"}"), binding())))
                    .isEqualTo("QUARANTINED:UNKNOWN_MAJOR_VERSION");
        }

        @Test
        void twoMajorsResolveToTheirOwnParsers() {
            Stubs.Parser one = new Stubs.Parser().id("one");
            Stubs.Parser two = new Stubs.Parser().id("two").range(VersionRange.majorMinor("2.0", "2.3"));
            DefaultParserRegistry r = registry(one, two);
            assertThat(r.parse(record("{\"v\":\"2.1\"}"), binding()).parserVersion()).contains("two@1.0.0");
            assertThat(r.parse(record("{\"v\":\"1.1\"}"), binding()).parserVersion()).contains("one@1.0.0");
            assertThat(r.parse(record("{\"v\":\"2.9\"}"), binding()).effective()).contains(KEY.at("2.3"));
        }

        @Test
        void namedVersionsMatchExactly() {
            Stubs.Parser p = new Stubs.Parser().range(VersionRange.named("auditlog", "bq-auditmetadata"));
            DefaultParserRegistry r = registry(p);
            assertThat(code(r.parse(record("{\"v\":\"bq-auditmetadata\"}"), binding()))).isEqualTo("PARSED");
            assertThat(code(r.parse(record("{\"v\":\"bq-auditdata-legacy\"}"), binding())))
                    .isEqualTo("QUARANTINED:UNKNOWN_MAJOR_VERSION");
        }
    }

    @Nested
    class Pins {

        @Test
        void quarantineOnMismatch() {
            ParseOutcome o = registry(new Stubs.Parser())
                    .parse(record("{\"v\":\"1.11\"}"), binding().pinned("1.08", OnMismatch.QUARANTINE));
            assertThat(code(o)).isEqualTo("QUARANTINED:VERSION_PIN_MISMATCH");
            assertThat(o.pinnedVersion()).contains("1.08");
            assertThat(o.detected()).contains(KEY.at("1.11"));
        }

        @Test
        void parseWithPin() {
            Stubs.Parser p = new Stubs.Parser();
            ParseOutcome o = registry(p).parse(record("{\"v\":\"1.11\"}"), binding().pinned("1.08", OnMismatch.PARSE_WITH_PIN));
            assertThat(p.seen).containsExactly(KEY.at("1.08"));
            assertThat(((ParseResult.Parsed) o.result()).warnings())
                    .containsExactly(ParseWarning.PARSE_WARNING_VERSION_PIN_MISMATCH);
        }

        @Test
        void parseWithDetectedIsTheDefault() {
            Stubs.Parser p = new Stubs.Parser();
            BindingConfig b = binding().pinned("1.08", null);
            assertThat(b.onMismatch()).isEqualTo(OnMismatch.PARSE_WITH_DETECTED);
            ParseOutcome o = registry(p).parse(record("{\"v\":\"1.11\"}"), b);
            assertThat(p.seen).containsExactly(KEY.at("1.11"));
            assertThat(((ParseResult.Parsed) o.result()).warnings())
                    .containsExactly(ParseWarning.PARSE_WARNING_VERSION_PIN_MISMATCH);
        }

        @Test
        void aPinNoParserReadsIsAPinMismatch() {
            ParseOutcome o = registry(new Stubs.Parser())
                    .parse(record("{\"v\":\"1.11\"}"), binding().pinned("3.0", OnMismatch.PARSE_WITH_PIN));
            assertThat(code(o)).isEqualTo("QUARANTINED:VERSION_PIN_MISMATCH");
        }

        @Test
        void pinsCompareByNumberNotSpelling() {
            ParseOutcome o = registry(new Stubs.Parser())
                    .parse(record("{\"v\":\"1.08\"}"), binding().pinned("1.8", OnMismatch.QUARANTINE));
            assertThat(code(o)).isEqualTo("PARSED");
        }

        @Test
        void autoMeansNoPin() {
            BindingConfig b = binding().pinned(BindingConfig.AUTO, OnMismatch.QUARANTINE);
            assertThat(b.pinnedVersion()).isEmpty();
            assertThat(code(registry(new Stubs.Parser()).parse(record("{\"v\":\"1.3\"}"), b))).isEqualTo("PARSED");
        }
    }

    @Nested
    class Failures {

        @Test
        void noDetectorIsUnknownEnvelope() {
            RawRecord other = RawRecord.builder().bindingId(Stubs.BINDING).sourceKind(Stubs.KIND).envelope("other-env")
                    .ref(RawRef.of("TEST", "test://unit", 0)).fetchedAt(Stubs.T0).text("{}").build();
            assertThat(code(registry(new Stubs.Parser()).parse(other, binding()))).isEqualTo("QUARANTINED:UNKNOWN_ENVELOPE");
        }

        @Test
        void aBodyTheDetectorDoesNotKnowIsUnknownEnvelope() {
            assertThat(code(registry(new Stubs.Parser()).parse(record("{\"no\":\"version\"}"), binding())))
                    .isEqualTo("QUARANTINED:UNKNOWN_ENVELOPE");
        }

        @Test
        void badBytesAreInvalidEncodingAndBadTextIsInvalidJson() {
            DefaultParserRegistry r = registry(new Stubs.Parser());
            assertThat(code(r.parse(record(new byte[] {'{', (byte) 0xC3, (byte) 0x28, '}'}), binding())))
                    .isEqualTo("QUARANTINED:INVALID_ENCODING");
            assertThat(code(r.parse(record("{\"v\":"), binding()))).isEqualTo("QUARANTINED:INVALID_JSON");
            assertThat(code(r.parse(record("{} {}"), binding()))).isEqualTo("QUARANTINED:INVALID_JSON");
            assertThat(code(r.parse(record("   "), binding()))).isEqualTo("QUARANTINED:INVALID_JSON");
        }

        @Test
        void aByteOrderMarkIsNotAnError() {
            byte[] bom = "﻿{\"v\":\"1.0\"}".getBytes(StandardCharsets.UTF_8);
            assertThat(code(registry(new Stubs.Parser()).parse(record(bom), binding()))).isEqualTo("PARSED");
        }

        @Test
        void anOversizeRecordIsRefusedBeforeDetection() {
            Stubs.Parser p = new Stubs.Parser();
            ParseOutcome o = registry(p).parse(record("{\"v\":\"1.0\",\"pad\":\"xxxxxxxxxxxxxxxx\"}"),
                    binding().withMaxRecordBytes(10));
            assertThat(code(o)).isEqualTo("QUARANTINED:OVERSIZE_RECORD");
            assertThat(o.detected()).isEmpty();
            assertThat(p.seen).isEmpty();
        }

        @Test
        void aParserExceptionIsQuarantinedWithoutItsMessage() {
            Stubs.Parser p = new Stubs.Parser().behaviour(r -> {
                throw new IllegalStateException("secret-value-from-the-record");
            });
            ParseOutcome o = registry(p).parse(record("{\"v\":\"1.0\"}"), binding());
            assertThat(reason(o)).isEqualTo(QuarantineReason.QUARANTINE_REASON_PARSER_EXCEPTION);
            assertThat(((ParseResult.Quarantined) o.result()).detail())
                    .contains("IllegalStateException").doesNotContain("secret-value-from-the-record");
            assertThat(o.effective()).contains(KEY.at("1.0"));
        }

        @Test
        void aNullResultIsAParserException() {
            assertThat(reason(registry(new Stubs.Parser().behaviour(r -> null)).parse(record("{\"v\":\"1.0\"}"), binding())))
                    .isEqualTo(QuarantineReason.QUARANTINE_REASON_PARSER_EXCEPTION);
        }

        @Test
        void anEventOfAnotherProviderIsAParserException() {
            Stubs.Parser p = new Stubs.Parser().behaviour(r -> ParseResult.Parsed.of(
                    event("k").toBuilder().provider(SourceProvider.GCP).build()));
            assertThat(reason(registry(p).parse(record("{\"v\":\"1.0\"}"), binding())))
                    .isEqualTo(QuarantineReason.QUARANTINE_REASON_PARSER_EXCEPTION);
        }

        @Test
        void aThrowingDetectorIsAParserException() {
            VersionDetector boom = new VersionDetector() {
                @Override
                public EnvelopeKey envelopeKey() {
                    return KEY;
                }

                @Override
                public Optional<FormatKey> detect(RawRecord record) {
                    throw new IllegalArgumentException("boom");
                }
            };
            DefaultParserRegistry r = new DefaultParserRegistry(List.of(new Stubs.Parser()), List.of(boom));
            assertThat(reason(r.parse(record("{\"v\":\"1.0\"}"), binding())))
                    .isEqualTo(QuarantineReason.QUARANTINE_REASON_PARSER_EXCEPTION);
        }

        @Test
        void aDetectorThatAnswersForAnotherEnvelopeIsAParserException() {
            VersionDetector liar = new VersionDetector() {
                @Override
                public EnvelopeKey envelopeKey() {
                    return KEY;
                }

                @Override
                public Optional<FormatKey> detect(RawRecord record) {
                    return Optional.of(new FormatKey(Stubs.KIND, "elsewhere", "1.0"));
                }
            };
            DefaultParserRegistry r = new DefaultParserRegistry(List.of(new Stubs.Parser()), List.of(liar));
            assertThat(reason(r.parse(record("{\"v\":\"1.0\"}"), binding())))
                    .isEqualTo(QuarantineReason.QUARANTINE_REASON_PARSER_EXCEPTION);
        }

        @Test
        void aBindingOfAnotherRecordIsACallerError() {
            assertThatThrownBy(() -> registry(new Stubs.Parser())
                    .parse(record("{\"v\":\"1.0\"}"), BindingConfig.auto("other-binding", Stubs.KIND)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> registry(new Stubs.Parser())
                    .parse(record("{\"v\":\"1.0\"}"), BindingConfig.auto(Stubs.BINDING, "OTHER_KIND")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Results {

        @Test
        void undeclaredPathsAddTheUnknownFieldsWarning() {
            Stubs.Parser p = new Stubs.Parser();
            p.declared = Set.of(FieldPath.of("/v"), FieldPath.of("/id"));
            ParseOutcome o = registry(p).parse(record("{\"v\":\"1.0\",\"id\":\"a\",\"extra\":{\"deep\":1}}"), binding());
            assertThat(((ParseResult.Parsed) o.result()).warnings()).containsExactly(ParseWarning.PARSE_WARNING_UNKNOWN_FIELDS);
            assertThat(o.fingerprint()).isPresent();
            assertThat(o.fingerprint().get().unknownPaths()).containsExactly("/extra");
        }

        @Test
        void noDeclaredPathsMeansNoFingerprint() {
            ParseOutcome o = registry(new Stubs.Parser()).parse(record("{\"v\":\"1.0\",\"extra\":1}"), binding());
            assertThat(o.fingerprint()).isEmpty();
            assertThat(code(o)).isEqualTo("PARSED");
        }

        @Test
        void skippedResultsPassThroughUnchanged() {
            Stubs.Parser p = new Stubs.Parser().behaviour(r -> new ParseResult.Skipped(SkipReason.MISSING_EVENT_TIME, "x"));
            ParseOutcome o = registry(p).parse(record("{\"v\":\"1.20\"}"), binding());
            assertThat(code(o)).isEqualTo("SKIPPED:MISSING_EVENT_TIME");
        }

        @Test
        void recordWarningsReachEveryEvent() {
            Stubs.Parser p = new Stubs.Parser().behaviour(r -> new ParseResult.Parsed(List.of(event("a"),
                    event("b").withWarnings(Set.of(ParseWarning.PARSE_WARNING_PROVIDER_TRUNCATED))), Set.of()));
            ParseOutcome o = registry(p).parse(record("{\"v\":\"1.12\"}"), binding());
            ParseResult.Parsed parsed = (ParseResult.Parsed) o.result();
            assertThat(parsed.warningsOf(parsed.events().get(0))).containsExactly(ParseWarning.PARSE_WARNING_NEWER_MINOR);
            assertThat(parsed.warningsOf(parsed.events().get(1))).containsExactlyInAnyOrder(
                    ParseWarning.PARSE_WARNING_NEWER_MINOR, ParseWarning.PARSE_WARNING_PROVIDER_TRUNCATED);
        }

        @Test
        void theRegistryReportsWhatItKnows() {
            DefaultParserRegistry r = registry(new Stubs.Parser());
            assertThat(r.envelopes()).containsExactly(KEY);
            assertThat(r.rangeFor(KEY, "1.4")).isPresent();
            assertThat(r.rangeFor(KEY, "1.40")).isPresent();
            assertThat(r.rangeFor(KEY, "2.0")).isEmpty();
            assertThat(r.describe().get(KEY)).containsExactly("stub-parser@1.0.0 [1.0, 1.11]");
        }
    }

    @Nested
    class Registration {

        @Test
        void twoDetectorsForOneEnvelopeFail() {
            assertThatThrownBy(() -> new DefaultParserRegistry(List.of(),
                    List.of(new Stubs.Detector(), new Stubs.Detector())))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("two version detectors");
        }

        @Test
        void overlappingParsersFail() {
            Stubs.Parser a = new Stubs.Parser().id("a");
            Stubs.Parser b = new Stubs.Parser().id("b").range(VersionRange.majorMinor("1.5", "1.12"));
            assertThatThrownBy(() -> registry(a, b)).isInstanceOf(IllegalStateException.class).hasMessageContaining("overlap");
        }

        @Test
        void mixedSchemesOnOneEnvelopeFail() {
            Stubs.Parser a = new Stubs.Parser().id("a");
            Stubs.Parser b = new Stubs.Parser().id("b").range(VersionRange.named("auditlog"));
            assertThatThrownBy(() -> registry(a, b)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void overlappingNamedParsersFail() {
            Stubs.Parser a = new Stubs.Parser().id("a").range(VersionRange.named("auditlog", "x"));
            Stubs.Parser b = new Stubs.Parser().id("b").range(VersionRange.named("x"));
            assertThatThrownBy(() -> registry(a, b)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void aParserWithoutADetectorFails() {
            assertThatThrownBy(() -> new DefaultParserRegistry(List.of(new Stubs.Parser()), List.of()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("no version detector");
        }

        @Test
        void oneSourceKindCannotHaveTwoProviders() {
            Stubs.Parser a = new Stubs.Parser().id("a");
            Stubs.Parser b = new Stubs.Parser().id("b").range(VersionRange.majorMinor("2.0", "2.0"));
            b.provider = SourceProvider.GCP;
            assertThatThrownBy(() -> registry(a, b)).isInstanceOf(IllegalStateException.class).hasMessageContaining("providers");
        }

        @Test
        void parserIdentityIsChecked() {
            assertThatThrownBy(() -> registry(new Stubs.Parser().id("Bad Id"))).isInstanceOf(IllegalStateException.class);
            Stubs.Parser badVersion = new Stubs.Parser();
            badVersion.semver = "1.0";
            assertThatThrownBy(() -> registry(badVersion)).isInstanceOf(IllegalStateException.class);
            Stubs.Parser noProvider = new Stubs.Parser();
            noProvider.provider = SourceProvider.SOURCE_PROVIDER_UNSPECIFIED;
            assertThatThrownBy(() -> registry(noProvider)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void aDetectorWithoutParsersQuarantinesItsRecordsAsUnknownMajor() {
            DefaultParserRegistry r = new DefaultParserRegistry(List.of(), List.of(new Stubs.Detector()));
            assertThat(code(r.parse(record("{\"v\":\"1.0\"}"), binding()))).isEqualTo("QUARANTINED:UNKNOWN_MAJOR_VERSION");
        }
    }
}
