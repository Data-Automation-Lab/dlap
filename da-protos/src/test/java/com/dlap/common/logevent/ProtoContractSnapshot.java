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

package com.dlap.common.logevent;

import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Wire-contract snapshot of a protobuf file, used by the descriptor-diff gate
 * (decision D7: no {@code buf}; a unit test instead).
 *
 * <p>Every message, field, enum and enum value becomes one line
 * {@code <key> | <attributes>}. Fields and enum values are keyed by their
 * number, so a renumbering shows up as a removal plus an addition, and a
 * rename, retype or relabel shows up as a changed line. In the v1 package any
 * removal or change is breaking; additions are allowed but must be recorded in
 * the committed baseline so the next diff can see them.
 */
final class ProtoContractSnapshot {

    private static final String SEPARATOR = " | ";

    /**
     * Orders keys so the baseline reads top-down: the file line, then each
     * message or enum followed by its fields or values in number order.
     */
    private static final Comparator<String> KEY_ORDER = Comparator
            .comparing(ProtoContractSnapshot::owner)
            .thenComparingInt(ProtoContractSnapshot::rank)
            .thenComparingInt(ProtoContractSnapshot::number)
            .thenComparing(Comparator.naturalOrder());

    private ProtoContractSnapshot() {
    }

    /**
     * Builds the snapshot of one file descriptor.
     *
     * @param file the compiled descriptor
     * @return key to attribute string, sorted by key
     */
    static SortedMap<String, String> of(FileDescriptor file) {
        SortedMap<String, String> out = new TreeMap<>(KEY_ORDER);
        FileDescriptorProto fp = file.toProto();
        out.put("file " + file.getName(), String.join(" ",
                "package=" + file.getPackage(),
                "syntax=" + fp.getSyntax(),
                "java_package=" + fp.getOptions().getJavaPackage(),
                "java_outer_classname=" + fp.getOptions().getJavaOuterClassname(),
                "java_multiple_files=" + fp.getOptions().getJavaMultipleFiles()));
        for (Descriptor message : file.getMessageTypes()) {
            addMessage(out, message);
        }
        for (EnumDescriptor e : file.getEnumTypes()) {
            addEnum(out, e);
        }
        return out;
    }

    private static void addMessage(SortedMap<String, String> out, Descriptor message) {
        out.put("message " + message.getFullName(),
                message.getOptions().getMapEntry() ? "map_entry=true" : "");
        for (FieldDescriptor field : message.getFields()) {
            out.put("field " + message.getFullName() + "#" + field.getNumber(), fieldAttributes(field));
        }
        for (Descriptor nested : message.getNestedTypes()) {
            addMessage(out, nested);
        }
        for (EnumDescriptor nested : message.getEnumTypes()) {
            addEnum(out, nested);
        }
    }

    private static void addEnum(SortedMap<String, String> out, EnumDescriptor e) {
        out.put("enum " + e.getFullName(), "");
        for (EnumValueDescriptor value : e.getValues()) {
            out.put("value " + e.getFullName() + "#" + value.getNumber(), "name=" + value.getName());
        }
    }

    private static String fieldAttributes(FieldDescriptor field) {
        FieldDescriptorProto fp = field.toProto();
        List<String> parts = new ArrayList<>();
        parts.add("name=" + field.getName());
        parts.add("json=" + field.getJsonName());
        parts.add("label=" + fp.getLabel().name().substring("LABEL_".length()).toLowerCase(Locale.ROOT));
        parts.add("type=" + typeOf(field));
        if (fp.getProto3Optional()) {
            parts.add("presence=explicit");
        }
        OneofDescriptor oneof = field.getContainingOneof();
        if (oneof != null && !fp.getProto3Optional()) {
            parts.add("oneof=" + oneof.getName());
        }
        return String.join(" ", parts);
    }

    private static String typeOf(FieldDescriptor field) {
        return switch (field.getType()) {
            case MESSAGE, GROUP -> "message:" + field.getMessageType().getFullName();
            case ENUM -> "enum:" + field.getEnumType().getFullName();
            default -> field.getType().name().toLowerCase(Locale.ROOT);
        };
    }

    /**
     * Renders a snapshot as baseline-file lines.
     *
     * @param snapshot the snapshot
     * @return one line per entry, in key order
     */
    static List<String> render(SortedMap<String, String> snapshot) {
        List<String> lines = new ArrayList<>(snapshot.size());
        snapshot.forEach((k, v) -> lines.add(line(k, v)));
        return lines;
    }

    /**
     * Parses baseline-file lines; blank lines and lines starting with {@code #}
     * are comments.
     *
     * @param lines the file content
     * @return the snapshot the lines describe
     */
    static SortedMap<String, String> parse(List<String> lines) {
        SortedMap<String, String> out = new TreeMap<>(KEY_ORDER);
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int sep = line.indexOf(" |");
            if (sep < 0) {
                throw new IllegalArgumentException("Baseline line has no ' | ' separator: " + line);
            }
            String key = line.substring(0, sep).strip();
            String attrs = line.substring(sep + 2).strip();
            if (out.put(key, attrs) != null) {
                throw new IllegalArgumentException("Duplicate baseline key: " + key);
            }
        }
        return out;
    }

    /**
     * Compares a committed baseline with the current snapshot.
     *
     * @param baseline the committed contract
     * @param current  the compiled contract
     * @return removed, changed and added lines
     */
    static Diff diff(SortedMap<String, String> baseline, SortedMap<String, String> current) {
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        for (Map.Entry<String, String> e : baseline.entrySet()) {
            String now = current.get(e.getKey());
            if (now == null) {
                removed.add(line(e.getKey(), e.getValue()));
            } else if (!now.equals(e.getValue())) {
                changed.add(line(e.getKey(), e.getValue()) + "  =>  " + now);
            }
        }
        for (Map.Entry<String, String> e : current.entrySet()) {
            if (!baseline.containsKey(e.getKey())) {
                added.add(line(e.getKey(), e.getValue()));
            }
        }
        return new Diff(removed, changed, added);
    }

    private static String owner(String key) {
        int space = key.indexOf(' ');
        String name = key.substring(space + 1);
        int hash = name.indexOf('#');
        return key.startsWith("file ") ? "" : (hash < 0 ? name : name.substring(0, hash));
    }

    private static int rank(String key) {
        return key.startsWith("field ") || key.startsWith("value ") ? 1 : 0;
    }

    private static int number(String key) {
        int hash = key.indexOf('#');
        return hash < 0 ? -1 : Integer.parseInt(key.substring(hash + 1));
    }

    private static String line(String key, String attrs) {
        return attrs.isEmpty() ? key + " |" : key + SEPARATOR + attrs;
    }

    /**
     * Result of a snapshot comparison.
     *
     * @param removed baseline entries missing from the current contract
     * @param changed baseline entries whose attributes changed (old  =>  new)
     * @param added   current entries the baseline does not record
     */
    record Diff(List<String> removed, List<String> changed, List<String> added) {

        /**
         * True when the change would break v1 producers or consumers.
         *
         * @return whether anything was removed or changed
         */
        boolean breaking() {
            return !removed.isEmpty() || !changed.isEmpty();
        }
    }
}
