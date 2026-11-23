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

import com.dlap.common.assetid.AssetKind;
import com.dlap.logingest.parse.core.CanonicalField;
import com.dlap.logingest.parse.core.ClassificationRule;
import com.dlap.logingest.parse.core.FieldPath;
import com.dlap.logingest.parse.core.FormatNames;
import com.dlap.logingest.parse.core.MajorMinor;
import com.dlap.logingest.parse.core.OnMismatch;
import com.dlap.logingest.parse.core.PersistRaw;
import com.dlap.logingest.parse.core.SkipReason;
import com.dlap.logingest.parse.core.VersionScheme;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.SourceProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Validates a mapping YAML tree and builds the {@link MappingSpec} (ADR-0054).
 *
 * <p>It enforces everything the JSON Schema {@code log-format-mapping.schema.json} says, so the
 * service needs no schema library at runtime, plus the rules a JSON Schema cannot express:
 * <ul>
 *   <li>envelope ids and version ids are unique ({@code 1.8} and {@code 1.08} are the same
 *       major.minor version);</li>
 *   <li>every named version is reachable by a detection rule, and every rule names a listed
 *       version;</li>
 *   <li>every required field has a source in every version's effective mapping;</li>
 *   <li>no two classification rows share a service and an operation or prefix;</li>
 *   <li>{@code asset-kind} names an {@code AssetKind};</li>
 *   <li>no two {@code principal-types} keys collide once case and separators are ignored;</li>
 *   <li>the family matches its directory.</li>
 * </ul>
 * It never throws on content: every defect becomes a problem line naming the file and the path
 * inside it, so one run lists them all.
 */
public final class MappingSpecValidator {

    /** Largest {@code max-record-bytes} a family may set: 16 MiB. */
    public static final long MAX_RECORD_BYTES_LIMIT = 16L * 1024 * 1024;

    /** Largest {@code persist-raw-sample-one-in}. */
    public static final int MAX_SAMPLE_ONE_IN = 1_000_000;

    private static final Set<String> TOP_KEYS = Set.of(
            "spec-version", "family", "source-kind", "source-provider", "description", "settings", "envelopes",
            "extensions");
    private static final Set<String> SETTINGS_KEYS = Set.of(
            "on-mismatch", "persist-raw", "persist-raw-sample-one-in", "max-record-bytes", "overlap", "poll-interval");
    private static final Set<String> ENVELOPE_KEYS = Set.of(
            "id", "description", "framing", "records-pointer", "version-detection", "native-key", "fields", "required",
            "time-fallback", "known-paths", "result", "skip-when", "principal-types", "classification", "versions");
    private static final Set<String> DETECTION_KEYS = Set.of("scheme", "pointer", "rules");
    private static final Set<String> RULE_KEYS = Set.of("version", "when");
    private static final Set<String> NATIVE_KEY_KEYS = Set.of("namespaced", "parts");
    private static final Set<String> PART_KEYS = Set.of("pointer", "as");
    private static final Set<String> RESULT_KEYS = Set.of("failure-when", "pending-when");
    private static final Set<String> SKIP_KEYS = Set.of("reason", "when");
    private static final Set<String> CLASSIFICATION_KEYS = Set.of("rules");
    private static final Set<String> CLASS_RULE_KEYS = Set.of(
            "service", "operation", "operation-prefix", "change-type", "category", "asset-kind", "extractor");
    private static final Set<String> VERSION_KEYS = Set.of("id", "description", "fields", "required", "known-paths");
    private static final Set<String> CONDITION_KEYS = Set.of("pointer", "present", "equals", "not-equals", "in");
    private static final Set<String> CONDITION_OPS = Set.of("present", "equals", "not-equals", "in");
    private static final Set<String> FIELD_SOURCE_KEYS = Set.of("const");

    private static final Set<String> PROVIDERS = Set.of("GCP", "AWS", "AZURE", "OCI", "UNITY", "DATABRICKS", "SNOWFLAKE");
    private static final Set<String> CHANGE_TYPES = Set.of("CREATE", "UPDATE", "DELETE", "ACCESS", "PERMISSION", "ARCHIVE");
    private static final Set<String> CATEGORIES = Set.of(
            "MANAGEMENT", "DATA_READ", "DATA_WRITE", "PERMISSION", "DATA_READ_OBSERVED");
    private static final Set<String> PRINCIPAL_TYPES = Set.of("USER", "SERVICE_ACCOUNT", "GROUP", "ROLE", "SYSTEM");
    private static final Set<String> SKIP_REASONS = Set.of(SkipReason.NOT_AN_EVENT.name(),
            SkipReason.FILTERED_BY_BINDING.name());

