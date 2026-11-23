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

package com.dlap.logingest.health;

import com.dlap.logingest.model.BindingState;
import com.dlap.proto.log.v1.SourceProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the service knows about its bindings at one moment: the effective configuration joined with
 * the cursors and the format observations in the database. Rebuilt every
 * {@code dlap.logingest.metrics.snapshot-interval}; the gauges, the health API, the drift report and
 * the status publisher all read the same snapshot, so they agree.
 *
 * @param at       when the snapshot was built
 * @param bindings one entry per effective binding, sorted by id
 */
public record HealthSnapshot(Instant at, List<BindingSnapshot> bindings) {

    /**
     * Freezes the snapshot.
     *
     * @param at       see the record description
     * @param bindings see the record description
     */
    public HealthSnapshot {
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
    }

    /**
     * One binding.
     *
     * @param bindingId     the binding id
     * @param sourceId      the source it belongs to
     * @param sourceKind    the source kind, for example {@code AWS_CLOUDTRAIL}
     * @param provider      the cloud provider when the kind has a registered one
     * @param scope         the scope map of the configuration (account, project, regions); ids only
     * @param state         the configuration state
     * @param runs          whether the binding is polled or fed (valid, enabled, registry pipeline)
     * @param configHash    the SHA-256 of the effective configuration
     * @param streams       its streams with their cursors
     * @param observed      the formats and versions seen
     */
    public record BindingSnapshot(String bindingId, String sourceId, String sourceKind,
                                  Optional<SourceProvider> provider, Map<String, Object> scope, BindingState state,
                                  boolean runs, String configHash, List<StreamSnapshot> streams,
                                  List<ObservedVersion> observed) {

        /**
         * Freezes the binding.
         *
         * @param bindingId  see the record description
         * @param sourceId   see the record description
         * @param sourceKind see the record description
         * @param provider   see the record description
         * @param scope      see the record description
         * @param state      see the record description
         * @param runs       see the record description
         * @param configHash see the record description
         * @param streams    see the record description
         * @param observed   see the record description
         */
        public BindingSnapshot {
            provider = provider == null ? Optional.empty() : provider;
            scope = scope == null ? Map.of() : Map.copyOf(scope);
            streams = streams == null ? List.of() : List.copyOf(streams);
            observed = observed == null ? List.of() : List.copyOf(observed);
        }

        /**
         * The oldest unacknowledged event time over the streams.
         *
         * @return the earliest low watermark, or empty when no stream has a cursor yet
         */
        public Optional<Instant> lowWatermark() {
            return streams.stream().map(StreamSnapshot::lowWatermark).filter(java.util.Objects::nonNull)
                    .min(java.util.Comparator.naturalOrder());
        }

        /**
         * The newest event time seen over the streams.
         *
         * @return the latest high watermark, or empty
         */
        public Optional<Instant> highWatermark() {
            return streams.stream().map(StreamSnapshot::highWatermark).filter(java.util.Objects::nonNull)
                    .max(java.util.Comparator.naturalOrder());
        }

        /**
         * The last time any stream moved its cursor.
         *
         * @return the latest cursor update, or empty
         */
        public Optional<Instant> lastPoll() {
            return streams.stream().map(StreamSnapshot::updatedAt).filter(java.util.Objects::nonNull)
                    .max(java.util.Comparator.naturalOrder());
        }
    }

    /**
     * One stream of a binding.
     *
     * @param streamId      the stream id
     * @param transport     the transport id
     * @param envelope      the envelope it delivers
     * @param pinnedVersion the version the stream pins, or {@code auto}
     * @param lowWatermark  the oldest event time not yet acknowledged, or {@code null} before the first poll
     * @param highWatermark the newest event time seen, or {@code null}
     * @param updatedAt     when the cursor last moved, or {@code null}
     */
    public record StreamSnapshot(String streamId, String transport, String envelope, String pinnedVersion,
                                 Instant lowWatermark, Instant highWatermark, Instant updatedAt) {
    }

    /**
     * A format and version a binding delivered, from {@code format_observation}.
     *
     * @param envelope    the envelope
     * @param version     the detected version
     * @param fingerprint the shape fingerprint, empty when the parser declares no paths
     * @param firstSeen   when it was first seen
     * @param lastSeen    when it was last seen
     * @param count       records seen
     */
    public record ObservedVersion(String envelope, String version, String fingerprint, Instant firstSeen,
                                  Instant lastSeen, long count) {
    }
}
