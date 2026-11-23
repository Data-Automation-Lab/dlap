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

package com.dlap.catalog.coverage;

import java.util.Locale;
import java.util.Optional;

/**
 * Read-logging coverage of an asset (COV-01, spec "Read coverage" in
 * {@code docs/specs/asset-descriptor.md}). Stored by name in {@code dlap_assets.read_coverage}.
 * Unknown is not a level: it is {@code null} in the column and {@code UNKNOWN} on the wire.
 */
public enum ReadCoverageLevel {

    /** The provider configuration and a DLAP binding prove that every data read is recorded and ingested. */
    FULL,
    /** Some reads are recorded and ingested, others are not; the reasons name the gap. */
    PARTIAL,
    /** No read of the asset reaches DLAP's read history. */
    NONE;

    /** Wire spelling of an unknown coverage. */
    public static final String UNKNOWN = "UNKNOWN";

    /**
     * Parses a wire value.
     *
     * @param value {@code FULL}, {@code PARTIAL}, {@code NONE} or {@code UNKNOWN}, in any case
     * @return the level, or empty for {@code UNKNOWN}
     * @throws IllegalArgumentException for any other value, including blank
     */
    public static Optional<ReadCoverageLevel> parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("read coverage level is missing");
        }
        String v = value.strip().toUpperCase(Locale.ROOT);
        if (UNKNOWN.equals(v)) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(v));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown read coverage level", e);
        }
    }
}