    /** The duration syntax of the JSON Schema: days, hours, minutes and seconds, no sign. */
    private static final Pattern DURATION = Pattern.compile(
            "P(?!$)([0-9]+D)?(T(?=[0-9])([0-9]+H)?([0-9]+M)?([0-9]+([.][0-9]{1,9})?S)?)?");

    private final String file;
    private final List<String> problems = new ArrayList<>();

    private MappingSpecValidator(String file) {
        this.file = file;
    }

    /**
     * The validation result.
     *
     * @param spec     the spec, or {@code null} when there are problems
     * @param problems every defect found; empty when valid
     */
    public record Result(MappingSpec spec, List<String> problems) {

        /**
         * Freezes the problems.
         *
         * @param spec     see the record description
         * @param problems see the record description
         */
        public Result {
            problems = List.copyOf(problems);
        }

        /**
         * Whether the tree is a valid mapping.
         *
         * @return true when there are no problems
         */
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    /**
     * Validates a tree.
     *
     * @param root the YAML tree
     * @param file a label for problem lines, for example {@code cloudtrail/mapping.yaml}
     * @return the result
     */
    public static Result validate(JsonNode root, String file) {
        return validate(root, file, null);
    }

    /**
     * Validates a tree that was loaded from a family directory.
     *
     * @param root           the YAML tree
     * @param file           a label for problem lines
     * @param expectedFamily the directory name, or {@code null} to skip the check
     * @return the result
     */
    public static Result validate(JsonNode root, String file, String expectedFamily) {
        MappingSpecValidator v = new MappingSpecValidator(file);
        MappingSpec spec = v.top(root, expectedFamily);
        return new Result(v.problems.isEmpty() ? spec : null, v.problems);
    }

    // ------------------------------------------------------------------
    // Top level
    // ------------------------------------------------------------------

    private MappingSpec top(JsonNode root, String expectedFamily) {
        if (root == null || !root.isObject()) {
            problem("", "must be a mapping with spec-version, family, source-kind, source-provider and envelopes");
            return null;
        }
        keys(root, TOP_KEYS, "");
        JsonNode sv = root.get("spec-version");
        if (sv == null) {
            problem("spec-version", "is required");
        } else if (!sv.isIntegralNumber() || sv.asInt() != MappingSpec.SPEC_VERSION) {
            problem("spec-version", "must be the integer " + MappingSpec.SPEC_VERSION);
        }
        String family = reqText(root, "family", "");
        if (family != null) {
            if (!FormatNames.FAMILY.matcher(family).matches()) {
                problem("family", "must be lower kebab case");
            } else if (MappingSpec.RESERVED_FAMILY.equals(family)) {
                problem("family", "'" + MappingSpec.RESERVED_FAMILY + "' is reserved for the schema directory");
            } else if (expectedFamily != null && !expectedFamily.equals(family)) {
                problem("family", "does not match its directory '" + expectedFamily + "'");
            }
        }
        String kind = reqText(root, "source-kind", "");
        if (kind != null && !FormatNames.SOURCE_KIND.matcher(kind).matches()) {
            problem("source-kind", "must be upper snake case");
            kind = null;
        }
        String provider = reqText(root, "source-provider", "");
        if (provider != null && !PROVIDERS.contains(provider)) {
            problem("source-provider", "must be one of " + new TreeSet<>(PROVIDERS));
            provider = null;
        }
        String description = optText(root, "description", "");
        MappingSpec.Settings settings = settings(root.get("settings"));
        List<MappingSpec.Envelope> envelopes = envelopes(root.get("envelopes"));
        JsonNode extensions = root.get("extensions");
        if (extensions != null && !extensions.isObject()) {
            problem("extensions", "must be a mapping");
        }
        if (!problems.isEmpty()) {
            return null;
        }
        return build("", () -> new MappingSpec(MappingSpec.SPEC_VERSION, family, root.get("source-kind").asText(),
                SourceProvider.valueOf(root.get("source-provider").asText()), description, settings, envelopes,
                extensions));
    }

