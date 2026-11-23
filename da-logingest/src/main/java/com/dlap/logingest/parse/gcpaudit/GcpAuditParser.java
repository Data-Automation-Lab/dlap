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

package com.dlap.logingest.parse.gcpaudit;

import com.dlap.common.assetid.AssetIdHints;
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
import com.dlap.logingest.parse.core.mapping.Condition;
import com.dlap.logingest.parse.core.mapping.MappingDrivenParser;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.QuarantineReason;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The parser of one envelope of the {@code GCP_CLOUD_AUDIT} family (GCP-01). The family mapping
 * ({@code dlap/log-formats/gcpaudit/mapping.yaml}) supplies the field locations, the required
 * fields, the time fallback, the failure conditions and the classification table; this class adds
 * what needs code: the BigQuery event keys, reads from {@code tableDataRead} and
 * {@code referencedTables}, the fan-out of one job into one read per table, the actor classes and
 * the sink-row view.
 *
 * <p>Order for one record:
 * <ol>
 *   <li>a {@code bq-sink-row} is turned into the LogEntry view ({@link BqSinkRows}); the view is the
 *       {@code raw_event} and every later step reads it;</li>
 *   <li>{@code protoPayload} must be an AuditLog: absent is {@code MISSING_REQUIRED_FIELD}, another
 *       {@code @type} is {@code UNKNOWN_ENVELOPE};</li>
 *   <li>a part after the first of a split entry ({@code split.index > 0}) is
 *       {@code SKIPPED:NOT_AN_EVENT}: the first part carries the event and
 *       {@code PARSE_WARNING_PROVIDER_TRUNCATED}, so an oversized entry is never counted twice;</li>
 *   <li>event time is {@code timestamp}, else {@code receiveTimestamp} with
 *       {@code PARSE_WARNING_EVENT_TIME_FALLBACK}. A LogEntry with neither is not a valid stored
 *       entry (Cloud Logging always sets both), so it is quarantined as
 *       {@code MISSING_REQUIRED_FIELD}, never stamped with DLAP's time;</li>
 *   <li>native key {@code logName|time|insertId} (log-event spec section 5.2);</li>
 *   <li>operation and the mapping's required fields; from here a quarantine carries a
 *       pass-through event;</li>
 *   <li>the version's events (Cloud Storage and other services, BigQuery v2, BigQuery v1), the
 *       result, the actor and the targets; a data event the family classified (a table row or
 *       BigQuery code, not the verb heuristics) whose target does not normalize to a canonical
 *       asset id is {@code UNRESOLVABLE_ASSET_ID}.</li>
 * </ol>
 *
 * <p>Result: {@code FAILURE} when any mapping {@code failure-when} condition holds (a non-zero
 * {@code status.code}, an {@code authorizationInfo} with {@code granted: false}, a
 * {@code policyViolationInfo}, VPC Service Controls metadata), when an {@code authorizationInfo}
 * entry has no {@code granted} at all (proto3 JSON leaves a {@code false} bool out, so the API, a
 * Cloud Storage sink and a BigQuery sink all show a denied check that way), for every entry of the
 * Policy Denied log, and for a BigQuery job that ended with an error.
 */
public final class GcpAuditParser implements LogRecordParser {


    /**
     * Whether an {@code authorizationInfo} entry carries a permission check without {@code granted}:
     * proto3 JSON omits a {@code false} bool, so that is a denied check. An entry with neither a
     * permission nor a resource is not a check.
     *
     * @param entry the LogEntry view
     * @return true when a check is denied by omission
     */
    static boolean deniedByDefault(JsonNode entry) {
        JsonNode checks = entry.path("protoPayload").path("authorizationInfo");
        if (!checks.isArray()) {
            return false;
        }
        for (JsonNode c : checks) {
            boolean isCheck = c.hasNonNull("permission") || c.hasNonNull("resource");
            if (isCheck && (!c.has("granted") || c.get("granted").isNull())) {
                return true;
            }
        }
        return false;
    }
    private static final String POLICY_LOG_SUFFIX = "cloudaudit.googleapis.com%2Fpolicy";

