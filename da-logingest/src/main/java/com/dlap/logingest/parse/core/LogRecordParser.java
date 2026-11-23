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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns one decoded record of one envelope and version range into canonical events (design B1).
 *
 * <p>Parsers self-register: a family declares each parser as a Spring bean in its own package
 * ({@code parse/<family>}) and the registry collects every {@code LogRecordParser} bean. No
 * central switch names a family. The registry refuses two parsers whose ranges overlap on one
 * envelope, and a parser whose envelope has no detector.
 *
 * <p>Contract, checked by the registry and by the fixture contract tests:
 * <ul>
 *   <li>{@code parse} is total and pure: it returns a result for every decoded JSON value, keeps
 *       no state between calls and does no I/O. An exception is quarantined as
 *       {@code PARSER_EXCEPTION}, which is a parser defect.</li>
 *   <li>It is deterministic: the same record, format and binding give equal results.</li>
 *   <li>Every event it returns has {@link #sourceProvider()} as its provider.</li>
 *   <li>Business time comes from the record. With no provider clock the result is
 *       {@code Skipped(MISSING_EVENT_TIME)}.</li>
 *   <li>Parsers use only JSON and the da-protos contract types, never a cloud SDK (the
 *       {@code parse/**} boundary, enforced by an ArchUnit test).</li>
 * </ul>
 */
public interface LogRecordParser {

    /** Shape of a parser id, for example {@code cloudtrail-record}. */
    Pattern PARSER_ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /** Shape of a parser version: semantic versioning without build metadata. */
    Pattern SEMVER = Pattern.compile("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(-[0-9A-Za-z.-]+)?");

    /**
     * Returns the envelope this parser reads.
     *
     * @return the source kind and envelope
     */
    EnvelopeKey envelopeKey();

    /**
     * Returns the versions this parser reads, for example CloudTrail {@code [1.0, 1.11]}.
     *
     * @return the range
     */
    VersionRange supports();

    /**
     * Returns the provider of every event this parser emits; it enters the {@code event_id}.
     *
     * @return the provider
     */
    SourceProvider sourceProvider();

    /**
     * Returns the parser id, stable across releases.
     *
     * @return lower kebab case id
     */
    String parserId();

    /**
     * Returns the parser's semantic version. Bump the minor version when output changes for some
     * input, the major when a field's meaning changes.
     *
     * @return for example {@code 1.0.0}
     */
    String parserSemver();

    /**
     * Returns {@code DlapLogEvent.parser_version}.
     *
     * @return {@code <parser-id>@<semver>}
     */
    default String parserVersion() {
        return parserId() + "@" + parserSemver();
    }

    /**
     * Returns the JSON paths the parser reads or knows, for the shape fingerprint. Paths outside
     * this set are reported as unknown fields. An empty set disables unknown-field detection for
     * the version.
     *
     * @param effectiveVersion the version the record is parsed as
     * @return declared paths
     */
    default Set<FieldPath> declaredPaths(String effectiveVersion) {
        return Set.of();
    }

    /**
     * Parses a record.
     *
     * @param record    the record; its body decoded
     * @param effective the format to parse as: the detected or pinned version, or the highest known
     *                  version for a newer minor
     * @param binding   the binding's effective settings
     * @return the result; never {@code null}
     */
    ParseResult parse(RawRecord record, FormatKey effective, BindingConfig binding);
}
