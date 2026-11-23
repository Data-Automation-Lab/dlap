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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.AnyProto;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DurationProto;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.EmptyProto;
import com.google.protobuf.FieldMaskProto;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.StructProto;
import com.google.protobuf.TimestampProto;
import com.google.protobuf.WrappersProto;
import com.google.protobuf.util.JsonFormat;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the legacy BigQuery {@code AuditData} that the {@code api-proto} envelope carries as
 * {@code serviceData: {"typeUrl": ..., "base64": ...}} (log-event spec section 6) and prints it as
 * the REST JSON the {@code json} envelope carries, so one code path reads both.
 *
 * <p>No Java class for {@code google.cloud.bigquery.logging.v1.AuditData} is published, so the
 * message is decoded with the protobuf runtime against a descriptor set of the vendored googleapis
 * {@code audit_data.proto} and its imports, shipped as the resource {@value #RESOURCE} (printed by
 * {@code JsonFormat}; {@code GcpAuditDescriptorSyncTest} keeps it equal to the descriptor set that
 * {@code da-log-fixtures} compiles from the vendored protos). The well-known
 * {@code google/protobuf} types come from the protobuf runtime.
 */
public final class AuditDataDecoder {

    /** The descriptor set resource, a {@code FileDescriptorSet} in proto3 JSON. */
    public static final String RESOURCE = "dlap/log-formats/gcpaudit/bigquery-auditdata-v1.descriptors.json";

    /** Full name of the message. */
    public static final String MESSAGE = "google.cloud.bigquery.logging.v1.AuditData";

    private static final ObjectMapper JSON = new ObjectMapper();

    private AuditDataDecoder() {
    }

    /**
     * Decodes a {@code {typeUrl, base64}} service data object.
     *
     * @param serviceData the object
     * @return the expanded JSON with its {@code @type}, or empty when the type is not AuditData or
     *         the bytes do not decode
     */
    public static Optional<ObjectNode> expand(JsonNode serviceData) {
        if (serviceData == null || !serviceData.isObject()) {
            return Optional.empty();
        }
        Optional<String> typeUrl = GcpJson.text(serviceData, "typeUrl");
        JsonNode base64 = serviceData.path("base64");
        // An empty message encodes to no bytes, so "" is a valid payload.
        if (typeUrl.isEmpty() || !base64.isTextual() || !GcpAuditFormat.BQ_AUDIT_DATA_TYPE.equals(typeUrl.get())) {
            return Optional.empty();
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64.textValue().strip());
            Holder h = Holder.INSTANCE;
            DynamicMessage message = DynamicMessage.parseFrom(h.auditData, bytes);
            String printed = h.printer.print(message);
            JsonNode tree = JSON.readTree(printed);
            if (!(tree instanceof ObjectNode o)) {
                return Optional.empty();
            }
            ObjectNode out = JSON.createObjectNode();
            out.put("@type", GcpAuditFormat.BQ_AUDIT_DATA_TYPE);
            out.setAll(o);
            return Optional.of(out);
        } catch (IllegalArgumentException | InvalidProtocolBufferException e) {
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * The decoded descriptor of {@code AuditData}, for tests.
     *
     * @return the descriptor
     */
    static Descriptors.Descriptor descriptor() {
        return Holder.INSTANCE.auditData;
    }

    /** Loads the descriptors once, on first use. */
    private static final class Holder {
        private static final Holder INSTANCE = new Holder();

        private final Descriptors.Descriptor auditData;
        private final JsonFormat.Printer printer;

        private Holder() {
            DescriptorProtos.FileDescriptorSet set = read();
            Map<String, Descriptors.FileDescriptor> built = new HashMap<>();
            for (Descriptors.FileDescriptor wkt : List.of(AnyProto.getDescriptor(), DurationProto.getDescriptor(),
                    TimestampProto.getDescriptor(), StructProto.getDescriptor(), DescriptorProtos.getDescriptor(),
                    WrappersProto.getDescriptor(), EmptyProto.getDescriptor(), FieldMaskProto.getDescriptor())) {
                built.put(wkt.getName(), wkt);
            }
            Map<String, DescriptorProtos.FileDescriptorProto> byName = new HashMap<>();
            set.getFileList().forEach(f -> byName.put(f.getName(), f));
            JsonFormat.TypeRegistry.Builder registry = JsonFormat.TypeRegistry.newBuilder();
            Descriptors.Descriptor found = null;
            for (DescriptorProtos.FileDescriptorProto f : set.getFileList()) {
                Descriptors.FileDescriptor fd = build(f.getName(), byName, built);
                registry.add(fd.getMessageTypes());
                Descriptors.Descriptor d = fd.findMessageTypeByName("AuditData");
                if (d != null && d.getFullName().equals(MESSAGE)) {
                    found = d;
                }
            }
            if (found == null) {
                throw new IllegalStateException(RESOURCE + " does not define " + MESSAGE);
            }
            this.auditData = found;
            this.printer = JsonFormat.printer().usingTypeRegistry(registry.build()).omittingInsignificantWhitespace();
        }

        private static Descriptors.FileDescriptor build(String name,
                                                        Map<String, DescriptorProtos.FileDescriptorProto> byName,
                                                        Map<String, Descriptors.FileDescriptor> built) {
            Descriptors.FileDescriptor done = built.get(name);
            if (done != null) {
                return done;
            }
            DescriptorProtos.FileDescriptorProto proto = byName.get(name);
            if (proto == null) {
                throw new IllegalStateException(RESOURCE + " lacks " + name);
            }
            Descriptors.FileDescriptor[] deps = new Descriptors.FileDescriptor[proto.getDependencyCount()];
            for (int i = 0; i < deps.length; i++) {
                deps[i] = build(proto.getDependency(i), byName, built);
            }
            try {
                Descriptors.FileDescriptor fd = Descriptors.FileDescriptor.buildFrom(proto, deps);
                built.put(name, fd);
                return fd;
            } catch (Descriptors.DescriptorValidationException e) {
                throw new IllegalStateException(RESOURCE + ": " + name + " does not build", e);
            }
        }

        private static DescriptorProtos.FileDescriptorSet read() {
            try (InputStream in = AuditDataDecoder.class.getClassLoader().getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("resource " + RESOURCE + " is missing");
                }
                DescriptorProtos.FileDescriptorSet.Builder b = DescriptorProtos.FileDescriptorSet.newBuilder();
                JsonFormat.parser().merge(new String(in.readAllBytes(), StandardCharsets.UTF_8), b);
                return b.build();
            } catch (IOException e) {
                throw new UncheckedIOException(RESOURCE, e);
            }
        }
    }
}
