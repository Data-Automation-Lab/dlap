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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;

/**
 * Builds one synthetic Cloud Audit Logs {@code LogEntry} in REST JSON field naming, from the field
 * lists of googleapis {@code log_entry.proto} and {@code audit_log.proto}. Every value is invented;
 * nothing is copied from a vendor sample. Fields are written in proto3 JSON form (int64 as text,
 * enums by name) so the proto round-trip test can parse them strictly.
 */
final class Entry {

    static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final ObjectNode root = F.objectNode();
    private final String logName;
    private final String timestamp;
    private final String insertId;

    private Entry(String logName, String insertId, String timestamp) {
        this.logName = logName;
        this.insertId = insertId;
        this.timestamp = timestamp;
        root.put("insertId", insertId);
        root.put("logName", logName);
        if (timestamp != null) {
            root.put("timestamp", timestamp);
        }
    }

    /**
     * Starts an entry.
     *
     * @param parent    {@code projects/P}, {@code folders/F} or {@code organizations/O}
     * @param logId     {@code activity}, {@code data_access}, {@code system_event} or {@code policy}
     * @param insertId  the insert id
     * @param timestamp the entry time, or {@code null} for none
     * @return the builder
     */
    static Entry of(String parent, String logId, String insertId, String timestamp) {
        return new Entry(parent + "/logs/cloudaudit.googleapis.com%2F" + logId, insertId, timestamp);
    }

    /**
     * The native key a correct parser computes (log-event spec section 5.2).
     *
     * @return {@code logName|timestamp|insertId}, the time rendered by {@code Instant#toString}
     */
    String nativeKey() {
        String t = timestamp != null ? timestamp : root.path("receiveTimestamp").asText();
        return logName + "|" + Instant.parse(t) + "|" + insertId;
    }

    /**
     * The event time a correct parser reports.
     *
     * @return the time as {@code Instant#toString}
     */
    String eventTime() {
        return Instant.parse(timestamp != null ? timestamp : root.path("receiveTimestamp").asText()).toString();
    }

    /**
     * Sets the monitored resource.
     *
     * @param type   the resource type
     * @param labels label key and value pairs
     * @return this builder
     */
    Entry resource(String type, String... labels) {
        ObjectNode r = root.putObject("resource");
        r.put("type", type);
        ObjectNode l = r.putObject("labels");
        for (int i = 0; i + 1 < labels.length; i += 2) {
            l.put(labels[i], labels[i + 1]);
        }
        return this;
    }

    /**
     * Sets the AuditLog payload with its {@code @type}.
     *
     * @param service      the service name
     * @param method       the method name
     * @param resourceName the resource name
     * @return this builder
     */
    Entry payload(String service, String method, String resourceName) {
        ObjectNode p = root.putObject("protoPayload");
        p.put("@type", "type.googleapis.com/google.cloud.audit.AuditLog");
        p.putObject("status");
        if (service != null) {
            p.put("serviceName", service);
        }
        if (method != null) {
            p.put("methodName", method);
        }
        if (resourceName != null) {
            p.put("resourceName", resourceName);
        }
        return this;
    }

    /**
     * The payload object.
     *
     * @return the protoPayload
     */
    ObjectNode pp() {
        return (ObjectNode) root.get("protoPayload");
    }

    /**
     * Sets {@code authenticationInfo.principalEmail}.
     *
     * @param email the email
     * @return this builder
     */
    Entry principal(String email) {
        pp().withObject("/authenticationInfo").put("principalEmail", email);
        return this;
    }

    /**
     * Sets {@code authenticationInfo.principalSubject}.
     *
     * @param subject the subject
     * @return this builder
     */
    Entry principalSubject(String subject) {
        pp().withObject("/authenticationInfo").put("principalSubject", subject);
        return this;
    }

    /**
     * Adds a first-party service account delegation entry.
     *
     * @param email the delegating principal
     * @return this builder
     */
    Entry delegation(String email) {
        ArrayNode a = pp().withObject("/authenticationInfo").withArrayProperty("serviceAccountDelegationInfo");
        a.addObject().putObject("firstPartyPrincipal").put("principalEmail", email);
        return this;
    }

