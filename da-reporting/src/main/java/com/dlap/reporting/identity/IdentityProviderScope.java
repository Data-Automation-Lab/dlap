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

package com.dlap.reporting.identity;

import java.util.Locale;
import java.util.Optional;

/**
 * The provider a map entry applies to: one cloud or data platform, or {@link #ANY}. The names of
 * the platforms are those of {@code dlap.log.v1.SourceProvider}, the field a log event names its
 * source with.
 */
public enum IdentityProviderScope {

    /** Every provider: the entry matches a principal whatever log it came from. */
    ANY,
    /** Amazon Web Services. */
    AWS,
    /** Google Cloud. */
    GCP,
    /** Microsoft Azure and Entra ID. */
    AZURE,
    /** Oracle Cloud. */
    OCI,
    /** Open-source Unity Catalog. */
    UNITY,
    /** Databricks. */
    DATABRICKS,
    /** Snowflake. */
    SNOWFLAKE;

    /**
     * The scope of a provider name, ignoring case and surrounding white space.
     *
     * @param name a scope name such as {@code GCP} or {@code any}; may be {@code null}
     * @return the scope, or empty when the name is blank or unknown
     */
    public static Optional<IdentityProviderScope> parse(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(name.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
