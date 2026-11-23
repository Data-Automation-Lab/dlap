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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.dlap.common.constants.DlapConstants;
import com.dlap.common.model.RetentionBasis;
import com.dlap.common.model.RetentionStatus;

import jakarta.persistence.Column;

/**
 * Text-level contract between the dlap_common scripts, the Java enums and the frozen wave-1 kind
 * list (ADR-0046, ADR-0047). Runs without Docker; {@link AssetSchemaUpgradeContainerTest} checks
 * the same scripts against a real Postgres.
 */
class CommonSchemaScriptContractTest {

    /** Providers frozen for wave 1 beyond {@code DlapConstants.CloudProvider} at sdg 7984d1d. */
    private static final List<String> FROZEN_PROVIDERS = List.of("DATABRICKS", "SNOWFLAKE");

    /** Kinds frozen for wave 1 beyond {@code DlapConstants.ServiceType} at sdg 7984d1d. */
    private static final List<String> FROZEN_KINDS = List.of(
            "S3_OBJECT", "GCS_OBJECT", "BLOB_CONTAINER", "BLOB_OBJECT",
            "GLUE_DATABASE", "GLUE_TABLE", "UNITY_VOLUME", "SNOWFLAKE_TABLE");

    /** The Asset columns ADR-0046 adds; each must exist in 02 and in the delta. */
    private static final Set<String> ADR_0046_COLUMNS = Set.of(
            "cloud_created_at", "cloud_created_source", "size_bytes", "size_observed_at",
            "last_accessed_at", "last_access_source", "last_modified_at", "last_permission_change_at",
            "lifecycle_event_time", "recreated_at", "discovery_source",
            "retention_days", "retention_basis", "retention_source", "retention_policy_id",
            "retention_expires_at", "retention_status", "retention_evaluated_at", "legal_hold");

    private final String commonSchema = SchemaScripts.commonSchema();
    private final String delta = SchemaScripts.assetTimeDelta();

    @Test
    void providerCheckIsTheConstantsPlusTheFrozenProviders() {
        Set<String> expected = new LinkedHashSet<>(stringConstants(DlapConstants.CloudProvider.class));
        expected.addAll(FROZEN_PROVIDERS);

        List<String> inInit = SchemaScripts.checkValues(commonSchema, "ck_asset_cloud_provider_valid");

        assertThat(inInit).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        assertThat(SchemaScripts.checkValues(delta, "ck_asset_cloud_provider_valid")).isEqualTo(inInit);
    }

    @Test
    void serviceTypeCheckIsTheConstantsPlusTheFrozenKinds() {
        Set<String> expected = new LinkedHashSet<>(stringConstants(DlapConstants.ServiceType.class));
        expected.addAll(FROZEN_KINDS);

        List<String> inInit = SchemaScripts.checkValues(commonSchema, "ck_asset_service_type_valid");

        assertThat(inInit).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expected);
        assertThat(SchemaScripts.checkValues(delta, "ck_asset_service_type_valid")).isEqualTo(inInit);
    }

    @Test
    void retentionChecksListExactlyTheEnumValues() {
        assertThat(SchemaScripts.checkValues(commonSchema, "ck_asset_retention_status_valid"))
                .containsExactlyElementsOf(names(RetentionStatus.values()));
        assertThat(SchemaScripts.checkValues(commonSchema, "ck_asset_retention_basis_valid"))
                .containsExactlyElementsOf(names(RetentionBasis.values()));
    }

    @Test
    void retentionStatusDefaultsToUnknownAndHoldToFalse() {
        String block = SchemaScripts.adr0046Block(commonSchema);
        assertThat(block).containsPattern(
                "ADD COLUMN IF NOT EXISTS retention_status\\s+VARCHAR\\(20\\) NOT NULL DEFAULT '"
                        + RetentionStatus.UNKNOWN.name() + "'");
        assertThat(block).containsPattern(
                "ADD COLUMN IF NOT EXISTS legal_hold\\s+BOOLEAN NOT NULL DEFAULT FALSE");
    }

    @Test
    void deltaCarriesTheSameAdr0046BlockAsTheInitScript() {
        assertThat(SchemaScripts.adr0046Block(delta)).isEqualTo(SchemaScripts.adr0046Block(commonSchema));
    }

    @Test
    void everyAdr0046ColumnIsAddedAndMappedOnAsset() {
        Set<String> mapped = Arrays.stream(Asset.class.getDeclaredFields())
                .map(f -> f.getAnnotation(Column.class))
                .filter(c -> c != null)
                .map(Column::name)
                .collect(Collectors.toSet());
        String block = SchemaScripts.adr0046Block(commonSchema);
        for (String column : ADR_0046_COLUMNS) {
            assertThat(mapped).as("Asset maps %s", column).contains(column);
            assertThat(block).as("02 adds %s", column)
                    .containsPattern("ADD COLUMN IF NOT EXISTS " + column + "\\s");
            assertThat(block).as("02 documents %s", column)
                    .contains("COMMENT ON COLUMN dlap_assets." + column + " IS");
        }
    }

    @Test
    void retentionRuleAndResultColumnsAreDocumentedAsOutputOnly() {
        // ADR-0046: rules are kept at their source; these columns record the deciding rule and
        // the result of the last evaluation. The column comments must say so.
        String block = SchemaScripts.adr0046Block(commonSchema);
        for (String column : List.of("retention_days", "retention_basis", "retention_source",
                "retention_policy_id", "retention_expires_at")) {
            assertThat(block).as("comment on %s", column)
                    .containsPattern("COMMENT ON COLUMN dlap_assets\\." + column + " IS '[^']*Output only");
        }
    }

    @Test
    void deltaRunsAgainstWhicheverDatabaseTheCallerConnectsTo() {
        // No psql meta-commands (\c, \set): the upgrade runner and the container test connect
        // to the target database themselves.
        assertThat(delta.lines().map(String::strip)).noneMatch(line -> line.startsWith("\\"));
        assertThat(delta).contains("SET search_path TO dlap_common;").contains("RESET search_path;");
    }

    @Test
    void scriptsCarryNoEmDashOrEnDash() {
        for (String sql : List.of(commonSchema, delta)) {
            assertThat(sql).doesNotContain("\u2014").doesNotContain("\u2013");
        }
    }

    private static List<String> stringConstants(Class<?> holder) {
        return Arrays.stream(holder.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers()))
                .filter(f -> f.getType() == String.class)
                .map(CommonSchemaScriptContractTest::value)
                .toList();
    }

    private static String value(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
