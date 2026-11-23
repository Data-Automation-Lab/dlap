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

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetProvider;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalEvent;
import com.dlap.logingest.parse.core.EnvelopeKey;
import com.dlap.logingest.parse.core.FieldPath;
import com.dlap.logingest.parse.core.FormatKey;
import com.dlap.logingest.parse.core.LogRecordParser;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.SkipReason;
import com.dlap.logingest.parse.core.VersionRange;
import com.dlap.logingest.parse.core.mapping.MappingDrivenParser;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.QuarantineReason;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Parses one provider's normalized records ({@code IngestRequestDTO} JSON) into canonical events.
 *
 * <p>Identity: the native key is the caller's {@code requestId}, else
 * {@code operation|eventTime|targetResourceId|principalId} with {@code eventTime} rendered by
 * {@link Instant#toString()} (log-event spec section 5.2, row {@code DLAP_NORMALIZED}). That is
 * byte for byte what {@code LogIngestServiceImpl.computeEventId} hashes, so a record ingested by
 * the legacy pipeline and again by the registry has one {@code event_id} and is stored and
 * published once.
 *
 * <p>Classification: the record carries a change type, not a category, so the category is
 * derived from it as the spec's legacy rule does (section 4.3: {@code ACCESS} is
 * {@code DATA_READ}, {@code PERMISSION} is {@code PERMISSION}, the rest {@code MANAGEMENT}) and
 * the event carries {@code PARSE_WARNING_HEURISTIC_CLASSIFICATION}, so reports can mark it.
 *
 * <p>Outcomes: no {@code eventTime} skips the record ({@code MISSING_EVENT_TIME}; DLAP never
 * stamps business time); an unreadable time, a provider that is not the binding's, or an
 * over-long native key quarantines it as {@code FIELD_TYPE_MISMATCH}; a missing operation is
 * {@code MISSING_REQUIRED_FIELD}. A missing or unknown change type or result status quarantines
 * the record with a {@code PARSE_STATUS_PARTIAL} pass-through event, which keeps the audit fact
 * and never changes state. {@code raw_event} is the caller's {@code rawEvent} object.
 */
public final class DlapNormalizedParser implements LogRecordParser {

    private static final Set<FieldPath> DECLARED = Stream.of(
                    "sourceProvider", "sourceService", "operation", "changeType", "resultStatus", "eventTime",
                    "principalId", "principalType", "principalDisplayName", "principalAccountId",
                    "targetResourceKind", "targetResourceId", "targetResourceName", "targetProject",
                    "targetRegion", "callerIp", "userAgent", "requestId", "traceId", "rawEvent")
            .map(f -> FieldPath.of("/" + f))
            .collect(Collectors.toUnmodifiableSet());

    private static final Pattern AWS_ACCOUNT = Pattern.compile("\\d{12}");
    private static final Pattern GUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * Provider identity types to principal types, keyed without case or separators, the same table
     * the codec reads legacy {@code principal.type} values with (log-event spec section 12).
     */
    private static final Map<String, PrincipalType> PRINCIPAL_TYPES = Map.ofEntries(
            Map.entry("USER", PrincipalType.USER),
            Map.entry("SERVICEACCOUNT", PrincipalType.SERVICE_ACCOUNT),
            Map.entry("GROUP", PrincipalType.GROUP),
            Map.entry("ROLE", PrincipalType.ROLE),
            Map.entry("SYSTEM", PrincipalType.SYSTEM),
            Map.entry("ROOT", PrincipalType.USER),
            Map.entry("IAMUSER", PrincipalType.USER),
            Map.entry("FEDERATEDUSER", PrincipalType.USER),
            Map.entry("IDENTITYCENTERUSER", PrincipalType.USER),
            Map.entry("ASSUMEDROLE", PrincipalType.ROLE),
            Map.entry("AWSSERVICE", PrincipalType.SYSTEM),
            Map.entry("SERVICEPRINCIPAL", PrincipalType.SERVICE_ACCOUNT),
            Map.entry("MANAGEDIDENTITY", PrincipalType.SERVICE_ACCOUNT));

    private final SourceProvider provider;
    private final EnvelopeKey key;

    /**
     * Creates the parser of one provider's source kind.
     *
     * @param provider the provider
     */
    public DlapNormalizedParser(SourceProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.key = DlapNormalizedFormat.envelopeKey(provider);
    }

    @Override
    public EnvelopeKey envelopeKey() {
        return key;
    }

    @Override
    public VersionRange supports() {
        return DlapNormalizedFormat.RANGE;
    }

    @Override
    public SourceProvider sourceProvider() {
        return provider;
    }

    @Override
    public String parserId() {
        return DlapNormalizedFormat.PARSER_ID;
    }

    @Override
    public String parserSemver() {
        return DlapNormalizedFormat.PARSER_SEMVER;
    }

    @Override
    public Set<FieldPath> declaredPaths(String effectiveVersion) {
        return DECLARED;
    }

