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

import com.dlap.proto.log.v1.Origin;
import java.util.Locale;

/**
 * How a record entered DLAP: live streaming, a bounded backfill, or a replay of stored or fixture
 * data. It travels as the reserved Kafka header {@value #HEADER} (log-event spec section 2) and
 * decides {@code DlapLogEvent.origin}.
 *
 * <p>It also carries the republish rule of ADR-0045 decision 4: an {@code event_id} that
 * da-logingest already stored is not published again, except in {@link #REPLAY} mode
 * ({@link #shouldPublish(boolean)}).
 */
public enum IngestMode {

    /** Continuous polling of a live source. Origin {@code ORIGIN_CLOUD_LOG}. */
    STREAM(Origin.ORIGIN_CLOUD_LOG),

    /** A bounded, resumable history read. Origin {@code ORIGIN_BACKFILL}. */
    BACKFILL(Origin.ORIGIN_BACKFILL),

    /** Stored events, fixtures or demo seeding re-driven on purpose. Origin {@code ORIGIN_REPLAY}. */
    REPLAY(Origin.ORIGIN_REPLAY);

    /** The reserved Kafka header that carries the mode. */
    public static final String HEADER = "dlap-ingest-mode";

    private final Origin origin;

    IngestMode(Origin origin) {
        this.origin = origin;
    }

    /**
     * Returns the {@code DlapLogEvent.origin} of events read in this mode.
     *
     * @return the origin
     */
    public Origin origin() {
        return origin;
    }

    /**
     * The republish rule: publish an event unless its id is already stored, and always in replay.
     *
     * @param alreadyStored whether {@code log_events} already holds the event id
     * @return true when the event goes to {@code dlap.log.event.normalized}
     */
    public boolean shouldPublish(boolean alreadyStored) {
        return !alreadyStored || this == REPLAY;
    }

    /**
     * Reads the header value.
     *
     * @param header the {@value #HEADER} value; {@code null} or blank means {@link #STREAM}
     * @return the mode
     * @throws IllegalArgumentException when the value names no mode
     */
    public static IngestMode fromHeader(String header) {
        if (header == null || header.isBlank()) {
            return STREAM;
        }
        try {
            return valueOf(header.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(HEADER + " must be STREAM, BACKFILL or REPLAY", e);
        }
    }
}
