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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** The pieces behind the export manifest: the hashing stream and the registry of recent manifests. */
class ExportManifestTest {

    /** A clock a test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now;
        private final ZoneId zone;

        MutableClock(Instant now, ZoneId zone) {
            this.now = now;
            this.zone = zone;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void theHashIsTheSha256OfExactlyTheBytesWritten() throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        HashingOutputStream out = new HashingOutputStream(sink);

        out.write("abc".getBytes(StandardCharsets.UTF_8));

        // FIPS 180-4 test vector for "abc"
        assertThat(out.sha256Hex()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(out.bytesWritten()).isEqualTo(3);
        assertThat(sink.toString(StandardCharsets.UTF_8)).isEqualTo("abc");
    }

    @Test
    void theEmptyStreamHasTheEmptyHashAndReadingTheHashDoesNotResetIt() throws IOException {
        HashingOutputStream out = new HashingOutputStream(new ByteArrayOutputStream());

        assertThat(out.sha256Hex()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        out.write('a');
        out.write(new byte[] {'b', 'c'}, 0, 2);
        assertThat(out.sha256Hex()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(out.sha256Hex()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void aPartialArrayWriteHashesOnlyTheRequestedRange() throws IOException {
        HashingOutputStream out = new HashingOutputStream(new ByteArrayOutputStream());

        out.write("xxabcxx".getBytes(StandardCharsets.UTF_8), 2, 3);

        assertThat(out.sha256Hex()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(out.bytesWritten()).isEqualTo(3);
    }

    private static ExportManifest manifest(UUID id) {
        return new ExportManifest(id, "asset-activity-expiry", "1.0", "csv", "f.csv", "00", 1, 0, List.of("a"),
                Instant.parse("2026-10-19T12:00:00Z"), "live", Map.of(), Map.of(), Instant.parse("2026-10-19T12:00:00Z"));
    }

    @Test
    void aManifestIsFoundUntilItExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
        ExportManifestRegistry registry = new ExportManifestRegistry(clock, Duration.ofHours(1), 10);
        UUID id = UUID.randomUUID();
        registry.register(manifest(id));

        clock.advance(Duration.ofMinutes(59));
        assertThat(registry.find(id)).isPresent();
        clock.advance(Duration.ofMinutes(1));
        assertThat(registry.find(id)).isEmpty();
    }

    @Test
    void theOldestManifestIsDroppedBeyondTheBound() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
        ExportManifestRegistry registry = new ExportManifestRegistry(clock, Duration.ofHours(1), 2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        registry.register(manifest(first));
        registry.register(manifest(second));
        registry.register(manifest(third));

        assertThat(registry.find(first)).isEmpty();
        assertThat(registry.find(second)).isPresent();
        assertThat(registry.find(third)).isPresent();
    }

    @Test
    void registeringTheSameExportAgainRefreshesItInsteadOfDuplicatingIt() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
        ExportManifestRegistry registry = new ExportManifestRegistry(clock, Duration.ofHours(1), 2);
        UUID id = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        registry.register(manifest(id));
        registry.register(manifest(other));
        registry.register(manifest(id));

        assertThat(registry.find(id)).isPresent();
        assertThat(registry.find(other)).isPresent();
    }

    @Test
    void refusesAnUnusableConfiguration() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);

        assertThatThrownBy(() -> new ExportManifestRegistry(clock, Duration.ZERO, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExportManifestRegistry(clock, Duration.ofHours(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theManifestKeepsItsFilterOrder() {
        Map<String, Object> filters = new java.util.LinkedHashMap<>();
        filters.put("z", 1);
        filters.put("a", 2);
        filters.put("m", 3);

        ExportManifest manifest = new ExportManifest(UUID.randomUUID(), "r", "1.0", "csv", "f", "00", 0, 0, List.of(),
                Instant.EPOCH, "live", filters, Map.of(), Instant.EPOCH);

        assertThat(manifest.filters().keySet()).containsExactly("z", "a", "m");
    }
}