    @Override
    public ParseResult parse(RawRecord record, FormatKey effective, BindingConfig binding) {
        JsonNode b = record.body();
        if (!b.isObject()) {
            return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                    "a normalized record is a JSON object");
        }
        if (!provider.name().equalsIgnoreCase(text(b, "sourceProvider"))) {
            return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                    "sourceProvider is not the provider of binding " + binding.bindingId());
        }
        JsonNode timeNode = b.get("eventTime");
        if (timeNode == null || timeNode.isNull()) {
            return new ParseResult.Skipped(SkipReason.MISSING_EVENT_TIME, "eventTime is missing");
        }
        Optional<Instant> time = MappingDrivenParser.time(timeNode);
        if (time.isEmpty()) {
            return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                    "eventTime is not RFC 3339 text with a zone");
        }
        String operation = text(b, "operation");
        if (operation.isBlank()) {
            return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD,
                    "operation is missing");
        }
        String nativeKey = nativeKey(b, time.get(), operation);
        if (nativeKey.length() > CanonicalEvent.MAX_NATIVE_KEY_LENGTH) {
            return ParseResult.Quarantined.of(QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                    "requestId is longer than " + CanonicalEvent.MAX_NATIVE_KEY_LENGTH + " characters");
        }

        CanonicalEvent.Builder event = CanonicalEvent.builder()
                .nativeKey(nativeKey)
                .provider(provider)
                .service(text(b, "sourceService"))
                .operation(operation)
                .eventTime(time.get())
                .principal(new CanonicalEvent.Actor(principalType(text(b, "principalType")), text(b, "principalId"),
                        text(b, "principalDisplayName"), text(b, "principalAccountId")))
                .target(target(b))
                .request(new CanonicalEvent.Request(text(b, "callerIp"), text(b, "userAgent"), text(b, "requestId"),
                        text(b, "traceId")))
                .raw(b.path("rawEvent").isObject() ? b.get("rawEvent") : JsonNodeFactory.instance.objectNode());

        String changeTypeText = text(b, "changeType");
        Optional<ChangeType> changeType = changeType(changeTypeText);
        if (changeType.isEmpty()) {
            return quarantined("changeType", changeTypeText, event);
        }
        String resultText = text(b, "resultStatus");
        Optional<ResultStatus> result = result(resultText);
        if (result.isEmpty()) {
            return quarantined("resultStatus", resultText, event);
        }
        return ParseResult.Parsed.of(event
                .changeType(changeType.get())
                .category(category(changeType.get()))
                .result(result.get())
                .warning(ParseWarning.PARSE_WARNING_HEURISTIC_CLASSIFICATION)
                .build());
    }

    /**
     * Quarantines a record whose change type or result is missing or unknown, keeping the audit
     * fact as a pass-through event.
     *
     * @param field the field
     * @param value its text, empty when absent
     * @param event the event built so far
     * @return the result
     */
    private static ParseResult quarantined(String field, String value, CanonicalEvent.Builder event) {
        boolean missing = value.isBlank();
        return new ParseResult.Quarantined(
                missing ? QuarantineReason.QUARANTINE_REASON_MISSING_REQUIRED_FIELD
                        : QuarantineReason.QUARANTINE_REASON_FIELD_TYPE_MISMATCH,
                field + (missing ? " is missing" : " is not a known value"),
                Optional.of(event.passThrough().build()));
    }

    /**
     * The native key of a normalized record (log-event spec section 5.2).
     *
     * @param body      the record
     * @param eventTime its event time
     * @param operation its operation
     * @return the caller's {@code requestId}, else the legacy fallback
     */
    static String nativeKey(JsonNode body, Instant eventTime, String operation) {
        String requestId = text(body, "requestId");
        if (!requestId.isBlank()) {
            return requestId;
        }
        return operation + "|" + eventTime + "|" + text(body, "targetResourceId") + "|" + text(body, "principalId");
    }

    /**
     * The category the spec's legacy rule derives from a change type (section 4.3).
     *
     * @param changeType the change type
     * @return the category
     */
    static EventCategory category(ChangeType changeType) {
        return switch (changeType) {
            case ACCESS -> EventCategory.EVENT_CATEGORY_DATA_READ;
            case PERMISSION -> EventCategory.EVENT_CATEGORY_PERMISSION;
            default -> EventCategory.EVENT_CATEGORY_MANAGEMENT;
        };
    }

    private CanonicalEvent.Resource target(JsonNode b) {
        String id = text(b, "targetResourceId");
        return CanonicalEvent.Resource.builder()
                .kind(text(b, "targetResourceKind"))
                .id(id)
                .name(text(b, "targetResourceName"))
                .project(text(b, "targetProject"))
                .region(text(b, "targetRegion"))
                .canonicalFrom(id, hints(text(b, "targetProject"), text(b, "targetRegion")))
                .build();
    }

    /** Asset-id hints from the record: the provider, and the project or account it names. */
    private AssetIdHints hints(String project, String region) {
        AssetIdHints.Builder h = AssetIdHints.builder().provider(AssetProvider.valueOf(provider.name()));
        switch (provider) {
            case AWS -> {
                if (AWS_ACCOUNT.matcher(project).matches()) {
                    h.account(project);
                }
                h.region(region);
            }
            case GCP -> h.project(project);
            case AZURE -> {
                if (GUID.matcher(project).matches()) {
                    h.subscription(project);
                }
            }
            default -> {
                // No scope hint the normalizer can use without guessing.
            }
        }
        return h.build();
    }

    private static Optional<ChangeType> changeType(String text) {
        if (text.isBlank()) {
            return Optional.empty();
        }
        String upper = text.strip().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "CREATE", "UPDATE", "DELETE", "ACCESS", "PERMISSION", "ARCHIVE" -> Optional.of(ChangeType.valueOf(upper));
            default -> Optional.empty();
        };
    }

    private static Optional<ResultStatus> result(String text) {
        if (text.isBlank()) {
            return Optional.empty();
        }
        String upper = text.strip().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "SUCCESS", "FAILURE", "PENDING" -> Optional.of(ResultStatus.valueOf(upper));
            default -> Optional.empty();
        };
    }

    private static PrincipalType principalType(String raw) {
        if (raw.isBlank()) {
            return PrincipalType.PRINCIPAL_TYPE_UNSPECIFIED;
        }
        String key = raw.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        return PRINCIPAL_TYPES.getOrDefault(key, PrincipalType.PRINCIPAL_TYPE_UNSPECIFIED);
    }

    private static String text(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n == null || n.isNull() || n.isContainerNode() ? "" : n.asText();
    }
}
