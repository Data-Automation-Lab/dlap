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

package com.dlap.discovery.coverage;

import java.util.List;
import java.util.Objects;

/**
 * What a collector found for one subject: the asset with {@link #rootUri} and every asset under
 * {@code rootUri + "/"} of the kinds in {@link #appliesTo}.
 *
 * @param provider  {@code AWS}, {@code GCP} or {@code AZURE}
 * @param scopeId   the account, project or subscription that holds the configuration; the
 *                  catalog looks up the log bindings of this scope
 * @param rootUri   canonical URI of the subject ({@code s3://bucket}, {@code gs://bucket},
 *                  {@code bq://project}, {@code az://account}, ADR-0047)
 * @param appliesTo the DLAP service types covered
 * @param channel   the log source kind that carries the recorded reads
 * @param verdict   what the configuration proves
 * @param collector {@code <collector-id>@<version>}
 */
public record ReadCoverageObservation(String provider, String scopeId, String rootUri, List<String> appliesTo,
                                      String channel, CoverageVerdict verdict, String collector) {

    /**
     * Creates the observation.
     *
     * @param provider  the provider
     * @param scopeId   the scope
     * @param rootUri   the subject
     * @param appliesTo the service types
     * @param channel   the source kind
     * @param verdict   the verdict
     * @param collector the collector
     */
    public ReadCoverageObservation {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(rootUri, "rootUri");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(collector, "collector");
        appliesTo = List.copyOf(appliesTo);
        if (appliesTo.isEmpty()) {
            throw new IllegalArgumentException("an observation applies to at least one service type");
        }
    }
}
