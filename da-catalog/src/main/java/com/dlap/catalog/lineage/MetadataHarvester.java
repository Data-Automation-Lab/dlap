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

package com.dlap.catalog.lineage;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Harvests business metadata (owner, team, account, region, provider) from a
 * normalized log event's principal and target-resource so it can be merged
 * into an asset's {@code businessMetadataJson}.
 *
 * <p>Everything derives from the actor and target the cloud audit log already
 * carries (no external lookup), which is the platform's "reconstruct truth
 * from logs" differentiator. Defensive throughout: a missing or malformed
 * field yields an omitted key, never an exception.
 */
@Component
public class MetadataHarvester {

    /** Principal-id prefixes cloud providers use, stripped from the harvested owner. */
    private static final String[] PRINCIPAL_PREFIXES = {"user:", "serviceaccount:", "service_account:", "group:"};

    /**
     * Harvest a metadata map from the event. Keys are only present when their
     * source value is present, so the caller can merge without clobbering
     * existing entries with nulls.
     *
     * @param normalizedEvent decoded event (camelCase); may be {@code null}
     * @return harvested {@code harvested*} keys plus {@code harvestedAt}; never
     *         {@code null}
     */
    public Map<String, Object> harvest(Map<String, Object> normalizedEvent) {
        Map<String, Object> harvested = new LinkedHashMap<>();
        if (normalizedEvent == null) {
            return harvested;
        }

        Map<String, Object> principal = asMap(normalizedEvent.get("principal"));
        Map<String, Object> target = asMap(normalizedEvent.get("targetResource"));

        String rawOwner = principal == null
                ? null
                : firstNonBlank(str(principal.get("displayName")), str(principal.get("id")));
        String owner = stripPrincipalPrefix(rawOwner);
        putIfPresent(harvested, "harvestedOwner", owner);

        if (principal != null) {
            putIfPresent(harvested, "harvestedOwnerType", str(principal.get("type")));
        }

        String team = inferTeam(owner);
        putIfPresent(harvested, "harvestedTeam", team);

        if (target != null) {
            putIfPresent(harvested, "harvestedRegion", str(target.get("region")));
        }
        putIfPresent(harvested, "harvestedProvider", str(normalizedEvent.get("sourceProvider")));

        String account = principal == null ? null : str(principal.get("accountId"));
        if (account == null && target != null) {
            account = str(target.get("project"));
        }
        putIfPresent(harvested, "harvestedAccount", account);

        harvested.put("harvestedAt", Instant.now().toString());
        return harvested;
    }

    /**
     * Infer a team from the owner. For an email-shaped owner, use the domain's
     * first label (e.g. {@code alice@data-eng.co} -> {@code data-eng}); if the
     * domain is a generic public mail host, fall back to the local-part.
     */
    static String inferTeam(String owner) {
        if (owner == null || owner.isBlank()) {
            return null;
        }
        int at = owner.indexOf('@');
        if (at < 0) {
            // Not email-shaped; nothing reliable to infer.
            return null;
        }
        String local = owner.substring(0, at).trim();
        String domain = owner.substring(at + 1).trim();
        if (domain.isEmpty()) {
            return local.isEmpty() ? null : local;
        }
        String firstLabel = domain.split("\\.")[0].trim();
        if (firstLabel.isEmpty()) {
            return local.isEmpty() ? null : local;
        }
        // Public mail hosts carry no org signal; the local-part is more useful.
        if (isGenericMailHost(firstLabel)) {
            return local.isEmpty() ? null : local;
        }
        return firstLabel;
    }

    private static boolean isGenericMailHost(String label) {
        return switch (label.toLowerCase(Locale.ROOT)) {
            case "gmail", "yahoo", "hotmail", "outlook", "icloud", "protonmail", "aol" -> true;
            default -> false;
        };
    }

    private static String stripPrincipalPrefix(String raw) {
        if (raw == null) {
            return null;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        for (String prefix : PRINCIPAL_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return raw.substring(prefix.length()).trim();
            }
        }
        return raw;
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
