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
import com.dlap.proto.log.v1.SourceProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The registry of ADR-0045 decision 8 and ADR-0054: resolves each record to one parser by
 * (source kind, envelope, schema version) and applies the frozen version rules.
 *
 * <p>Resolution, for a record and its binding:
 * <ol>
 *   <li>A record larger than the binding's {@code maxRecordBytes} is {@code OVERSIZE_RECORD}.</li>
 *   <li>No detector for the record's envelope, or a detector that does not recognize the body, is
 *       {@code UNKNOWN_ENVELOPE}. Bytes that are not UTF-8 are {@code INVALID_ENCODING}; text that
 *       is not one JSON value is {@code INVALID_JSON}.</li>
 *   <li>A detected version no registered parser reads (an unknown major, a minor below a floor,
 *       an unlisted named version) is {@code UNKNOWN_MAJOR_VERSION}, whatever the binding says.</li>
 *   <li>A pinned binding whose pin differs from the detected version applies its
 *       {@link OnMismatch} policy: quarantine as {@code VERSION_PIN_MISMATCH}, or parse as the pin
 *       or as the detected version with {@code PARSE_WARNING_VERSION_PIN_MISMATCH}. A pin no
 *       parser reads is {@code VERSION_PIN_MISMATCH}.</li>
 *   <li>A minor above the highest known is parsed as the highest known with
 *       {@code PARSE_WARNING_NEWER_MINOR}.</li>
 *   <li>The parser runs. An exception, a {@code null} result or an event with another provider
 *       is {@code PARSER_EXCEPTION}.</li>
 *   <li>When the parser declares paths, the record's {@link ShapeFingerprint} is computed and
 *       undeclared paths add {@code PARSE_WARNING_UNKNOWN_FIELDS}.</li>
 * </ol>
 *
 * <p>Construction fails fast on an ambiguous set of beans: two detectors for one envelope, two
 * parsers whose ranges overlap, mixed version schemes on one envelope, a parser without a detector,
 * or one source kind claimed by two providers.
 */
public final class DefaultParserRegistry implements ParserRegistry {

    private final Map<EnvelopeKey, VersionDetector> detectors;
    private final Map<EnvelopeKey, List<LogRecordParser>> parsers;
    private final Map<String, SourceProvider> providers;

    /**
     * Builds a registry.
     *
     * @param parsers   every parser bean
     * @param detectors every detector, beans and declarative ones
     * @throws IllegalStateException when the set is ambiguous or incomplete
     */
    public DefaultParserRegistry(Collection<? extends LogRecordParser> parsers,
                                 Collection<? extends VersionDetector> detectors) {
        Map<EnvelopeKey, VersionDetector> d = new LinkedHashMap<>();
        for (VersionDetector detector : detectors) {
            VersionDetector previous = d.putIfAbsent(Objects.requireNonNull(detector.envelopeKey(), "envelopeKey"),
                    detector);
            if (previous != null) {
                throw new IllegalStateException("two version detectors for " + detector.envelopeKey() + ": "
                        + previous.getClass().getName() + " and " + detector.getClass().getName());
            }
        }
        Map<EnvelopeKey, List<LogRecordParser>> p = new LinkedHashMap<>();
        Map<String, SourceProvider> prov = new TreeMap<>();
        for (LogRecordParser parser : parsers) {
            EnvelopeKey key = Objects.requireNonNull(parser.envelopeKey(), "envelopeKey");
            checkIdentity(parser);
            if (!d.containsKey(key)) {
                throw new IllegalStateException("parser " + parser.parserVersion() + " reads " + key
                        + " but no version detector is registered for it");
            }
            for (LogRecordParser other : p.getOrDefault(key, List.of())) {
                if (other.supports().overlaps(parser.supports())) {
                    throw new IllegalStateException("parsers " + other.parserVersion() + " " + other.supports().describe()
                            + " and " + parser.parserVersion() + " " + parser.supports().describe()
                            + " overlap on " + key + " (or mix version schemes)");
                }
            }
            p.computeIfAbsent(key, k -> new ArrayList<>()).add(parser);
            SourceProvider provider = parser.sourceProvider();
            if (provider == null || provider == SourceProvider.SOURCE_PROVIDER_UNSPECIFIED
                    || provider == SourceProvider.UNRECOGNIZED) {
                throw new IllegalStateException("parser " + parser.parserVersion() + " declares no source provider");
            }
            SourceProvider claimed = prov.putIfAbsent(key.sourceKind(), provider);
            if (claimed != null && claimed != provider) {
                throw new IllegalStateException("source kind " + key.sourceKind() + " is claimed by providers "
                        + claimed + " and " + provider);
            }
        }
        this.detectors = Collections.unmodifiableMap(d);
        Map<EnvelopeKey, List<LogRecordParser>> frozen = new LinkedHashMap<>();
        p.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        this.parsers = Collections.unmodifiableMap(frozen);
        this.providers = Collections.unmodifiableMap(prov);
    }

