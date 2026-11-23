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

package com.dlap.logingest.parse.core.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalEvent;
import com.dlap.logingest.parse.core.CanonicalField;
import com.dlap.logingest.parse.core.ClassificationRule;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.EnvelopeKey;
import com.dlap.logingest.parse.core.FieldPath;
import com.dlap.logingest.parse.core.FormatKey;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.parse.core.VersionScheme;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.QuarantineReason;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** Declarative detection, framing, the mapping-driven parser and classpath loading. */
class MappingRuntimeTest {

    private static final MappingSpec DEMO = MappingSpecLoader.parse(MappingSamples.text(MappingSamples.CONTRACT_DEMO),
            MappingSamples.CONTRACT_DEMO, "contract-demo");
    private static final EnvelopeKey KEY = DEMO.keyOf(DEMO.envelopes().get(0));
    private static final DefaultParserRegistry REGISTRY = new DefaultParserRegistry(
            MappingDrivenParser.forEnvelope(DEMO, "generic-json", "demo", "1.0.0"), new MappingSpecs(List.of(DEMO)).detectors());

    private static RawRecord rec(String json) {
        return RawRecord.builder().bindingId("b").sourceKind(KEY.sourceKind()).envelope(KEY.envelope())
                .ref(RawRef.of("T", "u", 0)).fetchedAt(Instant.EPOCH).text(json).build();
    }

    private static ParseOutcome parse(String json, BindingConfig b) {
        return REGISTRY.parse(rec(json), b);
    }

    private static BindingConfig binding() {
        return DEMO.defaultBinding("b");
    }

    private static final String READ = "{\"v\":\"1.0\",\"id\":\"e1\",\"at\":\"2026-02-01T10:00:00+02:00\",\"svc\":\"store\","
            + "\"op\":\"GetObject\",\"who\":{\"type\":\"AssumedRole\",\"arn\":\"arn:aws:iam::111122223333:role/r\"},"
            + "\"object\":\"s3://bucket-a/k\",\"alt\":{\"actor\":\"override-actor\"}}";

    @Nested
    class Detection {

        @Test
        void majorMinorReadsTextOrIntegralNumbers() {
            DeclarativeVersionDetector d = new DeclarativeVersionDetector(KEY, DEMO.envelopes().get(0).detection());
            assertThat(d.detect(rec("{\"v\":\"1.2\"}"))).contains(KEY.at("1.2"));
            assertThat(d.detect(rec("{\"v\":2}"))).contains(KEY.at("2"));
            assertThat(d.detect(rec("{\"v\":1.10}"))).as("1.10 as a number is 1.1; never read").isEmpty();
            assertThat(d.detect(rec("{\"v\":\"bad version!\"}"))).isEmpty();
            assertThat(d.detect(rec("[1,2]"))).isEmpty();
            assertThat(d.detect(rec("{}"))).isEmpty();
        }

        @Test
        void namedRulesPickTheFirstMatch() {
            MappingSpec.Detection named = new MappingSpec.Detection(VersionScheme.NAMED, null, List.of(
                    new MappingSpec.NamedRule("bq-auditmetadata", List.of(new Condition(
                            FieldPath.of("/protoPayload/metadata/@type"), Condition.Op.EQUALS,
                            JsonNodeFactory.instance.textNode("type.googleapis.com/google.cloud.audit.BigQueryAuditMetadata")))),
                    new MappingSpec.NamedRule("auditlog", List.of(new Condition(
                            FieldPath.of("/protoPayload"), Condition.Op.PRESENT, null)))));
            DeclarativeVersionDetector d = new DeclarativeVersionDetector(new EnvelopeKey("GCP_CLOUD_AUDIT", "json"), named);
            RawRecord bq = RawRecord.builder().bindingId("b").sourceKind("GCP_CLOUD_AUDIT").envelope("json")
                    .ref(RawRef.of("T", "u", 0)).fetchedAt(Instant.EPOCH)
                    .text("{\"protoPayload\":{\"metadata\":{\"@type\":\"type.googleapis.com/google.cloud.audit.BigQueryAuditMetadata\"}}}")
                    .build();
            RawRecord plain = RawRecord.builder().bindingId("b").sourceKind("GCP_CLOUD_AUDIT").envelope("json")
                    .ref(RawRef.of("T", "u", 0)).fetchedAt(Instant.EPOCH).text("{\"protoPayload\":{}}").build();
            assertThat(d.detect(bq)).map(FormatKey::schemaVersion).contains("bq-auditmetadata");
            assertThat(d.detect(plain)).map(FormatKey::schemaVersion).contains("auditlog");
        }
    }

    @Nested
    class Framings {

        private final Set<Framing> all = Set.of(Framing.RECORD, Framing.JSON_LINES, Framing.JSON_ARRAY,
                Framing.JSON_OBJECT_ARRAY);

