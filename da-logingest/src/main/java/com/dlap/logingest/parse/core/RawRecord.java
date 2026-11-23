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

package com.dlap.logingest.parse.core;

import com.dlap.common.logevent.LogEventIds;
import com.dlap.proto.log.v1.QuarantineReason;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * One provider record as a transport delivered it, before parsing (design B1 "RawRecord").
 *
 * <p>A transport builds one per record: an element of a CloudTrail file's {@code Records} array, a
 * line of a JSON-lines blob, one LogEntry from an API page. It carries the exact bytes (hashed for
 * {@code raw_sha256} and quarantine ids) and decodes them on first use, so an undecodable record still
 * reaches the registry and is quarantined as {@code INVALID_ENCODING} or {@code INVALID_JSON}
 * instead of being lost inside a transport.
 *
 * <p>Instances are immutable and thread safe.
 */
public final class RawRecord {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final char BYTE_ORDER_MARK = '﻿';

    private final String bindingId;
    private final EnvelopeKey envelopeKey;
    private final RawRef ref;
    private final IngestMode mode;
    private final Instant fetchedAt;
    private final Instant observedTime;
    private final byte[] bytes;
    private volatile Decoded decoded;

    private RawRecord(Builder b, byte[] bytes, Decoded decoded) {
        this.bindingId = FormatNames.requireBindingId(b.bindingId);
        this.envelopeKey = new EnvelopeKey(b.sourceKind, b.envelope);
        this.ref = Objects.requireNonNull(b.ref, "ref");
        this.mode = b.mode == null ? IngestMode.STREAM : b.mode;
        this.fetchedAt = Objects.requireNonNull(b.fetchedAt, "fetchedAt");
        this.observedTime = b.observedTime;
        this.bytes = bytes;
        this.decoded = decoded;
    }

    /**
     * The decoded body, or why it could not be decoded.
     *
     * @param body    the JSON tree; {@code null} on failure
     * @param failure {@code INVALID_ENCODING} or {@code INVALID_JSON}; {@code null} on success
     * @param detail  what failed; never quotes the record
     */
    public record Decoded(JsonNode body, QuarantineReason failure, String detail) {

        /**
         * Whether the bytes decoded to JSON.
         *
         * @return true on success
         */
        public boolean ok() {
            return failure == null;
        }
    }

    /**
     * Starts a record.
     *
     * @return a builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the binding the record came through.
     *
     * @return the binding id
     */
    public String bindingId() {
        return bindingId;
    }

    /**
     * Returns the binding's source kind.
     *
     * @return upper snake case source kind
     */
    public String sourceKind() {
        return envelopeKey.sourceKind();
    }

    /**
     * Returns the envelope the transport delivered the record in.
     *
     * @return lower kebab case envelope id
     */
    public String envelope() {
        return envelopeKey.envelope();
    }

    /**
     * Returns source kind and envelope.
     *
     * @return the envelope key
     */
    public EnvelopeKey envelopeKey() {
        return envelopeKey;
    }

    /**
     * Returns where the record lives at its source.
     *
     * @return the reference
     */
    public RawRef ref() {
        return ref;
    }

    /**
     * Returns how the record entered DLAP.
     *
     * @return the mode; {@link IngestMode#STREAM} unless set
     */
    public IngestMode mode() {
        return mode;
    }

    /**
     * Returns when the transport fetched the record. Operational time only, never business time.
     *
     * @return the fetch instant
     */
    public Instant fetchedAt() {
        return fetchedAt;
    }

    /**
     * Returns the provider's receipt or delivery time when the transport knows it (the
     * {@code LastModified} of a CloudTrail file), for {@code observed_time}.
     *
     * @return the time, or empty
     */
    public Optional<Instant> observedTime() {
        return Optional.ofNullable(observedTime);
    }

    /**
     * Returns a copy of the exact record bytes.
     *
     * @return the bytes
     */
    public byte[] bytes() {
        return bytes.clone();
    }

    /**
     * Returns the size of the record bytes.
     *
     * @return the size in bytes
     */
    public long sizeBytes() {
        return bytes.length;
    }

    /**
     * Returns the lowercase hex SHA-256 of the record bytes ({@code LogQuarantineRecord.raw_sha256}).
     *
     * @return 64 hex characters
     */
    public String sha256() {
        return LogEventIds.sha256Hex(bytes);
    }

