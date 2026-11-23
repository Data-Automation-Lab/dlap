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

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.proto.log.v1.LogEventProto;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.DescriptorValidationException;
import com.google.protobuf.Descriptors.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.SortedMap;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Descriptor-diff gate for {@code dlap/log/v1/log_event.proto} (ADR-0045,
 * decision D7). The committed baseline is the published v1 wire contract:
 * removing, renumbering, renaming or retyping anything in it fails the build.
 * Additive changes must be appended to the baseline in the same commit.
 */
class LogEventProtoContractTest {

    private static final String BASELINE = "log_event_v1.contract.txt";
    private static final Path SNAPSHOT_OUT = Path.of("build", "proto-contract", BASELINE);

    @Test
    void compiledContractMatchesCommittedBaseline() throws IOException {
        SortedMap<String, String> current = ProtoContractSnapshot.of(LogEventProto.getDescriptor());
        // Always write the current snapshot so an additive change can be copied
        // into the baseline after review.
        Files.createDirectories(SNAPSHOT_OUT.getParent());
        Files.write(SNAPSHOT_OUT, ProtoContractSnapshot.render(current), StandardCharsets.UTF_8);

        ProtoContractSnapshot.Diff diff = ProtoContractSnapshot.diff(baseline(), current);

        assertThat(diff.removed())
                .as("BREAKING: v1 fields, values or messages removed or renumbered")
                .isEmpty();
        assertThat(diff.changed())
                .as("BREAKING: v1 entries renamed, retyped or relabelled")
                .isEmpty();
        assertThat(diff.added())
                .as("Unrecorded additive change: append these lines to src/test/resources/"
                        + "com/dlap/common/logevent/" + BASELINE + " (full snapshot at " + SNAPSHOT_OUT + ")")
                .isEmpty();
    }

    @Test
    void removedFieldIsBreaking() throws Exception {
        ProtoContractSnapshot.Diff diff = diffAgainst(fp -> mutateMessage(fp, "DlapLogEvent",
                m -> m.toBuilder().clearField().addAllField(m.getFieldList().stream()
                        .filter(f -> f.getNumber() != 1).toList()).build()));

        assertThat(diff.breaking()).isTrue();
        assertThat(diff.removed()).anyMatch(l -> l.startsWith("field dlap.log.v1.DlapLogEvent#1 |"));
    }

    @Test
    void renumberedFieldIsBreaking() throws Exception {
        ProtoContractSnapshot.Diff diff = diffAgainst(fp -> mutateMessage(fp, "DlapLogEvent",
                m -> m.toBuilder().setField(indexOfField(m, 2),
                        m.getField(indexOfField(m, 2)).toBuilder().setNumber(999)).build()));

        assertThat(diff.breaking()).isTrue();
        assertThat(diff.removed()).anyMatch(l -> l.startsWith("field dlap.log.v1.DlapLogEvent#2 |"));
        assertThat(diff.added()).anyMatch(l -> l.startsWith("field dlap.log.v1.DlapLogEvent#999 |"));
    }

    @Test
    void renamedFieldIsBreaking() throws Exception {
        ProtoContractSnapshot.Diff diff = diffAgainst(fp -> mutateMessage(fp, "DlapLogEvent",
                m -> m.toBuilder().setField(indexOfField(m, 7),
                        m.getField(indexOfField(m, 7)).toBuilder().setName("op").clearJsonName()).build()));

        assertThat(diff.breaking()).isTrue();
        assertThat(diff.changed()).anyMatch(l -> l.startsWith("field dlap.log.v1.DlapLogEvent#7 |"));
    }

    @Test
    void retypedFieldIsBreaking() throws Exception {
        ProtoContractSnapshot.Diff diff = diffAgainst(fp -> mutateMessage(fp, "DlapLogEvent",
                m -> m.toBuilder().setField(indexOfField(m, 7),
                        m.getField(indexOfField(m, 7)).toBuilder()
                                .setType(FieldDescriptorProto.Type.TYPE_BYTES)).build()));

        assertThat(diff.breaking()).isTrue();
        assertThat(diff.changed()).anyMatch(l -> l.contains("#7 |") && l.endsWith("type=bytes"));
    }