        @Test
        void unitsSplitByShape() {
            FieldPath records = FieldPath.of("/Records");
            assertThat(RecordFraming.split("{\"Records\":[{\"a\":1},{\"a\":2}]}", all, records)).hasSize(2)
                    .allSatisfy(p -> assertThat(p.json()).isNotNull());
            assertThat(RecordFraming.split("[{\"a\":1},{\"a\":2},{\"a\":3}]", all, records)).hasSize(3);
            List<RecordFraming.Piece> lines = RecordFraming.split("{\"a\":1}\n\n{\"a\":2}\r\nnot json\n", all, records);
            assertThat(lines).extracting(RecordFraming.Piece::index).containsExactly(0, 2, 3);
            assertThat(lines.get(2).text()).isEqualTo("not json");
            assertThat(RecordFraming.split("{\"a\":1}", Set.of(Framing.RECORD), null)).singleElement()
                    .satisfies(p -> assertThat(p.text()).isEqualTo("{\"a\":1}"));
            assertThat(RecordFraming.split("﻿  ", all, records)).isEmpty();
        }

        @Test
        void aUnitNoFramingAcceptsIsRefused() {
            assertThatThrownBy(() -> RecordFraming.split("[1,2]", Set.of(Framing.JSON_OBJECT_ARRAY), FieldPath.of("/r")))
                    .isInstanceOf(RecordFraming.FramingException.class);
            assertThatThrownBy(() -> RecordFraming.split("{\"a\":1}\n{\"a\":2}", Set.of(Framing.JSON_ARRAY), null))
                    .isInstanceOf(RecordFraming.FramingException.class);
            assertThat(RecordFraming.split("{broken", Set.of(Framing.RECORD), null)).hasSize(1);
        }
    }

    @Nested
    class Parsing {

        @Test
        void aRecordMapsThroughTheYaml() {
            ParseOutcome o = parse(READ, binding());
            CanonicalEvent e = ((ParseResult.Parsed) o.result()).events().get(0);
            assertThat(e.nativeKey()).isEqualTo("e1");
            assertThat(e.eventTime()).isEqualTo(Instant.parse("2026-02-01T08:00:00Z"));
            assertThat(e.changeType()).isEqualTo(ChangeType.ACCESS);
            assertThat(e.target().canonicalUri()).isEqualTo("s3://bucket-a/k");
            assertThat(e.principal().id()).isEqualTo("arn:aws:iam::111122223333:role/r");
        }

        @Test
        void bindingFieldOverridesComeFirst() {
            BindingConfig b = binding().withFieldOverrides(Map.of(CanonicalField.PRINCIPAL_ID, List.of(FieldPath.of("/alt/actor"))));
            CanonicalEvent e = ((ParseResult.Parsed) parse(READ, b).result()).events().get(0);
            assertThat(e.principal().id()).isEqualTo("override-actor");
        }

        @Test
        void bindingOperationOverridesWin() {
            BindingConfig b = binding().withOperationOverrides(List.of(ClassificationRule.exact("store", "GetObject",
                    ChangeType.UPDATE, EventCategory.EVENT_CATEGORY_MANAGEMENT)));
            CanonicalEvent e = ((ParseResult.Parsed) parse(READ, b).result()).events().get(0);
            assertThat(e.changeType()).isEqualTo(ChangeType.UPDATE);
        }

        @Test
        void anUnlistedOperationIsHeuristic() {
            CanonicalEvent e = ((ParseResult.Parsed) parse(READ.replace("GetObject", "DeleteObject"), binding()).result())
                    .events().get(0);
            assertThat(e.changeType()).isEqualTo(ChangeType.DELETE);
            assertThat(e.warnings()).contains(ParseWarning.PARSE_WARNING_HEURISTIC_CLASSIFICATION);
        }

        @Test
        void aDataTargetThatDoesNotNormalizeIsUnresolvable() {
            ParseOutcome o = parse(READ.replace("s3://bucket-a/k", "not an asset id"), binding());
            ParseResult.Quarantined q = (ParseResult.Quarantined) o.result();
            assertThat(q.reason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_UNRESOLVABLE_ASSET_ID);
            assertThat(q.passThrough()).isPresent();
            assertThat(q.detail()).doesNotContain("not an asset id");
        }

        @Test
        void bindingHintsResolveShortForms() {
            ParseOutcome o = parse(READ.replace("s3://bucket-a/k", "arn:aws:s3:::bucket-a/k"),
                    binding().withAssetIdHints(AssetIdHints.builder().account("111122223333").build()));
            assertThat(((ParseResult.Parsed) o.result()).events().get(0).target().canonicalUri()).isEqualTo("s3://bucket-a/k");
        }

