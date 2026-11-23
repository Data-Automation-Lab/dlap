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
import java.util.Objects;

/**
 * The event-time window one poll reads: {@code [from, to)}.
 *
 * @param from inclusive start: the low watermark minus the overlap, or now minus the initial
 *             lookback on the first poll
 * @param to   exclusive end: now minus the transport's settle time
 */
public record FetchWindow(Instant from, Instant to) {

    /**
     * Validates the window.
     *
     * @param from see the record description
     * @param to   see the record description
     */
    public FetchWindow {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("a fetch window ends after it starts");
        }
    }
}