    @Override
    public ParseOutcome parse(RawRecord record, BindingConfig binding) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(binding, "binding");
        if (!binding.bindingId().equals(record.bindingId()) || !binding.sourceKind().equals(record.sourceKind())) {
            throw new IllegalArgumentException("binding " + binding.bindingId() + " (" + binding.sourceKind()
                    + ") does not belong to the record's binding " + record.bindingId() + " (" + record.sourceKind() + ")");
        }
        Attempt a = new Attempt(record, binding, Optional.ofNullable(providers.get(record.sourceKind())));
        if (record.sizeBytes() > binding.maxRecordBytes()) {
            return a.quarantine(QuarantineReason.QUARANTINE_REASON_OVERSIZE_RECORD,
                    "record is " + record.sizeBytes() + " bytes; the binding allows " + binding.maxRecordBytes());
        }
        EnvelopeKey key = record.envelopeKey();
        VersionDetector detector = detectors.get(key);
        if (detector == null) {
            return a.quarantine(QuarantineReason.QUARANTINE_REASON_UNKNOWN_ENVELOPE,
                    "no version detector is registered for " + key);
        }
        RawRecord.Decoded decoded = record.decode();
        if (!decoded.ok()) {
            return a.quarantine(decoded.failure(), decoded.detail());
        }
        Optional<FormatKey> found;
        try {
            found = detector.detect(record);
        } catch (RuntimeException | StackOverflowError e) {
            return a.quarantine(QuarantineReason.QUARANTINE_REASON_PARSER_EXCEPTION,
                    "version detector " + detector.getClass().getName() + " threw " + e.getClass().getName());
        }
        if (found == null || found.isEmpty()) {
            return a.quarantine(QuarantineReason.QUARANTINE_REASON_UNKNOWN_ENVELOPE,
                    "the body is not a " + key + " record or carries no readable version");
        }
        FormatKey detected = found.get();
        if (!detected.envelopeKey().equals(key)) {
            return a.quarantine(QuarantineReason.QUARANTINE_REASON_PARSER_EXCEPTION,
                    "version detector for " + key + " returned another envelope " + detected.envelopeKey());
        }
        a.detected = detected;
        List<LogRecordParser> candidates = parsers.getOrDefault(key, List.of());

        Set<ParseWarning> warnings = EnumSet.noneOf(ParseWarning.class);
        Choice choice = choose(candidates, detected.schemaVersion());
        if (choice.parser() == null) {
            // The frozen rule: a version no parser reads is quarantined whatever the binding says.
            return a.quarantine(QuarantineReason.QUARANTINE_REASON_UNKNOWN_MAJOR_VERSION,
                    "no parser reads the detected version of " + key + " (" + choice.why() + ")");
        }
        Optional<String> pin = binding.pinnedVersion();
        if (pin.isPresent() && !candidates.get(0).supports().sameVersion(pin.get(), detected.schemaVersion())) {
            switch (binding.onMismatch()) {
                case QUARANTINE -> {
                    return a.quarantine(QuarantineReason.QUARANTINE_REASON_VERSION_PIN_MISMATCH,
                            "detected_version differs from pinned_version");
                }
                case PARSE_WITH_PIN -> {
                    choice = choose(candidates, pin.get());
                    if (choice.parser() == null) {
                        return a.quarantine(QuarantineReason.QUARANTINE_REASON_VERSION_PIN_MISMATCH,
                                "detected_version differs from pinned_version, and no parser reads the pinned"
                                        + " version (" + choice.why() + ")");
                    }
                    warnings.add(ParseWarning.PARSE_WARNING_VERSION_PIN_MISMATCH);
                }
                case PARSE_WITH_DETECTED -> warnings.add(ParseWarning.PARSE_WARNING_VERSION_PIN_MISMATCH);
                default -> throw new IllegalStateException("unhandled policy " + binding.onMismatch());
            }
        }
        LogRecordParser chosen = choice.parser();
        VersionMatch match = choice.match();
        if (match.status() == VersionMatch.Status.NEWER_MINOR) {
            warnings.add(ParseWarning.PARSE_WARNING_NEWER_MINOR);
        }
        FormatKey effective = key.at(match.effectiveVersion());
        a.effective = effective;
        a.parser = chosen;

        Set<FieldPath> declared = chosen.declaredPaths(effective.schemaVersion());
        if (declared != null && !declared.isEmpty()) {
            a.fingerprint = ShapeFingerprint.of(decoded.body(), declared);
        }

