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

package com.dlap.logingest.parse.dlapnormalized;

import com.dlap.logingest.parse.core.EnvelopeKey;
import com.dlap.logingest.parse.core.VersionRange;
import com.dlap.proto.log.v1.SourceProvider;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Names of the {@code DLAP_NORMALIZED} family: source kinds, envelope and version.
 */
public final class DlapNormalizedFormat {

    /** Prefix of every source kind of the family; the provider name follows. */
    public static final String SOURCE_KIND_PREFIX = "DLAP_NORMALIZED_";

    /** The envelope: one {@code IngestRequestDTO} as a JSON object. */
    public static final String ENVELOPE = "ingest-dto";

    /** The one shape version: the DTO of {@code POST /api/v1/logingest/events}. */
    public static final String VERSION = "v1";

    /** The parser id. */
    public static final String PARSER_ID = "dlap-normalized";

    /** The parser version. */
    public static final String PARSER_SEMVER = "1.0.0";

    /** The supported versions. */
    public static final VersionRange RANGE = VersionRange.named(VERSION);

    private DlapNormalizedFormat() {
    }

    /**
     * Every provider a normalized record may name: the known {@code SourceProvider} values.
     *
     * @return the providers, in enum order
     */
    public static List<SourceProvider> providers() {
        return Arrays.stream(SourceProvider.values())
                .filter(p -> p != SourceProvider.SOURCE_PROVIDER_UNSPECIFIED && p != SourceProvider.UNRECOGNIZED)
                .toList();
    }

    /**
     * The source kind of one provider's normalized records.
     *
     * @param provider the provider
     * @return for example {@code DLAP_NORMALIZED_AWS}
     */
    public static String sourceKind(SourceProvider provider) {
        return SOURCE_KIND_PREFIX + provider.name();
    }

    /**
     * The envelope key of one provider's normalized records.
     *
     * @param provider the provider
     * @return the key
     */
    public static EnvelopeKey envelopeKey(SourceProvider provider) {
        return new EnvelopeKey(sourceKind(provider), ENVELOPE);
    }

    /**
     * Whether a source kind belongs to this family.
     *
     * @param sourceKind a source kind
     * @return true for {@code DLAP_NORMALIZED_<PROVIDER>}
     */
    public static boolean isFamilyKind(String sourceKind) {
        return sourceKind != null && sourceKind.startsWith(SOURCE_KIND_PREFIX);
    }

    /**
     * Reads a provider name as a normalized record spells it ({@code AWS}, {@code aws}).
     *
     * @param name the name
     * @return the provider, or empty when the name is blank or unknown
     */
    public static Optional<SourceProvider> provider(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String upper = name.strip().toUpperCase(Locale.ROOT);
        return providers().stream().filter(p -> p.name().equals(upper)).findFirst();
    }
}
