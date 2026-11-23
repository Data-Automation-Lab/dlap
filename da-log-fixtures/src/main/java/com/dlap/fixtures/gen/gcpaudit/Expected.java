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

package com.dlap.fixtures.gen.gcpaudit;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One expected event of a generated case, in the {@code DlapLogEvent} proto3 JSON the contract test
 * compares (listed fields only). The values are what the GCP-01 parser contract says a correct
 * parser emits for the entry; they are written here, by hand, per entry, not computed by the
 * parser.
 */
final class Expected {

    private String nativeKey;
    private final String eventTime;
    private final String service;
    private final String operation;
    private final String schema;
    private String changeType;
    private String category;
    private String result = "SUCCESS";
    private String targetUri;
    private String sizeBytes;
    private String createdTime;
    private final Map<String, String> technical = new LinkedHashMap<>();
    private String principalType;
    private String principalId;
    private String principalDisplay;
    private final List<String> warnings = new ArrayList<>();
    private String shared;
    private String requestId;

    private Expected(String nativeKey, String eventTime, String service, String operation, String schema) {
        this.nativeKey = nativeKey;
        this.eventTime = eventTime;
        this.service = service;
        this.operation = operation;
        this.schema = schema;
    }

    /**
     * The primary event of an entry.
     *
     * @param e      the entry
     * @param schema the {@code source_schema_version} a correct parser reports
     * @return the expectation
     */
    static Expected of(Entry e, String schema) {
        ObjectNode pp = e.pp();
        return new Expected(e.nativeKey(), e.eventTime(), pp.path("serviceName").asText(),
                pp.path("methodName").asText(), schema);
    }

    /**
     * An additional event of the same entry for another target (log-event spec section 5.3).
     *
     * @param canonicalUri the additional event's target
     * @return a copy with the fan-out key and the target set, other fields kept
     */
    Expected fan(String canonicalUri) {
        Expected x = copy();
        x.nativeKey = nativeKey + "#" + canonicalUri;
        x.targetUri = canonicalUri;
        x.sizeBytes = null;
        x.createdTime = null;
        x.technical.clear();
        return x;
    }

    /**
     * Sets the change type and category.
     *
     * @param changeType for example {@code ACCESS}
     * @param category   for example {@code DATA_READ}, without the prefix
     * @return this
     */
    Expected is(String changeType, String category) {
        this.changeType = changeType;
        this.category = category;
        return this;
    }

    /**
     * Sets the result.
     *
     * @param value {@code SUCCESS} or {@code FAILURE}
     * @return this
     */
    Expected result(String value) {
        this.result = value;
        return this;
    }

    /**
     * Marks the event failed.
     *
     * @return this
     */
    Expected failed() {
        return result("FAILURE");
    }

    /**
     * Sets the target's canonical URI.
     *
     * @param uri the URI
     * @return this
     */
    Expected target(String uri) {
        this.targetUri = uri;
        return this;
    }

    /**
     * Sets the target's size and creation time.
     *
     * @param size    bytes
     * @param created the creation time
     * @return this
     */
    Expected sizeAndCreated(long size, String created) {
        this.sizeBytes = Long.toString(size);
        this.createdTime = created;
        return this;
    }

    /**
     * Sets the target's creation time.
     *
     * @param created the time
     * @return this
     */
    Expected created(String created) {
        this.createdTime = created;
        return this;
    }

    /**
     * Adds a technical-metadata entry of the target.
     *
     * @param key   the key
     * @param value the value
     * @return this
     */
    Expected technical(String key, String value) {
        technical.put(key, value);
        return this;
    }

    /**
     * Sets the principal.
     *
     * @param type the principal type, or {@code null} for none
     * @param id   the id
     * @return this
     */
    Expected principal(String type, String id) {
        this.principalType = type;
        this.principalId = id;
        return this;
    }

    /**
     * Sets a principal that names nobody: no type, no id, the masked text as display name.
     *
     * @param display the masked text
     * @return this
     */
    Expected maskedPrincipal(String display) {
        this.principalType = null;
        this.principalId = null;
        this.principalDisplay = display;
        return this;
    }

    /**
     * Sets the warnings, in enum order.
     *
     * @param values warning names without the prefix, for example {@code UNKNOWN_FIELDS}
     * @return this
     */
    Expected warnings(String... values) {
        warnings.clear();
        for (String v : values) {
            warnings.add("PARSE_WARNING_" + v);
        }
        return this;
    }

    /**
     * Sets {@code shared_event_id}.
     *
     * @param value the id
     * @return this
     */
    Expected shared(String value) {
        this.shared = value;
        return this;
    }

    /**
     * Sets {@code request_metadata.request_id}.
     *
     * @param value the id
     * @return this
     */
    Expected requestId(String value) {
        this.requestId = value;
        return this;
    }

    /**
     * Renders the expectation for one envelope.
     *
     * @param envelope the envelope id
     * @return the JSON object
     */
    ObjectNode render(String envelope) {
        ObjectNode o = Entry.F.objectNode();
        o.put("nativeEventId", nativeKey);
        o.put("eventTime", eventTime);
        o.put("sourceProvider", "GCP");
        o.put("sourceService", service);
        if (changeType != null) {
            o.put("changeType", changeType);
        }
        o.put("operation", operation);
        o.put("resultStatus", result);
        if (principalType != null || principalId != null || principalDisplay != null) {
            ObjectNode p = o.putObject("principal");
            if (principalType != null) {
                p.put("type", principalType);
            }
            if (principalId != null) {
                p.put("id", principalId);
            }
            if (principalDisplay != null) {
                p.put("displayName", principalDisplay);
            }
        }
        if (targetUri != null || sizeBytes != null || createdTime != null || !technical.isEmpty()) {
            ObjectNode t = o.putObject("targetResource");
            if (targetUri != null) {
                t.put("canonicalUri", targetUri);
            }
            if (createdTime != null) {
                t.put("createdTime", createdTime);
            }
            if (sizeBytes != null) {
                t.put("sizeBytes", sizeBytes);
            }
            if (!technical.isEmpty()) {
                ObjectNode m = t.putObject("technicalMetadata");
                technical.forEach(m::put);
            }
        }
        if (requestId != null) {
            o.putObject("requestMetadata").put("requestId", requestId);
        }
        o.put("sourceFormat", "GCP_CLOUD_AUDIT/" + envelope);
        o.put("sourceSchemaVersion", schema);
        if (category != null) {
            o.put("eventCategory", "EVENT_CATEGORY_" + category);
        }
        if (shared != null) {
            o.put("sharedEventId", shared);
        }
        if (!warnings.isEmpty()) {
            ArrayNode w = o.putArray("parseWarnings");
            warnings.forEach(w::add);
        }
        return o;
    }

    private Expected copy() {
        Expected x = new Expected(nativeKey, eventTime, service, operation, schema);
        x.changeType = changeType;
        x.category = category;
        x.result = result;
        x.targetUri = targetUri;
        x.sizeBytes = sizeBytes;
        x.createdTime = createdTime;
        x.technical.putAll(technical);
        x.principalType = principalType;
        x.principalId = principalId;
        x.principalDisplay = principalDisplay;
        x.warnings.addAll(warnings);
        x.shared = shared;
        x.requestId = requestId;
        return x;
    }
}