    @Test
    void removedOrRenumberedEnumValueIsBreaking() throws Exception {
        ProtoContractSnapshot.Diff removed = diffAgainst(fp -> mutateEnum(fp, "ChangeType",
                e -> e.toBuilder().clearValue().addAllValue(e.getValueList().stream()
                        .filter(v -> v.getNumber() != 3).toList()).build()));
        ProtoContractSnapshot.Diff renumbered = diffAgainst(fp -> mutateEnum(fp, "ResultStatus",
                e -> e.toBuilder().setValue(2, e.getValue(2).toBuilder().setNumber(42)).build()));

        assertThat(removed.removed()).contains("value dlap.log.v1.ChangeType#3 | name=DELETE");
        assertThat(renumbered.breaking()).isTrue();
        assertThat(renumbered.removed()).anyMatch(l -> l.startsWith("value dlap.log.v1.ResultStatus#2 |"));
    }

    @Test
    void removedMessageIsBreaking() throws Exception {
        ProtoContractSnapshot.Diff diff = diffAgainst(fp -> fp.toBuilder().clearMessageType()
                .addAllMessageType(fp.getMessageTypeList().stream()
                        .filter(m -> !m.getName().equals("RequestMetadata")).toList())
                .setMessageType(0, fp.getMessageType(0).toBuilder().clearField()
                        .addAllField(fp.getMessageType(0).getFieldList().stream()
                                .filter(f -> !f.getTypeName().endsWith("RequestMetadata")).toList()))
                .build());

        assertThat(diff.breaking()).isTrue();
        assertThat(diff.removed()).contains("message dlap.log.v1.RequestMetadata |");
    }

    @Test
    void addedFieldIsAdditiveOnly() throws Exception {
        ProtoContractSnapshot.Diff diff = diffAgainst(fp -> mutateMessage(fp, "DlapLogEvent",
                m -> m.toBuilder().addField(FieldDescriptorProto.newBuilder()
                        .setName("added_for_test").setNumber(900)
                        .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
                        .setType(FieldDescriptorProto.Type.TYPE_STRING)).build()));

        assertThat(diff.breaking()).isFalse();
        assertThat(diff.added()).containsExactly(
                "field dlap.log.v1.DlapLogEvent#900 | name=added_for_test json=addedForTest"
                        + " label=optional type=string");
    }

    private static SortedMap<String, String> baseline() throws IOException {
        try (InputStream in = LogEventProtoContractTest.class.getResourceAsStream(BASELINE)) {
            assertThat(in).as("baseline resource " + BASELINE).isNotNull();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return ProtoContractSnapshot.parse(Arrays.asList(text.split("\\R")));
        }
    }

    /** Diffs the compiled descriptor (as baseline) against a mutated copy of it. */
    private static ProtoContractSnapshot.Diff diffAgainst(UnaryOperator<FileDescriptorProto> mutation)
            throws DescriptorValidationException {
        FileDescriptor original = LogEventProto.getDescriptor();
        FileDescriptor mutated = FileDescriptor.buildFrom(mutation.apply(original.toProto()),
                original.getDependencies().toArray(new FileDescriptor[0]));
        return ProtoContractSnapshot.diff(ProtoContractSnapshot.of(original), ProtoContractSnapshot.of(mutated));
    }

    private static FileDescriptorProto mutateMessage(FileDescriptorProto fp, String name,
                                                     UnaryOperator<DescriptorProto> change) {
        List<DescriptorProto> messages = fp.getMessageTypeList();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).getName().equals(name)) {
                return fp.toBuilder().setMessageType(i, change.apply(messages.get(i))).build();
            }
        }
        throw new IllegalArgumentException("no message " + name);
    }

    private static FileDescriptorProto mutateEnum(FileDescriptorProto fp, String name,
                                                  UnaryOperator<EnumDescriptorProto> change) {
        List<EnumDescriptorProto> enums = fp.getEnumTypeList();
        for (int i = 0; i < enums.size(); i++) {
            if (enums.get(i).getName().equals(name)) {
                return fp.toBuilder().setEnumType(i, change.apply(enums.get(i))).build();
            }
        }
        throw new IllegalArgumentException("no enum " + name);
    }

    private static int indexOfField(DescriptorProto message, int number) {
        for (int i = 0; i < message.getFieldCount(); i++) {
            if (message.getField(i).getNumber() == number) {
                return i;
            }
        }
        throw new IllegalArgumentException("no field #" + number + " in " + message.getName());
    }
}