    private MappingSpec.Settings settings(JsonNode node) {
        if (node == null || node.isNull()) {
            return MappingSpec.Settings.NONE;
        }
        String at = "settings";
        if (!node.isObject()) {
            problem(at, "must be a mapping");
            return MappingSpec.Settings.NONE;
        }
        keys(node, SETTINGS_KEYS, at);
        OnMismatch onMismatch = optEnum(node, "on-mismatch", at, OnMismatch::valueOf,
                Set.of("QUARANTINE", "PARSE_WITH_PIN", "PARSE_WITH_DETECTED"));
        PersistRaw.Policy persist = optEnum(node, "persist-raw", at, PersistRaw.Policy::valueOf,
                Set.of("ALL", "QUARANTINE_ONLY", "SAMPLED"));
        Long sample = optLong(node, "persist-raw-sample-one-in", at, 1, MAX_SAMPLE_ONE_IN);
        Long maxBytes = optLong(node, "max-record-bytes", at, 1, MAX_RECORD_BYTES_LIMIT);
        Duration overlap = optDuration(node, "overlap", at);
        Duration poll = optDuration(node, "poll-interval", at);
        return new MappingSpec.Settings(onMismatch, persist, sample == null ? null : sample.intValue(), maxBytes,
                overlap, poll);
    }

    // ------------------------------------------------------------------
    // Envelopes
    // ------------------------------------------------------------------

    private List<MappingSpec.Envelope> envelopes(JsonNode node) {
        List<MappingSpec.Envelope> out = new ArrayList<>();
        if (node == null) {
            problem("envelopes", "is required");
            return out;
        }
        if (!node.isArray() || node.isEmpty()) {
            problem("envelopes", "must be a non-empty list");
            return out;
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            String at = "envelopes[" + i + "]";
            MappingSpec.Envelope e = envelope(node.get(i), at);
            if (e != null) {
                if (!ids.add(e.id())) {
                    problem(at + ".id", "repeats envelope '" + e.id() + "'");
                }
                out.add(e);
            }
        }
        return out;
    }

    private MappingSpec.Envelope envelope(JsonNode node, String at) {
        if (!node.isObject()) {
            problem(at, "must be a mapping");
            return null;
        }
        int before = problems.size();
        keys(node, ENVELOPE_KEYS, at);
        String id = reqText(node, "id", at);
        if (id != null && !FormatNames.ENVELOPE.matcher(id).matches()) {
            problem(at + ".id", "must be lower kebab case");
        }
        String description = optText(node, "description", at);
        Set<Framing> framing = framing(node.get("framing"), at + ".framing");
        FieldPath recordsPointer = optPointer(node, "records-pointer", at);
        if (framing.contains(Framing.JSON_OBJECT_ARRAY) && recordsPointer == null) {
            problem(at + ".records-pointer", "is required with framing json-object-array");
        }
        if (!framing.contains(Framing.JSON_OBJECT_ARRAY) && recordsPointer != null) {
            problem(at + ".records-pointer", "is only allowed with framing json-object-array");
        }
        MappingSpec.Detection detection = detection(node.get("version-detection"), at + ".version-detection");
        MappingSpec.NativeKey nativeKey = nativeKey(node.get("native-key"), at + ".native-key");
        Map<CanonicalField, MappingSpec.FieldSource> fields = fields(node.get("fields"), at + ".fields", true);
        Set<CanonicalField> required = fieldList(node.get("required"), at + ".required");
        List<FieldPath> timeFallback = pointerList(node.get("time-fallback"), at + ".time-fallback");
        List<FieldPath> knownPaths = pointerList(node.get("known-paths"), at + ".known-paths");
        List<Condition> failureWhen = List.of();
        List<Condition> pendingWhen = List.of();
        JsonNode result = node.get("result");
        if (result != null) {
            if (!result.isObject()) {
                problem(at + ".result", "must be a mapping");
            } else {
                keys(result, RESULT_KEYS, at + ".result");
                failureWhen = conditions(result.get("failure-when"), at + ".result.failure-when", false);
                pendingWhen = conditions(result.get("pending-when"), at + ".result.pending-when", false);
            }
        }
        List<MappingSpec.SkipRule> skips = skips(node.get("skip-when"), at + ".skip-when");
        Map<String, PrincipalType> principalTypes = principalTypes(node.get("principal-types"), at + ".principal-types");
        List<ClassificationRule> rules = classification(node.get("classification"), at + ".classification");
        VersionScheme scheme = detection == null ? null : detection.scheme();
        List<MappingSpec.Version> versions = versions(node.get("versions"), at + ".versions", scheme);
        if (detection != null && detection.scheme() == VersionScheme.NAMED && !versions.isEmpty()) {
            Set<String> listed = new HashSet<>();
            versions.forEach(v -> listed.add(v.id()));
            Set<String> reachable = new HashSet<>();
            for (int i = 0; i < detection.rules().size(); i++) {
                String v = detection.rules().get(i).version();
                reachable.add(v);
                if (!listed.contains(v)) {
                    problem(at + ".version-detection.rules[" + i + "].version", "names '" + v
                            + "', which versions does not list");
                }
            }
            for (String v : listed) {
                if (!reachable.contains(v)) {
                    problem(at + ".versions", "lists '" + v + "', which no detection rule names");
                }
            }
        }
        if (problems.size() > before) {
            return null;
        }
        List<Condition> failures = failureWhen;
        List<Condition> pendings = pendingWhen;
        MappingSpec.Envelope env = build(at, () -> new MappingSpec.Envelope(id, description, framing, recordsPointer,
                detection, nativeKey, fields, required, timeFallback, knownPaths, failures, pendings, skips,
                principalTypes, rules, versions));
        if (env != null) {
            for (MappingSpec.Version v : env.versions()) {
                env.effective(v.id()).ifPresent(eff -> {
                    for (CanonicalField f : eff.required()) {
                        if (!eff.fields().containsKey(f)) {
                            problem(at + ".required", "names '" + f.key() + "', which has no source in version '"
                                    + v.id() + "'");
                        }
                    }
                });
            }
        }
        return env;
    }

