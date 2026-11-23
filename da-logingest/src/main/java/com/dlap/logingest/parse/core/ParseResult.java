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

import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.QuarantineReason;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The outcome of parsing one record. Every record ends in exactly one of three results
 * (ADR-0045 decision 7), and {@link #outcome()} names four outcomes because a parsed record with
 * warnings is reported apart from a clean one.
 *
 * <ul>
 *   <li>{@link Parsed}: one or more canonical events, plus record-level warnings that apply to
 *       every event of the record.</li>
 *   <li>{@link Skipped}: a valid record that yields no event, with a {@link SkipReason}; counted.</li>
 *   <li>{@link Quarantined}: a record DLAP cannot trust, with a {@code QuarantineReason} and a
 *       bounded detail that names fields and never quotes values, optionally with a
 *       {@code PARSE_STATUS_PARTIAL} pass-through event that keeps the audit fact.</li>
 * </ul>
 */
public sealed interface ParseResult permits ParseResult.Parsed, ParseResult.Skipped, ParseResult.Quarantined {

    /** Longest {@code reason_detail}, in UTF-8 bytes (log-event spec section 9). */
    int MAX_DETAIL_BYTES = 1024;

    /** The four outcomes, as counted and as written in fixture {@code expected_outcome}. */
    enum Outcome {
        /** Parsed, no warnings. */
        PARSED,
        /** Parsed with at least one warning on the record or an event. */
        PARSED_WITH_WARNINGS,
        /** Skipped. */
        SKIPPED,
        /** Quarantined. */
        QUARANTINED
    }

    /**
     * Returns the outcome.
     *
     * @return the outcome
     */
    Outcome outcome();

    /**
     * Returns the outcome as fixture {@code expected_outcome} spells it: {@code PARSED},
     * {@code PARSED_WITH_WARNINGS}, {@code SKIPPED:<SkipReason>} or
     * {@code QUARANTINED:<reason without the QUARANTINE_REASON_ prefix>}.
     *
     * @return the outcome code
     */
    String outcomeCode();

    /**
     * Bounds a detail to {@link #MAX_DETAIL_BYTES} UTF-8 bytes without splitting a character.
     *
     * @param detail the text; {@code null} becomes empty
     * @return the bounded text
     */
    static String boundDetail(String detail) {
        if (detail == null) {
            return "";
        }
        byte[] bytes = detail.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_DETAIL_BYTES) {
            return detail;
        }
        String marker = "...";
        int budget = MAX_DETAIL_BYTES - marker.length();
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < detail.length(); ) {
            int cp = detail.codePointAt(i);
            int len = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (used + len > budget) {
                break;
            }
            sb.appendCodePoint(cp);
            used += len;
            i += Character.charCount(cp);
        }
        return sb.append(marker).toString();
    }

    /**
     * Parsed events.
     *
     * @param events   the events, at least one, in record order; the first is the primary event
     * @param warnings record-level warnings, added to every event by the mapper
     */
    record Parsed(List<CanonicalEvent> events, Set<ParseWarning> warnings) implements ParseResult {

        /**
         * Validates the result.
         *
         * @param events   see the record description
         * @param warnings see the record description
         */
        public Parsed {
            Objects.requireNonNull(events, "events");
            if (events.isEmpty()) {
                throw new IllegalArgumentException("a parsed result has at least one event; skip the record instead");
            }
            events = List.copyOf(events);
            if (events.stream().anyMatch(CanonicalEvent::passThrough)) {
                throw new IllegalArgumentException("pass-through events belong to quarantined results");
            }
            Set<ParseWarning> w = EnumSet.noneOf(ParseWarning.class);
            if (warnings != null) {
                w.addAll(warnings);
            }
            w.remove(ParseWarning.PARSE_WARNING_UNSPECIFIED);
            if (w.contains(ParseWarning.UNRECOGNIZED)) {
                throw new IllegalArgumentException("parse warnings must be known values");
            }
            warnings = Collections.unmodifiableSet(w);
        }

        /**
         * Parsed events without record-level warnings.
         *
         * @param events the events
         * @return the result
         */
        public static Parsed of(CanonicalEvent... events) {
            return new Parsed(List.of(events), Set.of());
        }

        /**
         * A copy with more record-level warnings.
         *
         * @param more the warnings
         * @return a new result
         */
        public Parsed withWarnings(Set<ParseWarning> more) {
            if (more == null || more.isEmpty()) {
                return this;
            }
            Set<ParseWarning> all = EnumSet.noneOf(ParseWarning.class);
            all.addAll(warnings);
            all.addAll(more);
            return new Parsed(events, all);
        }

        /**
         * The warnings that end up on one event: the record's plus the event's own.
         *
         * @param event one of {@link #events()}
         * @return the union
         */
        public Set<ParseWarning> warningsOf(CanonicalEvent event) {
            Set<ParseWarning> all = EnumSet.noneOf(ParseWarning.class);
            all.addAll(warnings);
            all.addAll(event.warnings());
            return all;
        }

        @Override
        public Outcome outcome() {
            boolean any = !warnings.isEmpty() || events.stream().anyMatch(e -> !e.warnings().isEmpty());
            return any ? Outcome.PARSED_WITH_WARNINGS : Outcome.PARSED;
        }

        @Override
        public String outcomeCode() {
            return outcome().name();
        }
    }

    /**
     * A skipped record.
     *
     * @param reason why
     * @param detail what; names fields, never quotes values
     */
    record Skipped(SkipReason reason, String detail) implements ParseResult {

        /**
         * Validates the result.
         *
         * @param reason see the record description
         * @param detail see the record description
         */
        public Skipped {
            Objects.requireNonNull(reason, "reason");
            detail = boundDetail(detail);
        }

        @Override
        public Outcome outcome() {
            return Outcome.SKIPPED;
        }

        @Override
        public String outcomeCode() {
            return Outcome.SKIPPED.name() + ":" + reason.name();
        }
    }

    /**
     * A quarantined record.
     *
     * @param reason      why; never unspecified
     * @param detail      what, at most {@link #MAX_DETAIL_BYTES} bytes; names fields, never quotes values
     * @param passThrough the {@code PARSE_STATUS_PARTIAL} event, when provider, event time,
     *                    operation and native key could be extracted
     */
    record Quarantined(QuarantineReason reason, String detail, Optional<CanonicalEvent> passThrough)
            implements ParseResult {

        /** Prefix of the proto enum names, dropped in {@link #outcomeCode()}. */
        public static final String REASON_PREFIX = "QUARANTINE_REASON_";

        /**
         * Validates the result.
         *
         * @param reason      see the record description
         * @param detail      see the record description
         * @param passThrough see the record description
         */
        public Quarantined {
            if (reason == null || reason == QuarantineReason.QUARANTINE_REASON_UNSPECIFIED
                    || reason == QuarantineReason.UNRECOGNIZED) {
                throw new IllegalArgumentException("a quarantined result needs a known reason");
            }
            detail = boundDetail(detail);
            passThrough = passThrough == null ? Optional.empty() : passThrough;
            passThrough.ifPresent(e -> {
                if (!e.passThrough()) {
                    throw new IllegalArgumentException("the pass-through event must be built with passThrough()");
                }
            });
        }

        /**
         * A quarantine without pass-through.
         *
         * @param reason why
         * @param detail what
         * @return the result
         */
        public static Quarantined of(QuarantineReason reason, String detail) {
            return new Quarantined(reason, detail, Optional.empty());
        }

        /**
         * Returns the reason without its proto prefix.
         *
         * @return for example {@code UNKNOWN_MAJOR_VERSION}
         */
        public String reasonCode() {
            return reason.name().substring(REASON_PREFIX.length());
        }

        @Override
        public Outcome outcome() {
            return Outcome.QUARANTINED;
        }

        @Override
        public String outcomeCode() {
            return Outcome.QUARANTINED.name() + ":" + reasonCode();
        }
    }
}
