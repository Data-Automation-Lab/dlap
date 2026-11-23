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

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalEvent;
import com.dlap.logingest.parse.core.CanonicalField;
import com.dlap.logingest.parse.core.Classification;
import com.dlap.logingest.parse.core.EnvelopeKey;
import com.dlap.logingest.parse.core.FieldPath;
import com.dlap.logingest.parse.core.FormatKey;
import com.dlap.logingest.parse.core.LogRecordParser;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.PersistRaw;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.SkipReason;
import com.dlap.logingest.parse.core.VersionRange;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.QuarantineReason;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A {@link LogRecordParser} that executes a family's mapping YAML, so most of a family is data.
 * A family declares one bean per version range ({@link #forEnvelope}) and overrides the hooks
 * where its records need code: {@link #target}, {@link #related} and {@link #rawEvent}.
 *
 * <p>Order of evaluation for one record, with the effective mapping of its version:
 * <ol>
 *   <li>{@code skip-when}: the first rule whose conditions hold skips the record.</li>
 *   <li>Event time from {@code event-time}; when absent, from {@code time-fallback} with
 *       {@code PARSE_WARNING_EVENT_TIME_FALLBACK}; when neither holds a value,
 *       {@code Skipped(MISSING_EVENT_TIME)}. Text that is not RFC 3339 with a zone is
 *       {@code FIELD_TYPE_MISMATCH}.</li>
 *   <li>Native key from {@code native-key}; a missing part is {@code MISSING_REQUIRED_FIELD}.</li>
 *   <li>{@code operation}, then every {@code required} field; a missing one is
 *       {@code MISSING_REQUIRED_FIELD}, an object or array where text belongs is
 *       {@code FIELD_TYPE_MISMATCH}. From here on a quarantine carries a
 *       {@code PARSE_STATUS_PARTIAL} pass-through event.</li>
 *   <li>Classification: binding {@code operation-overrides}, then the mapping's rows, then the
 *       shared verb heuristics.</li>
 *   <li>Result: any {@code failure-when} gives {@code FAILURE}, else any {@code pending-when}
 *       gives {@code PENDING}, else {@code SUCCESS}.</li>
 *   <li>Principal, with {@code principal-types}; target ({@link #target}), normalized through
 *       {@code AssetIdNormalizer}; a data-category row with an {@code asset-kind} whose target
 *       does not normalize is {@code UNRESOLVABLE_ASSET_ID}.</li>
 * </ol>
 * A binding's {@code field-overrides} are tried before the mapping's sources for each field. An
 * optional field that holds an object or array where text belongs reads as absent.
 */
public class MappingDrivenParser implements LogRecordParser {

    private final MappingSpec spec;
    private final MappingSpec.Envelope envelope;
    private final VersionRange range;
    private final EnvelopeKey key;
    private final String parserId;
    private final String parserSemver;

    /**
     * Creates a parser for one version range of one envelope.
     *
     * @param spec         the family mapping
     * @param envelopeId   the envelope
     * @param range        one of the envelope's {@link MappingSpec.Envelope#ranges()}
     * @param parserId     the parser id
     * @param parserSemver the parser's semantic version
     */
    public MappingDrivenParser(MappingSpec spec, String envelopeId, VersionRange range, String parserId,
                               String parserSemver) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.envelope = spec.envelope(envelopeId).orElseThrow(() ->
                new IllegalArgumentException("family " + spec.family() + " has no envelope " + envelopeId));
        if (!envelope.ranges().contains(range)) {
            throw new IllegalArgumentException("range " + range.describe() + " is not one of " + envelope.ranges());
        }
        this.range = range;
        this.key = spec.keyOf(envelope);
        this.parserId = parserId;
        this.parserSemver = parserSemver;
    }

    /**
     * One parser per version range of an envelope, without hooks overridden.
     *
     * @param spec         the family mapping
     * @param envelopeId   the envelope
     * @param parserId     the parser id
     * @param parserSemver the parser's semantic version
     * @return the parsers
     */
    public static List<MappingDrivenParser> forEnvelope(MappingSpec spec, String envelopeId, String parserId,
                                                        String parserSemver) {
        MappingSpec.Envelope e = spec.envelope(envelopeId).orElseThrow(() ->
                new IllegalArgumentException("family " + spec.family() + " has no envelope " + envelopeId));
        List<MappingDrivenParser> out = new ArrayList<>();
        for (VersionRange r : e.ranges()) {
            out.add(new MappingDrivenParser(spec, envelopeId, r, parserId, parserSemver));
        }
        return List.copyOf(out);
    }

    @Override
    public EnvelopeKey envelopeKey() {
        return key;
    }

    @Override
    public VersionRange supports() {
        return range;
    }

    @Override
    public SourceProvider sourceProvider() {
        return spec.sourceProvider();
    }

    @Override
    public String parserId() {
        return parserId;
    }

    @Override
    public String parserSemver() {
        return parserSemver;
    }

    @Override
    public Set<FieldPath> declaredPaths(String effectiveVersion) {
        return envelope.effective(effectiveVersion).map(MappingSpec.Effective::declaredPaths).orElse(Set.of());
    }

    /**
     * Returns the family mapping.
     *
     * @return the spec
     */
    protected MappingSpec spec() {
        return spec;
    }

    @Override
    public ParseResult parse(RawRecord record, FormatKey effective, BindingConfig binding) {
        Optional<MappingSpec.Effective> found = envelope.effective(effective.schemaVersion());
        if (found.isEmpty()) {
            return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_UNKNOWN_MAJOR_VERSION,
                    "the mapping lists no entry for the effective version");
        }
        MappingSpec.Effective m = found.get();
        JsonNode body = record.body();
        if (!body.isObject()) {
            return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                    "the record is not a JSON object");
        }
        for (int i = 0; i < m.skipWhen().size(); i++) {
            MappingSpec.SkipRule rule = m.skipWhen().get(i);
            if (Condition.allMatch(rule.when(), body)) {
                return new ParseResult.Skipped(rule.reason(), "skip-when rule " + i);
            }
        }
        Fields f = new Fields(m, binding, body);
        Set<ParseWarning> warnings = new LinkedHashSet<>();

        Instant eventTime;
        Optional<JsonNode> timeNode = f.node(CanonicalField.EVENT_TIME);
        if (timeNode.isPresent()) {
            Optional<Instant> t = time(timeNode.get());
            if (t.isEmpty()) {
                return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                        "event-time is not an RFC 3339 time with a zone in the supported range");
            }
            eventTime = t.get();
        } else {
            Optional<JsonNode> fallback = m.timeFallback().stream().map(p -> p.first(body))
                    .flatMap(Optional::stream).findFirst();
            if (fallback.isEmpty()) {
                return new ParseResult.Skipped(SkipReason.MISSING_EVENT_TIME, "no event-time and no fallback clock");
            }
            Optional<Instant> t = time(fallback.get());
            if (t.isEmpty()) {
                return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                        "time-fallback is not an RFC 3339 time with a zone in the supported range");
            }
            eventTime = t.get();
            warnings.add(ParseWarning.PARSE_WARNING_EVENT_TIME_FALLBACK);
        }

        List<String> parts = new ArrayList<>();
        for (int i = 0; i < m.nativeKey().parts().size(); i++) {
            MappingSpec.KeyPart part = m.nativeKey().parts().get(i);
            Optional<JsonNode> n = part.path().first(body);
            if (n.isEmpty() || n.get().isContainerNode()) {
                return ParseResult.Quarantined.of(n.isEmpty()
                                ? QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD
                                : QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                        "native-key part " + i + " (" + part.path() + ") is " + (n.isEmpty() ? "missing" : "not a scalar"));
            }
            if (part.format() == MappingSpec.PartFormat.INSTANT) {
                Optional<Instant> t = time(n.get());
                if (t.isEmpty()) {
                    return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                            "native-key part " + i + " (" + part.path() + ") is not an RFC 3339 time");
                }
                parts.add(t.get().toString());
            } else {
                parts.add(n.get().asText());
            }
        }
        String nativeKey = m.nativeKey().namespaced()
                ? LogEventIds.namespacedNativeKey(key.sourceKind(), parts.toArray(String[]::new))
                : String.join(LogEventIds.SEPARATOR, parts);

        Optional<String> operation = f.text(CanonicalField.OPERATION);
        if (operation.isEmpty()) {
            return ParseResult.Quarantined.of(f.typeMismatch(CanonicalField.OPERATION)
                            ? QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH
                            : QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD,
                    "operation is " + (f.typeMismatch(CanonicalField.OPERATION) ? "not text" : "missing"));
        }
        CanonicalEvent passThrough = CanonicalEvent.builder()
                .nativeKey(nativeKey)
                .provider(spec.sourceProvider())
                .service(f.text(CanonicalField.SERVICE).orElse(""))
                .operation(operation.get())
                .eventTime(eventTime)
                .raw(rawEvent(record, m))
                .warnings(warnings)
                .passThrough()
                .build();
        for (CanonicalField req : m.required()) {
            if (f.text(req).isEmpty()) {
                boolean mismatch = f.typeMismatch(req);
                return new ParseResult.Quarantined(mismatch
                        ? QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH
                        : QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD,
                        req.key() + " is " + (mismatch ? "not text" : "missing"), Optional.of(passThrough));
            }
        }

        String service = f.text(CanonicalField.SERVICE).orElse("");
        Classification classification = Classification.classify(binding.operationOverrides(), m.rules(), service,
                operation.get());
        ResultStatus result = Condition.anyMatch(m.failureWhen(), body)
                ? ResultStatus.FAILURE
                : Condition.anyMatch(m.pendingWhen(), body) ? ResultStatus.PENDING : ResultStatus.SUCCESS;
        PrincipalType principalType = f.text(CanonicalField.PRINCIPAL_TYPE).flatMap(m::principalType)
                .orElse(PrincipalType.PRINCIPAL_TYPE_UNSPECIFIED);
        CanonicalEvent.Actor principal = new CanonicalEvent.Actor(principalType,
                f.text(CanonicalField.PRINCIPAL_ID).orElse(""),
                f.text(CanonicalField.PRINCIPAL_DISPLAY_NAME).orElse(""),
                f.text(CanonicalField.PRINCIPAL_ACCOUNT_ID).orElse(""));
        Context ctx = new Context(record, body, m, binding, classification, f);
        CanonicalEvent.Resource target = target(ctx);
        if (classification.assetKind().isPresent() && PersistRaw.isDataCategory(classification.category())
                && target.canonicalUri().isEmpty()) {
            return new ParseResult.Quarantined(QuarantineReason.QUARANTINE_REASON_UNRESOLVABLE_ASSET_ID,
                    "target.id does not normalize to a " + classification.assetKind().get() + " asset id",
                    Optional.of(passThrough));
        }
        Optional<Instant> observed = f.node(CanonicalField.OBSERVED_TIME).flatMap(MappingDrivenParser::time);

        CanonicalEvent.Builder event = CanonicalEvent.builder()
                .nativeKey(nativeKey)
                .provider(spec.sourceProvider())
                .service(service)
                .operation(operation.get())
                .classification(classification)
                .result(result)
                .eventTime(eventTime)
                .observedTime(observed.orElse(null))
                .principal(principal)
                .target(target)
                .request(new CanonicalEvent.Request(
                        f.text(CanonicalField.CALLER_IP).orElse(""),
                        f.text(CanonicalField.USER_AGENT).orElse(""),
                        f.text(CanonicalField.REQUEST_ID).orElse(""),
                        f.text(CanonicalField.TRACE_ID).orElse("")))
                .raw(rawEvent(record, m))
                .sharedEventId(f.text(CanonicalField.SHARED_EVENT_ID).orElse(""))
                .supersedesNativeKey(f.text(CanonicalField.SUPERSEDES_NATIVE_KEY).orElse(""));
        warnings.forEach(event::warning);
        related(ctx).forEach(event::addRelated);
        return ParseResult.Parsed.of(event.build());
    }

    /**
     * Builds the target. The default reads the {@code target.*} fields and normalizes
     * {@code target.id} with the binding's hints, refined by the classification's asset kind.
     *
     * @param ctx the record context
     * @return the target
     */
    protected CanonicalEvent.Resource target(Context ctx) {
        String id = ctx.fields().text(CanonicalField.TARGET_ID).orElse("");
        CanonicalEvent.ResourceBuilder b = CanonicalEvent.Resource.builder()
                .kind(ctx.fields().text(CanonicalField.TARGET_KIND).orElse(""))
                .id(id)
                .name(ctx.fields().text(CanonicalField.TARGET_NAME).orElse(""))
                .project(ctx.fields().text(CanonicalField.TARGET_PROJECT).orElse(""))
                .region(ctx.fields().text(CanonicalField.TARGET_REGION).orElse(""))
                .resourcePath(ctx.fields().text(CanonicalField.TARGET_RESOURCE_PATH).orElse(""));
        if (!id.isEmpty()) {
            b.canonicalFrom(id, ctx.assetIdHints());
        }
        return b.build();
    }

    /**
     * Builds related resources. The default has none.
     *
     * @param ctx the record context
     * @return resources with a relation each
     */
    protected List<CanonicalEvent.Resource> related(Context ctx) {
        return List.of();
    }

    /**
     * Returns the value for {@code raw_event}. The default is {@code null}: the mapper uses the
     * record body, which is right when the transport already delivers the rawEvent convention.
     *
     * @param record  the record
     * @param mapping the effective mapping
     * @return the raw value, or {@code null}
     */
    protected JsonNode rawEvent(RawRecord record, MappingSpec.Effective mapping) {
        return null;
    }

    /**
     * Reads an RFC 3339 time with a zone, inside the protobuf range.
     *
     * @param node the value
     * @return the instant, or empty
     */
    public static Optional<Instant> time(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return Optional.empty();
        }
        String s = node.asText().strip();
        Instant t;
        try {
            t = OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        if (t.isBefore(CanonicalEvent.MIN_WIRE_TIME) || t.isAfter(CanonicalEvent.MAX_WIRE_TIME)) {
            return Optional.empty();
        }
        return Optional.of(t);
    }

    /**
     * What hooks see of one record.
     *
     * @param record         the record
     * @param body           its decoded body
     * @param mapping        the effective mapping
     * @param binding        the binding
     * @param classification the event's classification
     * @param fields         field lookups with binding overrides applied
     */
    public record Context(
            RawRecord record,
            JsonNode body,
            MappingSpec.Effective mapping,
            BindingConfig binding,
            Classification classification,
            Fields fields) {

        /**
         * The binding's asset-id hints refined by the classification's asset kind.
         *
         * @return the hints
         */
        public AssetIdHints assetIdHints() {
            AssetIdHints hints = binding.assetIdHints();
            Optional<AssetKind> kind = classification.assetKind();
            return kind.isPresent() ? hints.withKind(kind.get()) : hints;
        }

        /**
         * Whether the event's category is a data category.
         *
         * @return true for data reads and writes
         */
        public boolean dataEvent() {
            EventCategory c = classification.category();
            return PersistRaw.isDataCategory(c);
        }
    }

    /**
     * Canonical field lookups for one record: binding {@code field-overrides} first, then the
     * mapping's source.
     */
    public static final class Fields {
        private final MappingSpec.Effective mapping;
        private final BindingConfig binding;
        private final JsonNode body;

        /**
         * Creates the lookups.
         *
         * @param mapping the effective mapping
         * @param binding the binding
         * @param body    the record body
         */
        public Fields(MappingSpec.Effective mapping, BindingConfig binding, JsonNode body) {
            this.mapping = mapping;
            this.binding = binding;
            this.body = body;
        }

        /**
         * The node of a field.
         *
         * @param field the field
         * @return the first node that holds a value, or empty
         */
        public Optional<JsonNode> node(CanonicalField field) {
            List<FieldPath> override = binding.fieldOverrides().get(field);
            if (override != null) {
                for (FieldPath p : override) {
                    Optional<JsonNode> hit = p.first(body);
                    if (hit.isPresent()) {
                        return hit;
                    }
                }
            }
            MappingSpec.FieldSource source = mapping.fields().get(field);
            return source == null ? Optional.empty() : source.resolve(body);
        }

        /**
         * The text of a field: text as is, numbers and booleans as their text.
         *
         * @param field the field
         * @return the text, or empty when absent or a container
         */
        public Optional<String> text(CanonicalField field) {
            return node(field).filter(n -> !n.isContainerNode()).map(JsonNode::asText);
        }

        /**
         * Whether a field holds an object or array where text belongs.
         *
         * @param field the field
         * @return true on a type mismatch
         */
        public boolean typeMismatch(CanonicalField field) {
            return node(field).map(JsonNode::isContainerNode).orElse(false);
        }
    }
}