    private Set<Framing> framing(JsonNode node, String at) {
        Set<Framing> out = EnumSet.noneOf(Framing.class);
        if (node == null) {
            return out;
        }
        if (!node.isArray() || node.isEmpty()) {
            problem(at, "must be a non-empty list of record, json-lines, json-array, json-object-array");
            return out;
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode n = node.get(i);
            Optional<Framing> f = n.isTextual() ? Framing.fromId(n.asText()) : Optional.empty();
            if (f.isEmpty()) {
                problem(at + "[" + i + "]", "must be record, json-lines, json-array or json-object-array");
            } else if (!out.add(f.get())) {
                problem(at + "[" + i + "]", "repeats " + f.get().id());
            }
        }
        return out;
    }

    private MappingSpec.Detection detection(JsonNode node, String at) {
        if (node == null) {
            problem(at, "is required");
            return null;
        }
        if (!node.isObject()) {
            problem(at, "must be a mapping");
            return null;
        }
        keys(node, DETECTION_KEYS, at);
        String scheme = reqText(node, "scheme", at);
        if (scheme == null) {
            return null;
        }
        switch (scheme) {
            case "major-minor" -> {
                if (node.has("rules")) {
                    problem(at + ".rules", "is only allowed with scheme named");
                }
                FieldPath pointer = reqPointer(node, "pointer", at);
                return pointer == null ? null : new MappingSpec.Detection(VersionScheme.MAJOR_MINOR, pointer, List.of());
            }
            case "named" -> {
                if (node.has("pointer")) {
                    problem(at + ".pointer", "is only allowed with scheme major-minor");
                }
                JsonNode rules = node.get("rules");
                if (rules == null || !rules.isArray() || rules.isEmpty()) {
                    problem(at + ".rules", "must be a non-empty list with scheme named");
                    return null;
                }
                List<MappingSpec.NamedRule> out = new ArrayList<>();
                for (int i = 0; i < rules.size(); i++) {
                    String rat = at + ".rules[" + i + "]";
                    JsonNode r = rules.get(i);
                    if (!r.isObject()) {
                        problem(rat, "must be a mapping with version and when");
                        continue;
                    }
                    keys(r, RULE_KEYS, rat);
                    String version = reqText(r, "version", rat);
                    if (version != null && !FormatNames.isVersion(version)) {
                        problem(rat + ".version", "must match " + FormatNames.VERSION.pattern());
                        version = null;
                    }
                    List<Condition> when = conditions(r.get("when"), rat + ".when", true);
                    if (version != null && !when.isEmpty()) {
                        String v = version;
                        MappingSpec.NamedRule rule = build(rat, () -> new MappingSpec.NamedRule(v, when));
                        if (rule != null) {
                            out.add(rule);
                        }
                    }
                }
                return out.isEmpty() ? null : new MappingSpec.Detection(VersionScheme.NAMED, null, out);
            }
            default -> {
                problem(at + ".scheme", "must be major-minor or named");
                return null;
            }
        }
    }

