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

package com.dlap.reporting.identity;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Attributes a cloud principal to a DLAP subject through the principal identity map.
 *
 * <p>Lookup order: the candidate keys of {@link PrincipalKeys#candidates} in order (the exact key
 * before the role of an assumed-role session), and within one key an entry for the event's own
 * provider before an {@link IdentityProviderScope#ANY} entry. A principal with no entry keeps its
 * key as its subject, so a log that already names DLAP subjects joins without a map; the lookup is
 * counted as {@code outcome=unmapped} on {@value #RESOLUTIONS_COUNTER}.
 */
@Service
public class PrincipalIdentityResolver {

    /** Counter of principal lookups, tagged {@code outcome=mapped|unmapped}. */
    public static final String RESOLUTIONS_COUNTER = "dlap.drift.principal.resolutions";

    private final PrincipalIdentityMapRepository repository;
    private final MeterRegistry meterRegistry;

    /**
     * Creates the resolver.
     *
     * @param repository    the map store
     * @param meterRegistry where lookups are counted
     */
    public PrincipalIdentityResolver(PrincipalIdentityMapRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Resolves a principal.
     *
     * @param provider    the provider whose log named the principal; {@code null} searches only
     *                    {@link IdentityProviderScope#ANY} entries
     * @param principalId the principal id as the log names it
     * @return the subject, or empty when {@code principalId} is blank
     */
    public Optional<ResolvedPrincipal> resolve(IdentityProviderScope provider, String principalId) {
        List<String> candidates = PrincipalKeys.candidates(principalId);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        String key = candidates.get(0);
        EnumSet<IdentityProviderScope> scopes = EnumSet.of(IdentityProviderScope.ANY);
        if (provider != null) {
            scopes.add(provider);
        }
        Optional<PrincipalIdentityMapping> best = repository.findByPrincipalKeyInAndProviderIn(candidates, scopes)
                .stream()
                .filter(m -> candidates.contains(m.getPrincipalKey()))
                .min(Comparator
                        .comparingInt((PrincipalIdentityMapping m) -> candidates.indexOf(m.getPrincipalKey()))
                        .thenComparingInt(m -> m.getProvider() == IdentityProviderScope.ANY ? 1 : 0));
        meterRegistry.counter(RESOLUTIONS_COUNTER, "outcome", best.isPresent() ? "mapped" : "unmapped").increment();
        return Optional.of(best
                .map(m -> new ResolvedPrincipal(m.getSubjectId(), key, true))
                .orElseGet(() -> new ResolvedPrincipal(key, key, false)));
    }
}
