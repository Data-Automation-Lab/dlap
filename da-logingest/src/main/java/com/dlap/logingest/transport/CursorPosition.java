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

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * Where a stream partition stands (design B5 "Cursors replace watermarks").
 *
 * @param lowWatermark  oldest event time not yet acknowledged; the next poll reads from here minus
 *                      the overlap; {@code null} before the first batch
 * @param highWatermark newest event time seen; {@code null} before the first batch
 * @param state         opaque transport state (page tokens, the last listed object key); values
 *                      are JSON scalars
 */
public record CursorPosition(Instant lowWatermark, Instant highWatermark, Map<String, Object> state) {

    /** A partition that has not been read. */
    public static final CursorPosition INITIAL = new CursorPosition(null, null, Map.of());

    /**
     * Freezes the state.
     *
     * @param lowWatermark  see the record description
     * @param highWatermark see the record description
     * @param state         see the record description
     */
    public CursorPosition {
        state = state == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(state));
    }

    /**
     * The same position with other watermarks.
     *
     * @param low  the low watermark
     * @param high the high watermark
     * @return a new position
     */
    public CursorPosition withWatermarks(Instant low, Instant high) {
        return new CursorPosition(low, high, state);
    }
}