    private MappingSpec.NativeKey nativeKey(JsonNode node, String at) {
        if (node == null) {
            problem(at, "is required");
            return null;
        }
        if (!node.isObject()) {
            problem(at, "must be a mapping");
            return null;
        }
        keys(node, NATIVE_KEY_KEYS, at);
        boolean namespaced = false;
        JsonNode ns = node.get("namespaced");
        if (ns != null) {
            if (!ns.isBoolean()) {
                problem(at + ".namespaced", "must be true or false");
            } else {
                namespaced = ns.asBoolean();
            }
        }
        JsonNode parts = node.get("parts");
        if (parts == null || !parts.isArray() || parts.isEmpty()) {
            problem(at + ".parts", "must be a non-empty list");
            return null;
        }
        List<MappingSpec.KeyPart> out = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            String pat = at + ".parts[" + i + "]";
            JsonNode p = parts.get(i);
            if (!p.isObject()) {
                problem(pat, "must be a mapping with pointer and optional as");
                continue;
            }
            keys(p, PART_KEYS, pat);
            FieldPath pointer = reqPointer(p, "pointer", pat);
            MappingSpec.PartFormat format = MappingSpec.PartFormat.TEXT;
            String as = optText(p, "as", pat);
            if (as != null) {
                switch (as) {
                    case "text" -> format = MappingSpec.PartFormat.TEXT;
                    case "instant" -> format = MappingSpec.PartFormat.INSTANT;
                    default -> problem(pat + ".as", "must be text or instant");
                }
            }
            if (pointer != null) {
                out.add(new MappingSpec.KeyPart(pointer, format));
            }
        }
        return out.isEmpty() ? null : new MappingSpec.NativeKey(namespaced, out);
    }

    private Map<CanonicalField, MappingSpec.FieldSource> fields(JsonNode node, String at, boolean envelopeLevel) {
        Map<CanonicalField, MappingSpec.FieldSource> out = new EnumMap<>(CanonicalField.class);
        if (node == null) {
            if (envelopeLevel) {
                problem(at, "is required and must map event-time and operation");
            }
            return out;
        }
        if (!node.isObject()) {
            problem(at, "must be a mapping of canonical field to pointer, pointer list or {const: text}");
            return out;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String fat = at + "." + e.getKey();
            Optional<CanonicalField> field = CanonicalField.fromKey(e.getKey());
            if (field.isEmpty()) {
                problem(fat, "is not a canonical field (allowed: " + canonicalKeys() + ")");
                continue;
            }
            MappingSpec.FieldSource source = fieldSource(e.getValue(), fat);
            if (source != null) {
                out.put(field.get(), source);
            }
        }
        if (envelopeLevel) {
            for (CanonicalField f : List.of(CanonicalField.EVENT_TIME, CanonicalField.OPERATION)) {
                if (!node.has(f.key())) {
                    problem(at + "." + f.key(), "is required");
                }
            }
        }
        return out;
    }

    private MappingSpec.FieldSource fieldSource(JsonNode v, String at) {
        if (v.isTextual()) {
            FieldPath p = pointer(v, at);
            return p == null ? null : MappingSpec.FieldSource.of(List.of(p));
        }
        if (v.isArray()) {
            if (v.isEmpty()) {
                problem(at, "must list at least one pointer");
                return null;
            }
            List<FieldPath> paths = new ArrayList<>();
            for (int i = 0; i < v.size(); i++) {
                FieldPath p = pointer(v.get(i), at + "[" + i + "]");
                if (p != null) {
                    paths.add(p);
                }
            }
            return paths.size() == v.size() ? MappingSpec.FieldSource.of(paths) : null;
        }
        if (v.isObject()) {
            keys(v, FIELD_SOURCE_KEYS, at);
            String c = reqText(v, "const", at);
            return c == null ? null : MappingSpec.FieldSource.constant(c);
        }
        problem(at, "must be a pointer, a list of pointers or {const: text}");
        return null;
    }

    private Set<CanonicalField> fieldList(JsonNode node, String at) {
        Set<CanonicalField> out = EnumSet.noneOf(CanonicalField.class);
        if (node == null) {
            return out;
        }
        if (!node.isArray()) {
            problem(at, "must be a list of canonical fields");
            return out;
        }
        for (int i = 0; i < node.size(); i++) {
            JsonNode n = node.get(i);
            Optional<CanonicalField> f = n.isTextual() ? CanonicalField.fromKey(n.asText()) : Optional.empty();
            if (f.isEmpty()) {
                problem(at + "[" + i + "]", "is not a canonical field (allowed: " + canonicalKeys() + ")");
            } else if (!out.add(f.get())) {
                problem(at + "[" + i + "]", "repeats " + f.get().key());
            }
        }
        return out;
    }

