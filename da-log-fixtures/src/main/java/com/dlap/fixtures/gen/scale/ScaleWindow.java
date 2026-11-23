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

package com.dlap.fixtures.gen.scale;

import java.time.Duration;
import java.time.Instant;

/**
 * The event-time window of one generated stream: {@code count} events spread evenly over the
 * {@code span} that ends at {@code end}. Event times grow with the index, so files cut by index are
 * also cut by time, the way a trail delivers them.
 *
 * @param seed  the run seed
 * @param count the number of events, at least 1
 * @param end   the exclusive end of the window; the harness passes a moment safely before "now"
 * @param span  the length of the window, positive
 */
public record ScaleWindow(long seed, long count, Instant end, Duration span) {

    /** Validates the window. */
    public ScaleWindow {
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1");
        }
        if (end == null || span == null || span.isZero() || span.isNegative()) {
            throw new IllegalArgumentException("end is required and span must be positive");
        }
    }

    /**
     * The start of the window.
     *
     * @return {@code end - span}
     */
    public Instant start() {
        return end.minus(span);
    }

    /**
     * The event time of one record, to the millisecond.
     *
     * @param index the record index, 0 to {@code count - 1}
     * @return the time
     */
    public Instant eventTime(long index) {
        if (index < 0 || index >= count) {
            throw new IllegalArgumentException("index " + index + " is outside 0.." + (count - 1));
        }
        long millis = span.toMillis();
        long offset = Math.multiplyExact(millis, index) / count;
        return start().plusMillis(offset);
    }
}