    /**
     * Returns the bytes as text, replacing invalid UTF-8, for redacted excerpts only.
     *
     * @return the lenient text
     */
    public String lenientText() {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Returns the decoded body or the decoding failure. Bytes are decoded on the first call, so the
     * registry can refuse an oversize record before spending time on it.
     *
     * @return the decode result
     */
    public Decoded decode() {
        Decoded d = decoded;
        if (d == null) {
            d = decodeBytes(bytes);
            decoded = d;
        }
        return d;
    }

    /**
     * Returns the decoded JSON body. The registry only hands records that decoded to a parser, so
     * parsers may call this freely. The tree is shared and must not be mutated.
     *
     * @return the body
     * @throws IllegalStateException when the record did not decode
     */
    public JsonNode body() {
        Decoded d = decode();
        if (!d.ok()) {
            throw new IllegalStateException("record did not decode: " + d.failure());
        }
        return d.body();
    }

    @Override
    public String toString() {
        return "RawRecord[" + envelopeKey + " binding=" + bindingId + " ref=" + ref.uri() + "#" + ref.recordIndex()
                + " bytes=" + bytes.length + "]";
    }

    private static Decoded decodeBytes(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new Decoded(null, QuarantineReason.QUARANTINE_REASON_INVALID_ENCODING, "record is not valid UTF-8");
        }
        if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            text = text.substring(1);
        }
        if (text.isBlank()) {
            return new Decoded(null, QuarantineReason.QUARANTINE_REASON_INVALID_JSON, "record is empty");
        }
        try {
            return new Decoded(JSON.readTree(text), null, "");
        } catch (JsonProcessingException e) {
            long line = e.getLocation() == null ? -1 : e.getLocation().getLineNr();
            long column = e.getLocation() == null ? -1 : e.getLocation().getColumnNr();
            return new Decoded(null, QuarantineReason.QUARANTINE_REASON_INVALID_JSON,
                    "record is not one JSON value (line " + line + ", column " + column + ")");
        }
    }

    /** Builds a {@link RawRecord}. Exactly one of the payload setters must be called. */
    public static final class Builder {
        private String bindingId;
        private String sourceKind;
        private String envelope;
        private RawRef ref;
        private IngestMode mode;
        private Instant fetchedAt;
        private Instant observedTime;
        private byte[] bytes;
        private JsonNode json;

        private Builder() {
        }

        /**
         * Sets the binding.
         *
         * @param value the binding id
         * @return this builder
         */
        public Builder bindingId(String value) {
            this.bindingId = value;
            return this;
        }

        /**
         * Sets the source kind.
         *
         * @param value upper snake case
         * @return this builder
         */
        public Builder sourceKind(String value) {
            this.sourceKind = value;
            return this;
        }

        /**
         * Sets the envelope.
         *
         * @param value lower kebab case
         * @return this builder
         */
        public Builder envelope(String value) {
            this.envelope = value;
            return this;
        }

        /**
         * Sets the reference.
         *
         * @param value where the record lives
         * @return this builder
         */
        public Builder ref(RawRef value) {
            this.ref = value;
            return this;
        }

        /**
         * Sets the ingest mode.
         *
         * @param value the mode; defaults to {@link IngestMode#STREAM}
         * @return this builder
         */
        public Builder mode(IngestMode value) {
            this.mode = value;
            return this;
        }

        /**
         * Sets the fetch time.
         *
         * @param value when the transport fetched the record
         * @return this builder
         */
        public Builder fetchedAt(Instant value) {
            this.fetchedAt = value;
            return this;
        }

        /**
         * Sets the provider receipt or delivery time.
         *
         * @param value the time; may be {@code null}
         * @return this builder
         */
        public Builder observedTime(Instant value) {
            this.observedTime = value;
            return this;
        }

        /**
         * Sets the payload from the exact delivered bytes.
         *
         * @param value the bytes; copied
         * @return this builder
         */
        public Builder bytes(byte[] value) {
            this.bytes = value == null ? null : Arrays.copyOf(value, value.length);
            this.json = null;
            return this;
        }

        /**
         * Sets the payload from delivered text.
         *
         * @param value the text; encoded as UTF-8
         * @return this builder
         */
        public Builder text(String value) {
            return bytes(value == null ? null : value.getBytes(StandardCharsets.UTF_8));
        }

        /**
         * Sets the payload from a JSON tree a transport already split out of a container. The
         * record bytes are its compact serialization, so its hash is deterministic.
         *
         * @param value the record; copied
         * @return this builder
         */
        public Builder json(JsonNode value) {
            this.json = value == null ? null : value.deepCopy();
            this.bytes = null;
            return this;
        }

        /**
         * Builds the record.
         *
         * @return the record
         * @throws IllegalArgumentException when an identifier is malformed or no payload is set
         */
        public RawRecord build() {
            if (json != null) {
                byte[] serialized;
                try {
                    serialized = JSON.writeValueAsBytes(json);
                } catch (JsonProcessingException e) {
                    throw new IllegalArgumentException("record JSON cannot be serialized", e);
                }
                return new RawRecord(this, serialized, new Decoded(json, null, ""));
            }
            if (bytes == null) {
                throw new IllegalArgumentException("a raw record needs bytes, text or json");
            }
            return new RawRecord(this, bytes, null);
        }
    }
}
