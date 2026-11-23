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

package com.dlap.mcppep;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A small, dependency-free TTL cache of PDP verdicts.
 *
 * <p>Keyed on everything the verdict depends on (Q-461, D42): the principal id,
 * the action, the resource ({@code assetId} else {@code resourceUri}), the caller's
 * roles (sorted), the tier and a SHA-256 digest of the purpose and consent context.
 * A verdict is therefore never served to a request whose roles, tier or context
 * differ from the cached one. Entries carry an insertion timestamp; {@link #get} treats an entry older than the configured TTL as a
 * miss (and prunes it). Backed by a {@link ConcurrentHashMap}, so no external
 * cache library is pulled in (Caffeine is deliberately NOT added).
 *
 * <p>A {@link Clock} is injectable so tests can drive expiry deterministically.
 */
@Component
public class DecisionCache {

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final Clock clock;

    @Autowired
    public DecisionCache(McpPepProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** Test/advanced constructor allowing an injectable clock. */
    public DecisionCache(McpPepProperties properties, Clock clock) {
        this.ttlMillis = Math.max(0L, properties.getMcppep().getCache().getTtlSeconds()) * 1000L;
        this.clock = clock;
    }

    /**
     * @return the cached decision for {@code req} if present and not expired,
     *         else empty
     */
    public Optional<McpDecision> get(McpAccessRequest req) {
        String key = key(req);
        Entry entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (isExpired(entry)) {
            store.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.decision());
    }

    /** Store {@code decision} for {@code req}, stamped with the current time. */
    public void put(McpAccessRequest req, McpDecision decision) {
        store.put(key(req), new Entry(decision, clock.millis()));
    }

    /** Clear all cached entries (test/operational aid). */
    public void clear() {
        store.clear();
    }

    private boolean isExpired(Entry entry) {
        return clock.millis() - entry.storedAtMillis() >= ttlMillis;
    }

    /**
     * Cache key over everything the PDP verdict depends on: {@code principalId}, {@code action},
     * the resource ({@code assetId} when present, else {@code resourceUri}), the roles sorted,
     * the tier and the SHA-256 digest of the canonical context. Every part is length-prefixed, so
     * no two different requests can produce the same key by shifting characters between parts.
     * Only a request that is identical in all of these hits the same entry.
     */
    static String key(McpAccessRequest req) {
        String resourceKey = (req.assetId() != null && !req.assetId().isBlank())
                ? req.assetId()
                : req.resourceUri();
        StringBuilder sb = new StringBuilder();
        part(sb, req.principalId());
        part(sb, req.action());
        part(sb, resourceKey);
        part(sb, rolesKey(req.roles()));
        part(sb, req.tier());
        part(sb, contextDigest(req.context()));
        return sb.toString();
    }

    /**
     * SHA-256 (hex) of a canonical, type-tagged and length-prefixed rendering of the context. Map
     * entries are ordered by key, so two contexts that differ only in key order share a digest; a
     * null context and an empty one are the same (the mapper omits both).
     */
    static String contextDigest(Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        canonical(sb, context == null ? Map.of() : context);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every Java platform; reaching this is a broken runtime.
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String rolesKey(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return "";
        }
        List<String> sorted = new ArrayList<>(roles.size());
        for (String role : roles) {
            sorted.add(nz(role));
        }
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (String role : sorted) {
            part(sb, role);
        }
        return sb.toString();
    }

    private static void canonical(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append('n');
        } else if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> ordered = new TreeMap<>();
            map.forEach((k, v) -> ordered.put(String.valueOf(k), v));
            sb.append('m').append(ordered.size()).append('{');
            ordered.forEach((k, v) -> {
                part(sb, k);
                canonical(sb, v);
            });
            sb.append('}');
        } else if (value instanceof Iterable<?> items) {
            List<Object> list = new ArrayList<>();
            items.forEach(list::add);
            sb.append('l').append(list.size()).append('[');
            list.forEach(item -> canonical(sb, item));
            sb.append(']');
        } else if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            sb.append('l').append(length).append('[');
            for (int i = 0; i < length; i++) {
                canonical(sb, Array.get(value, i));
            }
            sb.append(']');
        } else if (value instanceof CharSequence text) {
            sb.append('s');
            part(sb, text.toString());
        } else if (value instanceof Boolean || value instanceof Number) {
            // The class name is part of the rendering: 1 and 1.0 stay different, which can only cost a miss.
            sb.append('v');
            part(sb, value.getClass().getName() + ":" + value);
        } else {
            sb.append('o');
            part(sb, value.getClass().getName() + ":" + value);
        }
    }

    private static void part(StringBuilder sb, String s) {
        String v = nz(s);
        sb.append(v.length()).append(':').append(v).append(';');
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private record Entry(McpDecision decision, long storedAtMillis) {
    }
}
