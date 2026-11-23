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

package com.dlap.logingest.transport;

import com.dlap.logingest.parse.core.FormatNames;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * What a transport is and needs, for the bindings validator and the stream runner.
 *
 * @param transportId      upper snake case id, for example {@code S3_TRAIL_FILES}
 * @param requiredLocation location keys every stream must set
 * @param optionalLocation location keys the transport also reads
 * @param cursorKind       how the transport tracks progress
 * @param initialLookback  how far back the first poll of a stream reads, for time cursors
 * @param settle           how far behind now a time-cursored poll stops, so late provider writes
 *                         inside the window are not skipped
 */
public record TransportDescriptor(
        String transportId,
        Set<String> requiredLocation,
        Set<String> optionalLocation,
        CursorKind cursorKind,
        Duration initialLookback,
        Duration settle) {

    /** How a transport tracks progress. */
    public enum CursorKind {
        /** An event-time window per partition ({@code stream_cursor} watermarks). */
        TIME,
        /** Objects listed in order, each checkpointed in {@code processed_object}. */
        OBJECT
    }

    /**
     * Validates the descriptor.
     *
     * @param transportId      see the record description
     * @param requiredLocation see the record description
     * @param optionalLocation see the record description
     * @param cursorKind       see the record description
     * @param initialLookback  see the record description
     * @param settle           see the record description
     */
    public TransportDescriptor {
        if (transportId == null || !FormatNames.SOURCE_KIND.matcher(transportId).matches()) {
            throw new IllegalArgumentException("a transport id is upper snake case");
        }
        requiredLocation = requiredLocation == null ? Set.of() : Set.copyOf(requiredLocation);
        optionalLocation = optionalLocation == null ? Set.of() : Set.copyOf(optionalLocation);
        Objects.requireNonNull(cursorKind, "cursorKind");
        initialLookback = initialLookback == null ? Duration.ofHours(1) : initialLookback;
        settle = settle == null ? Duration.ZERO : settle;
        if (initialLookback.isNegative() || settle.isNegative()) {
            throw new IllegalArgumentException("lookback and settle must not be negative");
        }
    }
}
