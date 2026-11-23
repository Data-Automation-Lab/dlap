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

package com.dlap.catalog.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The retention-due message follows docs/specs/asset-descriptor.md "Retention-due topic": camelCase
 * keys, no null fields, and an event id that is the SHA-256 of
 * {@code assetId|previousStatus|status|evaluatedAt} so a resent transition keeps its id.
 */
class RetentionDueEventTest {

    private static final UUID ASSET = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID POLICY = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final Instant EVALUATED = Instant.parse("2026-10-20T12:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static RetentionEvaluation due() {
        RetentionRule rule = RetentionRule.ofPolicy(RetentionBasis.CREATED, 365, POLICY);
        return new RetentionEvaluation(RetentionStatus.DUE, RetentionEvaluation.EXPIRY_PASSED, rule,
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void eventIdIsTheSha256OfAssetPreviousStatusStatusAndEvaluatedAt() throws Exception {
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("11111111-2222-3333-4444-555555555555|RETAINED|DUE|2026-10-20T12:00:00Z"
                        .getBytes(StandardCharsets.UTF_8)));

        RetentionDueEvent event = RetentionDueEvent.of(ASSET, "gs://b", "GCP", "GCS_BUCKET", RetentionStatus.RETAINED,
                due(), false, EVALUATED);

        assertThat(event.eventId()).isEqualTo(expected).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(event.eventId()).isEqualTo(RetentionDueEvent.eventId(ASSET, RetentionStatus.RETAINED,
                RetentionStatus.DUE, EVALUATED));
    }

    @Test
    void aTransitionSentAgainKeepsItsIdAndADifferentOneDoesNot() {
        RetentionDueEvent a = RetentionDueEvent.of(ASSET, "gs://b", "GCP", "GCS_BUCKET", RetentionStatus.UNKNOWN,
                due(), false, EVALUATED);
        RetentionDueEvent again = RetentionDueEvent.of(ASSET, "gs://b", "GCP", "GCS_BUCKET", RetentionStatus.UNKNOWN,
                due(), false, EVALUATED);
        RetentionDueEvent other = RetentionDueEvent.of(ASSET, "gs://b", "GCP", "GCS_BUCKET", RetentionStatus.RETAINED,
                due(), false, EVALUATED);
        RetentionDueEvent later = RetentionDueEvent.of(ASSET, "gs://b", "GCP", "GCS_BUCKET", RetentionStatus.UNKNOWN,
                due(), false, EVALUATED.plusSeconds(1));

        assertThat(a.eventId()).isEqualTo(again.eventId());
        assertThat(a.eventId()).isNotEqualTo(other.eventId()).isNotEqualTo(later.eventId());
    }

    @Test
    void payloadCarriesTheSpecifiedKeysKeyedByTheAssetUri() throws Exception {
        RetentionDueEvent event = RetentionDueEvent.of(ASSET, "gs://b", "GCP", "GCS_BUCKET", RetentionStatus.RETAINED,
                due(), false, EVALUATED);

        JsonNode json = JSON.readTree(event.payload());

        assertThat(event.key()).isEqualTo("gs://b");
        assertThat(json.get("schemaVersion").asText()).isEqualTo("v1");
        assertThat(json.get("eventId").asText()).isEqualTo(event.eventId());
        assertThat(json.get("assetId").asText()).isEqualTo(ASSET.toString());
        assertThat(json.get("assetUri").asText()).isEqualTo("gs://b");
        assertThat(json.get("cloudProvider").asText()).isEqualTo("GCP");
        assertThat(json.get("serviceType").asText()).isEqualTo("GCS_BUCKET");
        assertThat(json.get("previousStatus").asText()).isEqualTo("RETAINED");
        assertThat(json.get("status").asText()).isEqualTo("DUE");
        assertThat(json.get("statusReason").asText()).isEqualTo("EXPIRY_PASSED");
        assertThat(json.get("retentionDays").asInt()).isEqualTo(365);
        assertThat(json.get("retentionBasis").asText()).isEqualTo("CREATED");
        assertThat(json.get("retentionSource").asText()).isEqualTo("POLICY");
        assertThat(json.get("retentionPolicyId").asText()).isEqualTo(POLICY.toString());
        assertThat(json.get("expiresAt").asText()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(json.get("legalHold").asBoolean()).isFalse();
        assertThat(json.get("evaluatedAt").asText()).isEqualTo("2026-10-20T12:00:00Z");
    }

    @Test
    void nullFieldsAreOmittedNotWrittenAsNull() throws Exception {
        RetentionEvaluation unknown = new RetentionEvaluation(RetentionStatus.UNKNOWN,
                RetentionEvaluation.READ_COVERAGE_NOT_CAPTURED, null, null);

        JsonNode json = JSON.readTree(RetentionDueEvent.of(ASSET, "gs://b", "GCP", "GCS_BUCKET",
                RetentionStatus.DUE, unknown, true, EVALUATED).payload());

        assertThat(json.has("retentionDays")).isFalse();
        assertThat(json.has("retentionBasis")).isFalse();
        assertThat(json.has("retentionSource")).isFalse();
        assertThat(json.has("retentionPolicyId")).isFalse();
        assertThat(json.has("expiresAt")).isFalse();
        assertThat(json.get("status").asText()).isEqualTo("UNKNOWN");
        assertThat(json.get("legalHold").asBoolean()).isTrue();
        json.fields().forEachRemaining(f -> assertThat(f.getValue().isNull()).isFalse());
    }

    @Test
    void aFixedDateRuleHasNoDays() throws Exception {
        RetentionEvaluation fixed = new RetentionEvaluation(RetentionStatus.RETAINED, RetentionEvaluation.NOT_EXPIRED,
                RetentionRule.ofFixedDate(RuleSource.PROVIDER, Instant.parse("2027-03-01T00:00:00Z")),
                Instant.parse("2027-03-01T00:00:00Z"));

        JsonNode json = JSON.readTree(RetentionDueEvent.of(ASSET, "bq://p/d/t", "GCP", "BIGQUERY_TABLE",
                RetentionStatus.UNKNOWN, fixed, false, EVALUATED).payload());

        assertThat(json.has("retentionDays")).isFalse();
        assertThat(json.get("retentionBasis").asText()).isEqualTo("FIXED_DATE");
        assertThat(json.get("retentionSource").asText()).isEqualTo("PROVIDER");
    }
}
