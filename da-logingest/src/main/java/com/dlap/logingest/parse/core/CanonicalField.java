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

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The canonical event fields a mapping YAML ({@code fields}) and a binding ({@code field-overrides})
 * may point at. The key is the YAML spelling. This list is part of the frozen contract: a family
 * cannot invent a field, and a binding override naming an unknown field fails validation.
 *
 * <p>Fields without a key here are not mapped by path: the native key has its own
 * {@code native-key} section, change type and category come from {@code classification}, the
 * result from {@code result}, and resources beyond the target from family code.
 */
public enum CanonicalField {

    /** {@code event_time}: the provider clock. RFC 3339 text. */
    EVENT_TIME("event-time", Type.TIME),
    /** {@code observed_time}: provider receipt or delivery. RFC 3339 text. */
    OBSERVED_TIME("observed-time", Type.TIME),
    /** {@code source_service}, also the service key of the classification table. */
    SERVICE("service", Type.TEXT),
    /** {@code operation}, exactly as the provider spells it; the operation key of the classification table. */
    OPERATION("operation", Type.TEXT),
    /** {@code principal.id}: the stable principal. */
    PRINCIPAL_ID("principal.id", Type.TEXT),
    /** {@code principal.type}: the provider's identity type, mapped through {@code principal-types}. */
    PRINCIPAL_TYPE("principal.type", Type.TEXT),
    /** {@code principal.display_name}. */
    PRINCIPAL_DISPLAY_NAME("principal.display-name", Type.TEXT),
    /** {@code principal.account_id}. */
    PRINCIPAL_ACCOUNT_ID("principal.account-id", Type.TEXT),
    /** {@code target_resource.kind}: the provider resource kind. */
    TARGET_KIND("target.kind", Type.TEXT),
    /** {@code target_resource.id}: the provider-native id; normalized into {@code canonical_uri}. */
    TARGET_ID("target.id", Type.TEXT),
    /** {@code target_resource.name}. */
    TARGET_NAME("target.name", Type.TEXT),
    /** {@code target_resource.project}. */
    TARGET_PROJECT("target.project", Type.TEXT),
    /** {@code target_resource.region}. */
    TARGET_REGION("target.region", Type.TEXT),
    /** {@code target_resource.resource_path}. */
    TARGET_RESOURCE_PATH("target.resource-path", Type.TEXT),
    /** {@code request_metadata.caller_ip}. */
    CALLER_IP("request.caller-ip", Type.TEXT),
    /** {@code request_metadata.user_agent}. */
    USER_AGENT("request.user-agent", Type.TEXT),
    /** {@code request_metadata.request_id}: the provider's own request id, not the native key. */
    REQUEST_ID("request.request-id", Type.TEXT),
    /** {@code request_metadata.trace_id}. */
    TRACE_ID("request.trace-id", Type.TEXT),
    /** {@code shared_event_id}: CloudTrail {@code sharedEventID}; drives usage dedup. */
    SHARED_EVENT_ID("shared-event-id", Type.TEXT),
    /** The native key of the event this one replaces; becomes {@code supersedes_event_id}. */
    SUPERSEDES_NATIVE_KEY("supersedes-native-key", Type.TEXT);

    /** What a field's value must be. */
    public enum Type {
        /** A string; numbers and booleans are read as their text. */
        TEXT,
        /** RFC 3339 text with a zone, read as an instant. */
        TIME
    }

    private static final Map<String, CanonicalField> BY_KEY = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(CanonicalField::key, Function.identity()));

    private final String key;
    private final Type type;

    CanonicalField(String key, Type type) {
        this.key = key;
        this.type = type;
    }

    /**
     * Returns the YAML spelling.
     *
     * @return for example {@code principal.account-id}
     */
    public String key() {
        return key;
    }

    /**
     * Returns the value type.
     *
     * @return the type
     */
    public Type type() {
        return type;
    }

    /**
     * Looks a field up by its YAML spelling.
     *
     * @param key the spelling
     * @return the field, or empty
     */
    public static Optional<CanonicalField> fromKey(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }
}
