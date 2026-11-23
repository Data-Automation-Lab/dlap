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

package com.dlap.catalog.retention;

import java.time.Instant;
import java.util.Objects;

/**
 * The read signal of one asset: data reads are captured for it from {@link #since} up to
 * {@link #validThrough}. Without it the evaluator cannot prove that an asset was not read, so it
 * never calls a last-access rule due (docs/specs/log-event.md section 10).
 *
 * @param since        earliest event time from which reads are known to be captured
 * @param validThrough event time up to which the capture is complete: the binding's low
 *                     watermark. Reads after it may still be on their way, so a last-access rule is
 *                     proven expired only if its expiry is at or before this instant
 */
public record ReadCoverage(Instant since, Instant validThrough) {

    /**
     * Creates the coverage.
     *
     * @param since        start of capture
     * @param validThrough end of the complete part of the capture
     */
    public ReadCoverage {
        Objects.requireNonNull(since, "since");
        Objects.requireNonNull(validThrough, "validThrough");
    }
}
