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

import com.google.protobuf.AnyProto;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DurationProto;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The vendored googleapis descriptors (src/main/proto, compiled by the {@code googleapisDescriptorSet}
 * Gradle task into {@value #RESOURCE}): file descriptors, message lookups and a JSON type registry
 * for {@code LogEntry}, {@code AuditLog}, {@code BigQueryAuditMetadata} and the legacy BigQuery
 * {@code AuditData}. No generated Java classes are involved, so nothing clashes with the Google
 * client libraries.
 */
public final class GoogleApisDescriptors {

    /** The compiled descriptor set on the class path. */
    public static final String RESOURCE = "dlap-fixtures-proto/googleapis.desc";

    /** The file of the legacy BigQuery AuditData message. */
    public static final String AUDIT_DATA_FILE = "google/cloud/bigquery/logging/v1/audit_data.proto";

    private static final String WELL_KNOWN_PREFIX = "google/protobuf/";

    private static volatile GoogleApisDescriptors instance;

    private final DescriptorProtos.FileDescriptorSet set;
    private final Map<String, Descriptors.FileDescriptor> files;
    private final JsonFormat.TypeRegistry registry;

    private GoogleApisDescriptors(DescriptorProtos.FileDescriptorSet set) {
        this.set = set;
        Map<String, Descriptors.FileDescriptor> built = new HashMap<>();
        for (Descriptors.FileDescriptor wkt : List.of(AnyProto.getDescriptor(), DurationProto.getDescriptor(),
                TimestampProto.getDescriptor(), StructProto.getDescriptor(), DescriptorProtos.getDescriptor(),
                WrappersProto.getDescriptor(), EmptyProto.getDescriptor(), FieldMaskProto.getDescriptor())) {
            built.put(wkt.getName(), wkt);
        }
        Map<String, DescriptorProtos.FileDescriptorProto> byName = new LinkedHashMap<>();
        set.getFileList().forEach(f -> byName.put(f.getName(), f));
        Map<String, Descriptors.FileDescriptor> out = new LinkedHashMap<>();
        JsonFormat.TypeRegistry.Builder types = JsonFormat.TypeRegistry.newBuilder();
        for (String name : byName.keySet()) {
            Descriptors.FileDescriptor fd = build(name, byName, built);
            out.put(name, fd);
            if (!name.startsWith(WELL_KNOWN_PREFIX)) {
                types.add(fd.getMessageTypes());
            }
        }
        this.files = Collections.unmodifiableMap(out);
        this.registry = types.build();
    }

    /**
     * The descriptors, loaded once from the class path.
     *
     * @return the instance
     */
    public static GoogleApisDescriptors get() {
        GoogleApisDescriptors d = instance;
        if (d == null) {
            synchronized (GoogleApisDescriptors.class) {
                d = instance;
                if (d == null) {
                    d = new GoogleApisDescriptors(read());
                    instance = d;
                }
            }
        }
        return d;
    }

    /**
     * Finds a message type by its full name, for example {@code google.logging.v2.LogEntry}.
     *
     * @param fullName the full name
     * @return the descriptor
     * @throws IllegalArgumentException when no vendored file defines it
     */
    public Descriptors.Descriptor message(String fullName) {
        Descriptors.Descriptor d = registry.find(fullName);
        if (d == null) {
            throw new IllegalArgumentException("no vendored proto defines " + fullName);
        }
        return d;
    }

    /**
     * The JSON type registry of every vendored message, for {@code Any} fields.
     *
     * @return the registry
     */
    public JsonFormat.TypeRegistry registry() {
        return registry;
    }

    /**
     * The descriptor set da-logingest ships to decode the legacy AuditData of the api-proto envelope:
     * {@code audit_data.proto} and its imports except the well-known {@code google/protobuf} files, in
     * dependency order, without source info, printed as proto3 JSON.
     *
     * @return the JSON text, ending in a newline
     */
    public String auditDataDescriptorsJson() {
        Set<String> closure = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(AUDIT_DATA_FILE));
        while (!todo.isEmpty()) {
            String name = todo.pop();
            if (name.startsWith(WELL_KNOWN_PREFIX) || !closure.add(name)) {
                continue;
            }
            Descriptors.FileDescriptor fd = files.get(name);
            if (fd == null) {
                throw new IllegalStateException(RESOURCE + " lacks " + name);
            }
            fd.toProto().getDependencyList().forEach(todo::add);
        }
        DescriptorProtos.FileDescriptorSet.Builder out = DescriptorProtos.FileDescriptorSet.newBuilder();
        for (DescriptorProtos.FileDescriptorProto f : set.getFileList()) {
            if (closure.contains(f.getName())) {
                out.addFile(f.toBuilder().clearSourceCodeInfo().build());
            }
        }
        try {
            return JsonFormat.printer().print(out.build()) + "\n";
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("cannot print the AuditData descriptors", e);
        }
    }

    /**
     * Writes {@link #auditDataDescriptorsJson()} to a file.
     *
     * <pre>
     *   java ... com.dlap.fixtures.gen.gcpaudit.GoogleApisDescriptors &lt;output file&gt;
     * </pre>
     *
     * @param args the output file
     * @throws IOException when the file cannot be written
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: GoogleApisDescriptors <output file>");
            System.exit(2);
        }
        Files.writeString(Path.of(args[0]), get().auditDataDescriptorsJson(), StandardCharsets.UTF_8);
    }

    /**
     * Finds the vendored file that defines a message.
     *
     * @param fullName the message
     * @return the file, or empty
     */
    Optional<Descriptors.FileDescriptor> fileOf(String fullName) {
        Descriptors.Descriptor d = registry.find(fullName);
        return d == null ? Optional.empty() : Optional.of(d.getFile());
    }

    private static Descriptors.FileDescriptor build(String name, Map<String, DescriptorProtos.FileDescriptorProto> byName,
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
        try (InputStream in = GoogleApisDescriptors.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the class path; run the googleapisDescriptorSet task");
            }
            return DescriptorProtos.FileDescriptorSet.parseFrom(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(RESOURCE, e);
        }
    }
}
