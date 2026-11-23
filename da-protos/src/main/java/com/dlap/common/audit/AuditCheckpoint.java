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

package com.dlap.common.audit;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A checkpoint: the head of one stream at one moment, as the bytes the checkpoint key signs
 * (docs/specs/audit-log.md, section "Checkpoints").
 *
 * <p>The signed payload is the canonical JSON of the six members below. {@link #parse} accepts
 * only that exact form, so a payload has one meaning and a verifier never re-serializes it.
 *
 * @param streamId  the stream
 * @param seq       the position of the entry the checkpoint covers, at least 1
 * @param entryHash that entry's hash
 * @param signedAt  when the checkpoint was signed (signer clock, microsecond precision)
 * @param keyId     the id of the signing key ({@link CheckpointSignatures#keyId})
 */
public record AuditCheckpoint(String streamId, long seq, String entryHash, Instant signedAt, String keyId) {

    /** The {@code format} member of every checkpoint payload. */
    public static final String FORMAT = "dlap-audit-checkpoint/1";

    private static final Pattern KEY_ID = Pattern.compile("^[0-9a-f]{16}$");
    private static final Set<String> MEMBERS = Set.of("entryHash", "format", "keyId", "seq", "signedAt", "streamId");
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Validates the checkpoint.
     *
     * @param streamId  the stream
     * @param seq       the position
     * @param entryHash the hash
     * @param signedAt  the signing time
     * @param keyId     the key id
     */
    public AuditCheckpoint {
        AuditStreams.requireValid(streamId);
        if (seq < 1) {
            throw new IllegalArgumentException("checkpoint seq must be at least 1");
        }
        if (!AuditChain.isHash(entryHash)) {
            throw new IllegalArgumentException("checkpoint entryHash must be 64 lower-case hex characters");
        }
        Objects.requireNonNull(signedAt, "signedAt");
        signedAt = Instant.parse(AuditChain.formatInstant(signedAt));
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("checkpoint keyId must be 16 lower-case hex characters");
        }
    }

    /**
     * The bytes to sign, as text: canonical JSON, printable ASCII.
     *
     * @return the payload
     */
    public String payload() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("streamId", streamId);
        m.put("seq", seq);
        m.put("entryHash", entryHash);
        m.put("signedAt", AuditChain.formatInstant(signedAt));
        m.put("keyId", keyId);
        return CanonicalJson.write(m);
    }

    /**
     * Reads a payload. Only the exact canonical text that {@link #payload()} produces is accepted.
     *
     * @param payload the payload text
     * @return the checkpoint
     * @throws IllegalArgumentException when the text is not a canonical checkpoint payload
     */
    public static AuditCheckpoint parse(String payload) {
        if (!CanonicalJson.isPrintableAscii(payload)) {
            throw new IllegalArgumentException("checkpoint payload is not printable ASCII");
        }
        JsonNode node;
        try {
            node = JSON.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("checkpoint payload is not JSON", e);
        }
        if (node == null || !node.isObject() || node.size() != MEMBERS.size()) {
            throw new IllegalArgumentException("checkpoint payload must be an object with " + MEMBERS);
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext();) {
            if (!MEMBERS.contains(names.next())) {
                throw new IllegalArgumentException("checkpoint payload must be an object with " + MEMBERS);
            }
        }
        if (!FORMAT.equals(node.path("format").asText(null)) || !node.path("seq").isIntegralNumber()) {
            throw new IllegalArgumentException("checkpoint payload has an unknown format or a bad seq");
        }
        AuditCheckpoint checkpoint;
        try {
            checkpoint = new AuditCheckpoint(node.path("streamId").asText(null), node.path("seq").longValue(),
                    node.path("entryHash").asText(null), Instant.parse(node.path("signedAt").asText("")),
                    node.path("keyId").asText(null));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("checkpoint payload has a bad signedAt", e);
        }
        if (!checkpoint.payload().equals(payload)) {
            throw new IllegalArgumentException("checkpoint payload is not in canonical form");
        }
        return checkpoint;
    }

    /**
     * The object name under which the payload is exported, below the configured prefix:
     * {@code <streamId>/<seq, 20 digits>.json}. The signature goes next to it with {@code .sig}.
     *
     * @return the relative name
     */
    public String objectName() {
        return streamId + "/" + String.format("%020d", seq) + ".json";
    }
}
