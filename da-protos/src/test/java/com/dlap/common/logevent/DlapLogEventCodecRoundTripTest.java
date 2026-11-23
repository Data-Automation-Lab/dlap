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

import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.ParseStatus;
import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.Principal;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.RequestMetadata;
import com.dlap.proto.log.v1.ResourceRelation;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ListValue;
import com.google.protobuf.ProtocolMessageEnum;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Seeded generator test (decision D7: no jqwik). Fixed seeds keep failures
 * reproducible; each failure message names the seed and the case index.
 *
 * <p>Properties, for every generated event:
 * <ol>
 *   <li>decode(encode(e)) equals e, and the strict JsonFormat parser agrees;</li>
 *   <li>the legacy double encoding of encode(e) decodes to e;</li>
 *   <li>encode is stable: encode(decode(encode(e))) equals encode(e);</li>
 *   <li>unknown fields injected anywhere outside maps and rawEvent leave the
 *       decoded event unchanged and are reported as warnings.</li>
 * </ol>
 */
class DlapLogEventCodecRoundTripTest {

    private static final long[] SEEDS = {20261018L, 7L, 0xD1A9L};
    private static final int CASES_PER_SEED = 300;
    private static final String ALPHABET =
            "abcXYZ019 _-./:|#@\"\\éß中文😀\n\t";

    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();

    @Test
    void generatedEventsRoundTripInEveryEncoding() throws Exception {
        for (long seed : SEEDS) {
            Random random = new Random(seed);
            for (int i = 0; i < CASES_PER_SEED; i++) {
                DlapLogEvent event = randomEvent(random);
                String label = "seed " + seed + " case " + i;

                String encoded = codec.encode(event);
                DlapLogEvent.Builder strict = DlapLogEvent.newBuilder();
                JsonFormat.parser().merge(encoded, strict);

                assertThat(strict.build()).as(label + " strict JsonFormat").isEqualTo(event);
                assertThat(codec.decode(encoded)).as(label + " decode").isEqualTo(event);
                assertThat(codec.decode(ProducerShapes.doubleEncoded(encoded))).as(label + " double encoding")
                        .isEqualTo(event);
                assertThat(codec.encode(codec.decode(encoded))).as(label + " stable encoding").isEqualTo(encoded);
            }
        }
    }

    @Test
    void injectedUnknownFieldsNeverChangeTheDecodedEvent() throws Exception {
        for (long seed : SEEDS) {
            Random random = new Random(seed ^ 0x5EEDL);
            for (int i = 0; i < CASES_PER_SEED; i++) {
                DlapLogEvent event = randomEvent(random);
                ObjectNode tree = (ObjectNode) ProducerShapes.MAPPER.readTree(codec.encode(event));
                int injected = inject(tree, random);

                DecodedLogEvent decoded = codec.decodeDetailed(tree);

                assertThat(decoded.event()).as("seed " + seed + " case " + i).isEqualTo(event);
                assertThat(decoded.warnings().stream().filter(w -> w.startsWith("dropped unknown field")))
                        .as("seed " + seed + " case " + i).hasSize(injected);
            }
        }
    }

    /** Adds 1 to 3 unknown fields to random message objects; skips maps and rawEvent. */
    private static int inject(ObjectNode root, Random random) {
        List<ObjectNode> targets = new ArrayList<>();
        collectMessageObjects(root, targets);
        int count = 1 + random.nextInt(3);
        for (int k = 0; k < count; k++) {
            ObjectNode target = targets.get(random.nextInt(targets.size()));
            target.put("zzUnknown" + k + "x" + random.nextInt(1000), randomString(random));
        }
        return count;
    }