    private final MappingSpec spec;
    private final String envelope;
    private final MappingSpec.Envelope mapping;
    private final EnvelopeKey key;

    /**
     * Creates the parser of one envelope.
     *
     * @param spec     the family mapping
     * @param envelope {@code api-proto}, {@code json} or {@code bq-sink-row}
     */
    public GcpAuditParser(MappingSpec spec, String envelope) {
        this.spec = Objects.requireNonNull(spec, "spec");
        if (!GcpAuditFormat.ENVELOPES.contains(envelope)) {
            throw new IllegalArgumentException("not a " + GcpAuditFormat.FAMILY + " envelope: " + envelope);
        }
        this.envelope = envelope;
        // A sink row is parsed through its LogEntry view, with the json envelope's mapping.
        String fieldsFrom = GcpAuditFormat.BQ_SINK_ROW.equals(envelope) ? GcpAuditFormat.JSON : envelope;
        this.mapping = spec.envelope(fieldsFrom).orElseThrow(() ->
                new IllegalArgumentException("mapping " + spec.family() + " has no envelope " + fieldsFrom));
        this.key = GcpAuditFormat.key(envelope);
    }

    @Override
    public EnvelopeKey envelopeKey() {
        return key;
    }

    @Override
    public VersionRange supports() {
        return GcpAuditFormat.supported();
    }

    @Override
    public SourceProvider sourceProvider() {
        return spec.sourceProvider();
    }

    @Override
    public String parserId() {
        return GcpAuditFormat.PARSER_ID_PREFIX + envelope;
    }

    @Override
    public String parserSemver() {
        return GcpAuditFormat.PARSER_SEMVER;
    }

    @Override
    public Set<FieldPath> declaredPaths(String effectiveVersion) {
        if (GcpAuditFormat.BQ_SINK_ROW.equals(envelope)) {
            return BqSinkRows.declaredPaths();
        }
        return mapping.effective(GcpAuditFormat.schemaOf(effectiveVersion))
                .map(MappingSpec.Effective::declaredPaths).orElse(Set.of());
    }

