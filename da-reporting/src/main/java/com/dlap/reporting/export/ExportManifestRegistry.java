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

package com.dlap.reporting.export;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps the manifests of recent exports so a client can fetch the manifest of the file it just
 * downloaded. The export itself is streamed and never stored, so the manifest is the only thing
 * kept, and only for a bounded time and a bounded count: the oldest manifest is dropped first.
 *
 * <p>The registry lives in one service instance's memory. Behind several instances, fetch the
 * manifest from the instance that served the download (a sticky route), or check the hash that
 * the service also writes to its log at INFO when an export completes. A manifest lost to a
 * restart is never wrong, only missing.
 *
 * <p>Thread-safe. Time comes from the injected {@link Clock}.
 */
public final class ExportManifestRegistry {

    private final Clock clock;
    private final Duration timeToLive;
    private final int maxEntries;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    private record Entry(ExportManifest manifest, Instant expiresAt) {
    }

    /**
     * Creates a registry.
     *
     * @param clock      the time source
     * @param timeToLive how long a manifest stays available, positive
     * @param maxEntries the most manifests kept, at least 1
     */
    public ExportManifestRegistry(Clock clock, Duration timeToLive, int maxEntries) {
        if (timeToLive == null || timeToLive.isZero() || timeToLive.isNegative()) {
            throw new IllegalArgumentException("The manifest time to live must be positive");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("The registry must keep at least one manifest");
        }
        this.clock = clock;
        this.timeToLive = timeToLive;
        this.maxEntries = maxEntries;
    }

    /**
     * Stores a manifest, dropping expired ones and then the oldest ones beyond the bound.
     *
     * @param manifest a completed export's manifest
     */
    public synchronized void register(ExportManifest manifest) {
        Instant now = clock.instant();
        dropExpired(now);
        entries.remove(manifest.exportId());
        entries.put(manifest.exportId(), new Entry(manifest, now.plus(timeToLive)));
        Iterator<UUID> oldest = entries.keySet().iterator();
        while (entries.size() > maxEntries && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
    }

    /**
     * Finds a manifest.
     *
     * @param exportId the id from the {@code X-Export-Id} header
     * @return the manifest, or empty when it is unknown or has expired
     */
    public synchronized Optional<ExportManifest> find(UUID exportId) {
        dropExpired(clock.instant());
        Entry entry = entries.get(exportId);
        return entry == null ? Optional.empty() : Optional.of(entry.manifest());
    }

    private void dropExpired(Instant now) {
        entries.values().removeIf(entry -> !entry.expiresAt().isAfter(now));
    }
}