    private List<MappingSpec.SkipRule> skips(JsonNode node, String at) {
        List<MappingSpec.SkipRule> out = new ArrayList<>();
        if (node == null) {
            return out;
        }
        if (!node.isArray()) {
            problem(at, "must be a list");
            return out;
        }
        for (int i = 0; i < node.size(); i++) {
            String sat = at + "[" + i + "]";
            JsonNode s = node.get(i);
            if (!s.isObject()) {
                problem(sat, "must be a mapping with reason and when");
                continue;
            }
            keys(s, SKIP_KEYS, sat);
            SkipReason reason = reqEnum(s, "reason", sat, SkipReason::valueOf, SKIP_REASONS);
            List<Condition> when = conditions(s.get("when"), sat + ".when", true);
            if (reason != null && !when.isEmpty()) {
                MappingSpec.SkipRule rule = build(sat, () -> new MappingSpec.SkipRule(reason, when));
                if (rule != null) {
                    out.add(rule);
                }
            }
        }
        return out;
    }

    private Map<String, PrincipalType> principalTypes(JsonNode node, String at) {
        Map<String, PrincipalType> out = new LinkedHashMap<>();
        if (node == null) {
            return out;
        }
        if (!node.isObject()) {
            problem(at, "must be a mapping of provider identity type to " + new TreeSet<>(PRINCIPAL_TYPES));
            return out;
        }
        Map<String, String> normalized = new HashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String pat = at + "." + e.getKey();
            String norm = MappingSpec.normalizeIdentityType(e.getKey());
            if (norm.isEmpty()) {
                problem(pat, "key must contain an ASCII letter or digit");
                continue;
            }
            String clash = normalized.putIfAbsent(norm, e.getKey());
            if (clash != null) {
                problem(pat, "collides with '" + clash + "' once case and separators are ignored");
                continue;
            }
            JsonNode v = e.getValue();
            if (!v.isTextual() || !PRINCIPAL_TYPES.contains(v.asText())) {
                problem(pat, "must be one of " + new TreeSet<>(PRINCIPAL_TYPES));
                continue;
            }
            out.put(e.getKey(), PrincipalType.valueOf(v.asText()));
        }
        return out;
    }

    private List<ClassificationRule> classification(JsonNode node, String at) {
        List<ClassificationRule> out = new ArrayList<>();
        if (node == null) {
            return out;
        }
        if (!node.isObject()) {
            problem(at, "must be a mapping with rules");
            return out;
        }
        keys(node, CLASSIFICATION_KEYS, at);
        JsonNode rules = node.get("rules");
        if (rules == null) {
            return out;
        }
        if (!rules.isArray()) {
            problem(at + ".rules", "must be a list");
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            String rat = at + ".rules[" + i + "]";
            JsonNode r = rules.get(i);
            if (!r.isObject()) {
                problem(rat, "must be a mapping");
                continue;
            }
            int before = problems.size();
            keys(r, CLASS_RULE_KEYS, rat);
            String service = reqText(r, "service", rat);
            String operation = optText(r, "operation", rat);
            String prefix = optText(r, "operation-prefix", rat);
            if ((operation == null) == (prefix == null)) {
                problem(rat, "needs exactly one of operation and operation-prefix");
            }
            ChangeType changeType = reqEnum(r, "change-type", rat, ChangeType::valueOf, CHANGE_TYPES);
            EventCategory category = reqEnum(r, "category", rat, c -> EventCategory.valueOf("EVENT_CATEGORY_" + c),
                    CATEGORIES);
            AssetKind kind = null;
            String kindName = optText(r, "asset-kind", rat);
            if (kindName != null) {
                try {
                    kind = AssetKind.valueOf(kindName);
                } catch (IllegalArgumentException e) {
                    problem(rat + ".asset-kind", "is not an AssetKind");
                }
            }
            String extractor = optText(r, "extractor", rat);
            if (extractor != null && !ClassificationRule.EXTRACTOR.matcher(extractor).matches()) {
                problem(rat + ".extractor", "must match " + ClassificationRule.EXTRACTOR.pattern());
            }
            if (problems.size() > before) {
                continue;
            }
            String key = service + "\u0000" + (operation != null ? "=" + operation : "^" + prefix);
            if (!seen.add(key)) {
                problem(rat, "repeats the service and " + (operation != null ? "operation" : "operation-prefix")
                        + " of an earlier rule");
                continue;
            }
            AssetKind k = kind;
            ClassificationRule rule = build(rat, () -> new ClassificationRule(service, operation, prefix, changeType,
                    category, k, extractor));
            if (rule != null) {
                out.add(rule);
            }
        }
        return out;
    }

    private List<MappingSpec.Version> versions(JsonNode node, String at, VersionScheme scheme) {
        List<MappingSpec.Version> out = new ArrayList<>();
        if (node == null) {
            problem(at, "is required");
            return out;
        }
        if (!node.isArray() || node.isEmpty()) {
            problem(at, "must be a non-empty list");
            return out;
        }
        Set<String> ids = new HashSet<>();
        Set<MajorMinor> numbers = new HashSet<>();
        for (int i = 0; i < node.size(); i++) {
            String vat = at + "[" + i + "]";
            JsonNode v = node.get(i);
            if (!v.isObject()) {
                problem(vat, "must be a mapping with id");
                continue;
            }
            int before = problems.size();
            keys(v, VERSION_KEYS, vat);
            String id = reqText(v, "id", vat);
            if (id != null) {
                if (!FormatNames.isVersion(id)) {
                    problem(vat + ".id", "must match " + FormatNames.VERSION.pattern() + " (quote numbers: \"1.10\")");
                } else if (scheme == VersionScheme.MAJOR_MINOR) {
                    Optional<MajorMinor> mm = MajorMinor.parse(id);
                    if (mm.isEmpty()) {
                        problem(vat + ".id", "must be major.minor digits with scheme major-minor");
                    } else if (!numbers.add(mm.get())) {
                        problem(vat + ".id", "repeats version " + mm.get());
                    }
                } else if (!ids.add(id)) {
                    problem(vat + ".id", "repeats version '" + id + "'");
                }
            }
            String description = optText(v, "description", vat);
            Map<CanonicalField, MappingSpec.FieldSource> fields = fields(v.get("fields"), vat + ".fields", false);
            Set<CanonicalField> required = fieldList(v.get("required"), vat + ".required");
            List<FieldPath> known = pointerList(v.get("known-paths"), vat + ".known-paths");
            if (problems.size() == before && id != null) {
                out.add(new MappingSpec.Version(id, description, fields, required, known));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Conditions and pointers
    // ------------------------------------------------------------------

    private List<Condition> conditions(JsonNode node, String at, boolean required) {
        List<Condition> out = new ArrayList<>();
        if (node == null) {
            if (required) {
                problem(at, "is required");
            }
            return out;
        }
        if (!node.isArray() || (required && node.isEmpty())) {
            problem(at, required ? "must be a non-empty list of conditions" : "must be a list of conditions");
            return out;
        }
        for (int i = 0; i < node.size(); i++) {
            Condition c = condition(node.get(i), at + "[" + i + "]");
            if (c != null) {
                out.add(c);
            }
        }
        return out.size() == node.size() ? out : List.of();
    }

    private Condition condition(JsonNode node, String at) {
        if (!node.isObject()) {
            problem(at, "must be a mapping with pointer and one of present, equals, not-equals, in");
            return null;
        }
        keys(node, CONDITION_KEYS, at);
        FieldPath path = reqPointer(node, "pointer", at);
        List<String> ops = new ArrayList<>();
        node.fieldNames().forEachRemaining(k -> {
            if (CONDITION_OPS.contains(k)) {
                ops.add(k);
            }
        });
        if (ops.size() != 1) {
            problem(at, "needs exactly one of present, equals, not-equals, in");
            return null;
        }
        String op = ops.get(0);
        JsonNode v = node.get(op);
        Condition.Op kind;
        JsonNode operand = null;
        switch (op) {
            case "present" -> {
                if (!v.isBoolean()) {
                    problem(at + ".present", "must be true or false");
                    return null;
                }
                kind = v.asBoolean() ? Condition.Op.PRESENT : Condition.Op.ABSENT;
            }
            case "equals", "not-equals" -> {
                if (!v.isValueNode() || v.isNull() || !(v.isTextual() || v.isNumber() || v.isBoolean())) {
                    problem(at + "." + op, "must be a string, number or boolean");
                    return null;
                }
                kind = "equals".equals(op) ? Condition.Op.EQUALS : Condition.Op.NOT_EQUALS;
                operand = v;
            }
            default -> {
                boolean scalars = v.isArray() && !v.isEmpty();
                if (scalars) {
                    for (JsonNode item : v) {
                        scalars &= item.isTextual() || item.isNumber() || item.isBoolean();
                    }
                }
                if (!scalars) {
                    problem(at + ".in", "must be a non-empty list of strings, numbers or booleans");
                    return null;
                }
                kind = Condition.Op.IN;
                operand = v;
            }
        }
        if (path == null) {
            return null;
        }
        Condition.Op k = kind;
        JsonNode o = operand;
        return build(at, () -> new Condition(path, k, o));
    }

    private List<FieldPath> pointerList(JsonNode node, String at) {
        List<FieldPath> out = new ArrayList<>();
        if (node == null) {
            return out;
        }
        if (!node.isArray()) {
            problem(at, "must be a list of pointers");
            return out;
        }
        for (int i = 0; i < node.size(); i++) {
            FieldPath p = pointer(node.get(i), at + "[" + i + "]");
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    private FieldPath reqPointer(JsonNode node, String key, String at) {
        JsonNode v = node.get(key);
        if (v == null) {
            problem(at + "." + key, "is required");
            return null;
        }
        return pointer(v, at + "." + key);
    }

    private FieldPath optPointer(JsonNode node, String key, String at) {
        JsonNode v = node.get(key);
        return v == null ? null : pointer(v, at + "." + key);
    }

    private FieldPath pointer(JsonNode v, String at) {
        if (!v.isTextual()) {
            problem(at, "must be a JSON pointer string");
            return null;
        }
        if (!FieldPath.isValid(v.asText()) || v.asText().isEmpty()) {
            problem(at, "must be a JSON pointer such as /eventName ('~' written ~0 or ~1, '*' for any element)");
            return null;
        }
        return FieldPath.of(v.asText());
    }

    // ------------------------------------------------------------------
    // Scalars
    // ------------------------------------------------------------------

    private void keys(JsonNode node, Set<String> allowed, String at) {
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            if (!allowed.contains(k)) {
                problem(at.isEmpty() ? k : at + "." + k, "is not a known key (allowed: " + new TreeSet<>(allowed) + ")");
            }
        }
    }

    private String reqText(JsonNode node, String key, String at) {
        JsonNode v = node.get(key);
        if (v == null) {
            problem(join(at, key), "is required");
            return null;
        }
        return text(v, join(at, key));
    }

    private String optText(JsonNode node, String key, String at) {
        JsonNode v = node.get(key);
        return v == null ? null : text(v, join(at, key));
    }

    private String text(JsonNode v, String at) {
        if (!v.isTextual()) {
            problem(at, "must be a string (quote numbers and dates in YAML)");
            return null;
        }
        if (v.asText().isBlank()) {
            problem(at, "must not be blank");
            return null;
        }
        return v.asText();
    }

    private <E> E reqEnum(JsonNode node, String key, String at, Function<String, E> parse, Set<String> allowed) {
        if (!node.has(key)) {
            problem(join(at, key), "is required");
            return null;
        }
        return optEnum(node, key, at, parse, allowed);
    }

    private <E> E optEnum(JsonNode node, String key, String at, Function<String, E> parse, Set<String> allowed) {
        String s = optText(node, key, at);
        if (s == null) {
            return null;
        }
        if (!allowed.contains(s)) {
            problem(join(at, key), "must be one of " + new TreeSet<>(allowed));
            return null;
        }
        return parse.apply(s);
    }

    private Long optLong(JsonNode node, String key, String at, long min, long max) {
        JsonNode v = node.get(key);
        if (v == null) {
            return null;
        }
        if (!v.isIntegralNumber() || !v.canConvertToLong() || v.asLong() < min || v.asLong() > max) {
            problem(join(at, key), "must be an integer from " + min + " to " + max);
            return null;
        }
        return v.asLong();
    }

    private Duration optDuration(JsonNode node, String key, String at) {
        String s = optText(node, key, at);
        if (s == null) {
            return null;
        }
        if (!DURATION.matcher(s).matches()) {
            problem(join(at, key), "must be a positive ISO-8601 duration such as PT20M");
            return null;
        }
        try {
            Duration d = Duration.parse(s);
            if (d.isNegative() || d.isZero()) {
                problem(join(at, key), "must be a positive ISO-8601 duration such as PT20M");
                return null;
            }
            return d;
        } catch (DateTimeParseException e) {
            problem(join(at, key), "must be an ISO-8601 duration such as PT20M");
            return null;
        }
    }

    private <T> T build(String at, java.util.function.Supplier<T> constructor) {
        try {
            return constructor.get();
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException e) {
            problem(at, String.valueOf(e.getMessage()));
            return null;
        }
    }

    private void problem(String at, String message) {
        problems.add(file + ": " + (at.isEmpty() ? "(root)" : at) + ": " + message);
    }

    private static String join(String at, String key) {
        return at.isEmpty() ? key : at + "." + key;
    }

    private static String canonicalKeys() {
        Set<String> keys = new TreeSet<>();
        for (CanonicalField f : CanonicalField.values()) {
            keys.add(f.key());
        }
        return keys.toString();
    }
}
