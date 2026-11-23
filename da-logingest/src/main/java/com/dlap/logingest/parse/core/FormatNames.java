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

import java.util.regex.Pattern;

/**
 * Syntax of the open-string identifiers of the parser contract (ADR-0045 decision 8, ADR-0054).
 *
 * <p>Source kinds, envelopes, versions, families and binding ids are strings, not Java enums, so a
 * new log family adds classes and a mapping YAML and never edits a central switch. These patterns
 * keep the strings safe to use in {@code source_format}, in hash inputs ({@code |} never appears)
 * and in fixture paths.
 */
public final class FormatNames {

    /** Source kind: upper snake case, for example {@code AWS_CLOUDTRAIL} (same rule as {@code SourceFormats}). */
    public static final Pattern SOURCE_KIND = Pattern.compile("[A-Z][A-Z0-9_]*");

    /** Envelope: lower kebab case, for example {@code s3-file} (same rule as {@code SourceFormats}). */
    public static final Pattern ENVELOPE = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /**
     * Schema version as detected on a record or listed in a mapping: {@code 1.11},
     * {@code bq-auditmetadata}. Also a valid fixture directory segment.
     */
    public static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** Mapping family: lower kebab case, the directory under {@code dlap/log-formats/}. */
    public static final Pattern FAMILY = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /** Binding id: no whitespace and no {@code |}, at most 128 characters. */
    public static final Pattern BINDING_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    /** Longest version string accepted, so a hostile record cannot grow labels or keys. */
    public static final int MAX_VERSION_LENGTH = 64;

    private FormatNames() {
    }

    /**
     * Checks a source kind.
     *
     * @param sourceKind the value
     * @return the value
     * @throws IllegalArgumentException when it is not upper snake case
     */
    public static String requireSourceKind(String sourceKind) {
        return require(sourceKind, SOURCE_KIND, "source kind", Integer.MAX_VALUE);
    }

    /**
     * Checks an envelope id.
     *
     * @param envelope the value
     * @return the value
     * @throws IllegalArgumentException when it is not lower kebab case
     */
    public static String requireEnvelope(String envelope) {
        return require(envelope, ENVELOPE, "envelope", Integer.MAX_VALUE);
    }

    /**
     * Checks a schema version.
     *
     * @param version the value
     * @return the value
     * @throws IllegalArgumentException when it breaks {@link #VERSION} or is longer than
     *                                  {@link #MAX_VERSION_LENGTH}
     */
    public static String requireVersion(String version) {
        return require(version, VERSION, "schema version", MAX_VERSION_LENGTH);
    }

    /**
     * Checks a binding id.
     *
     * @param bindingId the value
     * @return the value
     * @throws IllegalArgumentException when it breaks {@link #BINDING_ID}
     */
    public static String requireBindingId(String bindingId) {
        return require(bindingId, BINDING_ID, "binding id", Integer.MAX_VALUE);
    }

    /**
     * Whether a string is a valid schema version.
     *
     * @param version the candidate; may be {@code null}
     * @return true when {@link #requireVersion} would accept it
     */
    public static boolean isVersion(String version) {
        return version != null && version.length() <= MAX_VERSION_LENGTH && VERSION.matcher(version).matches();
    }

    private static String require(String value, Pattern pattern, String what, int maxLength) {
        if (value == null || value.length() > maxLength || !pattern.matcher(value).matches()) {
            String limit = maxLength == Integer.MAX_VALUE ? "" : " and be at most " + maxLength + " characters";
            throw new IllegalArgumentException(what + " must match " + pattern.pattern() + limit);
        }
        return value;
    }
}
