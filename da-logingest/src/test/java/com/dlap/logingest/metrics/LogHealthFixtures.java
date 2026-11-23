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

package com.dlap.logingest.metrics;

import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.parse.core.VersionDetector;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedDetector;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedFormat;
import com.dlap.logingest.parse.dlapnormalized.DlapNormalizedParser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Shared test inputs of the parser-health tests: a registry of the DLAP-normalized family (the one
 * family every build ships), records that parse, warn, skip and quarantine, and a clock a test moves.
 */
public final class LogHealthFixtures {

    /** The source kind of the AWS normalized family. */
    public static final String KIND = "DLAP_NORMALIZED_AWS";

    /** The envelope of the normalized family. */
    public static final String ENVELOPE = "ingest-dto";

    /** The binding the helpers use unless told otherwise. */
    public static final String BINDING = "dlap-rest-aws";

    /** The instant the fixed clock starts at. */
    public static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");

    private LogHealthFixtures() {
    }

    /**
     * A registry of the normalized family.
     *
     * @return the registry
     */
    public static DefaultParserRegistry registry() {
        return new DefaultParserRegistry(
                DlapNormalizedFormat.providers().stream().map(DlapNormalizedParser::new).toList(),
                DlapNormalizedFormat.providers().stream().map(p -> (VersionDetector) new DlapNormalizedDetector(p))
                        .toList());
    }

    /**
     * A normalized AWS record as JSON text.
     *
     * @param operation  the provider operation
     * @param changeType the change type word, or {@code null} to omit
     * @param result     the result word, or {@code null} to omit
     * @param requestId  the request id, which becomes the native key
     * @param eventTime  the event time
     * @param extra      extra JSON members with a leading comma, or empty
     * @return the JSON text
     */
    public static String dto(String operation, String changeType, String result, String requestId, Instant eventTime,
                             String extra) {
        StringBuilder sb = new StringBuilder("{\"sourceProvider\":\"AWS\",\"sourceService\":\"s3.amazonaws.com\"")
                .append(",\"operation\":\"").append(operation).append('"')
                .append(",\"eventTime\":\"").append(eventTime).append('"')
                .append(",\"targetResourceId\":\"s3://health-bucket/").append(requestId).append('"')
                .append(",\"requestId\":\"").append(requestId).append('"');
        if (changeType != null) {
            sb.append(",\"changeType\":\"").append(changeType).append('"');
        }
        if (result != null) {
            sb.append(",\"resultStatus\":\"").append(result).append('"');
        }
        return sb.append(extra == null ? "" : extra).append('}').toString();
    }

    /**
     * A record that parses (with the heuristic warning of the normalized family).
     *
     * @param requestId the request id
     * @return the JSON text
     */
    public static String write(String requestId) {
        return dto("PutObject", "CREATE", "SUCCESS", requestId, T0.minusSeconds(30),
                ",\"principalId\":\"AIDAEXAMPLE\",\"targetProject\":\"123456789012\",\"targetRegion\":\"us-east-1\"");
    }

    /**
     * A data-read record.
     *
     * @param requestId the request id
     * @return the JSON text
     */
    public static String read(String requestId) {
        return dto("GetObject", "ACCESS", "SUCCESS", requestId, T0.minusSeconds(30), ",\"principalId\":\"AIDAEXAMPLE\"");
    }

    /**
     * A record that is quarantined (no result), with a pass-through event.
     *
     * @param requestId the request id
     * @return the JSON text
     */
    public static String broken(String requestId) {
        return dto("GetObject", "ACCESS", null, requestId, T0.minusSeconds(30), "");
    }

    /**
     * Builds the raw record of JSON text for a binding of the AWS normalized family.
     *
     * @param bindingId the binding
     * @param text      the record text
     * @return the record
     */
    public static RawRecord record(String bindingId, String text) {
        return RawRecord.builder().bindingId(bindingId).sourceKind(KIND).envelope(ENVELOPE)
                .ref(RawRef.of("REST", "rest:/api/v1/logingest/events", -1)).mode(IngestMode.STREAM)
                .fetchedAt(T0).text(text).build();
    }

    /**
     * Parses text through the family registry.
     *
     * @param registry  the registry
     * @param bindingId the binding
     * @param text      the record text
     * @return the outcome
     */
    public static ParseOutcome parse(DefaultParserRegistry registry, String bindingId, String text) {
        return registry.parse(record(bindingId, text), BindingConfig.auto(bindingId, KIND));
    }

    /** A clock a test sets and advances. */
    public static final class MutableClock extends Clock {
        private volatile Instant now;

        /**
         * Starts the clock.
         *
         * @param start the first instant
         */
        public MutableClock(Instant start) {
            this.now = start;
        }

        /**
         * Moves the clock forward.
         *
         * @param d the amount
         */
        public void advance(Duration d) {
            now = now.plus(d);
        }

        /**
         * Sets the clock.
         *
         * @param t the instant
         */
        public void set(Instant t) {
            now = t;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
