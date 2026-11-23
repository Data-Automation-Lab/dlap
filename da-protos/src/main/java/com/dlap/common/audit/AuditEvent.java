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

import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One event to append to an audit stream (docs/specs/audit-log.md, section "Record").
 *
 * <p>The writer adds the stream position, the hash chain fields and {@code recordedAt}; this
 * object holds what the caller states. {@code occurredAt} is the business time of the event
 * (work-plan rule R6), never the time the record is written.
 *
 * @param eventType      dotted lower-case name, for example {@code retire.delete.executed}
 * @param occurredAt     when the event happened (event time)
 * @param actor          who caused it
 * @param target         what it acted on; {@code null} when nothing in particular
 * @param outcome        what the record states
 * @param correlationId  links the records of one action (for example the approval id);
 *                       {@code null} when there is none
 * @param idempotencyKey makes a retried append return the first entry instead of a duplicate;
 *                       {@code null} when the caller does not retry
 * @param details        further facts: string keys, values that {@link CanonicalJson} accepts,
 *                       except {@code null}; never secrets or personal data
 */
public record AuditEvent(String eventType, Instant occurredAt, AuditActor actor, AuditTarget target,
        AuditOutcome outcome, String correlationId, String idempotencyKey, Map<String, Object> details) {

    /** Maximum length of {@link #eventType()}. */
    public static final int MAX_EVENT_TYPE_LENGTH = 128;

    /** Maximum length of {@link #correlationId()} and {@link #idempotencyKey()}. */
    public static final int MAX_KEY_LENGTH = 128;

    /** Maximum length of one string value inside {@link #details()}. */
    public static final int MAX_DETAIL_STRING_LENGTH = 4096;

    /** Maximum nesting of maps and lists inside {@link #details()}. */
    public static final int MAX_DETAIL_DEPTH = 4;

    private static final Pattern EVENT_TYPE = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){1,7}$");
    private static final Pattern KEY = Pattern.compile("^[!-~]{1,128}$");
    private static final Pattern DETAIL_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

    /**
     * Validates the event and freezes {@code details}.
     *
     * @param eventType      the event type
     * @param occurredAt     the event time
     * @param actor          the actor
     * @param target         the target, or {@code null}
     * @param outcome        the outcome
     * @param correlationId  the correlation id, or {@code null}
     * @param idempotencyKey the idempotency key, or {@code null}
     * @param details        the details, or {@code null} for none
     */
    public AuditEvent {
        Objects.requireNonNull(eventType, "eventType");
        if (eventType.length() > MAX_EVENT_TYPE_LENGTH || !EVENT_TYPE.matcher(eventType).matches()) {
            throw new IllegalArgumentException("eventType must match " + EVENT_TYPE.pattern()
                    + " and have at most " + MAX_EVENT_TYPE_LENGTH + " characters");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(outcome, "outcome");
        requireKey("correlationId", correlationId);
        requireKey("idempotencyKey", idempotencyKey);
        Map<String, Object> checked = details == null ? Map.of() : details;
        validateMap("details", checked, 1);
        details = CanonicalJson.copyMap(checked);
    }

    /**
     * Starts a builder.
     *
     * @param eventType the event type
     * @return the builder
     */
    public static Builder builder(String eventType) {
        return new Builder(eventType);
    }

    static void requireText(String field, String value, int maxLength) {
        if (value == null || value.isEmpty() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must have 1 to " + maxLength + " characters");
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException(field + " must not contain control characters");
            }
        }
    }

    private static void requireKey(String field, String value) {
        if (value != null && !KEY.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be 1 to " + MAX_KEY_LENGTH
                    + " printable ASCII characters without spaces");
        }
    }

    private static void validateMap(String path, Map<?, ?> map, int depth) {
        if (depth > MAX_DETAIL_DEPTH) {
            throw new IllegalArgumentException(path + " is nested deeper than " + MAX_DETAIL_DEPTH);
        }
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key) || !DETAIL_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException(path + " keys must match " + DETAIL_KEY.pattern());
            }
            validateValue(path + "." + key, e.getValue(), depth);
        }
    }

    private static void validateValue(String path, Object value, int depth) {
        if (value == null) {
            throw new IllegalArgumentException(path + " is null; leave the key out instead");
        }
        if (value instanceof String s) {
            if (s.length() > MAX_DETAIL_STRING_LENGTH) {
                throw new IllegalArgumentException(path + " is longer than " + MAX_DETAIL_STRING_LENGTH);
            }
        } else if (value instanceof Map<?, ?> nested) {
            validateMap(path, nested, depth + 1);
        } else if (value instanceof List<?> list) {
            if (depth + 1 > MAX_DETAIL_DEPTH) {
                throw new IllegalArgumentException(path + " is nested deeper than " + MAX_DETAIL_DEPTH);
            }
            for (int i = 0; i < list.size(); i++) {
                validateValue(path + "[" + i + "]", list.get(i), depth + 1);
            }
        } else if (value instanceof BigInteger big) {
            if (big.bitLength() > 63) {
                throw new IllegalArgumentException(path + " is outside the long range");
            }
        } else if (!(value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte)) {
            throw new IllegalArgumentException(path + " has type " + value.getClass().getSimpleName()
                    + "; allowed are string, boolean, integer, map and list");
        }
    }

    /** Builds an {@link AuditEvent}; {@link #build()} validates. */
    public static final class Builder {
        private final String eventType;
        private Instant occurredAt;
        private AuditActor actor;
        private AuditTarget target;
        private AuditOutcome outcome;
        private String correlationId;
        private String idempotencyKey;
        private final Map<String, Object> details = new LinkedHashMap<>();

        private Builder(String eventType) {
            this.eventType = eventType;
        }

        /**
         * Sets the event time.
         *
         * @param value when the event happened
         * @return this builder
         */
        public Builder occurredAt(Instant value) {
            this.occurredAt = value;
            return this;
        }

        /**
         * Sets the actor.
         *
         * @param value who caused the event
         * @return this builder
         */
        public Builder actor(AuditActor value) {
            this.actor = value;
            return this;
        }

        /**
         * Sets the target.
         *
         * @param value what the event acted on
         * @return this builder
         */
        public Builder target(AuditTarget value) {
            this.target = value;
            return this;
        }

        /**
         * Sets the outcome.
         *
         * @param value what the record states
         * @return this builder
         */
        public Builder outcome(AuditOutcome value) {
            this.outcome = value;
            return this;
        }

        /**
         * Sets the correlation id.
         *
         * @param value the id that links the records of one action
         * @return this builder
         */
        public Builder correlationId(String value) {
            this.correlationId = value;
            return this;
        }

        /**
         * Sets the idempotency key.
         *
         * @param value the key that makes a retried append return the first entry
         * @return this builder
         */
        public Builder idempotencyKey(String value) {
            this.idempotencyKey = value;
            return this;
        }

        /**
         * Adds one detail.
         *
         * @param key   the key
         * @param value the value
         * @return this builder
         */
        public Builder detail(String key, Object value) {
            this.details.put(key, value);
            return this;
        }

        /**
         * Validates and builds the event.
         *
         * @return the event
         */
        public AuditEvent build() {
            return new AuditEvent(eventType, occurredAt, actor, target, outcome, correlationId,
                    idempotencyKey, details);
        }
    }
}
