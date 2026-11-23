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

package com.dlap.catalog.lifecycle;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One Lifecycle State object, version 0.1 (docs/specs/lifecycle-state.md, ADR-0097): the lifecycle
 * state of one asset at one evaluation instant. The members are those of spec section 2, in that
 * order; a {@code null} member means unknown (rule R1), never a default.
 *
 * <p>{@link LifecycleStateJson} renders the record in the one form of rule R6. This type holds
 * values only; the rules that tie them together are applied by {@link LifecycleStateEmitter}.
 *
 * @param specVersion         always {@link #SPEC_VERSION}
 * @param asset               the canonical asset URI
 * @param platform            provider and kind
 * @param asOf                the evaluation instant, the only value from the engine's clock
 * @param createdAt           provider creation time, or {@code null}
 * @param lastReadAt          latest successful read, or {@code null}
 * @param lastWrittenAt       latest successful write, or {@code null}
 * @param readCoverage        {@code FULL}, {@code PARTIAL}, {@code NONE}, or {@code null}
 * @param readCoverageReasons the reasons, in the order of the observation; never {@code null}
 * @param readCoverageSince   start of the current run of {@code FULL}, or {@code null}
 * @param sizeBytes           latest observed size, or {@code null}
 * @param sizeObservedAt      when {@code sizeBytes} was true, or {@code null}
 * @param retention           the retention evaluation at {@code asOf}
 * @param legalHold           whether the asset is held
 * @param disposition         the executed archive or delete with its evidence, or {@code null}
 * @param provenance          the evidence of each field, in field order; never {@code null}
 */
public record LifecycleState(
        String specVersion,
        String asset,
        Platform platform,
        Instant asOf,
        Instant createdAt,
        Instant lastReadAt,
        Instant lastWrittenAt,
        String readCoverage,
        List<String> readCoverageReasons,
        Instant readCoverageSince,
        Long sizeBytes,
        Instant sizeObservedAt,
        Retention retention,
        boolean legalHold,
        Disposition disposition,
        List<ProvenanceEntry> provenance) {

    /** The specification version this record conforms to. */
    public static final String SPEC_VERSION = "0.1";

    /**
     * Checks the members that are never null and copies the lists.
     *
     * @throws NullPointerException when a required member is {@code null}
     */
    public LifecycleState {
        Objects.requireNonNull(specVersion, "specVersion");
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(retention, "retention");
        readCoverageReasons = List.copyOf(Objects.requireNonNull(readCoverageReasons, "readCoverageReasons"));
        provenance = List.copyOf(Objects.requireNonNull(provenance, "provenance"));
    }

    /**
     * The asset's platform (spec section 3.1).
     *
     * @param provider Asset Descriptor {@code cloudProvider}
     * @param kind     Asset Descriptor {@code serviceType}
     */
    public record Platform(String provider, String kind) {

        /**
         * Checks both members.
         *
         * @throws NullPointerException when a member is {@code null}
         */
        public Platform {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * The retention evaluation at {@code asOf} (spec section 3.5).
     *
     * @param status    {@code UNKNOWN}, {@code NO_POLICY}, {@code RETAINED}, {@code DUE} or {@code ON_HOLD}
     * @param basis     basis of the deciding rule, or {@code null}
     * @param days      period of the deciding rule, or {@code null}
     * @param fixedDate the expiry a {@code FIXED_DATE} rule carries, or {@code null}
     * @param expiresAt the evaluated expiry, or {@code null}
     * @param source    source of the deciding rule, or {@code null}
     */
    public record Retention(String status, String basis, Integer days, Instant fixedDate, Instant expiresAt,
                            String source) {

        /**
         * Checks the status.
         *
         * @throws NullPointerException when {@code status} is {@code null}
         */
        public Retention {
            Objects.requireNonNull(status, "status");
        }
    }

    /**
     * An executed archive or delete (spec section 3.7).
     *
     * @param action     {@code archive} or {@code delete}
     * @param executedAt event time of the provider event that records the action
     * @param executedBy {@code native-rule} or {@code engine}
     * @param evidence   the Log Event ids, ascending, at least one
     */
    public record Disposition(String action, Instant executedAt, String executedBy, List<String> evidence) {

        /**
         * Checks the members and copies the evidence.
         *
         * @throws NullPointerException     when a member is {@code null}
         * @throws IllegalArgumentException when {@code evidence} is empty
         */
        public Disposition {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(executedAt, "executedAt");
            Objects.requireNonNull(executedBy, "executedBy");
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
            if (evidence.isEmpty()) {
                throw new IllegalArgumentException("A disposition without evidence is not a disposition");
            }
        }
    }

    /**
     * Which log bindings and Log Event ids produced one field (spec section 3.8).
     *
     * @param field      the field the entry explains
     * @param channel    the evidence channel of the first id, or {@code null}
     * @param bindingIds the binding ids of the events, ascending; may be empty
     * @param eventIds   the event ids, ascending, at least one
     */
    public record ProvenanceEntry(String field, String channel, List<String> bindingIds, List<String> eventIds) {

        /**
         * Checks the members and copies the lists.
         *
         * @throws NullPointerException     when a member other than {@code channel} is {@code null}
         * @throws IllegalArgumentException when {@code eventIds} is empty
         */
        public ProvenanceEntry {
            Objects.requireNonNull(field, "field");
            bindingIds = List.copyOf(Objects.requireNonNull(bindingIds, "bindingIds"));
            eventIds = List.copyOf(Objects.requireNonNull(eventIds, "eventIds"));
            if (eventIds.isEmpty()) {
                throw new IllegalArgumentException("A provenance entry names at least one event");
            }
        }
    }
}