        ParseResult result;
        try {
            result = chosen.parse(record, effective, binding);
        } catch (RuntimeException | StackOverflowError e) {
            return a.quarantine(QuarantineReason.QUARANTINE_REASON_PARSER_EXCEPTION,
                    "parser " + chosen.parserVersion() + " threw " + e.getClass().getName());
        }
        if (result == null) {
            return a.quarantine(QuarantineReason.QUARANTINE_REASON_PARSER_EXCEPTION,
                    "parser " + chosen.parserVersion() + " returned no result");
        }
        Optional<String> wrongProvider = wrongProvider(result, chosen.sourceProvider());
        if (wrongProvider.isPresent()) {
            return a.quarantine(QuarantineReason.QUARANTINE_REASON_PARSER_EXCEPTION,
                    "parser " + chosen.parserVersion() + " emitted an event for provider " + wrongProvider.get()
                            + " but declares " + chosen.sourceProvider());
        }
        if (result instanceof ParseResult.Parsed parsed) {
            if (a.fingerprint != null && a.fingerprint.hasUnknownFields()) {
                warnings.add(ParseWarning.PARSE_WARNING_UNKNOWN_FIELDS);
            }
            result = parsed.withWarnings(warnings);
        }
        return a.outcome(result);
    }

    @Override
    public Set<EnvelopeKey> envelopes() {
        return detectors.keySet();
    }

    @Override
    public Optional<VersionRange> rangeFor(EnvelopeKey key, String version) {
        return parsers.getOrDefault(key, List.of()).stream()
                .map(LogRecordParser::supports)
                .filter(r -> r.match(version).accepted())
                .findFirst();
    }

    /**
     * Describes the registered parsers per envelope, for the startup log.
     *
     * @return for example {@code AWS_CLOUDTRAIL/s3-file -> [cloudtrail-record@1.0.0 [1.0, 1.11]]}
     */
    public Map<EnvelopeKey, List<String>> describe() {
        Map<EnvelopeKey, List<String>> out = new LinkedHashMap<>();
        for (EnvelopeKey key : detectors.keySet()) {
            out.put(key, parsers.getOrDefault(key, List.of()).stream()
                    .map(p -> p.parserVersion() + " " + p.supports().describe())
                    .collect(Collectors.toUnmodifiableList()));
        }
        return Collections.unmodifiableMap(out);
    }

    /** The parser chosen for a version, or why none was. */
    private record Choice(LogRecordParser parser, VersionMatch match, String why) {
    }

    /** A supported match wins over a newer-minor match; no match explains every refusal. */
    private static Choice choose(List<LogRecordParser> candidates, String version) {
        LogRecordParser newer = null;
        VersionMatch newerMatch = null;
        List<String> refusals = new ArrayList<>();
        for (LogRecordParser candidate : candidates) {
            VersionMatch m = candidate.supports().match(version);
            if (m.status() == VersionMatch.Status.SUPPORTED) {
                return new Choice(candidate, m, "");
            }
            if (m.status() == VersionMatch.Status.NEWER_MINOR) {
                if (newer == null) {
                    newer = candidate;
                    newerMatch = m;
                }
            } else {
                refusals.add(candidate.parserVersion() + " " + candidate.supports().describe() + ": " + m.detail());
            }
        }
        if (newer != null) {
            return new Choice(newer, newerMatch, "");
        }
        return new Choice(null, null, candidates.isEmpty() ? "no parser is registered" : String.join("; ", refusals));
    }

    private static Optional<String> wrongProvider(ParseResult result, SourceProvider declared) {
        List<CanonicalEvent> events = switch (result) {
            case ParseResult.Parsed p -> p.events();
            case ParseResult.Quarantined q -> q.passThrough().map(List::of).orElse(List.of());
            case ParseResult.Skipped s -> List.of();
        };
        return events.stream()
                .map(CanonicalEvent::provider)
                .filter(p -> p != declared)
                .map(Enum::name)
                .findFirst();
    }

    private static void checkIdentity(LogRecordParser parser) {
        String id = parser.parserId();
        String semver = parser.parserSemver();
        if (id == null || !LogRecordParser.PARSER_ID.matcher(id).matches()) {
            throw new IllegalStateException(parser.getClass().getName() + ": parser id must match "
                    + LogRecordParser.PARSER_ID.pattern());
        }
        if (semver == null || !LogRecordParser.SEMVER.matcher(semver).matches()) {
            throw new IllegalStateException(parser.getClass().getName() + ": parser version must be semantic");
        }
        Objects.requireNonNull(parser.supports(), "supports");
    }

    /** Accumulates context while one record is resolved. */
    private static final class Attempt {
        private final RawRecord record;
        private final BindingConfig binding;
        private final Optional<SourceProvider> provider;
        private FormatKey detected;
        private FormatKey effective;
        private LogRecordParser parser;
        private ShapeFingerprint fingerprint;

        private Attempt(RawRecord record, BindingConfig binding, Optional<SourceProvider> provider) {
            this.record = record;
            this.binding = binding;
            this.provider = provider;
        }

        private ParseOutcome quarantine(QuarantineReason reason, String detail) {
            return outcome(ParseResult.Quarantined.of(reason, detail));
        }

        private ParseOutcome outcome(ParseResult result) {
            return new ParseOutcome(record, binding.bindingId(), result, Optional.ofNullable(detected),
                    Optional.ofNullable(effective), binding.pinnedVersion(),
                    Optional.ofNullable(parser).map(LogRecordParser::parserVersion), provider,
                    Optional.ofNullable(fingerprint));
        }
    }
}
