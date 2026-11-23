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

import com.dlap.logingest.parse.core.FieldPath;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Turns a row of a BigQuery Log Router sink table into the {@code LogEntry} JSON the other
 * envelopes deliver, so one code path reads every envelope and {@code raw_event} holds the
 * LogEntry for every envelope (log-event spec section 6).
 *
 * <p>The row is the JSON object the BigQuery sink transport builds from a table row: one member
 * per column, nested {@code RECORD}s as objects, {@code REPEATED} fields as arrays and
 * {@code TIMESTAMP}s as RFC 3339 text. The export page
 * (https://docs.cloud.google.com/logging/docs/export/bigquery) renames these fields, and the view
 * reverses it:
 * <ul>
 *   <li>{@code protopayload_auditlog} becomes {@code protoPayload} with
 *       {@code "@type": "type.googleapis.com/google.cloud.audit.AuditLog"};</li>
 *   <li>the STRING columns {@code metadataJson}, {@code requestJson} and {@code responseJson}
 *       (and {@code resourceOriginalStateJson}) become the JSON objects {@code metadata},
 *       {@code request} and {@code response};</li>
 *   <li>{@code servicedata_v1_bigquery} becomes {@code serviceData} with the BigQuery v1
 *       {@code AuditData} type, {@code servicedata_v1_iam} the IAM policy delta type, and
 *       {@code servicedata_v1_iam_admin} the IAM admin type;</li>
 *   <li>a flattened {@code statuscode} or {@code statusMessage} becomes {@code status.code} and
 *       {@code status.message}.</li>
 * </ul>
 * Field names are matched without case, because the export page shows both {@code statuscode} and
 * {@code status.code} spellings and the casing of a live sink schema is unverified (coverage spec
 * variant {@code envelope.bq-statuscode-casing}). Keys of {@code labels} maps are kept as they are.
 * A TIMESTAMP that arrives as a number (seconds, or microseconds above 10^14) is rendered as RFC
 * 3339 text. BigQuery TIMESTAMPs hold microseconds, so an entry whose timestamp has more fraction
 * digits gets a different native key here than from the API or a Cloud Storage shard.
 */
public final class BqSinkRows {

    /** The column of the AuditLog payload. */
    public static final String PAYLOAD_COLUMN = "protopayload_auditlog";

    private static final ObjectMapper JSON = new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(200).build())
            .build());

    private static final Map<String, String> NAMES = names();

    /** Payload columns that hold a JSON string and the LogEntry field each one becomes. */
    private static final Map<String, String> JSON_COLUMNS = Map.of(
            "metadatajson", "metadata",
            "requestjson", "request",
            "responsejson", "response",
            "resourceoriginalstatejson", "resourceOriginalState");

    /** Service-data columns and the type each one carries. */
    private static final Map<String, String> SERVICE_DATA_COLUMNS = Map.of(
            "servicedata_v1_bigquery", GcpAuditFormat.BQ_AUDIT_DATA_TYPE,
            "servicedata_v1_iam", GcpAuditFormat.IAM_AUDIT_DATA_TYPE,
            "servicedata_v1_iam_admin", "type.googleapis.com/google.iam.admin.v1.AuditData");

    private BqSinkRows() {
    }

    /**
     * The outcome of converting one row.
     *
     * @param entry   the LogEntry view, or {@code null} when the row cannot be converted
     * @param problem why the row cannot be converted; {@code null} on success
     */
    public record View(ObjectNode entry, String problem) {

        /**
         * Whether the row converted.
         *
         * @return true when {@link #entry()} is set
         */
        public boolean ok() {
            return entry != null;
        }
    }

    /**
     * Converts a row.
     *
     * @param row the row
     * @return the view; never {@code null}
     */
    public static View toEntry(JsonNode row) {
        if (row == null || !row.isObject()) {
            return new View(null, "the row is not a JSON object");
        }
        JsonNode payload = null;
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = row.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String lower = e.getKey().toLowerCase(Locale.ROOT);
            if (PAYLOAD_COLUMN.equals(lower)) {
                payload = e.getValue();
                continue;
            }
            String name = NAMES.getOrDefault(lower, e.getKey());
            JsonNode value = e.getValue();
            if (("timestamp".equals(name) || "receiveTimestamp".equals(name)) && value.isNumber()) {
                value = numericTime(value);
            } else if (!"labels".equals(name)) {
                value = canonical(value);
            }
            out.set(name, value);
        }
        if (payload == null || !payload.isObject()) {
            return new View(null, "the row has no " + PAYLOAD_COLUMN + " object");
        }
        ObjectNode pp = JsonNodeFactory.instance.objectNode();
        pp.put("@type", GcpAuditFormat.AUDIT_LOG_TYPE);
        ObjectNode status = null;
        for (Iterator<Map.Entry<String, JsonNode>> it = payload.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            String lower = e.getKey().toLowerCase(Locale.ROOT);
            JsonNode value = e.getValue();
            if (JSON_COLUMNS.containsKey(lower)) {
                if (value.isNull()) {
                    continue;
                }
                if (!value.isTextual()) {
                    return new View(null, PAYLOAD_COLUMN + "." + e.getKey() + " is not a JSON string column");
                }
                Optional<JsonNode> parsed = parse(value.textValue());
                if (parsed.isEmpty()) {
                    return new View(null, PAYLOAD_COLUMN + "." + e.getKey() + " does not hold a JSON object");
                }
                pp.set(JSON_COLUMNS.get(lower), parsed.get());
                continue;
            }
            if (SERVICE_DATA_COLUMNS.containsKey(lower)) {
                if (value.isObject()) {
                    ObjectNode sd = JsonNodeFactory.instance.objectNode();
                    sd.put("@type", SERVICE_DATA_COLUMNS.get(lower));
                    sd.setAll((ObjectNode) canonical(value));
                    pp.set("serviceData", sd);
                }
                continue;
            }
            if ("statuscode".equals(lower) || "statusmessage".equals(lower)) {
                if (status == null) {
                    status = JsonNodeFactory.instance.objectNode();
                }
                status.set("statuscode".equals(lower) ? "code" : "message", value);
                continue;
            }
            pp.set(NAMES.getOrDefault(lower, e.getKey()), canonical(value));
        }
        if (status != null) {
            JsonNode existing = pp.get("status");
            if (existing instanceof ObjectNode o) {
                status.fields().forEachRemaining(f -> {
                    if (!o.has(f.getKey())) {
                        o.set(f.getKey(), f.getValue());
                    }
                });
            } else {
                pp.set("status", status);
            }
        }
        out.set("protoPayload", pp);
        return new View(out, null);
    }

    /**
     * The paths a sink row may carry, for the shape fingerprint: the LogEntry columns, and every
     * AuditLog field under {@code protopayload_auditlog} in both its camel case and lower case
     * spelling; each declared path covers its subtree.
     *
     * @return the declared paths
     */
    public static Set<FieldPath> declaredPaths() {
        Set<FieldPath> out = new LinkedHashSet<>();
        for (String top : new String[] {"logName", "resource", "timestamp", "receiveTimestamp", "severity", "insertId",
            "labels", "operation", "trace", "spanId", "traceSampled", "split", "httpRequest", "sourceLocation",
            "textPayload", "errorGroups"}) {
            out.add(FieldPath.of("/" + top));
            out.add(FieldPath.of("/" + top.toLowerCase(Locale.ROOT)));
        }
        for (String field : new String[] {"serviceName", "methodName", "resourceName", "resourceLocation",
            "resourceOriginalState", "numResponseItems", "status", "authenticationInfo", "authorizationInfo",
            "policyViolationInfo", "requestMetadata", "metadataJson", "requestJson", "responseJson",
            "resourceOriginalStateJson", "servicedata_v1_bigquery", "servicedata_v1_iam", "servicedata_v1_iam_admin",
            "statusCode", "statusMessage"}) {
            out.add(FieldPath.of("/" + PAYLOAD_COLUMN + "/" + field));
            out.add(FieldPath.of("/" + PAYLOAD_COLUMN + "/" + field.toLowerCase(Locale.ROOT)));
        }
        return out;
    }

    private static Optional<JsonNode> parse(String text) {
        try {
            JsonNode n = JSON.readTree(text);
            return n != null && n.isObject() ? Optional.of(n) : Optional.empty();
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static JsonNode numericTime(JsonNode value) {
        try {
            BigDecimal v = value.decimalValue();
            Instant t;
            if (v.abs().compareTo(BigDecimal.valueOf(100_000_000_000_000L)) >= 0) {
                long micros = v.longValue();
                t = Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L);
            } else {
                BigDecimal seconds = v.setScale(6, java.math.RoundingMode.HALF_EVEN);
                long s = seconds.longValue();
                long nanos = seconds.subtract(BigDecimal.valueOf(s)).movePointRight(9).longValue();
                t = Instant.ofEpochSecond(s, nanos);
            }
            return JsonNodeFactory.instance.textNode(t.toString());
        } catch (ArithmeticException | java.time.DateTimeException e) {
            return value;
        }
    }

    /** Renames known field names to their camel case spelling, recursively, except label maps. */
    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode o = JsonNodeFactory.instance.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = value.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String name = NAMES.getOrDefault(e.getKey().toLowerCase(Locale.ROOT), e.getKey());
                o.set(name, "labels".equals(name) ? e.getValue() : canonical(e.getValue()));
            }
            return o;
        }
        if (value.isArray()) {
            var a = JsonNodeFactory.instance.arrayNode();
            value.forEach(v -> a.add(canonical(v)));
            return a;
        }
        return value;
    }

    /**
     * Lower case to camel case spelling of the LogEntry, AuditLog and BigQuery v1 AuditData field
     * names the parser reads (googleapis log_entry.proto, audit_log.proto, attribute_context.proto,
     * audit_data.proto).
     */
    private static Map<String, String> names() {
        String[] camel = {
            // LogEntry, MonitoredResource, LogEntryOperation, LogSplit
            "logName", "resource", "type", "labels", "timestamp", "receiveTimestamp", "severity", "insertId",
            "httpRequest", "operation", "id", "producer", "first", "last", "trace", "spanId", "traceSampled",
            "sourceLocation", "split", "uid", "index", "totalSplits", "textPayload", "errorGroups",
            // AuditLog and its messages
            "serviceName", "methodName", "resourceName", "resourceLocation", "currentLocations",
            "originalLocations", "resourceOriginalState", "numResponseItems", "status", "code", "message",
            "details", "authenticationInfo", "principalEmail", "authoritySelector", "thirdPartyPrincipal",
            "serviceAccountKeyName", "serviceAccountDelegationInfo", "firstPartyPrincipal",
            "principalSubject", "serviceMetadata", "authorizationInfo", "permission", "granted",
            "resourceAttributes", "permissionType", "policyViolationInfo", "orgPolicyViolationInfo",
            "payload", "resourceType", "resourceTags", "violationInfo", "constraint", "errorMessage",
            "checkedValue", "policyType", "requestMetadata", "callerIp", "callerSuppliedUserAgent",
            "callerNetwork", "requestAttributes", "destinationAttributes", "method", "headers", "path",
            "host", "scheme", "query", "time", "size", "protocol", "reason", "auth", "principal",
            "audiences", "presenter", "claims", "accessLevels", "ip", "port", "regionCode", "request",
            "response", "metadata", "serviceData",
            // BigQuery v1 AuditData (the parts the parser reads)
            "jobCompletedEvent", "eventName", "job", "jobName", "projectId", "jobId", "location",
            "jobConfiguration", "destinationTable", "datasetId", "tableId", "statementType",
            "createDisposition", "writeDisposition", "defaultDataset", "queryPriority", "load", "sourceUris",
            "tableCopy", "sourceTables", "extract", "destinationUris", "sourceTable", "dryRun", "jobStatus",
            "state", "error", "additionalErrors", "jobStatistics", "createTime", "startTime", "endTime",
            "totalProcessedBytes", "totalBilledBytes", "billingTier", "totalSlotMs", "reservationUsage",
            "reservation", "referencedTables", "totalTablesProcessed", "referencedViews",
            "totalViewsProcessed", "queryOutputRowCount", "totalLoadOutputBytes", "tableDataReadEvents",
            "tableName", "referencedFields", "tableInsertRequest", "tableUpdateRequest", "tableInsertResponse",
            "tableUpdateResponse", "datasetListRequest", "datasetInsertRequest", "datasetInsertResponse",
            "datasetUpdateRequest", "datasetUpdateResponse", "jobInsertRequest", "jobInsertResponse",
            "jobQueryRequest", "jobQueryResponse", "jobGetQueryResultsRequest", "jobGetQueryResultsResponse",
            "jobQueryDoneResponse", "tableDataListRequest", "setIamPolicyRequest", "policyResponse",
            "totalResults", "maxResults", "startRow", "info", "friendlyName", "description", "schemaJson",
            "view", "expireTime", "truncateTime", "updateTime", "encryption", "kmsKeyName", "datasetName",
            "acl", "entries", "role", "groupEmail", "userEmail", "domain", "specialGroup", "viewName",
            "defaultTableExpireDuration", "tableDefinitions", "name", "policyDelta", "bindingDeltas",
            "action", "member", "condition",
        };
        Map<String, String> m = new TreeMap<>();
        for (String c : camel) {
            m.put(c.toLowerCase(Locale.ROOT), c);
        }
        return Map.copyOf(m);
    }
}
