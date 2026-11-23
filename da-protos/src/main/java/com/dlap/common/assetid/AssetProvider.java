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

import java.util.Locale;
import java.util.Optional;

/**
 * The platform that owns an asset. The names are the values stored in
 * {@code dlap_assets.cloud_provider}.
 *
 * <p>{@link #AWS}, {@link #GCP}, {@link #AZURE}, {@link #OCI} and {@link #UNITY} match
 * {@code DlapConstants.CloudProvider}. {@link #DATABRICKS} and {@link #SNOWFLAKE} are the two
 * providers frozen for wave 1 (ADR-0047); CAT-01 adds them to the CHECK constraint.
 */
public enum AssetProvider {
    /** Amazon Web Services. */
    AWS,
    /** Google Cloud. */
    GCP,
    /** Microsoft Azure. */
    AZURE,
    /** Oracle Cloud Infrastructure. No canonical scheme is defined yet. */
    OCI,
    /** Unity Catalog securables (catalogs, schemas, tables, volumes). */
    UNITY,
    /** Databricks workspace objects (jobs, runs, SQL queries). */
    DATABRICKS,
    /** Snowflake objects. */
    SNOWFLAKE;

    /**
     * The value stored in {@code dlap_assets.cloud_provider}.
     *
     * @return the provider name, for example {@code "AWS"}
     */
    public String cloudProvider() {
        return name();
    }

    /**
     * Parses a provider name, accepting the aliases services emit today
     * ({@code GOOGLE}, {@code AMAZON}, {@code MICROSOFT_AZURE}, {@code UNITY_CATALOG} and so on).
     * Matching ignores case and surrounding whitespace.
     *
     * @param raw the provider text, may be {@code null}
     * @return the provider, or empty when the text is blank or unknown
     */
    public static Optional<AssetProvider> fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String v = raw.strip().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (v) {
            case "AWS", "AMAZON", "AMAZON_WEB_SERVICES" -> Optional.of(AWS);
            case "GCP", "GOOGLE", "GOOGLE_CLOUD", "GOOGLE_CLOUD_PLATFORM" -> Optional.of(GCP);
            case "AZURE", "MICROSOFT", "MICROSOFT_AZURE" -> Optional.of(AZURE);
            case "OCI", "ORACLE", "ORACLE_CLOUD" -> Optional.of(OCI);
            case "UNITY", "UNITY_CATALOG", "UC" -> Optional.of(UNITY);
            case "DATABRICKS", "DBX" -> Optional.of(DATABRICKS);
            case "SNOWFLAKE" -> Optional.of(SNOWFLAKE);
            default -> Optional.empty();
        };
    }
}
