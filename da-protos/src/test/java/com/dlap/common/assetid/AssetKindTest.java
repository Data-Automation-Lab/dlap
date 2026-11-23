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

package com.dlap.common.assetid;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.common.constants.DlapConstants;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * The kind list is the frozen wave-1 contract (ADR-0047): every existing service type plus eight
 * new kinds, and every kind maps to exactly one {@code dlap_assets.service_type} value.
 */
class AssetKindTest {

    /** The eight kinds frozen for wave 1 that CAT-01 adds to the CHECK constraint. */
    private static final Set<String> FROZEN_NEW_KINDS = Set.of(
            "S3_OBJECT", "GCS_OBJECT", "BLOB_CONTAINER", "BLOB_OBJECT",
            "GLUE_DATABASE", "GLUE_TABLE", "UNITY_VOLUME", "SNOWFLAKE_TABLE");

    private static Set<String> stringConstants(Class<?> holder) {
        Set<String> out = new HashSet<>();
        for (Field f : holder.getDeclaredFields()) {
            int m = f.getModifiers();
            if (Modifier.isPublic(m) && Modifier.isStatic(m) && Modifier.isFinal(m) && f.getType() == String.class) {
                try {
                    out.add((String) f.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError(e);
                }
            }
        }
        return out;
    }

    @Test
    void kindsAreTheExistingServiceTypesPlusTheFrozenNewKinds() {
        Set<String> existing = stringConstants(DlapConstants.ServiceType.class);
        Set<String> expected = new HashSet<>(existing);
        expected.addAll(FROZEN_NEW_KINDS);

        Set<String> serviceTypes = Arrays.stream(AssetKind.values()).map(AssetKind::serviceType).collect(Collectors.toSet());

        // A later DlapConstants edit that adds the frozen kinds keeps this green; any other new
        // service type without an AssetKind fails it.
        assertThat(serviceTypes).isEqualTo(expected);
        assertThat(AssetKind.values()).hasSize(30);
    }

    @Test
    void serviceTypeLookupRoundTripsAndIgnoresCase() {
        for (AssetKind k : AssetKind.values()) {
            assertThat(k.serviceType()).isEqualTo(k.name());
            assertThat(AssetKind.fromServiceType(k.serviceType())).contains(k);
            assertThat(AssetKind.fromServiceType(" " + k.serviceType().toLowerCase() + " ")).contains(k);
        }
        assertThat(AssetKind.fromServiceType(null)).isEmpty();
        assertThat(AssetKind.fromServiceType("")).isEmpty();
        assertThat(AssetKind.fromServiceType("EC2_INSTANCE")).isEmpty();
    }

    @Test
    void jobKindsMatchTheSharedJobSet() {
        Set<String> jobs = Arrays.stream(AssetKind.values()).filter(AssetKind::isJob).map(AssetKind::serviceType)
                .collect(Collectors.toSet());

        assertThat(jobs).isEqualTo(DlapConstants.ServiceType.JOB_SERVICE_TYPES);
    }

    @Test
    void objectLevelKindsAreTheThreeObjectKinds() {
        Set<AssetKind> objects = Arrays.stream(AssetKind.values()).filter(AssetKind::isObjectLevel).collect(Collectors.toSet());

        assertThat(objects).containsExactlyInAnyOrder(AssetKind.S3_OBJECT, AssetKind.GCS_OBJECT, AssetKind.BLOB_OBJECT);
        objects.forEach(k -> assertThat(k.parentKind()).isPresent());
    }

    @Test
    void providersAreTheExistingCloudProvidersPlusDatabricksAndSnowflake() {
        Set<String> existing = stringConstants(DlapConstants.CloudProvider.class);
        Set<String> names = Arrays.stream(AssetProvider.values()).map(AssetProvider::cloudProvider).collect(Collectors.toSet());

        Set<String> expected = new HashSet<>(existing);
        expected.add("DATABRICKS");
        expected.add("SNOWFLAKE");
        assertThat(names).isEqualTo(expected);
    }

    @Test
    void providerAliasesParse() {
        assertThat(AssetProvider.fromString("google")).contains(AssetProvider.GCP);
        assertThat(AssetProvider.fromString("Amazon Web Services")).contains(AssetProvider.AWS);
        assertThat(AssetProvider.fromString("microsoft-azure")).contains(AssetProvider.AZURE);
        assertThat(AssetProvider.fromString("unity_catalog")).contains(AssetProvider.UNITY);
        assertThat(AssetProvider.fromString("Databricks")).contains(AssetProvider.DATABRICKS);
        assertThat(AssetProvider.fromString("snowflake")).contains(AssetProvider.SNOWFLAKE);
        assertThat(AssetProvider.fromString("oracle")).contains(AssetProvider.OCI);
        assertThat(AssetProvider.fromString("UNKNOWN")).isEmpty();
        assertThat(AssetProvider.fromString(null)).isEmpty();
        assertThat(AssetProvider.fromString(" ")).isEmpty();
    }

    @Test
    void singleProviderKindsNameTheirProvider() {
        assertThat(AssetKind.S3_OBJECT.defaultProvider()).contains(AssetProvider.AWS);
        assertThat(AssetKind.GCS_OBJECT.defaultProvider()).contains(AssetProvider.GCP);
        assertThat(AssetKind.BLOB_OBJECT.defaultProvider()).contains(AssetProvider.AZURE);
        assertThat(AssetKind.UNITY_VOLUME.defaultProvider()).contains(AssetProvider.UNITY);
        assertThat(AssetKind.SNOWFLAKE_TABLE.defaultProvider()).contains(AssetProvider.SNOWFLAKE);
        assertThat(AssetKind.DATABRICKS_JOB.defaultProvider()).contains(AssetProvider.DATABRICKS);
        assertThat(AssetKind.SPARK_JOB.defaultProvider()).isEmpty();
        assertThat(AssetKind.DATAFLOW_JOB.defaultProvider()).isEmpty();
    }
}
