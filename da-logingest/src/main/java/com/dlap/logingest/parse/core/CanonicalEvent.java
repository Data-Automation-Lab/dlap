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

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.logevent.LogEventIds;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.ResourceRelation;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a parser produces from a record: one provider fact in DLAP's vocabulary, before it is
 * stamped with registry context and written to the wire by {@link CanonicalEventMapper}.
 *
 * <p>The record enforces the producer rules of the log-event spec (sections 3, 5 and 8) at
 * construction, so a parser cannot emit an event that breaks them:
 * <ul>
 *   <li>a native key and a known provider, which fix the {@code event_id}
 *       ({@link #eventId()}, ADR-0045 decision 4);</li>
 *   <li>an event time from the provider clock; there is no way to build an event without one;</li>
 *   <li>a known change type, category and result, unless the event is a pass-through of a
 *       quarantined record ({@link #passThrough()}), which never changes state;</li>
 *   <li>a {@code canonical_uri} that is empty or canonical under {@code AssetIdNormalizer}
 *       (ADR-0047, plan rule R8);</li>
 *   <li>a relation on every related resource.</li>
 * </ul>
 * Missing strings are empty, never invented.
 *
 * @param nativeKey           the native idempotency key before hashing (spec section 5.2)
 * @param provider            the source provider used in the event id
 * @param service             provider service, for example {@code s3.amazonaws.com}
 * @param operation           provider operation, exactly as spelled by the provider
 * @param changeType          routing change type; unspecified only for a pass-through
 * @param category            event category; unspecified only for a pass-through
 * @param result              result status; unspecified only for a pass-through
 * @param eventTime           provider event time
 * @param observedTime        provider receipt or delivery time, or {@code null}
 * @param principal           who acted
 * @param target              what the operation acted on
 * @param related             further resources, each with a relation
 * @param request             request metadata
 * @param raw                 the provider record per the rawEvent conventions (spec section 6),
 *                            or {@code null} to use the record body
 * @param sharedEventId       CloudTrail {@code sharedEventID}, or empty
 * @param supersedesNativeKey native key of the event this one replaces, or empty
 * @param warnings            event-level parse warnings
 * @param passThrough         true for the {@code PARSE_STATUS_PARTIAL} pass-through of a quarantined record
 */
public record CanonicalEvent(
        String nativeKey,
        SourceProvider provider,
        String service,
        String operation,
        ChangeType changeType,
        EventCategory category,
        ResultStatus result,
        Instant eventTime,
        Instant observedTime,
        Actor principal,
        Resource target,
        List<Resource> related,
        Request request,
        JsonNode raw,
        String sharedEventId,
        String supersedesNativeKey,
        Set<ParseWarning> warnings,
        boolean passThrough) {

    /** Longest accepted native key, so a hostile record cannot grow hash inputs and rows without bound. */
    public static final int MAX_NATIVE_KEY_LENGTH = 4096;

    /**
     * Validates the event.
     *
     * @param nativeKey           see the record description
     * @param provider            see the record description
     * @param service             see the record description
     * @param operation           see the record description
     * @param changeType          see the record description
     * @param category            see the record description
     * @param result              see the record description
     * @param eventTime           see the record description
     * @param observedTime        see the record description
     * @param principal           see the record description
     * @param target              see the record description
     * @param related             see the record description
     * @param request             see the record description
     * @param raw                 see the record description
     * @param sharedEventId       see the record description
     * @param supersedesNativeKey see the record description
     * @param warnings            see the record description
     * @param passThrough         see the record description
     */
    public CanonicalEvent {
        if (nativeKey == null || nativeKey.isBlank()) {
            throw new IllegalArgumentException("an event needs a native key");
        }
        if (nativeKey.length() > MAX_NATIVE_KEY_LENGTH) {
            throw new IllegalArgumentException("native key longer than " + MAX_NATIVE_KEY_LENGTH + " characters");
        }
        if (provider == null || provider == SourceProvider.SOURCE_PROVIDER_UNSPECIFIED
                || provider == SourceProvider.UNRECOGNIZED) {
            throw new IllegalArgumentException("an event needs a known source provider");
        }
        Objects.requireNonNull(eventTime, "eventTime: DLAP never stamps business time; skip the record instead");
        requireWireRange(eventTime, "eventTime");
        if (observedTime != null) {
            requireWireRange(observedTime, "observedTime");
        }
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("an event needs the provider operation");
        }
        changeType = changeType == null ? ChangeType.CHANGE_TYPE_UNSPECIFIED : changeType;
        category = category == null ? EventCategory.EVENT_CATEGORY_UNSPECIFIED : category;
        result = result == null ? ResultStatus.RESULT_STATUS_UNSPECIFIED : result;
        if (changeType == ChangeType.UNRECOGNIZED || category == EventCategory.UNRECOGNIZED
                || result == ResultStatus.UNRECOGNIZED) {
            throw new IllegalArgumentException("UNRECOGNIZED is a decoding artifact, not a value");
        }
        if (passThrough) {
            if (changeType != ChangeType.CHANGE_TYPE_UNSPECIFIED) {
                throw new IllegalArgumentException("a pass-through event never routes: change type must be unspecified");
            }
        } else {
            if (changeType == ChangeType.CHANGE_TYPE_UNSPECIFIED) {
                throw new IllegalArgumentException("a parsed event needs a change type");
            }
            if (category == EventCategory.EVENT_CATEGORY_UNSPECIFIED) {
                throw new IllegalArgumentException("a parsed event needs an event category");
            }
            if (result == ResultStatus.RESULT_STATUS_UNSPECIFIED) {
                throw new IllegalArgumentException("a parsed event needs a result status");
            }
        }
        service = text(service);
        principal = principal == null ? Actor.UNKNOWN : principal;
        target = target == null ? Resource.NONE : target;
        if (target.relation() != ResourceRelation.RESOURCE_RELATION_UNSPECIFIED) {
            throw new IllegalArgumentException("the target carries no relation; relations are for related resources");
        }
        List<Resource> rel = related == null ? List.of() : List.copyOf(related);
        for (Resource r : rel) {
            if (r.relation() == ResourceRelation.RESOURCE_RELATION_UNSPECIFIED
                    || r.relation() == ResourceRelation.UNRECOGNIZED) {
                throw new IllegalArgumentException("every related resource needs a relation");
            }
        }
        related = rel;
        request = request == null ? Request.NONE : request;
        raw = raw == null ? null : raw.deepCopy();
        sharedEventId = text(sharedEventId);
        supersedesNativeKey = text(supersedesNativeKey);
        Set<ParseWarning> w = EnumSet.noneOf(ParseWarning.class);
        if (warnings != null) {
            for (ParseWarning pw : warnings) {
                if (pw == null || pw == ParseWarning.PARSE_WARNING_UNSPECIFIED || pw == ParseWarning.UNRECOGNIZED) {
                    throw new IllegalArgumentException("parse warnings must be known values");
                }
                w.add(pw);
            }
        }
        warnings = Collections.unmodifiableSet(w);
    }

    /**
     * Starts an event.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The idempotency id: {@code LogEventIds.eventId(provider, nativeKey)}.
     *
     * @return lowercase hex SHA-256
     */
    public String eventId() {
        return LogEventIds.eventId(provider, nativeKey);
    }

    /**
     * The id of the event this one replaces.
     *
     * @return the superseded event id, or empty
     */
    public Optional<String> supersedesEventId() {
        return supersedesNativeKey.isEmpty()
                ? Optional.empty()
                : Optional.of(LogEventIds.eventId(provider, supersedesNativeKey));
    }

    /**
     * A copy with more event-level warnings.
     *
     * @param more the warnings to add
     * @return a new event
     */
    public CanonicalEvent withWarnings(Set<ParseWarning> more) {
        if (more == null || more.isEmpty()) {
            return this;
        }
        Set<ParseWarning> all = EnumSet.noneOf(ParseWarning.class);
        all.addAll(warnings);
        all.addAll(more);
        return toBuilder().warnings(all).build();
    }

    /**
     * A builder holding this event's values.
     *
     * @return a builder
     */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.nativeKey = nativeKey;
        b.provider = provider;
        b.service = service;
        b.operation = operation;
        b.changeType = changeType;
        b.category = category;
        b.result = result;
        b.eventTime = eventTime;
        b.observedTime = observedTime;
        b.principal = principal;
        b.target = target;
        b.related = new ArrayList<>(related);
        b.request = request;
        b.raw = raw;
        b.sharedEventId = sharedEventId;
        b.supersedesNativeKey = supersedesNativeKey;
        b.warnings = EnumSet.noneOf(ParseWarning.class);
        b.warnings.addAll(warnings);
        b.passThrough = passThrough;
        return b;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    /**
     * Checks that an instant fits a protobuf {@code Timestamp}: years 1 to 9999 UTC.
     *
     * @param instant the instant
     * @param name    the field, for the message
     * @throws IllegalArgumentException outside the range
     */
    public static void requireWireRange(Instant instant, String name) {
        if (instant.isBefore(MIN_WIRE_TIME) || instant.isAfter(MAX_WIRE_TIME)) {
            throw new IllegalArgumentException(name + " is outside the protobuf Timestamp range (years 1 to 9999)");
        }
    }

    /** Earliest instant a protobuf {@code Timestamp} holds: 0001-01-01T00:00:00Z. */
    public static final Instant MIN_WIRE_TIME = Instant.parse("0001-01-01T00:00:00Z");

    /** Latest instant a protobuf {@code Timestamp} holds: 9999-12-31T23:59:59.999999999Z. */
    public static final Instant MAX_WIRE_TIME = Instant.parse("9999-12-31T23:59:59.999999999Z");

    /**
     * Who acted.
     *
     * @param type        principal type; unspecified when unknown
     * @param id          the stable principal id
     * @param displayName display name
     * @param accountId   the principal's account, project or tenant
     */
    public record Actor(PrincipalType type, String id, String displayName, String accountId) {

        /** No principal known. */
        public static final Actor UNKNOWN = new Actor(PrincipalType.PRINCIPAL_TYPE_UNSPECIFIED, "", "", "");

        /**
         * Normalizes absent values.
         *
         * @param type        see the record description
         * @param id          see the record description
         * @param displayName see the record description
         * @param accountId   see the record description
         */
        public Actor {
            type = type == null || type == PrincipalType.UNRECOGNIZED ? PrincipalType.PRINCIPAL_TYPE_UNSPECIFIED : type;
            id = text(id);
            displayName = text(displayName);
            accountId = text(accountId);
        }
    }

    /**
     * What request carried the operation.
     *
     * @param callerIp  caller IP address
     * @param userAgent user agent
     * @param requestId the provider's own request id
     * @param traceId   trace id
     */
    public record Request(String callerIp, String userAgent, String requestId, String traceId) {

        /** No request metadata. */
        public static final Request NONE = new Request("", "", "", "");

        /**
         * Normalizes absent values.
         *
         * @param callerIp  see the record description
         * @param userAgent see the record description
         * @param requestId see the record description
         * @param traceId   see the record description
         */
        public Request {
            callerIp = text(callerIp);
            userAgent = text(userAgent);
            requestId = text(requestId);
            traceId = text(traceId);
        }
    }

    /**
     * A resource: the target of an event or one of its related resources. Mirrors
     * {@code dlap.log.v1.TargetResource}.
     *
     * @param kind              provider resource kind
     * @param id                provider-native id, as the source names it
     * @param name              name
     * @param project           project or account
     * @param region            region, never re-based across providers
     * @param resourcePath      provider resource path
     * @param labels            resource tags
     * @param canonicalUri      canonical asset id, or empty when unresolvable
     * @param createdTime       provider creation time, or {@code null}
     * @param sizeBytes         size in bytes, or {@code null} when unknown; 0 is a real size
     * @param technicalMetadata observed technical metadata
     * @param relation          unspecified on a target, set on a related resource
     */
    public record Resource(
            String kind,
            String id,
            String name,
            String project,
            String region,
            String resourcePath,
            Map<String, String> labels,
            String canonicalUri,
            Instant createdTime,
            Long sizeBytes,
            Map<String, String> technicalMetadata,
            ResourceRelation relation) {

        /** No resource. */
        public static final Resource NONE = new Resource("", "", "", "", "", "", Map.of(), "", null, null, Map.of(),
                ResourceRelation.RESOURCE_RELATION_UNSPECIFIED);

        /**
         * Validates the resource.
         *
         * @param kind              see the record description
         * @param id                see the record description
         * @param name              see the record description
         * @param project           see the record description
         * @param region            see the record description
         * @param resourcePath      see the record description
         * @param labels            see the record description
         * @param canonicalUri      see the record description
         * @param createdTime       see the record description
         * @param sizeBytes         see the record description
         * @param technicalMetadata see the record description
         * @param relation          see the record description
         */
        public Resource {
            kind = text(kind);
            id = text(id);
            name = text(name);
            project = text(project);
            region = text(region);
            resourcePath = text(resourcePath);
            labels = labels == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(labels));
            canonicalUri = text(canonicalUri);
            if (!canonicalUri.isEmpty() && !AssetIdNormalizer.isCanonical(canonicalUri)) {
                throw new IllegalArgumentException(
                        "canonicalUri must come from AssetIdNormalizer (ADR-0047); set it with canonicalFrom");
            }
            if (createdTime != null) {
                requireWireRange(createdTime, "createdTime");
            }
            if (sizeBytes != null && sizeBytes < 0) {
                throw new IllegalArgumentException("sizeBytes must not be negative");
            }
            technicalMetadata = technicalMetadata == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(technicalMetadata));
            relation = relation == null ? ResourceRelation.RESOURCE_RELATION_UNSPECIFIED : relation;
        }

        /**
         * Starts a resource.
         *
         * @return a builder
         */
        public static ResourceBuilder builder() {
            return new ResourceBuilder();
        }

        /**
         * Whether the resource names anything.
         *
         * @return false for {@link #NONE}-like resources
         */
        public boolean isEmpty() {
            return id.isEmpty() && canonicalUri.isEmpty() && name.isEmpty();
        }
    }

    /** Builds a {@link Resource}. */
    public static final class ResourceBuilder {
        private String kind;
        private String id;
        private String name;
        private String project;
        private String region;
        private String resourcePath;
        private Map<String, String> labels;
        private String canonicalUri;
        private Instant createdTime;
        private Long sizeBytes;
        private Map<String, String> technicalMetadata;
        private ResourceRelation relation;

        private ResourceBuilder() {
        }

        /**
         * Sets the provider kind.
         *
         * @param value the kind
         * @return this builder
         */
        public ResourceBuilder kind(String value) {
            this.kind = value;
            return this;
        }

        /**
         * Sets the provider-native id.
         *
         * @param value the id
         * @return this builder
         */
        public ResourceBuilder id(String value) {
            this.id = value;
            return this;
        }

        /**
         * Sets the name.
         *
         * @param value the name
         * @return this builder
         */
        public ResourceBuilder name(String value) {
            this.name = value;
            return this;
        }

        /**
         * Sets the project or account.
         *
         * @param value the project
         * @return this builder
         */
        public ResourceBuilder project(String value) {
            this.project = value;
            return this;
        }

        /**
         * Sets the region.
         *
         * @param value the region
         * @return this builder
         */
        public ResourceBuilder region(String value) {
            this.region = value;
            return this;
        }

        /**
         * Sets the provider resource path.
         *
         * @param value the path
         * @return this builder
         */
        public ResourceBuilder resourcePath(String value) {
            this.resourcePath = value;
            return this;
        }

        /**
         * Sets the tags.
         *
         * @param value the tags
         * @return this builder
         */
        public ResourceBuilder labels(Map<String, String> value) {
            this.labels = value;
            return this;
        }

        /**
         * Sets an already canonical URI.
         *
         * @param value a URI {@code AssetIdNormalizer} returned
         * @return this builder
         */
        public ResourceBuilder canonicalUri(String value) {
            this.canonicalUri = value;
            return this;
        }

        /**
         * Normalizes a raw identifier through {@code AssetIdNormalizer} and sets the result; an
         * unresolvable identifier leaves the URI empty (plan rule R8).
         *
         * @param raw   the identifier in any form the normalizer accepts
         * @param hints the binding's hints, refined by the classification's asset kind
         * @return this builder
         */
        public ResourceBuilder canonicalFrom(String raw, AssetIdHints hints) {
            this.canonicalUri = AssetIdNormalizer.canonicalUri(raw, hints).orElse("");
            return this;
        }

        /**
         * Sets the provider creation time.
         *
         * @param value the time
         * @return this builder
         */
        public ResourceBuilder createdTime(Instant value) {
            this.createdTime = value;
            return this;
        }

        /**
         * Sets the observed size.
         *
         * @param value bytes, or {@code null} when unknown
         * @return this builder
         */
        public ResourceBuilder sizeBytes(Long value) {
            this.sizeBytes = value;
            return this;
        }

        /**
         * Sets observed technical metadata.
         *
         * @param value the metadata
         * @return this builder
         */
        public ResourceBuilder technicalMetadata(Map<String, String> value) {
            this.technicalMetadata = value;
            return this;
        }

        /**
         * Sets the relation, for a related resource.
         *
         * @param value the relation
         * @return this builder
         */
        public ResourceBuilder relation(ResourceRelation value) {
            this.relation = value;
            return this;
        }

        /**
         * Builds the resource.
         *
         * @return the resource
         */
        public Resource build() {
            return new Resource(kind, id, name, project, region, resourcePath, labels, canonicalUri, createdTime,
                    sizeBytes, technicalMetadata, relation);
        }
    }

    /** Builds a {@link CanonicalEvent}. */
    public static final class Builder {
        private String nativeKey;
        private SourceProvider provider;
        private String service;
        private String operation;
        private ChangeType changeType;
        private EventCategory category;
        private ResultStatus result;
        private Instant eventTime;
        private Instant observedTime;
        private Actor principal;
        private Resource target;
        private List<Resource> related = new ArrayList<>();
        private Request request;
        private JsonNode raw;
        private String sharedEventId;
        private String supersedesNativeKey;
        private Set<ParseWarning> warnings = EnumSet.noneOf(ParseWarning.class);
        private boolean passThrough;

        private Builder() {
        }

        /**
         * Sets the native key.
         *
         * @param value the key before hashing
         * @return this builder
         */
        public Builder nativeKey(String value) {
            this.nativeKey = value;
            return this;
        }

        /**
         * Sets the provider.
         *
         * @param value the provider
         * @return this builder
         */
        public Builder provider(SourceProvider value) {
            this.provider = value;
            return this;
        }

        /**
         * Sets the service.
         *
         * @param value the service
         * @return this builder
         */
        public Builder service(String value) {
            this.service = value;
            return this;
        }

        /**
         * Sets the operation.
         *
         * @param value the operation, exactly as the provider spells it
         * @return this builder
         */
        public Builder operation(String value) {
            this.operation = value;
            return this;
        }

        /**
         * Sets change type and category from a classification and adds the heuristic warning when
         * it applies.
         *
         * @param value the classification
         * @return this builder
         */
        public Builder classification(Classification value) {
            this.changeType = value.changeType();
            this.category = value.category();
            if (value.heuristic()) {
                this.warnings.add(ParseWarning.PARSE_WARNING_HEURISTIC_CLASSIFICATION);
            }
            return this;
        }

        /**
         * Sets the change type.
         *
         * @param value the change type
         * @return this builder
         */
        public Builder changeType(ChangeType value) {
            this.changeType = value;
            return this;
        }

        /**
         * Sets the category.
         *
         * @param value the category
         * @return this builder
         */
        public Builder category(EventCategory value) {
            this.category = value;
            return this;
        }

        /**
         * Sets the result.
         *
         * @param value the result
         * @return this builder
         */
        public Builder result(ResultStatus value) {
            this.result = value;
            return this;
        }

        /**
         * Sets the provider event time.
         *
         * @param value the time
         * @return this builder
         */
        public Builder eventTime(Instant value) {
            this.eventTime = value;
            return this;
        }

        /**
         * Sets the provider receipt or delivery time.
         *
         * @param value the time, or {@code null}
         * @return this builder
         */
        public Builder observedTime(Instant value) {
            this.observedTime = value;
            return this;
        }

        /**
         * Sets the principal.
         *
         * @param value the principal
         * @return this builder
         */
        public Builder principal(Actor value) {
            this.principal = value;
            return this;
        }

        /**
         * Sets the target.
         *
         * @param value the target
         * @return this builder
         */
        public Builder target(Resource value) {
            this.target = value;
            return this;
        }

        /**
         * Adds a related resource.
         *
         * @param value a resource with a relation
         * @return this builder
         */
        public Builder addRelated(Resource value) {
            this.related.add(value);
            return this;
        }

        /**
         * Sets the request metadata.
         *
         * @param value the metadata
         * @return this builder
         */
        public Builder request(Request value) {
            this.request = value;
            return this;
        }

        /**
         * Sets the provider record for {@code raw_event}.
         *
         * @param value the record, or {@code null} to use the record body
         * @return this builder
         */
        public Builder raw(JsonNode value) {
            this.raw = value;
            return this;
        }

        /**
         * Sets the shared event id.
         *
         * @param value CloudTrail {@code sharedEventID}
         * @return this builder
         */
        public Builder sharedEventId(String value) {
            this.sharedEventId = value;
            return this;
        }

        /**
         * Sets the native key of the event this one replaces.
         *
         * @param value the native key
         * @return this builder
         */
        public Builder supersedesNativeKey(String value) {
            this.supersedesNativeKey = value;
            return this;
        }

        /**
         * Adds a warning.
         *
         * @param value the warning
         * @return this builder
         */
        public Builder warning(ParseWarning value) {
            this.warnings.add(value);
            return this;
        }

        /**
         * Replaces the warnings.
         *
         * @param value the warnings
         * @return this builder
         */
        public Builder warnings(Set<ParseWarning> value) {
            this.warnings = EnumSet.noneOf(ParseWarning.class);
            if (value != null) {
                this.warnings.addAll(value);
            }
            return this;
        }

        /**
         * Marks the event as the pass-through of a quarantined record: change type unspecified,
         * {@code PARSE_STATUS_PARTIAL}, never a state change.
         *
         * @return this builder
         */
        public Builder passThrough() {
            this.passThrough = true;
            this.changeType = ChangeType.CHANGE_TYPE_UNSPECIFIED;
            return this;
        }

        /**
         * Builds the event.
         *
         * @return the event
         * @throws IllegalArgumentException when a producer rule is broken
         */
        public CanonicalEvent build() {
            return new CanonicalEvent(nativeKey, provider, service, operation, changeType, category, result,
                    eventTime, observedTime, principal, target, related, request, raw, sharedEventId,
                    supersedesNativeKey, warnings, passThrough);
        }
    }
}
