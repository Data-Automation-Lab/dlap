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

package com.dlap.common.logevent;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Builds and splits {@code DlapLogEvent.source_format} values,
 * {@code "<SOURCE_KIND>/<envelope>"} (ADR-0045). Source kinds are open upper
 * snake case strings (decision D1), envelopes are lower kebab case; neither
 * contains {@code /}.
 */
public final class SourceFormats {

    /** Crawler observations bridged onto the spine by da-discovery. */
    public static final String DLAP_CRAWLER = "DLAP_CRAWLER/resource-event";

    /** Changes published by the legacy da-discovery log-analyzer stack. */
    public static final String DLAP_LOGANALYZER = "DLAP_LOGANALYZER/resource-change";

    private static final Pattern KIND = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern ENVELOPE = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private SourceFormats() {
    }

    /**
     * Joins a source kind and an envelope.
     *
     * @param sourceKind upper snake case, for example {@code AWS_CLOUDTRAIL}
     * @param envelope   lower kebab case, for example {@code s3-file}
     * @return {@code "<sourceKind>/<envelope>"}
     * @throws IllegalArgumentException when either part breaks the syntax
     */
    public static String of(String sourceKind, String envelope) {
        if (sourceKind == null || !KIND.matcher(sourceKind).matches()) {
            throw new IllegalArgumentException("source kind must be upper snake case: " + sourceKind);
        }
        if (envelope == null || !ENVELOPE.matcher(envelope).matches()) {
            throw new IllegalArgumentException("envelope must be lower kebab case: " + envelope);
        }
        return sourceKind + "/" + envelope;
    }

    /**
     * Returns the source kind of a {@code source_format} value.
     *
     * @param sourceFormat the field value
     * @return the kind, or empty when the value is blank or malformed
     */
    public static Optional<String> sourceKind(String sourceFormat) {
        return part(sourceFormat, 0);
    }

    /**
     * Returns the envelope of a {@code source_format} value.
     *
     * @param sourceFormat the field value
     * @return the envelope, or empty when the value is blank or malformed
     */
    public static Optional<String> envelope(String sourceFormat) {
        return part(sourceFormat, 1);
    }

    private static Optional<String> part(String sourceFormat, int index) {
        if (sourceFormat == null) {
            return Optional.empty();
        }
        int slash = sourceFormat.indexOf('/');
        if (slash <= 0 || slash == sourceFormat.length() - 1 || sourceFormat.indexOf('/', slash + 1) >= 0) {
            return Optional.empty();
        }
        return Optional.of(index == 0 ? sourceFormat.substring(0, slash) : sourceFormat.substring(slash + 1));
    }
}