    /**
     * Sets request metadata.
     *
     * @param ip        the caller IP, or {@code null}
     * @param userAgent the user agent, or {@code null}
     * @return this builder
     */
    Entry caller(String ip, String userAgent) {
        ObjectNode m = pp().withObject("/requestMetadata");
        if (ip != null) {
            m.put("callerIp", ip);
        }
        if (userAgent != null) {
            m.put("callerSuppliedUserAgent", userAgent);
        }
        m.putObject("requestAttributes");
        m.putObject("destinationAttributes");
        return this;
    }

    /**
     * Adds an {@code authorizationInfo} entry.
     *
     * @param resource       the resource checked
     * @param permission     the permission
     * @param granted        whether it was granted
     * @param permissionType {@code ADMIN_READ}, {@code DATA_READ} ... (the 2025 field), or {@code null}
     * @return this builder
     */
    Entry auth(String resource, String permission, boolean granted, String permissionType) {
        ObjectNode a = pp().withArrayProperty("authorizationInfo").addObject();
        a.put("resource", resource);
        a.put("permission", permission);
        a.put("granted", granted);
        a.putObject("resourceAttributes");
        if (permissionType != null) {
            a.put("permissionType", permissionType);
        }
        return this;
    }

    /**
     * Sets {@code status}.
     *
     * @param code    the google.rpc code
     * @param message the message
     * @return this builder
     */
    Entry status(int code, String message) {
        ObjectNode s = pp().putObject("status");
        s.put("code", code);
        if (message != null) {
            s.put("message", message);
        }
        return this;
    }

    /**
     * Sets a payload member.
     *
     * @param field the AuditLog field
     * @param value the value
     * @return this builder
     */
    Entry pp(String field, JsonNode value) {
        pp().set(field, value);
        return this;
    }

    /**
     * Sets a top-level member.
     *
     * @param field the LogEntry field
     * @param value the value
     * @return this builder
     */
    Entry top(String field, JsonNode value) {
        root.set(field, value);
        return this;
    }

    /**
     * Sets a top-level text member.
     *
     * @param field the LogEntry field
     * @param value the value
     * @return this builder
     */
    Entry top(String field, String value) {
        root.put(field, value);
        return this;
    }

    /**
     * Sets {@code receiveTimestamp} and {@code severity}.
     *
     * @param receive  the receive time
     * @param severity the severity
     * @return this builder
     */
    Entry received(String receive, String severity) {
        root.put("receiveTimestamp", receive);
        root.put("severity", severity);
        return this;
    }

    /**
     * Sets {@code operation}.
     *
     * @param id    the operation id
     * @param first whether this is the first entry
     * @param last  whether this is the last entry
     * @return this builder
     */
    Entry operation(String id, boolean first, boolean last) {
        ObjectNode o = root.putObject("operation");
        o.put("id", id);
        o.put("producer", pp().path("serviceName").asText());
        if (first) {
            o.put("first", true);
        }
        if (last) {
            o.put("last", true);
        }
        return this;
    }

    /**
     * Sets {@code split}.
     *
     * @param uid   the split id
     * @param index the part index
     * @param total the number of parts
     * @return this builder
     */
    Entry split(String uid, int index, int total) {
        ObjectNode s = root.putObject("split");
        s.put("uid", uid);
        if (index > 0) {
            s.put("index", index);
        }
        s.put("totalSplits", total);
        return this;
    }

    /**
     * Removes the payload's {@code @type}.
     *
     * @return this builder
     */
    Entry withoutPayloadType() {
        pp().remove("@type");
        return this;
    }

    /**
     * Removes a top-level member.
     *
     * @param field the field
     * @return this builder
     */
    Entry without(String field) {
        root.remove(field);
        return this;
    }

    /**
     * The entry.
     *
     * @return a copy of the JSON
     */
    ObjectNode json() {
        return root.deepCopy();
    }
}
