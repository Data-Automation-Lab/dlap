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

package com.dlap.autoarchival.rule;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps a requested archive tier to a storage class the provider's native lifecycle rule accepts.
 *
 * <p>Three neutral tiers exist, ordered by cost and retrieval speed: {@code COOL} (infrequent
 * access, still instant), {@code COLD} (rarely read, instant or near instant) and {@code ARCHIVE}
 * (long term, slow or paid retrieval). Provider class names ({@code GLACIER_IR}, {@code COLDLINE},
 * {@code Archive}) and the older AWS-flavoured names the shared configuration used ({@code GLACIER},
 * {@code DEEP_ARCHIVE}) are accepted too.
 *
 * <p>Nothing is guessed: a name that is not an archive class on any cloud ({@code STANDARD},
 * {@code HOT}, and the retired configuration default {@code STANDARD_ARCHIVE}) is refused with a
 * {@link RuleException}, so a typo can no longer turn into a transition to the wrong class, and an
 * invalid default can no longer fail every task at the provider (RET-03, evidence
 * {@code ArchivalConfigServiceImpl.java:50-56}).
 *
 * <p>Thread-safe and stateless.
 */
public final class TierResolver {

    /** The tier used when neither the action nor the configuration names one. */
    public static final String DEFAULT_TIER = "COLD";

    /**
     * A storage class a native rule can transition to.
     *
     * @param name    the provider's own spelling, as it appears in the rule
     * @param minDays the earliest age in days the provider accepts for the transition
     */
    public record NativeTier(String name, int minDays) {
    }

    private static final Map<String, NativeTier> AWS = Map.ofEntries(
            Map.entry("COOL", new NativeTier("STANDARD_IA", 30)),
            Map.entry("STANDARD_IA", new NativeTier("STANDARD_IA", 30)),
            Map.entry("IA", new NativeTier("STANDARD_IA", 30)),
            Map.entry("NEARLINE", new NativeTier("STANDARD_IA", 30)),
            Map.entry("ONEZONE_IA", new NativeTier("ONEZONE_IA", 30)),
            Map.entry("COLD", new NativeTier("GLACIER_IR", 0)),
            Map.entry("COLDLINE", new NativeTier("GLACIER_IR", 0)),
            Map.entry("GLACIER_IR", new NativeTier("GLACIER_IR", 0)),
            Map.entry("GLACIER", new NativeTier("GLACIER", 0)),
            Map.entry("ARCHIVE", new NativeTier("DEEP_ARCHIVE", 0)),
            Map.entry("DEEP_ARCHIVE", new NativeTier("DEEP_ARCHIVE", 0)));

    private static final Map<String, NativeTier> GCP = Map.ofEntries(
            Map.entry("COOL", new NativeTier("NEARLINE", 0)),
            Map.entry("NEARLINE", new NativeTier("NEARLINE", 0)),
            Map.entry("STANDARD_IA", new NativeTier("NEARLINE", 0)),
            Map.entry("IA", new NativeTier("NEARLINE", 0)),
            Map.entry("COLD", new NativeTier("COLDLINE", 0)),
            Map.entry("COLDLINE", new NativeTier("COLDLINE", 0)),
            Map.entry("GLACIER_IR", new NativeTier("COLDLINE", 0)),
            Map.entry("ARCHIVE", new NativeTier("ARCHIVE", 0)),
            Map.entry("GLACIER", new NativeTier("ARCHIVE", 0)),
            Map.entry("DEEP_ARCHIVE", new NativeTier("ARCHIVE", 0)));

    private static final Map<String, NativeTier> AZURE = Map.ofEntries(
            Map.entry("COOL", new NativeTier("Cool", 0)),
            Map.entry("NEARLINE", new NativeTier("Cool", 0)),
            Map.entry("STANDARD_IA", new NativeTier("Cool", 0)),
            Map.entry("IA", new NativeTier("Cool", 0)),
            Map.entry("COLD", new NativeTier("Cold", 0)),
            Map.entry("COLDLINE", new NativeTier("Cold", 0)),
            Map.entry("GLACIER_IR", new NativeTier("Cold", 0)),
            Map.entry("ARCHIVE", new NativeTier("Archive", 0)),
            Map.entry("GLACIER", new NativeTier("Archive", 0)),
            Map.entry("DEEP_ARCHIVE", new NativeTier("Archive", 0)));

    /** Names that look like tiers but are not an archive class on any cloud. */
    private static final Set<String> NOT_ARCHIVE = Set.of("STANDARD", "HOT", "STANDARD_ARCHIVE", "REGIONAL",
            "MULTI_REGIONAL");

    private TierResolver() {
    }

    /**
     * Resolves a tier for a provider.
     *
     * @param provider  {@code AWS}, {@code GCP} or {@code AZURE}
     * @param requested the requested tier; {@code null} or blank means {@link #DEFAULT_TIER}
     * @return the native class
     * @throws RuleException {@link RuleException.Reason#INVALID_TIER} when the name is not an archive
     *                       class the provider's rule accepts
     */
    public static NativeTier resolve(String provider, String requested) {
        String key = requested == null || requested.isBlank()
                ? DEFAULT_TIER : requested.strip().toUpperCase(Locale.ROOT);
        Map<String, NativeTier> table = switch (provider == null ? "" : provider.toUpperCase(Locale.ROOT)) {
            case "AWS" -> AWS;
            case "GCP" -> GCP;
            case "AZURE" -> AZURE;
            default -> throw new RuleException(RuleException.Reason.UNSUPPORTED_PROVIDER,
                    "no native archive rule for provider '" + provider + "'");
        };
        NativeTier tier = NOT_ARCHIVE.contains(key) ? null : table.get(key);
        if (tier == null) {
            throw new RuleException(RuleException.Reason.INVALID_TIER, "'" + requested
                    + "' is not an archive tier for " + provider + "; use COOL, COLD, ARCHIVE or a "
                    + provider + " storage class (" + String.join(", ", new java.util.TreeSet<>(table.keySet()))
                    + ")");
        }
        return tier;
    }
}
