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

import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.Origin;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The frozen rules of ADR-0054 that are plain values: open-string ids, republish, persist-raw
 * defaults, mismatch default.
 */
class FrozenRulesTest {

    @Test
    void sourceKindsEnvelopesAndVersionsAreOpenStringsWithASyntax() {
        assertThat(new FormatKey("AWS_CLOUDTRAIL", "s3-file", "1.11").sourceFormat()).isEqualTo("AWS_CLOUDTRAIL/s3-file");
        assertThat(new FormatKey("ANY_NEW_FAMILY", "some-envelope", "bq-auditmetadata").toString())
                .isEqualTo("ANY_NEW_FAMILY/some-envelope@bq-auditmetadata");
        assertThatThrownBy(() -> new FormatKey("aws_cloudtrail", "s3-file", "1.0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FormatKey("AWS", "S3_FILE", "1.0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FormatKey("AWS", "s3-file", "1|0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FormatKey("AWS", "s3-file", "1".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FormatNames.requireBindingId("a|b")).isInstanceOf(IllegalArgumentException.class);
        assertThat(FormatNames.requireBindingId("aws-111122223333")).isEqualTo("aws-111122223333");
    }

    @Test
    void aStoredEventIsRepublishedOnlyInReplay() {
        assertThat(IngestMode.STREAM.shouldPublish(false)).isTrue();
        assertThat(IngestMode.STREAM.shouldPublish(true)).isFalse();
        assertThat(IngestMode.BACKFILL.shouldPublish(true)).isFalse();
        assertThat(IngestMode.REPLAY.shouldPublish(true)).isTrue();
    }

    @Test
    void theIngestModeHeaderDecidesTheOrigin() {
        assertThat(IngestMode.HEADER).isEqualTo("dlap-ingest-mode");
        assertThat(IngestMode.fromHeader(null)).isEqualTo(IngestMode.STREAM);
        assertThat(IngestMode.fromHeader("replay")).isEqualTo(IngestMode.REPLAY);
        assertThat(IngestMode.STREAM.origin()).isEqualTo(Origin.ORIGIN_CLOUD_LOG);
        assertThat(IngestMode.BACKFILL.origin()).isEqualTo(Origin.ORIGIN_BACKFILL);
        assertThat(IngestMode.REPLAY.origin()).isEqualTo(Origin.ORIGIN_REPLAY);
        assertThatThrownBy(() -> IngestMode.fromHeader("live")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rawRecordsOfDataEventsAreKeptOnlyInQuarantineByDefault() {
        PersistRaw d = PersistRaw.DEFAULT;
        assertThat(d.effective(EventCategory.EVENT_CATEGORY_DATA_READ)).isEqualTo(PersistRaw.Policy.QUARANTINE_ONLY);
        assertThat(d.effective(EventCategory.EVENT_CATEGORY_DATA_WRITE)).isEqualTo(PersistRaw.Policy.QUARANTINE_ONLY);
        assertThat(d.effective(EventCategory.EVENT_CATEGORY_DATA_READ_OBSERVED)).isEqualTo(PersistRaw.Policy.QUARANTINE_ONLY);
        assertThat(d.effective(EventCategory.EVENT_CATEGORY_MANAGEMENT)).isEqualTo(PersistRaw.Policy.ALL);
        assertThat(d.effective(EventCategory.EVENT_CATEGORY_PERMISSION)).isEqualTo(PersistRaw.Policy.ALL);
        assertThat(d.persists(EventCategory.EVENT_CATEGORY_DATA_READ, "e")).isFalse();
        assertThat(d.persists(EventCategory.EVENT_CATEGORY_MANAGEMENT, "e")).isTrue();
        assertThat(BindingConfig.auto("b", "K").persistRaw()).isEqualTo(PersistRaw.DEFAULT);
    }

    @Test
    void aConfiguredPolicyAppliesToEveryCategory() {
        PersistRaw all = PersistRaw.of(PersistRaw.Policy.ALL);
        assertThat(all.persists(EventCategory.EVENT_CATEGORY_DATA_READ, "e")).isTrue();
        PersistRaw none = PersistRaw.of(PersistRaw.Policy.QUARANTINE_ONLY);
        assertThat(none.persists(EventCategory.EVENT_CATEGORY_MANAGEMENT, "e")).isFalse();
    }

    @Test
    void samplingIsDeterministicAndNearItsRate() {
        PersistRaw sampled = new PersistRaw(PersistRaw.Policy.SAMPLED, 10);
        long kept = IntStream.range(0, 10_000)
                .filter(i -> sampled.persists(EventCategory.EVENT_CATEGORY_DATA_READ, "event-" + i)).count();
        assertThat(kept).isBetween(850L, 1150L);
        assertThat(sampled.persists(EventCategory.EVENT_CATEGORY_DATA_READ, "event-7"))
                .isEqualTo(sampled.persists(EventCategory.EVENT_CATEGORY_DATA_READ, "event-7"));
        assertThat(new PersistRaw(PersistRaw.Policy.SAMPLED, 1).persists(EventCategory.EVENT_CATEGORY_DATA_READ, "x")).isTrue();
        assertThatThrownBy(() -> new PersistRaw(PersistRaw.Policy.SAMPLED, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theMismatchDefaultIsParseWithDetectedAndUnknownMajorIsNotConfigurable() {
        assertThat(OnMismatch.DEFAULT).isEqualTo(OnMismatch.PARSE_WITH_DETECTED);
        assertThat(BindingConfig.auto("b", "K").onMismatch()).isEqualTo(OnMismatch.PARSE_WITH_DETECTED);
        assertThat(OnMismatch.values()).extracting(Enum::name)
                .containsExactly("QUARANTINE", "PARSE_WITH_PIN", "PARSE_WITH_DETECTED");
    }

    @Test
    void theDefaultRecordLimitIsTheKafkaDefaultMessageSize() {
        assertThat(BindingConfig.DEFAULT_MAX_RECORD_BYTES).isEqualTo(1_048_576L);
        assertThatThrownBy(() -> BindingConfig.auto("b", "K").withMaxRecordBytes(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fieldOverridesNeedPaths() {
        assertThatThrownBy(() -> BindingConfig.auto("b", "K").withFieldOverrides(
                java.util.Map.of(CanonicalField.PRINCIPAL_ID, java.util.List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalFieldKeysAreStable() {
        assertThat(CanonicalField.fromKey("principal.account-id")).contains(CanonicalField.PRINCIPAL_ACCOUNT_ID);
        assertThat(CanonicalField.fromKey("principal.accountId")).isEmpty();
        assertThat(CanonicalField.values()).hasSize(20);
    }
}
