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

import java.util.Objects;

/**
 * How a {@link VersionRange} answers for one version.
 *
 * @param status           supported, newer minor, or unsupported
 * @param effectiveVersion the version to parse as: the asked version when supported, the highest
 *                         known version for a newer minor, {@code null} when unsupported
 * @param detail           why a version is unsupported; never quotes record values
 */
public record VersionMatch(Status status, String effectiveVersion, String detail) {

    /** The three answers. */
    public enum Status {
        /** The version is inside the range. */
        SUPPORTED,
        /** Same major, minor above the highest known: parse as the highest known, with a warning. */
        NEWER_MINOR,
        /** No parser with this range may read the version. */
        UNSUPPORTED
    }

    /**
     * Validates the combination.
     *
     * @param status           see the record description
     * @param effectiveVersion see the record description
     * @param detail           see the record description
     */
    public VersionMatch {
        Objects.requireNonNull(status, "status");
        if (status == Status.UNSUPPORTED) {
            if (effectiveVersion != null) {
                throw new IllegalArgumentException("an unsupported match has no effective version");
            }
        } else {
            FormatNames.requireVersion(effectiveVersion);
        }
        detail = detail == null ? "" : detail;
    }

    /**
     * A supported version.
     *
     * @param version the version
     * @return the match
     */
    public static VersionMatch supported(String version) {
        return new VersionMatch(Status.SUPPORTED, version, "");
    }

    /**
     * A newer minor, parsed as the highest known version.
     *
     * @param highestKnown the version to parse as
     * @return the match
     */
    public static VersionMatch newerMinor(String highestKnown) {
        return new VersionMatch(Status.NEWER_MINOR, highestKnown, "minor above the highest known");
    }

    /**
     * An unsupported version.
     *
     * @param detail why
     * @return the match
     */
    public static VersionMatch unsupported(String detail) {
        return new VersionMatch(Status.UNSUPPORTED, null, detail);
    }

    /**
     * Whether a parser may read the version.
     *
     * @return true unless unsupported
     */
    public boolean accepted() {
        return status != Status.UNSUPPORTED;
    }
}
