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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the TTL semantics and key isolation of {@link DecisionCache}, using
 * a mutable clock to drive expiry deterministically (no sleeps).
 */
class DecisionCacheTest {

    /** A clock whose "now" is advanced by the test. */
    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long deltaMillis) {
            this.millis += deltaMillis;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private McpPepProperties propsWithTtl(long ttlSeconds) {
        McpPepProperties props = new McpPepProperties();
        props.getMcppep().getCache().setTtlSeconds(ttlSeconds);
        return props;
    }

    private McpAccessRequest req(String principal, String action, String assetId, String uri) {
        return new McpAccessRequest(principal, List.of(), null, "tool", uri, assetId, action, Map.of());
    }

    @Test
    void hitWithinTtl() {
        MutableClock clock = new MutableClock(1_000_000L);
        DecisionCache cache = new DecisionCache(propsWithTtl(30L), clock);
        McpAccessRequest r = req("p1", "read", "a1", "bq://x");

        cache.put(r, McpDecision.allow("allowed", List.of()));
        clock.advance(29_000L); // still within the 30s TTL

        assertThat(cache.get(r)).isPresent();
        assertThat(cache.get(r).get().allow()).isTrue();
    }

    @Test
    void missAfterExpiry() {
        MutableClock clock = new MutableClock(1_000_000L);
        DecisionCache cache = new DecisionCache(propsWithTtl(30L), clock);
        McpAccessRequest r = req("p1", "read", "a1", "bq://x");

        cache.put(r, McpDecision.allow("allowed", List.of()));
        clock.advance(30_000L); // TTL boundary reached → expired

        assertThat(cache.get(r)).isEmpty();
    }

    @Test
    void distinctKeysDoNotCollide() {
        MutableClock clock = new MutableClock(0L);
        DecisionCache cache = new DecisionCache(propsWithTtl(60L), clock);

        McpAccessRequest allowReq = req("p1", "read", "a1", "bq://x");
        McpAccessRequest denyByAction = req("p1", "delete", "a1", "bq://x");
        McpAccessRequest denyByPrincipal = req("p2", "read", "a1", "bq://x");
        McpAccessRequest denyByAsset = req("p1", "read", "a2", "bq://y");

        cache.put(allowReq, McpDecision.allow("allowed", List.of()));
        cache.put(denyByAction, McpDecision.deny("denied-action"));
        cache.put(denyByPrincipal, McpDecision.deny("denied-principal"));
        cache.put(denyByAsset, McpDecision.deny("denied-asset"));

        assertThat(cache.get(allowReq).get().allow()).isTrue();
        assertThat(cache.get(denyByAction).get().allow()).isFalse();
        assertThat(cache.get(denyByPrincipal).get().allow()).isFalse();
        assertThat(cache.get(denyByAsset).get().allow()).isFalse();
    }

    @Test
    void assetIdPreferredOverUriInKey() {
        // Same assetId but different URI must collide on assetId (assetId wins).
        McpAccessRequest a = req("p1", "read", "asset-1", "bq://one");
        McpAccessRequest b = req("p1", "read", "asset-1", "bq://two");
        assertThat(DecisionCache.key(a)).isEqualTo(DecisionCache.key(b));

        // No assetId → URI is the discriminator.
        McpAccessRequest c = req("p1", "read", null, "bq://one");
        McpAccessRequest d = req("p1", "read", null, "bq://two");
        assertThat(DecisionCache.key(c)).isNotEqualTo(DecisionCache.key(d));
    }
}