    private static void collectMessageObjects(ObjectNode node, List<ObjectNode> out) {
        out.add(node);
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (name.equals("rawEvent") || name.equals("labels") || name.equals("technicalMetadata")) {
                continue;
            }
            JsonNode child = node.get(name);
            if (child.isObject()) {
                collectMessageObjects((ObjectNode) child, out);
            } else if (child.isArray()) {
                for (JsonNode element : child) {
                    if (element.isObject()) {
                        collectMessageObjects((ObjectNode) element, out);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ generator

    static DlapLogEvent randomEvent(Random r) {
        DlapLogEvent.Builder b = DlapLogEvent.newBuilder().setSchemaVersion("v1");
        if (r.nextBoolean()) {
            b.setEventId(LogEventIds.sha256Hex(randomString(r)));
        }
        if (r.nextInt(4) > 0) {
            b.setEventTime(randomTimestamp(r));
        }
        if (r.nextBoolean()) {
            b.setIngestTime(randomTimestamp(r));
        }
        if (r.nextBoolean()) {
            b.setObservedTime(randomTimestamp(r));
        }
        b.setSourceProvider(pick(r, SourceProvider.values()));
        b.setChangeType(pick(r, ChangeType.values()));
        b.setResultStatus(pick(r, ResultStatus.values()));
        b.setEventCategory(pick(r, EventCategory.values()));
        b.setOrigin(pick(r, Origin.values()));
        b.setParseStatus(pick(r, ParseStatus.values()));
        b.setSourceService(maybeString(r)).setOperation(maybeString(r)).setSourceFormat(maybeString(r))
                .setSourceSchemaVersion(maybeString(r)).setParserVersion(maybeString(r))
                .setBindingId(maybeString(r)).setNativeEventId(maybeString(r))
                .setSupersedesEventId(maybeString(r)).setSharedEventId(maybeString(r));
        if (r.nextBoolean()) {
            b.setPrincipal(Principal.newBuilder().setType(pick(r, PrincipalType.values()))
                    .setId(maybeString(r)).setDisplayName(maybeString(r)).setAccountId(maybeString(r)));
        }
        if (r.nextInt(4) > 0) {
            b.setTargetResource(randomTarget(r, false));
        }
        for (int i = r.nextInt(3); i > 0; i--) {
            b.addRelatedResources(randomTarget(r, true));
        }
        if (r.nextBoolean()) {
            b.setRequestMetadata(RequestMetadata.newBuilder().setCallerIp(maybeString(r))
                    .setUserAgent(maybeString(r)).setRequestId(maybeString(r)).setTraceId(maybeString(r)));
        }
        if (r.nextBoolean()) {
            b.setRawEvent(randomStruct(r, 3));
        }
        for (int i = r.nextInt(3); i > 0; i--) {
            b.addParseWarnings(pick(r, ParseWarning.values()));
        }
        return b.build();
    }

    private static TargetResource randomTarget(Random r, boolean related) {
        TargetResource.Builder t = TargetResource.newBuilder()
                .setKind(maybeString(r)).setId(maybeString(r)).setName(maybeString(r))
                .setProject(maybeString(r)).setRegion(maybeString(r)).setResourcePath(maybeString(r))
                .setCanonicalUri(maybeString(r));
        if (r.nextBoolean()) {
            t.setCreatedTime(randomTimestamp(r));
        }
        if (r.nextBoolean()) {
            t.setSizeBytes(r.nextInt(3) == 0 ? 0L : r.nextLong());
        }
        for (int i = r.nextInt(3); i > 0; i--) {
            t.putLabels(randomString(r), randomString(r));
        }
        for (int i = r.nextInt(3); i > 0; i--) {
            t.putTechnicalMetadata(randomString(r), randomString(r));
        }
        if (related) {
            t.setRelation(pick(r, ResourceRelation.values()));
        }
        return t.build();
    }

    private static Struct randomStruct(Random r, int depth) {
        Struct.Builder s = Struct.newBuilder();
        for (int i = r.nextInt(4); i > 0; i--) {
            s.putFields(randomString(r), randomValue(r, depth - 1));
        }
        return s.build();
    }

    private static Value randomValue(Random r, int depth) {
        int kind = depth <= 0 ? r.nextInt(4) : r.nextInt(6);
        return switch (kind) {
            case 0 -> Value.newBuilder().setStringValue(randomString(r)).build();
            // Struct numbers are doubles on the wire; keep them finite.
            case 1 -> Value.newBuilder().setNumberValue(r.nextBoolean() ? r.nextInt(100_000) : r.nextGaussian() * 1e6)
                    .build();
            case 2 -> Value.newBuilder().setBoolValue(r.nextBoolean()).build();
            case 3 -> Value.newBuilder().setNullValueValue(0).build();
            case 4 -> Value.newBuilder().setStructValue(randomStruct(r, depth)).build();
            default -> {
                ListValue.Builder l = ListValue.newBuilder();
                for (int i = r.nextInt(3); i > 0; i--) {
                    l.addValues(randomValue(r, depth - 1));
                }
                yield Value.newBuilder().setListValue(l).build();
            }
        };
    }

    private static Timestamp randomTimestamp(Random r) {
        // Years 1970 to 2100; nanos at every precision JSON can carry.
        long seconds = (long) (r.nextDouble() * 4_102_444_800L);
        int nanos = switch (r.nextInt(4)) {
            case 0 -> 0;
            case 1 -> r.nextInt(1000) * 1_000_000;
            case 2 -> r.nextInt(1_000_000) * 1000;
            default -> r.nextInt(1_000_000_000);
        };
        return Timestamp.newBuilder().setSeconds(seconds).setNanos(nanos).build();
    }

    private static String maybeString(Random r) {
        return r.nextInt(3) == 0 ? "" : randomString(r);
    }

    static String randomString(Random r) {
        int length = 1 + r.nextInt(12);
        StringBuilder sb = new StringBuilder(length);
        while (sb.length() < length) {
            int i = r.nextInt(ALPHABET.length());
            char c = ALPHABET.charAt(i);
            if (Character.isHighSurrogate(c)) {
                // Keep surrogate pairs whole.
                sb.append(c).append(ALPHABET.charAt(i + 1));
            } else if (!Character.isLowSurrogate(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Picks a defined value, never the proto-generated UNRECOGNIZED. */
    private static <E extends Enum<E> & ProtocolMessageEnum> E pick(Random r, E[] values) {
        E v;
        do {
            v = values[r.nextInt(values.length)];
        } while (v.name().equals("UNRECOGNIZED"));
        return v;
    }
}
