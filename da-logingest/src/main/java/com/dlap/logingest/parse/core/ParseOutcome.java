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

import com.dlap.proto.log.v1.SourceProvider;
import java.util.Objects;
import java.util.Optional;

/**
 * What the registry knows after parsing one record: the result plus the context that
 * {@link CanonicalEventMapper} stamps onto events and quarantine records.
 *
 * @param record         the record
 * @param bindingId      the binding the record came through
 * @param result         the parse result
 * @param detected       the format detected on the record, empty when detection failed
 * @param effective      the format the record was parsed as, empty when no parser ran
 * @param pinnedVersion  the binding's pin, empty for {@code auto}
 * @param parserVersion  {@code <parser-id>@<semver>} of the parser that ran, empty when none ran
 * @param sourceProvider the provider of the record's source kind, when a parser for it is registered
 * @param fingerprint    the record's shape fingerprint, when the parser declares paths
 */
public record ParseOutcome(
        RawRecord record,
        String bindingId,
        ParseResult result,
        Optional<FormatKey> detected,
        Optional<FormatKey> effective,
        Optional<String> pinnedVersion,
        Optional<String> parserVersion,
        Optional<SourceProvider> sourceProvider,
        Optional<ShapeFingerprint> fingerprint) {

    /**
     * Validates the outcome.
     *
     * @param record         see the record description
     * @param bindingId      see the record description
     * @param result         see the record description
     * @param detected       see the record description
     * @param effective      see the record description
     * @param pinnedVersion  see the record description
     * @param parserVersion  see the record description
     * @param sourceProvider see the record description
     * @param fingerprint    see the record description
     */
    public ParseOutcome {
        Objects.requireNonNull(record, "record");
        FormatNames.requireBindingId(bindingId);
        Objects.requireNonNull(result, "result");
        detected = detected == null ? Optional.empty() : detected;
        effective = effective == null ? Optional.empty() : effective;
        pinnedVersion = pinnedVersion == null ? Optional.empty() : pinnedVersion;
        parserVersion = parserVersion == null ? Optional.empty() : parserVersion;
        sourceProvider = sourceProvider == null ? Optional.empty() : sourceProvider;
        fingerprint = fingerprint == null ? Optional.empty() : fingerprint;
    }

    /**
     * The outcome code, as fixture {@code expected_outcome} spells it.
     *
     * @return for example {@code PARSED} or {@code QUARANTINED:UNKNOWN_MAJOR_VERSION}
     */
    public String outcomeCode() {
        return result.outcomeCode();
    }
}
