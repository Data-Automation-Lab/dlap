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

import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Small parsers, detectors and records for the registry contract tests. */
final class Stubs {

    static final String KIND = "TEST_AUDIT";
    static final String ENVELOPE = "json-record";
    static final EnvelopeKey KEY = new EnvelopeKey(KIND, ENVELOPE);
    static final String BINDING = "test-binding";
    static final Instant T0 = Instant.parse("2026-03-01T12:00:00Z");

    private Stubs() {
    }

    /** A record of the stub envelope. */
    static RawRecord record(String json) {
        return RawRecord.builder().bindingId(BINDING).sourceKind(KIND).envelope(ENVELOPE)
                .ref(RawRef.of("TEST", "test://unit", 0)).fetchedAt(T0).text(json).build();
    }

    /** A record of the stub envelope from raw bytes. */
    static RawRecord record(byte[] bytes) {
        return RawRecord.builder().bindingId(BINDING).sourceKind(KIND).envelope(ENVELOPE)
                .ref(RawRef.of("TEST", "test://unit", 0)).fetchedAt(T0).bytes(bytes).build();
    }

    /** A binding of the stub kind with every default. */
    static BindingConfig binding() {
        return BindingConfig.auto(BINDING, KIND);
    }

    /** A parsed event. */
    static CanonicalEvent event(String nativeKey) {
        return CanonicalEvent.builder()
                .nativeKey(nativeKey)
                .provider(SourceProvider.AWS)
                .service("s3.amazonaws.com")
                .operation("GetObject")
                .changeType(ChangeType.ACCESS)
                .category(EventCategory.EVENT_CATEGORY_DATA_READ)
                .result(ResultStatus.SUCCESS)
                .eventTime(T0)
                .build();
    }

    /** Detects the version at {@code /v} for the stub envelope. */
    static final class Detector implements VersionDetector {
        final EnvelopeKey key;

        Detector() {
            this(KEY);
        }

        Detector(EnvelopeKey key) {
            this.key = key;
        }

        @Override
        public EnvelopeKey envelopeKey() {
            return key;
        }

        @Override
        public Optional<FormatKey> detect(RawRecord record) {
            var v = record.body().path("v");
            return v.isTextual() ? Optional.of(key.at(v.asText())) : Optional.empty();
        }
    }

    /** A parser whose behaviour and identity are set per test; remembers the formats it was asked to parse. */
    static final class Parser implements LogRecordParser {
        EnvelopeKey key = KEY;
        VersionRange range = VersionRange.majorMinor("1.0", "1.11");
        SourceProvider provider = SourceProvider.AWS;
        String id = "stub-parser";
        String semver = "1.0.0";
        Set<FieldPath> declared = Set.of();
        Function<RawRecord, ParseResult> behaviour = r -> ParseResult.Parsed.of(event(r.body().path("id").asText("k")));
        final List<FormatKey> seen = new ArrayList<>();

        Parser range(VersionRange r) {
            this.range = r;
            return this;
        }

        Parser id(String value) {
            this.id = value;
            return this;
        }

        Parser behaviour(Function<RawRecord, ParseResult> b) {
            this.behaviour = b;
            return this;
        }

        @Override
        public EnvelopeKey envelopeKey() {
            return key;
        }

        @Override
        public VersionRange supports() {
            return range;
        }

        @Override
        public SourceProvider sourceProvider() {
            return provider;
        }

        @Override
        public String parserId() {
            return id;
        }

        @Override
        public String parserSemver() {
            return semver;
        }

        @Override
        public Set<FieldPath> declaredPaths(String effectiveVersion) {
            return declared;
        }

        @Override
        public ParseResult parse(RawRecord record, FormatKey effective, BindingConfig binding) {
            seen.add(effective);
            return behaviour.apply(record);
        }
    }
}