        @Test
        void typeMismatchesAndUnparseableTimes() {
            assertThat(((ParseResult.Quarantined) parse(READ.replace("\"s3://bucket-a/k\"", "{\"x\":1}"), binding()).result())
                    .reason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH);
            assertThat(((ParseResult.Quarantined) parse(READ.replace("2026-02-01T10:00:00+02:00", "yesterday"), binding())
                    .result()).reason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH);
            assertThat(((ParseResult.Quarantined) parse(READ.replace("2026-02-01T10:00:00+02:00", "2026-02-01T10:00:00"),
                    binding()).result()).reason()).as("a time without a zone is not business time")
                    .isEqualTo(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH);
            assertThat(((ParseResult.Quarantined) parse(READ.replace("\"id\":\"e1\",", ""), binding()).result()).reason())
                    .isEqualTo(QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD);
            assertThat(((ParseResult.Quarantined) parse(READ.replace("\"op\":\"GetObject\",", ""), binding()).result())
                    .reason()).isEqualTo(QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD);
        }

        @Test
        void noProviderClockMeansSkippedNeverStamped() {
            ParseOutcome o = parse(READ.replace("\"at\":\"2026-02-01T10:00:00+02:00\",", ""), binding());
            assertThat(o.outcomeCode()).isEqualTo("SKIPPED:MISSING_EVENT_TIME");
        }

        @Test
        void theDefaultHooks() {
            MappingDrivenParser p = MappingDrivenParser.forEnvelope(DEMO, "generic-json", "demo", "1.0.0").get(0);
            assertThat(p.declaredPaths("1.0")).contains(FieldPath.of("/object"));
            assertThat(p.declaredPaths("9.9")).isEmpty();
            assertThat(p.parserVersion()).isEqualTo("demo@1.0.0");
            assertThatThrownBy(() -> MappingDrivenParser.forEnvelope(DEMO, "nope", "demo", "1.0.0"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(MappingDrivenParser.time(JsonNodeFactory.instance.numberNode(1))).isEmpty();
            assertThat(p.parse(rec("{\"v\":\"1.0\"}"), KEY.at("9.0"), binding()).outcomeCode())
                    .isEqualTo("QUARANTINED:UNKNOWN_MAJOR_VERSION");
            assertThat(p.parse(rec("[]"), KEY.at("1.0"), binding()).outcomeCode())
                    .isEqualTo("QUARANTINED:FIELD_TYPE_MISMATCH");
        }
    }

    @Nested
    class Loading {

        @Test
        void familiesLoadFromTheirDirectoriesAndClashesFail(@TempDir Path dir) throws IOException {
            Path family = dir.resolve("dlap/log-formats/contract-demo");
            Files.createDirectories(family);
            Files.writeString(family.resolve("mapping.yaml"), MappingSamples.text(MappingSamples.CONTRACT_DEMO),
                    StandardCharsets.UTF_8);
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver() {
                @Override
                public Resource[] getResources(String locationPattern) {
                    return new Resource[] {new FileSystemResource(family.resolve("mapping.yaml"))};
                }
            };
            List<MappingSpec> specs = MappingSpecLoader.loadClasspath(resolver);
            assertThat(specs).extracting(MappingSpec::family).containsExactly("contract-demo");
            MappingSpecs index = new MappingSpecs(specs);
            assertThat(index.forSourceKind("DLAP_CONTRACT_DEMO")).isPresent();
            assertThat(index.family("contract-demo")).isPresent();
            assertThat(index.detectors()).hasSize(1);
            assertThatThrownBy(() -> new MappingSpecs(List.of(specs.get(0), specs.get(0))))
                    .isInstanceOf(IllegalStateException.class);

            Path wrong = dir.resolve("dlap/log-formats/misnamed");
            Files.createDirectories(wrong);
            Files.writeString(wrong.resolve("mapping.yaml"), MappingSamples.text(MappingSamples.CONTRACT_DEMO),
                    StandardCharsets.UTF_8);
            PathMatchingResourcePatternResolver bad = new PathMatchingResourcePatternResolver() {
                @Override
                public Resource[] getResources(String locationPattern) {
                    return new Resource[] {new FileSystemResource(wrong.resolve("mapping.yaml"))};
                }
            };
            assertThatThrownBy(() -> MappingSpecLoader.loadClasspath(bad))
                    .isInstanceOf(MappingSpecException.class).hasMessageContaining("misnamed");
        }

        @Test
        void theShippedSchemaIsOnTheClasspathWhereTheLoaderSaysItIs() {
            assertThat(MappingRuntimeTest.class.getClassLoader().getResource(MappingSpecLoader.SCHEMA_RESOURCE)).isNotNull();
            assertThat(Optional.ofNullable(MappingRuntimeTest.class.getClassLoader().getResource(
                    MappingSpecLoader.ROOT + "schema/" + MappingSpecLoader.MAPPING_FILE))).isEmpty();
        }
    }
}
