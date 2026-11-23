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

package com.dlap.logingest.config.sources;

import com.dlap.logingest.parse.core.BindingConfig;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One stream with every inherited setting resolved: what a transport and the parser registry see.
 *
 * @param bindingId         the binding
 * @param streamId          the stream id, unique in the binding
 * @param transport         the transport id
 * @param envelope          the envelope its records arrive in
 * @param location          transport location keys
 * @param parse             the binding settings the registry parses its records with
 * @param filters           service and operation filters
 * @param overlap           re-read window of a time-cursored transport
 * @param pollInterval      polling period
 * @param requestsPerSecond request rate limit, or {@code null} for none
 * @param credentialsRef    environment variable name or role ARN, or {@code null}
 * @param backfillAllowed   whether backfill jobs may run
 * @param crossStreamDedup  whether the stream declared {@code dedup: cross-stream}
 * @param push              true for a built-in push channel, which no poller runs
 */
public record EffectiveStream(
        String bindingId,
        String streamId,
        String transport,
        String envelope,
        Map<String, String> location,
        BindingConfig parse,
        StreamFilters filters,
        Duration overlap,
        Duration pollInterval,
        Double requestsPerSecond,
        String credentialsRef,
        boolean backfillAllowed,
        boolean crossStreamDedup,
        boolean push) {

    /**
     * Freezes the stream.
     *
     * @param bindingId         see the record description
     * @param streamId          see the record description
     * @param transport         see the record description
     * @param envelope          see the record description
     * @param location          see the record description
     * @param parse             see the record description
     * @param filters           see the record description
     * @param overlap           see the record description
     * @param pollInterval      see the record description
     * @param requestsPerSecond see the record description
     * @param credentialsRef    see the record description
     * @param backfillAllowed   see the record description
     * @param crossStreamDedup  see the record description
     * @param push              see the record description
     */
    public EffectiveStream {
        Objects.requireNonNull(bindingId, "bindingId");
        Objects.requireNonNull(streamId, "streamId");
        location = location == null ? Map.of() : Map.copyOf(new TreeMap<>(location));
        filters = filters == null ? StreamFilters.NONE : filters;
        overlap = overlap == null ? Duration.ZERO : overlap;
        pollInterval = pollInterval == null ? Duration.ZERO : pollInterval;
    }
}