    @Override
    public ParseResult parse(RawRecord record, FormatKey effective, BindingConfig binding) {
        JsonNode body = record.body();
        if (body == null || !body.isObject()) {
            return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                    "the record is not a JSON object");
        }
        JsonNode raw = null;
        if (GcpAuditFormat.BQ_SINK_ROW.equals(envelope)) {
            BqSinkRows.View view = BqSinkRows.toEntry(body);
            if (!view.ok()) {
                return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                        view.problem());
            }
            raw = view.entry();
        }
        final JsonNode entry = raw != null ? raw : body;
        String schema = GcpAuditFormat.schemaOf(effective.schemaVersion());
        Optional<MappingSpec.Effective> found = mapping.effective(schema);
        if (found.isEmpty()) {
            return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_UNKNOWN_MAJOR_VERSION,
                    "the mapping lists no entry for the effective version");
        }
        MappingSpec.Effective m = found.get();
        JsonNode payload = entry.path("protoPayload");
        if (!payload.isObject()) {
            return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD,
                    "protoPayload is missing or not an object");
        }
        Optional<String> payloadType = GcpJson.text(payload, "@type");
        if (payloadType.isPresent() && !GcpAuditFormat.AUDIT_LOG_TYPE.equals(payloadType.get())) {
            return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_UNKNOWN_ENVELOPE,
                    "protoPayload is not a google.cloud.audit.AuditLog");
        }
        Set<ParseWarning> warnings = EnumSet.noneOf(ParseWarning.class);
        Optional<Long> splitIndex = GcpJson.integer(entry, "split", "index");
        if (splitIndex.isPresent() && splitIndex.get() > 0) {
            return new ParseResult.Skipped(SkipReason.NOT_AN_EVENT,
                    "part after the first of a split entry; the first part carries the event");
        }
        if (GcpJson.object(entry, "split").isPresent()) {
            warnings.add(ParseWarning.PARSE_WARNING_PROVIDER_TRUNCATED);
        }

        MappingDrivenParser.Fields f = new MappingDrivenParser.Fields(m, binding, entry);
        Instant eventTime;
        Optional<JsonNode> timeNode = f.node(CanonicalField.EVENT_TIME);
        if (timeNode.isPresent()) {
            Optional<Instant> t = MappingDrivenParser.time(timeNode.get());
            if (t.isEmpty()) {
                return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                        "timestamp is not an RFC 3339 time with a zone in the supported range");
            }
            eventTime = t.get();
        } else {
            Optional<JsonNode> fallback = m.timeFallback().stream().map(p -> p.first(entry))
                    .flatMap(Optional::stream).findFirst();
            if (fallback.isEmpty()) {
                return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD,
                        "neither timestamp nor receiveTimestamp is set; a stored LogEntry always has both");
            }
            Optional<Instant> t = MappingDrivenParser.time(fallback.get());
            if (t.isEmpty()) {
                return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                        "receiveTimestamp is not an RFC 3339 time with a zone in the supported range");
            }
            eventTime = t.get();
            warnings.add(ParseWarning.PARSE_WARNING_EVENT_TIME_FALLBACK);
        }

        Optional<String> logName = GcpJson.text(entry, "logName");
        Optional<String> insertId = GcpJson.text(entry, "insertId");
        if (logName.isEmpty() || insertId.isEmpty()) {
            return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD,
                    (logName.isEmpty() ? "logName" : "insertId") + " is missing");
        }
        String nativeKey;
        try {
            nativeKey = LogEventIds.gcpAuditNativeKey(logName.get(), eventTime, insertId.get());
        } catch (IllegalArgumentException e) {
            return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                    "logName is not a full log resource name");
        }
        if (nativeKey.length() > CanonicalEvent.MAX_NATIVE_KEY_LENGTH) {
            return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                    "the native key is longer than " + CanonicalEvent.MAX_NATIVE_KEY_LENGTH + " characters");
        }

        Optional<String> operation = f.text(CanonicalField.OPERATION);
        if (operation.isEmpty()) {
            return ParseResult.Quarantined.of(f.typeMismatch(CanonicalField.OPERATION)
                            ? QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH
                            : QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD,
                    "protoPayload.methodName is " + (f.typeMismatch(CanonicalField.OPERATION) ? "not text" : "missing"));
        }
        String service = f.text(CanonicalField.SERVICE).orElse("");
        Optional<Instant> observed = f.node(CanonicalField.OBSERVED_TIME).flatMap(MappingDrivenParser::time);
        CanonicalEvent passThrough = CanonicalEvent.builder()
                .nativeKey(nativeKey)
                .provider(spec.sourceProvider())
                .service(service)
                .operation(operation.get())
                .eventTime(eventTime)
                .observedTime(observed.orElse(null))
                .raw(raw)
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

        ResultStatus result = Condition.anyMatch(m.failureWhen(), entry) || deniedByDefault(entry)
                || logName.get().endsWith(POLICY_LOG_SUFFIX) ? ResultStatus.FAILURE : ResultStatus.SUCCESS;
        Record r = new Record(record, entry, raw, m, binding, f, nativeKey, eventTime, observed.orElse(null),
                service, operation.get(), result, GcpPrincipals.of(f.text(CanonicalField.PRINCIPAL_ID).orElse(""), logName.get()),
                new CanonicalEvent.Request(
                        f.text(CanonicalField.CALLER_IP).orElse(""),
                        f.text(CanonicalField.USER_AGENT).orElse(""),
                        f.text(CanonicalField.REQUEST_ID).orElse(""),
                        f.text(CanonicalField.TRACE_ID).orElse("")),
                binding.assetIdHints(), passThrough);

        Outcome built = switch (schema) {
            case GcpAuditFormat.BQ_AUDIT_METADATA -> BigQueryV2.events(r);
            case GcpAuditFormat.BQ_AUDIT_DATA_LEGACY -> BigQueryV1.events(r);
            default -> generic(r);
        };
        if (built.quarantine() != null) {
            return built.quarantine();
        }
        warnings.addAll(built.warnings());
        List<CanonicalEvent> events = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < built.drafts().size(); i++) {
            Draft d = built.drafts().get(i);
            // A data event the family classified (not the verb heuristics) must name a canonical asset.
            if (PersistRaw.isDataCategory(d.classification().category()) && !d.classification().heuristic()
                    && d.target().canonicalUri().isEmpty()) {
                return new ParseResult.Quarantined(QuarantineReason.QUARANTINE_REASON_UNRESOLVABLE_ASSET_ID,
                        "the target of a data event does not normalize to a canonical asset id",
                        Optional.of(passThrough));
            }
            String fanTarget = !d.target().canonicalUri().isEmpty() ? d.target().canonicalUri()
                    : !d.target().id().isEmpty() ? d.target().id() : "event-" + i;
            String eventKey = i == 0 ? nativeKey : LogEventIds.fanOutNativeKey(nativeKey, fanTarget);
            if (keys.contains(eventKey)) {
                // One job that reads and writes the same table: the write keeps its own key.
                eventKey = eventKey + LogEventIds.FAN_OUT_SEPARATOR + d.classification().category().name();
            }
            if (!keys.add(eventKey) || eventKey.length() > CanonicalEvent.MAX_NATIVE_KEY_LENGTH) {
                continue;
            }
            CanonicalEvent.Builder b = CanonicalEvent.builder()
                    .nativeKey(eventKey)
                    .provider(spec.sourceProvider())
                    .service(service)
                    .operation(operation.get())
                    .classification(d.classification())
                    .result(d.result() == null ? result : d.result())
                    .eventTime(eventTime)
                    .observedTime(observed.orElse(null))
                    .principal(r.principal())
                    .target(d.target())
                    .request(r.request())
                    .raw(raw)
                    .sharedEventId(d.sharedEventId());
            d.related().forEach(b::addRelated);
            if (d.classification().heuristic()) {
                b.warning(ParseWarning.PARSE_WARNING_HEURISTIC_CLASSIFICATION);
            }
            events.add(b.build());
        }
        return new ParseResult.Parsed(events, warnings);
    }

    /** Cloud Storage and every service without a BigQuery payload: one event per entry. */
    private static Outcome generic(Record r) {
        Classification c = r.classify();
        String resourceName = r.text(CanonicalField.TARGET_ID);
        String project = r.text(CanonicalField.TARGET_PROJECT);
        String region = r.text(CanonicalField.TARGET_REGION);
        List<CanonicalEvent.Resource> related = new ArrayList<>();
        CanonicalEvent.Resource target;
        if (GcpAuditFormat.STORAGE.equals(r.service())) {
            target = GcpResources.gcs(resourceName, project, region, r.hints());
            if ("storage.objects.create".equals(r.operation())) {
                target = withCreatedAndSize(target, r.entry().path("protoPayload").path("response"));
            }
            GcpResources.gcsParent(resourceName, project, r.hints()).ifPresent(related::add);
        } else if (GcpAuditFormat.BIGQUERY.equals(r.service())) {
            target = GcpResources.bigQuery(resourceName, region, r.hints());
        } else {
            target = CanonicalEvent.Resource.builder()
                    .kind(r.text(CanonicalField.TARGET_KIND)).id(resourceName).name(resourceName)
                    .project(project).region(region).resourcePath(resourceName)
                    .canonicalFrom(resourceName, r.hints())
                    .build();
        }
        return Outcome.of(new Draft(c, target, related, "", null));
    }

    private static CanonicalEvent.Resource withCreatedAndSize(CanonicalEvent.Resource t, JsonNode response) {
        Instant created = MappingDrivenParser.time(response.path("timeCreated")).orElse(null);
        Long size = GcpJson.integer(response, "size").filter(v -> v >= 0).orElse(null);
        if (created == null && size == null) {
            return t;
        }
        return new CanonicalEvent.Resource(t.kind(), t.id(), t.name(), t.project(), t.region(), t.resourcePath(),
                t.labels(), t.canonicalUri(), created, size, t.technicalMetadata(), t.relation());
    }

    /**
     * Everything the version-specific code needs of one record.
     *
     * @param record      the raw record
     * @param entry       the LogEntry (the view of a sink row)
     * @param raw         the raw event to publish, or {@code null} for the record body
     * @param mapping     the effective mapping
     * @param binding     the binding
     * @param fields      field lookups with binding overrides
     * @param nativeKey   the record's native key
     * @param eventTime   event time
     * @param observed    provider receive time, or {@code null}
     * @param service     the service
     * @param operation   the method
     * @param result      the record's result before job outcomes
     * @param principal   the actor
     * @param request     request metadata
     * @param hints       the binding's asset-id hints
     * @param passThrough the pass-through event for a quarantine
     */
    record Record(
            RawRecord record,
            JsonNode entry,
            JsonNode raw,
            MappingSpec.Effective mapping,
            BindingConfig binding,
            MappingDrivenParser.Fields fields,
            String nativeKey,
            Instant eventTime,
            Instant observed,
            String service,
            String operation,
            ResultStatus result,
            CanonicalEvent.Actor principal,
            CanonicalEvent.Request request,
            AssetIdHints hints,
            CanonicalEvent passThrough) {

        /**
         * A field's text, or {@code ""}.
         *
         * @param field the field
         * @return the text
         */
        String text(CanonicalField field) {
            return fields.text(field).orElse("");
        }

        /**
         * The classification the binding overrides and the mapping table give, else the verb
         * heuristics.
         *
         * @return the classification
         */
        Classification classify() {
            return Classification.classify(binding.operationOverrides(), mapping.rules(), service, operation);
        }

        /**
         * The binding's override for this operation, if one matches.
         *
         * @return the override's classification
         */
        Optional<Classification> override() {
            Classification c = Classification.classify(binding.operationOverrides(), List.of(), service, operation);
            return c.heuristic() ? Optional.empty() : Optional.of(c);
        }

        /**
         * The result of a read or write event derived from a job: {@code FAILURE} when the entry
         * itself failed (a non-zero {@code status.code}, a denied authorization check, the Policy
         * Denied log or VPC Service Controls metadata), else {@code SUCCESS}. A job's own error is
         * decided by the caller and never reaches this method, because a failed job has no reads.
         *
         * @return the result of a derived data event
         */
        ResultStatus derivedResult() {
            return result == ResultStatus.FAILURE ? ResultStatus.FAILURE : ResultStatus.SUCCESS;
        }

        /**
         * A quarantine with this record's pass-through event.
         *
         * @param reason the reason
         * @param detail the detail
         * @return the outcome
         */
        Outcome quarantine(QuarantineReason reason, String detail) {
            return new Outcome(List.of(), Set.of(), new ParseResult.Quarantined(reason, detail, Optional.of(passThrough)));
        }
    }

    /**
     * One event to build.
     *
     * @param classification change type and category
     * @param target         the target
     * @param related        related resources, each with a relation
     * @param sharedEventId  the id shared by the entries of one job, or {@code ""}
     * @param result         the event's result, or {@code null} for the record's
     */
    record Draft(
            Classification classification,
            CanonicalEvent.Resource target,
            List<CanonicalEvent.Resource> related,
            String sharedEventId,
            ResultStatus result) {

        /**
         * A classification from explicit values.
         *
         * @param changeType the change type
         * @param category   the category
         * @return the classification
         */
        static Classification of(ChangeType changeType, EventCategory category) {
            return new Classification(changeType, category, Optional.empty(), Optional.empty(), false);
        }
    }

    /**
     * The events of one record, or its quarantine.
     *
     * @param drafts     the events; the first is the primary event
     * @param warnings   record-level warnings
     * @param quarantine the quarantine, or {@code null}
     */
    record Outcome(List<Draft> drafts, Set<ParseWarning> warnings, ParseResult.Quarantined quarantine) {

        /**
         * Events without record-level warnings.
         *
         * @param drafts the events
         * @return the outcome
         */
        static Outcome of(Draft... drafts) {
            return new Outcome(List.of(drafts), Set.of(), null);
        }
    }

    /**
     * The JSON shape of a {@code serviceData} or {@code metadata} object; used by the version code.
     *
     * @param node a node
     * @return the node as an object, or empty
     */
    static Optional<ObjectNode> asObject(JsonNode node) {
        return node instanceof ObjectNode o ? Optional.of(o) : Optional.empty();
    }
}
