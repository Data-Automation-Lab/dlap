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

package com.dlap.logingest.parse.core.mapping;

import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalField;
import com.dlap.logingest.parse.core.ClassificationRule;
import com.dlap.logingest.parse.core.EnvelopeKey;
import com.dlap.logingest.parse.core.FieldPath;
import com.dlap.logingest.parse.core.MajorMinor;
import com.dlap.logingest.parse.core.OnMismatch;
import com.dlap.logingest.parse.core.PersistRaw;
import com.dlap.logingest.parse.core.SkipReason;
import com.dlap.logingest.parse.core.VersionMatch;
import com.dlap.logingest.parse.core.VersionRange;
import com.dlap.logingest.parse.core.VersionScheme;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.SourceProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * One log family's mapping YAML ({@code dlap/log-formats/<family>/mapping.yaml}), validated and
 * frozen: the declarative half of a parser (design B1 "Declarative data", ADR-0054).
 *
 * <p>Per-family settings live here, not in {@code application.properties} (critic item 12):
 * {@link Settings} holds the defaults a binding inherits. Each {@link Envelope} says how records
 * are framed, how the version is detected, which versions are supported, how the native key is
 * built, where every canonical field is, which records fail, pend or are skipped, how principal
 * types map and how operations classify. {@link Version} entries add per-version deltas;
 * {@link Envelope#effective(String)} merges them into the {@link Effective} mapping a parser
 * uses. Instances come only from {@link MappingSpecValidator}.
 *
 * <p>The YAML syntax is specified by the JSON Schema
 * {@code dlap/log-formats/schema/log-format-mapping.schema.json} and in
 * {@code docs/specs/log-parser-contract.md}.
 *
 * @param specVersion    version of the mapping-YAML contract, {@value #SPEC_VERSION}
 * @param family         lower kebab case family id, the directory name
 * @param sourceKind     the family's source kind
 * @param sourceProvider the provider that enters every {@code event_id} of the family
 * @param description    free text
 * @param settings       per-family binding defaults
 * @param envelopes      the envelopes, in file order
 * @param extensions     family-specific settings, validated by the family's own code; an object
 */
public record MappingSpec(
        int specVersion,
        String family,
        String sourceKind,
        SourceProvider sourceProvider,
        String description,
        Settings settings,
        List<Envelope> envelopes,
        JsonNode extensions) {

    /** The mapping-YAML contract version this build reads. */
    public static final int SPEC_VERSION = 1;

    /** A family id that cannot be used: the schema directory. */
    public static final String RESERVED_FAMILY = "schema";

    /**
     * Freezes the spec.
     *
     * @param specVersion    see the record description
     * @param family         see the record description
     * @param sourceKind     see the record description
     * @param sourceProvider see the record description
     * @param description    see the record description
     * @param settings       see the record description
     * @param envelopes      see the record description
     * @param extensions     see the record description
     */
    public MappingSpec {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(sourceProvider, "sourceProvider");
        description = description == null ? "" : description;
        settings = settings == null ? Settings.NONE : settings;
        envelopes = List.copyOf(envelopes);
        extensions = extensions == null ? JsonNodeFactory.instance.objectNode() : extensions.deepCopy();
    }

    /**
     * Finds an envelope.
     *
     * @param id the envelope id
     * @return the envelope, or empty
     */
    public Optional<Envelope> envelope(String id) {
        return envelopes.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    /**
     * The registry key of one of this family's envelopes.
     *
     * @param envelope an envelope of this spec
     * @return the key
     */
    public EnvelopeKey keyOf(Envelope envelope) {
        return new EnvelopeKey(sourceKind, envelope.id());
    }

    /**
     * A binding with every default of this family applied, for tests and for bindings that set
     * nothing themselves.
     *
     * @param bindingId the binding id
     * @return the binding
     */
    public BindingConfig defaultBinding(String bindingId) {
        return settings.applyTo(BindingConfig.auto(bindingId, sourceKind));
    }

    /**
     * Per-family binding defaults. Every key is optional; an absent key leaves the contract
     * default.
     *
     * @param onMismatch           default {@code on-mismatch}
     * @param persistRaw           default {@code persist-raw}; absent means the default by category
     * @param persistRawSampleOneIn sampling rate for {@code SAMPLED}
     * @param maxRecordBytes       record size limit
     * @param overlap              re-read window for time-cursored transports
     * @param pollInterval         polling period for streaming transports
     */
    public record Settings(
            OnMismatch onMismatch,
            PersistRaw.Policy persistRaw,
            Integer persistRawSampleOneIn,
            Long maxRecordBytes,
            Duration overlap,
            Duration pollInterval) {

        /** No settings. */
        public static final Settings NONE = new Settings(null, null, null, null, null, null);

        /**
         * Applies the defaults that concern parsing to a binding.
         *
         * @param base the binding
         * @return the binding with this family's defaults
         */
        public BindingConfig applyTo(BindingConfig base) {
            BindingConfig b = base;
            if (onMismatch != null) {
                b = b.pinned(b.pinnedVersion().orElse(null), onMismatch);
            }
            if (maxRecordBytes != null) {
                b = b.withMaxRecordBytes(maxRecordBytes);
            }
            if (persistRaw != null || persistRawSampleOneIn != null) {
                PersistRaw raw = new PersistRaw(persistRaw,
                        persistRawSampleOneIn == null ? PersistRaw.DEFAULT_SAMPLE_ONE_IN : persistRawSampleOneIn);
                b = new BindingConfig(b.bindingId(), b.sourceKind(), b.pinnedVersion(), b.onMismatch(),
                        b.fieldOverrides(), b.operationOverrides(), raw, b.maxRecordBytes(), b.assetIdHints());
            }
            return b;
        }
    }

    /**
     * Where a canonical field's value comes from: the first path that holds a value, or a constant.
     *
     * @param paths    paths in fallback order; empty for a constant
     * @param constant the constant text, or {@code null}
     */
    public record FieldSource(List<FieldPath> paths, String constant) {

        /**
         * Validates the source.
         *
         * @param paths    see the record description
         * @param constant see the record description
         */
        public FieldSource {
            paths = paths == null ? List.of() : List.copyOf(paths);
            if (paths.isEmpty() == (constant == null)) {
                throw new IllegalArgumentException("a field source is either paths or a constant");
            }
        }

        /**
         * Paths in fallback order.
         *
         * @param paths the paths
         * @return the source
         */
        public static FieldSource of(List<FieldPath> paths) {
            return new FieldSource(paths, null);
        }

        /**
         * A constant.
         *
         * @param value the text
         * @return the source
         */
        public static FieldSource constant(String value) {
            return new FieldSource(List.of(), Objects.requireNonNull(value, "value"));
        }

        /**
         * Resolves the value on a record.
         *
         * @param record the record body
         * @return the first node that holds a value, the constant as text, or empty
         */
        public Optional<JsonNode> resolve(JsonNode record) {
            if (constant != null) {
                return Optional.of(TextNode.valueOf(constant));
            }
            for (FieldPath p : paths) {
                Optional<JsonNode> hit = p.first(record);
                if (hit.isPresent()) {
                    return hit;
                }
            }
            return Optional.empty();
        }
    }

    /** How a native-key part is rendered. */
    public enum PartFormat {
        /** The value's text. */
        TEXT,
        /** An RFC 3339 time, rendered by {@code Instant#toString} (log-event spec section 5.2). */
        INSTANT
    }

    /**
     * One part of the native key.
     *
     * @param path   where the part is
     * @param format how it is rendered
     */
    public record KeyPart(FieldPath path, PartFormat format) {

        /**
         * Validates the part.
         *
         * @param path   see the record description
         * @param format see the record description
         */
        public KeyPart {
            Objects.requireNonNull(path, "path");
            format = format == null ? PartFormat.TEXT : format;
        }
    }

    /**
     * How the native key is built (log-event spec section 5.2): parts joined with {@code |},
     * prefixed with {@code <SOURCE_KIND>|} when the source kind is not its provider's primary
     * audit stream.
     *
     * @param namespaced true to prefix the source kind
     * @param parts      the parts, at least one
     */
    public record NativeKey(boolean namespaced, List<KeyPart> parts) {

        /**
         * Validates the spec.
         *
         * @param namespaced see the record description
         * @param parts      see the record description
         */
        public NativeKey {
            parts = List.copyOf(parts);
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("a native key has at least one part");
            }
        }
    }

    /**
     * One rule of named version detection: the first rule whose conditions all hold names the
     * version.
     *
     * @param version the version it names
     * @param when    the conditions, at least one
     */
    public record NamedRule(String version, List<Condition> when) {

        /**
         * Validates the rule.
         *
         * @param version see the record description
         * @param when    see the record description
         */
        public NamedRule {
            Objects.requireNonNull(version, "version");
            when = List.copyOf(when);
            if (when.isEmpty()) {
                throw new IllegalArgumentException("a detection rule needs at least one condition");
            }
        }
    }

    /**
     * How an envelope's version is detected.
     *
     * @param scheme  the version scheme
     * @param pointer for {@link VersionScheme#MAJOR_MINOR}: where the version string is
     * @param rules   for {@link VersionScheme#NAMED}: the rules, in order
     */
    public record Detection(VersionScheme scheme, FieldPath pointer, List<NamedRule> rules) {

        /**
         * Validates the detection.
         *
         * @param scheme  see the record description
         * @param pointer see the record description
         * @param rules   see the record description
         */
        public Detection {
            Objects.requireNonNull(scheme, "scheme");
            rules = rules == null ? List.of() : List.copyOf(rules);
            if (scheme == VersionScheme.MAJOR_MINOR && (pointer == null || !rules.isEmpty())) {
                throw new IllegalArgumentException("major-minor detection reads one pointer and has no rules");
            }
            if (scheme == VersionScheme.NAMED && (pointer != null || rules.isEmpty())) {
                throw new IllegalArgumentException("named detection has rules and no pointer");
            }
        }
    }

    /**
     * A declared skip.
     *
     * @param reason why
     * @param when   the conditions, all of which must hold
     */
    public record SkipRule(SkipReason reason, List<Condition> when) {

        /**
         * Validates the rule.
         *
         * @param reason see the record description
         * @param when   see the record description
         */
        public SkipRule {
            Objects.requireNonNull(reason, "reason");
            if (reason == SkipReason.MISSING_EVENT_TIME) {
                throw new IllegalArgumentException("MISSING_EVENT_TIME is decided by the parser, not declared");
            }
            when = List.copyOf(when);
            if (when.isEmpty()) {
                throw new IllegalArgumentException("a skip rule needs at least one condition");
            }
        }
    }

    /**
     * One supported version and what it changes relative to the envelope.
     *
     * @param id          the version id
     * @param description free text
     * @param fields      field sources that replace the envelope's
     * @param required    fields that become required
     * @param knownPaths  paths that become declared
     */
    public record Version(
            String id,
            String description,
            Map<CanonicalField, FieldSource> fields,
            Set<CanonicalField> required,
            List<FieldPath> knownPaths) {

        /**
         * Freezes the version.
         *
         * @param id          see the record description
         * @param description see the record description
         * @param fields      see the record description
         * @param required    see the record description
         * @param knownPaths  see the record description
         */
        public Version {
            Objects.requireNonNull(id, "id");
            description = description == null ? "" : description;
            fields = freeze(fields);
            required = freezeSet(required);
            knownPaths = knownPaths == null ? List.of() : List.copyOf(knownPaths);
        }
    }

    /**
     * One envelope of the family.
     *
     * @param id             envelope id
     * @param description    free text
     * @param framing        accepted container framings
     * @param recordsPointer where the record array is, for {@link Framing#JSON_OBJECT_ARRAY}
     * @param detection      version detection
     * @param nativeKey      native key construction
     * @param fields         canonical field sources
     * @param required       fields whose absence quarantines the record as {@code MISSING_REQUIRED_FIELD}
     * @param timeFallback   documented fallback clocks, tried when {@code event-time} is absent
     * @param knownPaths     declared paths beyond those the mapping reads (subtree-inclusive)
     * @param failureWhen    any of these makes the result {@code FAILURE}
     * @param pendingWhen    any of these makes the result {@code PENDING}
     * @param skipWhen       declared skips
     * @param principalTypes provider identity type to principal type, keys normalized
     * @param rules          the classification table
     * @param versions       supported versions with their deltas
     */
    public record Envelope(
            String id,
            String description,
            Set<Framing> framing,
            FieldPath recordsPointer,
            Detection detection,
            NativeKey nativeKey,
            Map<CanonicalField, FieldSource> fields,
            Set<CanonicalField> required,
            List<FieldPath> timeFallback,
            List<FieldPath> knownPaths,
            List<Condition> failureWhen,
            List<Condition> pendingWhen,
            List<SkipRule> skipWhen,
            Map<String, PrincipalType> principalTypes,
            List<ClassificationRule> rules,
            List<Version> versions) {

        /**
         * Freezes the envelope.
         *
         * @param id             see the record description
         * @param description    see the record description
         * @param framing        see the record description
         * @param recordsPointer see the record description
         * @param detection      see the record description
         * @param nativeKey      see the record description
         * @param fields         see the record description
         * @param required       see the record description
         * @param timeFallback   see the record description
         * @param knownPaths     see the record description
         * @param failureWhen    see the record description
         * @param pendingWhen    see the record description
         * @param skipWhen       see the record description
         * @param principalTypes see the record description
         * @param rules          see the record description
         * @param versions       see the record description
         */
        public Envelope {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(detection, "detection");
            Objects.requireNonNull(nativeKey, "nativeKey");
            description = description == null ? "" : description;
            Set<Framing> f = EnumSet.noneOf(Framing.class);
            if (framing != null) {
                f.addAll(framing);
            }
            if (f.isEmpty()) {
                f.add(Framing.RECORD);
            }
            framing = Collections.unmodifiableSet(f);
            fields = freeze(fields);
            required = freezeSet(required);
            timeFallback = timeFallback == null ? List.of() : List.copyOf(timeFallback);
            knownPaths = knownPaths == null ? List.of() : List.copyOf(knownPaths);
            failureWhen = failureWhen == null ? List.of() : List.copyOf(failureWhen);
            pendingWhen = pendingWhen == null ? List.of() : List.copyOf(pendingWhen);
            skipWhen = skipWhen == null ? List.of() : List.copyOf(skipWhen);
            Map<String, PrincipalType> pt = new TreeMap<>();
            if (principalTypes != null) {
                principalTypes.forEach((k, v) -> pt.put(normalizeIdentityType(k), v));
            }
            principalTypes = Collections.unmodifiableMap(pt);
            rules = rules == null ? List.of() : List.copyOf(rules);
            versions = List.copyOf(versions);
            if (versions.isEmpty()) {
                throw new IllegalArgumentException("an envelope supports at least one version");
            }
        }

        /**
         * The version ranges the envelope's versions span: one {@code [lowest, highest]} range per
         * major for {@link VersionScheme#MAJOR_MINOR}, one named set for {@link VersionScheme#NAMED}.
         *
         * @return the ranges; a parser registers one bean per range
         */
        public List<VersionRange> ranges() {
            if (detection.scheme() == VersionScheme.NAMED) {
                return List.of(VersionRange.named(versions.stream().map(Version::id).toList()));
            }
            Map<Integer, List<Version>> byMajor = new TreeMap<>();
            for (Version v : versions) {
                byMajor.computeIfAbsent(numbers(v).major(), k -> new ArrayList<>()).add(v);
            }
            List<VersionRange> out = new ArrayList<>();
            for (List<Version> vs : byMajor.values()) {
                vs.sort(Comparator.comparing(Envelope::numbers));
                out.add(VersionRange.majorMinor(vs.get(0).id(), vs.get(vs.size() - 1).id()));
            }
            return List.copyOf(out);
        }

        /**
         * The mapping for one version: the envelope with the deltas of that version applied. For
         * {@link VersionScheme#MAJOR_MINOR} the deltas of every listed version of the same major
         * up to the asked one apply in ascending order, so fields added in {@code 1.08} stay
         * mapped in {@code 1.09}.
         *
         * @param version a version some range of {@link #ranges()} supports (not a newer minor;
         *                the registry passes the effective version)
         * @return the effective mapping, or empty when the version is not supported
         */
        public Optional<Effective> effective(String version) {
            boolean supported = ranges().stream()
                    .anyMatch(r -> r.match(version).status() == VersionMatch.Status.SUPPORTED);
            if (!supported) {
                return Optional.empty();
            }
            List<Version> deltas = new ArrayList<>();
            if (detection.scheme() == VersionScheme.NAMED) {
                versions.stream().filter(v -> v.id().equals(version)).forEach(deltas::add);
            } else {
                MajorMinor asked = MajorMinor.parse(version).orElseThrow();
                versions.stream()
                        .filter(v -> numbers(v).major() == asked.major() && numbers(v).compareTo(asked) <= 0)
                        .sorted(Comparator.comparing(Envelope::numbers))
                        .forEach(deltas::add);
            }
            Map<CanonicalField, FieldSource> f = new EnumMap<>(CanonicalField.class);
            f.putAll(fields);
            Set<CanonicalField> req = EnumSet.noneOf(CanonicalField.class);
            req.addAll(required);
            Set<FieldPath> known = new LinkedHashSet<>(knownPaths);
            for (Version d : deltas) {
                f.putAll(d.fields());
                req.addAll(d.required());
                known.addAll(d.knownPaths());
            }
            return Optional.of(new Effective(id, version, detection, nativeKey, f, req, timeFallback,
                    List.copyOf(known), failureWhen, pendingWhen, skipWhen, principalTypes, rules));
        }

        /**
         * Maps a provider identity type through {@code principal-types}, without case or separators.
         *
         * @param raw the provider value, for example {@code AssumedRole}
         * @return the principal type, or empty when unmapped
         */
        public Optional<PrincipalType> principalType(String raw) {
            return raw == null ? Optional.empty() : Optional.ofNullable(principalTypes.get(normalizeIdentityType(raw)));
        }

        private static MajorMinor numbers(Version v) {
            return MajorMinor.parse(v.id()).orElseThrow(() ->
                    new IllegalStateException("major-minor version id is not numeric"));
        }
    }

    /**
     * The mapping a parser applies to one version: an envelope with that version's deltas merged.
     *
     * @param envelope       envelope id
     * @param version        the version
     * @param detection      version detection
     * @param nativeKey      native key construction
     * @param fields         canonical field sources
     * @param required       required fields
     * @param timeFallback   fallback clocks
     * @param knownPaths     declared extra paths
     * @param failureWhen    failure conditions
     * @param pendingWhen    pending conditions
     * @param skipWhen       skip rules
     * @param principalTypes principal type map, keys normalized
     * @param rules          classification table
     */
    public record Effective(
            String envelope,
            String version,
            Detection detection,
            NativeKey nativeKey,
            Map<CanonicalField, FieldSource> fields,
            Set<CanonicalField> required,
            List<FieldPath> timeFallback,
            List<FieldPath> knownPaths,
            List<Condition> failureWhen,
            List<Condition> pendingWhen,
            List<SkipRule> skipWhen,
            Map<String, PrincipalType> principalTypes,
            List<ClassificationRule> rules) {

        /**
         * Freezes the mapping.
         *
         * @param envelope       see the record description
         * @param version        see the record description
         * @param detection      see the record description
         * @param nativeKey      see the record description
         * @param fields         see the record description
         * @param required       see the record description
         * @param timeFallback   see the record description
         * @param knownPaths     see the record description
         * @param failureWhen    see the record description
         * @param pendingWhen    see the record description
         * @param skipWhen       see the record description
         * @param principalTypes see the record description
         * @param rules          see the record description
         */
        public Effective {
            fields = freeze(fields);
            required = freezeSet(required);
            timeFallback = List.copyOf(timeFallback);
            knownPaths = List.copyOf(knownPaths);
            failureWhen = List.copyOf(failureWhen);
            pendingWhen = List.copyOf(pendingWhen);
            skipWhen = List.copyOf(skipWhen);
            principalTypes = Map.copyOf(principalTypes);
            rules = List.copyOf(rules);
        }

        /**
         * Every path this mapping reads or declares, for the shape fingerprint.
         *
         * @return the declared paths
         */
        public Set<FieldPath> declaredPaths() {
            Set<FieldPath> out = new LinkedHashSet<>();
            fields.values().forEach(s -> out.addAll(s.paths()));
            out.addAll(timeFallback);
            out.addAll(knownPaths);
            nativeKey.parts().forEach(p -> out.add(p.path()));
            failureWhen.forEach(c -> out.add(c.path()));
            pendingWhen.forEach(c -> out.add(c.path()));
            skipWhen.forEach(r -> r.when().forEach(c -> out.add(c.path())));
            if (detection.pointer() != null) {
                out.add(detection.pointer());
            }
            detection.rules().forEach(r -> r.when().forEach(c -> out.add(c.path())));
            return Collections.unmodifiableSet(out);
        }

        /**
         * Maps a provider identity type, without case or separators.
         *
         * @param raw the provider value
         * @return the principal type, or empty
         */
        public Optional<PrincipalType> principalType(String raw) {
            return raw == null ? Optional.empty() : Optional.ofNullable(principalTypes.get(normalizeIdentityType(raw)));
        }
    }

    /**
     * The lookup form of a provider identity type: ASCII letters lower-cased and digits, every
     * other character dropped, so {@code AssumedRole}, {@code assumed_role} and
     * {@code ASSUMED-ROLE} are one key (the codec's alias rule, log-event spec section 12).
     *
     * @param raw the provider value
     * @return the normalized key
     */
    public static String normalizeIdentityType(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Map<CanonicalField, FieldSource> freeze(Map<CanonicalField, FieldSource> fields) {
        Map<CanonicalField, FieldSource> m = new EnumMap<>(CanonicalField.class);
        if (fields != null) {
            m.putAll(fields);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(m));
    }

    private static Set<CanonicalField> freezeSet(Set<CanonicalField> set) {
        Set<CanonicalField> s = EnumSet.noneOf(CanonicalField.class);
        if (set != null) {
            s.addAll(set);
        }
        return Collections.unmodifiableSet(s);
    }
}
