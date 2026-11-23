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

package com.dlap.logingest.providers.gcp.logging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.audit.AuditLog;
import com.google.logging.v2.LogEntry;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.google.rpc.ErrorDetailsProto;
import com.google.rpc.Status;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Prints a Cloud Logging API {@code LogEntry} as the {@code api-proto} envelope's JSON, following
 * log-event spec section 6:
 * <ul>
 *   <li>a {@code protoPayload} that packs {@code google.cloud.audit.AuditLog} is unpacked with a type
 *       registry (a plain cast or an empty registry loses the entry, the defect GCP-00 fixed) and
 *       printed with its {@code @type};</li>
 *   <li>{@code serviceData}, an {@code Any} no registry here can print (the legacy BigQuery
 *       {@code AuditData} has no Java class), becomes {@code {"typeUrl", "base64"}} of its bytes;</li>
 *   <li>{@code status.details} entries print through the google.rpc error-detail types, and any other
 *       type becomes the same {@code {typeUrl, base64}} form;</li>
 *   <li>a {@code protoPayload} that is not an AuditLog becomes {@code {typeUrl, base64}} as well, so
 *       the parser quarantines it as {@code UNKNOWN_ENVELOPE} instead of the transport dropping it.</li>
 * </ul>
 * The API returns the full {@code logName} ({@code projects/P/logs/cloudaudit.googleapis.com%2F...}),
 * which the native key needs as it is.
 */
final class LogEntryJson {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AUDIT_LOG_TYPE = "type.googleapis.com/" + AuditLog.getDescriptor().getFullName();
    private static final JsonFormat.TypeRegistry REGISTRY = JsonFormat.TypeRegistry.newBuilder()
            .add(AuditLog.getDescriptor())
            .add(ErrorDetailsProto.getDescriptor().getMessageTypes())
            .build();
    private static final JsonFormat.Printer PRINTER = JsonFormat.printer().usingTypeRegistry(REGISTRY)
            .omittingInsignificantWhitespace();

    private LogEntryJson() {
    }

    /**
     * Prints an entry.
     *
     * @param entry the API's entry
     * @return the JSON text of one record
     */
    static String print(LogEntry entry) {
        try {
            LogEntry.Builder copy = entry.toBuilder();
            Any payload = entry.hasProtoPayload() ? entry.getProtoPayload() : null;
            JsonNode serviceData = null;
            JsonNode details = null;
            JsonNode opaquePayload = null;
            if (payload != null) {
                if (AUDIT_LOG_TYPE.equals(payload.getTypeUrl())) {
                    AuditLog audit = payload.unpack(AuditLog.class);
                    AuditLog.Builder ab = audit.toBuilder();
                    if (audit.hasServiceData()) {
                        serviceData = opaque(audit.getServiceData());
                        ab.clearServiceData();
                    }
                    if (audit.hasStatus() && audit.getStatus().getDetailsCount() > 0) {
                        details = details(audit.getStatus());
                        ab.setStatus(audit.getStatus().toBuilder().clearDetails());
                    }
                    copy.setProtoPayload(Any.pack(ab.build()));
                } else {
                    opaquePayload = opaque(payload);
                    copy.clearProtoPayload();
                }
            }
            ObjectNode out = (ObjectNode) JSON.readTree(PRINTER.print(copy.build()));
            if (opaquePayload != null) {
                out.set("protoPayload", opaquePayload);
            }
            if (out.get("protoPayload") instanceof ObjectNode pp) {
                if (serviceData != null) {
                    pp.set("serviceData", serviceData);
                }
                if (details != null) {
                    pp.withObjectProperty("status").set("details", details);
                }
            }
            return JSON.writeValueAsString(out);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("cannot print a Cloud Logging entry: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode details(Status status) throws IOException {
        ArrayNode out = JSON.createArrayNode();
        for (Any d : status.getDetailsList()) {
            try {
                out.add(JSON.readTree(PRINTER.print(d)));
            } catch (InvalidProtocolBufferException e) {
                out.add(opaque(d));
            }
        }
        return out;
    }

    private static ObjectNode opaque(Any any) {
        ObjectNode o = JSON.createObjectNode();
        o.put("typeUrl", any.getTypeUrl());
        o.put("base64", Base64.getEncoder().encodeToString(any.getValue().toByteArray()));
        return o;
    }

    /**
     * Prints a page of entries.
     *
     * @param entries the entries
     * @return one JSON text per entry, in order
     */
    static List<String> printAll(List<LogEntry> entries) {
        List<String> out = new ArrayList<>(entries.size());
        entries.forEach(e -> out.add(print(e)));
        return out;
    }
}
