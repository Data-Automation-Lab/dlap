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

package com.dlap.reporting.drift;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Counts the events the drift consumers skip, so a stream that stops joining shows on a dashboard
 * instead of only in a warning log.
 */
@Component
public class DriftEventSkips {

    /** Counter of skipped events, tagged {@code stream=read|decision} and {@code reason}. */
    public static final String SKIPPED_COUNTER = "dlap.drift.events.skipped";

    /** The two inputs of the join. */
    public enum Stream {
        /** {@code dlap.discovery.asset.accessed}. */
        READ,
        /** {@code dlap.access.decision}. */
        DECISION;

        String tag() {
            return this == READ ? "read" : "decision";
        }
    }

    private final MeterRegistry meterRegistry;

    /**
     * Creates the counter.
     *
     * @param meterRegistry where skips are counted
     */
    public DriftEventSkips(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Counts one skipped event.
     *
     * @param stream the stream it came from
     * @param reason why it was skipped
     */
    public void count(Stream stream, DriftSkipReason reason) {
        meterRegistry.counter(SKIPPED_COUNTER, "stream", stream.tag(), "reason", reason.tag()).increment();
    }
}
