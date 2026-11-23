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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/** The version rules: same major and a minor at or above the floor; newer minors; named sets. */
class VersionRangeTest {

    private static final VersionRange CLOUDTRAIL = VersionRange.majorMinor("1.0", "1.11");

    @ParameterizedTest(name = "{0} -> {1} {2}")
    @CsvSource({
        "1.0,  SUPPORTED,   1.0",
        "1.08, SUPPORTED,   1.08",
        "1.11, SUPPORTED,   1.11",
        "1,    SUPPORTED,   1",
        "1.12, NEWER_MINOR, 1.11",
        "1.99, NEWER_MINOR, 1.11",
        "2.0,  UNSUPPORTED, ",
        "0.9,  UNSUPPORTED, ",
        "1.x,  UNSUPPORTED, ",
        "v1.0, UNSUPPORTED, ",
    })
    void majorMinorMatching(String version, VersionMatch.Status status, String effective) {
        VersionMatch m = CLOUDTRAIL.match(version);
        assertThat(m.status()).isEqualTo(status);
        assertThat(m.effectiveVersion()).isEqualTo(effective);
    }

    @Test
    void missingOrHostileVersionsAreUnsupported() {
        assertThat(CLOUDTRAIL.match(null).accepted()).isFalse();
        assertThat(CLOUDTRAIL.match("").accepted()).isFalse();
        assertThat(CLOUDTRAIL.match("1." + "1".repeat(80)).accepted()).isFalse();
        assertThat(CLOUDTRAIL.match(" 1.0").accepted()).isFalse();
    }

    @Test
    void aFloorExcludesOlderMinors() {
        VersionRange r = VersionRange.majorMinor("1.05", "1.11");
        assertThat(r.match("1.04").accepted()).isFalse();
        assertThat(r.match("1.5").status()).isEqualTo(VersionMatch.Status.SUPPORTED);
    }

    @Test
    void spellingDoesNotMatterForMajorMinor() {
        assertThat(CLOUDTRAIL.sameVersion("1.08", "1.8")).isTrue();
        assertThat(CLOUDTRAIL.sameVersion("1", "1.0")).isTrue();
        assertThat(CLOUDTRAIL.sameVersion("1.08", "1.09")).isFalse();
        assertThat(MajorMinor.parse("1.08")).contains(new MajorMinor(1, 8));
        assertThat(new MajorMinor(1, 10).compareTo(new MajorMinor(1, 9))).isPositive();
    }

    @Test
    void namedVersionsAreExact() {
        VersionRange gcp = VersionRange.named("auditlog", "bq-auditmetadata", "bq-auditdata-legacy");
        assertThat(gcp.match("bq-auditmetadata").status()).isEqualTo(VersionMatch.Status.SUPPORTED);
        assertThat(gcp.match("BQ-AUDITMETADATA").accepted()).isFalse();
        assertThat(gcp.sameVersion("auditlog", "auditlog")).isTrue();
        assertThat(gcp.describe()).isEqualTo("{auditlog, bq-auditdata-legacy, bq-auditmetadata}");
    }

    @Test
    void overlapRules() {
        assertThat(CLOUDTRAIL.overlaps(VersionRange.majorMinor("1.5", "1.20"))).isTrue();
        assertThat(CLOUDTRAIL.overlaps(VersionRange.majorMinor("2.0", "2.1"))).isFalse();
        assertThat(CLOUDTRAIL.overlaps(VersionRange.named("1.0"))).as("mixed schemes").isTrue();
        assertThat(VersionRange.named("a", "b").overlaps(VersionRange.named("c"))).isFalse();
        assertThat(VersionRange.named("a", "b").overlaps(VersionRange.named("b"))).isTrue();
    }

    @Test
    void invalidRangesAreRefused() {
        assertThatThrownBy(() -> VersionRange.majorMinor("1.0", "2.0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VersionRange.majorMinor("1.5", "1.2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VersionRange.majorMinor("one", "1.2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VersionRange.named(java.util.List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
