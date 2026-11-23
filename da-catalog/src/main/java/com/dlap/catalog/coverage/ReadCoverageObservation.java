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

package com.dlap.catalog.coverage;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One coverage observation from a da-discovery collector, as decoded from the log spine
 * ({@link ReadCoverageWire}): what the provider configuration says about the recording of data
 * reads under {@link #rootUri}, for the asset kinds in {@link #appliesTo}.
 *
 * @param eventId    the spine {@code event_id} of the observation
 * @param provider   {@code AWS}, {@code GCP} or {@code AZURE}
 * @param scopeId    the account, project or subscription the configuration belongs to; the
 *                   log bindings of this scope decide whether DLAP ingests the recorded reads
 * @param rootUri    canonical URI of the subject ({@code s3://bucket}, {@code gs://bucket},
 *                   {@code bq://project}, {@code az://account}); the observation applies to the
 *                   asset with this URI and to every asset under {@code rootUri + "/"}
 * @param appliesTo  the service types the observation applies to
 * @param channel    the log source kind that carries the recorded reads ({@code AWS_CLOUDTRAIL},
 *                   {@code GCP_CLOUD_AUDIT}, {@code AZURE_STORAGE_RESOURCE_LOG})
 * @param level      what the configuration proves; {@code null} when the collector could not tell
 * @param reasons    the gaps (or, for {@code FULL}, the evidence); never {@code null}
 * @param observedAt when the configuration was read, by the collector's clock
 * @param collector  {@code <collector-id>@<version>}
 */
public record ReadCoverageObservation(String eventId, String provider, String scopeId, String rootUri,
                                      Set<String> appliesTo, String channel, ReadCoverageLevel level,
                                      List<String> reasons, Instant observedAt, String collector) {

    /**
     * Creates the observation.
     *
     * @param eventId    the event id
     * @param provider   the provider
     * @param scopeId    the scope
     * @param rootUri    the subject
     * @param appliesTo  the service types
     * @param channel    the log source kind
     * @param level      the level, or {@code null}
     * @param reasons    the reasons
     * @param observedAt the observation time
     * @param collector  the collector
     */
    public ReadCoverageObservation {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(rootUri, "rootUri");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(observedAt, "observedAt");
        appliesTo = Set.copyOf(appliesTo);
        reasons = List.copyOf(reasons);
    }

    /**
     * The level, empty when unknown.
     *
     * @return the level
     */
    public Optional<ReadCoverageLevel> knownLevel() {
        return Optional.ofNullable(level);
    }
}
