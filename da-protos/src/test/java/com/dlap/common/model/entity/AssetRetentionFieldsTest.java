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

package com.dlap.common.model.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * ADR-0046 invariants on the {@link Asset} entity: every new time and size field starts unknown,
 * retention starts {@link RetentionStatus#UNKNOWN}, a legal hold always wins, and placing or
 * releasing a hold touches nothing but the hold and the evaluation result.
 */
class AssetRetentionFieldsTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    static Stream<Named<Asset>> everyWayToConstruct() {
        return Stream.of(
                Named.of("no-arg constructor", new Asset()),
                Named.of("required-fields constructor", new Asset("gs://b", "GCP", "GCS_BUCKET")),
                Named.of("constructor with name", new Asset("gs://b", "GCP", "GCS_BUCKET", "b")),
                Named.of("builder", Asset.builder()
                        .assetUri("gs://b").cloudProvider("GCP").serviceType("GCS_BUCKET").build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyWayToConstruct")
    void newAssetKnowsNothingAboutTimeSizeOrRetention(Asset asset) {
        assertThat(asset.getCloudCreatedAt()).isNull();
        assertThat(asset.getCloudCreatedSource()).isNull();
        assertThat(asset.getSizeBytes()).isNull();
        assertThat(asset.getSizeObservedAt()).isNull();
        assertThat(asset.getLastAccessedAt()).isNull();
        assertThat(asset.getLastAccessSource()).isNull();
        assertThat(asset.getLastModifiedAt()).isNull();
        assertThat(asset.getLastPermissionChangeAt()).isNull();
        assertThat(asset.getLifecycleEventTime()).isNull();
        assertThat(asset.getRecreatedAt()).isNull();
        assertThat(asset.getDiscoverySource()).isNull();
        assertThat(asset.getRetentionDays()).isNull();
        assertThat(asset.getRetentionBasis()).isNull();
        assertThat(asset.getRetentionSource()).isNull();
        assertThat(asset.getRetentionPolicyId()).isNull();
        assertThat(asset.getRetentionExpiresAt()).isNull();
        assertThat(asset.getRetentionEvaluatedAt()).isNull();
        assertThat(asset.getRetentionStatus()).isEqualTo(RetentionStatus.UNKNOWN);
        // Also the raw field: Lombok's @Builder.Default must reach the no-arg constructor too.
        assertThat(storedStatus(asset)).isEqualTo(RetentionStatus.UNKNOWN);
        assertThat(asset.isLegalHold()).isFalse();
        assertThat(asset.retentionPermitsRetire()).isFalse();
    }

    @Test
    void prePersistFillsUnknownWhenTheStatusWasCleared() {
        Asset asset = new Asset("s3://b", "AWS", "S3_BUCKET");
        asset.setRetentionStatus(null);

        asset.onCreate();

        assertThat(storedStatus(asset)).isEqualTo(RetentionStatus.UNKNOWN);
    }

    @Test
    void preUpdateForcesOnHoldWhenTheHoldFlagWasSetDirectly() {
        Asset asset = new Asset("s3://b", "AWS", "S3_BUCKET");
        asset.setRetentionStatus(RetentionStatus.DUE);
        asset.setLegalHold(true);

        asset.onUpdate();

        // The stored field, not only the getter, satisfies ck_asset_legal_hold_status.
        assertThat(storedStatus(asset)).isEqualTo(RetentionStatus.ON_HOLD);
    }

    @ParameterizedTest
    @EnumSource(RetentionStatus.class)
    void legalHoldOverridesEveryStatus(RetentionStatus stored) {
        Asset asset = new Asset("s3://b", "AWS", "S3_BUCKET");
        asset.setRetentionStatus(stored);
        asset.setLegalHold(true);

        assertThat(asset.getRetentionStatus()).isEqualTo(RetentionStatus.ON_HOLD);
        assertThat(asset.retentionPermitsRetire()).isFalse();
    }

    @Test
    void onlyADueAssetWithoutHoldMayBeRetired() {
        Asset asset = new Asset("bq://p/d/t", "GCP", "BIGQUERY_TABLE");
        asset.setRetentionStatus(RetentionStatus.DUE);
        assertThat(asset.retentionPermitsRetire()).isTrue();

        asset.setRetentionStatus(RetentionStatus.RETAINED);
        assertThat(asset.retentionPermitsRetire()).isFalse();

        asset.setRetentionStatus(RetentionStatus.UNKNOWN);
        assertThat(asset.retentionPermitsRetire()).isFalse();
    }

    @Test
    void placingAHoldTakesEffectBeforeTheNextEvaluation() {
        Asset asset = dueAsset();

        asset.placeLegalHold();

        assertThat(asset.isLegalHold()).isTrue();
        assertThat(storedStatus(asset)).isEqualTo(RetentionStatus.ON_HOLD);
        assertThat(asset.retentionPermitsRetire()).isFalse();
        // The last evaluation's deciding rule and expiry stay, for information.
        assertThat(asset.getRetentionDays()).isEqualTo(30);
        assertThat(asset.getRetentionExpiresAt()).isNotNull();
    }

    @Test
    void placingAHoldChangesOnlyTheHoldAndTheStatus() {
        Asset asset = assetWithEveryFieldSet();
        ReflectionTestUtils.setField(asset, "legalHold", false);
        ReflectionTestUtils.setField(asset, "retentionStatus", RetentionStatus.DUE);
        Map<String, Object> before = snapshot(asset);

        asset.placeLegalHold();

        assertThat(changedFields(before, snapshot(asset)))
                .containsExactlyInAnyOrder("legalHold", "retentionStatus");
    }

    /**
     * Rules are kept at their source, so the asset row holds only evidence and evaluation output
     * (ADR-0046). A release must clear the result, which needs re-evaluation, and keep everything
     * else: every evidence field the evaluator reads (the basis times, technicalMetadata with its
     * provider settings) and the deciding-rule fields. The deciding rule is a MANUAL FIXED_DATE
     * one, the case whose date could be lost; retentionDays is set anyway so that every field is
     * non-null.
     */
    @Test
    void releasingAHoldClearsOnlyTheEvaluationResult() {
        Asset asset = assetWithEveryFieldSet();
        asset.setRetentionBasis(RetentionBasis.FIXED_DATE);
        asset.setRetentionSource("MANUAL");
        asset.placeLegalHold();
        Map<String, Object> before = snapshot(asset);
        assertThat(before).as("every field is set, so any change is visible").doesNotContainValue(null);

        asset.releaseLegalHold();

        Map<String, Object> after = snapshot(asset);
        assertThat(changedFields(before, after)).containsExactlyInAnyOrder(
                "legalHold", "retentionStatus", "retentionExpiresAt", "retentionEvaluatedAt");
        assertThat(after.get("legalHold")).isEqualTo(false);
        assertThat(after.get("retentionStatus")).isEqualTo(RetentionStatus.UNKNOWN);
        assertThat(after.get("retentionExpiresAt")).isNull();
        assertThat(after.get("retentionEvaluatedAt")).isNull();
        assertThat(asset.retentionPermitsRetire()).isFalse();
    }

    @Test
    void releasingAHoldNeedsAFreshEvaluationBeforeRetire() {
        Asset asset = dueAsset();
        asset.placeLegalHold();

        asset.releaseLegalHold();

        assertThat(asset.isLegalHold()).isFalse();
        assertThat(asset.getRetentionStatus()).isEqualTo(RetentionStatus.UNKNOWN);
        assertThat(asset.getRetentionEvaluatedAt()).isNull();
        assertThat(asset.getRetentionExpiresAt()).isNull();
        assertThat(asset.retentionPermitsRetire()).isFalse();
        assertThat(asset.getRetentionDays()).isEqualTo(30);
        assertThat(asset.getRetentionBasis()).isEqualTo(RetentionBasis.CREATED);
        assertThat(asset.getRetentionSource()).isEqualTo("POLICY");
    }

    @Test
    void negativeSizeAndRetentionDaysAreRejected() {
        Asset asset = new Asset("gs://b", "GCP", "GCS_BUCKET");
        asset.setSizeBytes(-1L);
        asset.setRetentionDays(-1);

        Set<ConstraintViolation<Asset>> violations = validator.validate(asset);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("sizeBytes", "retentionDays");
    }

    @Test
    void zeroSizeIsARealSize() {
        Asset asset = new Asset("gs://b", "GCP", "GCS_BUCKET");
        asset.setSizeBytes(0L);
        asset.setRetentionDays(0);

        assertThat(validator.validate(asset)).isEmpty();
    }

    @Test
    void sourceColumnsAreBoundedTo64Characters() {
        Asset asset = new Asset("gs://b", "GCP", "GCS_BUCKET");
        String tooLong = "A".repeat(65);
        asset.setCloudCreatedSource(tooLong);
        asset.setLastAccessSource(tooLong);
        asset.setDiscoverySource(tooLong);
        asset.setRetentionSource(tooLong);

        assertThat(validator.validate(asset)).extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder(
                        "cloudCreatedSource", "lastAccessSource", "discoverySource", "retentionSource");
    }

    /** The field Hibernate writes, bypassing the null and hold guards in the getter. */
    private static Object storedStatus(Asset asset) {
        return ReflectionTestUtils.getField(asset, "retentionStatus");
    }

    /** Every persistent field of {@link Asset}, including any added later. */
    private static List<Field> instanceFields() {
        return Arrays.stream(Asset.class.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()) && !f.isSynthetic())
                .toList();
    }

    /** An asset whose every field holds a distinct non-null value, so a change to any shows. */
    private static Asset assetWithEveryFieldSet() {
        Asset asset = new Asset();
        int n = 0;
        for (Field field : instanceFields()) {
            ReflectionTestUtils.setField(asset, field.getName(), sampleValue(field, ++n));
        }
        return asset;
    }

    private static Object sampleValue(Field field, int n) {
        Class<?> type = field.getType();
        if (type == String.class) {
            return "v" + n;
        } else if (type == Instant.class) {
            return Instant.parse("2026-01-01T00:00:00Z").plusSeconds(n);
        } else if (type == UUID.class) {
            return new UUID(0L, n);
        } else if (type == Long.class) {
            return (long) n;
        } else if (type == Integer.class) {
            return n;
        } else if (type == Map.class) {
            return Map.of("k", n);
        } else if (type == java.util.List.class) {
            return java.util.List.of("v" + n);
        } else if (type == boolean.class) {
            return false;
        } else if (type == RetentionBasis.class) {
            return RetentionBasis.CREATED;
        } else if (type == RetentionStatus.class) {
            return RetentionStatus.DUE;
        }
        throw new AssertionError("No sample value for " + field + "; extend sampleValue");
    }

    /** Field values by name; a {@link HashMap} because unset fields are {@code null}. */
    private static Map<String, Object> snapshot(Asset asset) {
        Map<String, Object> values = new HashMap<>();
        for (Field field : instanceFields()) {
            values.put(field.getName(), ReflectionTestUtils.getField(asset, field.getName()));
        }
        return values;
    }

    private static Set<String> changedFields(Map<String, Object> before, Map<String, Object> after) {
        return before.keySet().stream()
                .filter(name -> !Objects.equals(before.get(name), after.get(name)))
                .collect(Collectors.toSet());
    }

    private static Asset dueAsset() {
        Asset asset = new Asset("gs://b", "GCP", "GCS_BUCKET");
        asset.setCloudCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        asset.setRetentionDays(30);
        asset.setRetentionBasis(RetentionBasis.CREATED);
        asset.setRetentionSource("POLICY");
        asset.setRetentionPolicyId(UUID.randomUUID());
        asset.setRetentionExpiresAt(Instant.parse("2026-01-31T00:00:00Z"));
        asset.setRetentionEvaluatedAt(Instant.parse("2026-03-01T00:00:00Z"));
        asset.setRetentionStatus(RetentionStatus.DUE);
        return asset;
    }
}
