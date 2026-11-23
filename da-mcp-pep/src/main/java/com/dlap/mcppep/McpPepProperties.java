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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Bound configuration for the reference MCP PEP.
 *
 * <ul>
 *   <li>{@code dlap.pdp.base-url} - base URL of the DLAP PDP (da-policyengine).</li>
 *   <li>{@code dlap.pdp.token} - static bearer token for the PDP (dev default).</li>
 *   <li>{@code dlap.mcppep.cache.ttl-seconds} - decision-cache TTL.</li>
 *   <li>{@code dlap.mcppep.mode} - {@code enforce} | {@code observe}.</li>
 *   <li>{@code dlap.mcppep.ratelimit.*} - advisory rate-limit headers.</li>
 * </ul>
 *
 * <p>Two prefixes ({@code dlap.pdp} and {@code dlap.mcppep}) are bound via two
 * nested holders so the property names match the enforcement contract's
 * PDP-facing vocabulary while keeping PEP-local knobs under {@code mcppep}.
 */
@ConfigurationProperties(prefix = "dlap")
public class McpPepProperties {

    @NestedConfigurationProperty
    private final Pdp pdp = new Pdp();

    @NestedConfigurationProperty
    private final Mcppep mcppep = new Mcppep();

    public Pdp getPdp() {
        return pdp;
    }

    public Mcppep getMcppep() {
        return mcppep;
    }

    /** {@code dlap.pdp.*} - the AuthZEN PDP the PEP calls. */
    public static class Pdp {
        /** Base URL of da-policyengine; the PEP POSTs to {@code <base-url>/access/v1/evaluation}. */
        private String baseUrl = "http://localhost:8083";
        /** Static bearer token for the PDP (reference default; production uses client-credentials). */
        private String token = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }

    /** {@code dlap.mcppep.*} - PEP-local behavior. */
    public static class Mcppep {
        /** Enforcement mode: {@code enforce} (fail-closed) or {@code observe} (fail-open). */
        private String mode = "enforce";

        @NestedConfigurationProperty
        private final Cache cache = new Cache();

        @NestedConfigurationProperty
        private final RateLimit ratelimit = new RateLimit();

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public Cache getCache() {
            return cache;
        }

        public RateLimit getRatelimit() {
            return ratelimit;
        }

        /** True when running in observe mode (PDP-unavailable fails open). */
        public boolean isObserveMode() {
            return "observe".equalsIgnoreCase(mode);
        }
    }

    /** {@code dlap.mcppep.cache.*} - local decision cache. */
    public static class Cache {
        /** Time-to-live for cached verdicts, in seconds. */
        private long ttlSeconds = 30L;

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }

    /**
     * {@code dlap.mcppep.ratelimit.*} - advisory rate-limit headers emitted by
     * {@link RateLimitHeaderFilter}. Advisory only: headers inform clients, the
     * PEP does not reject over-limit requests. Off by default.
     */
    public static class RateLimit {
        /** Enable the advisory {@code X-RateLimit-*} response headers. */
        private boolean enabled = false;
        /** Per-window request ceiling (fixed 60-second window). */
        private long limit = 600L;
        /** Request header carrying the client identity; falls back to remote address. */
        private String clientHeader = "X-Client-Id";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getLimit() {
            return limit;
        }

        public void setLimit(long limit) {
            this.limit = limit;
        }

        public String getClientHeader() {
            return clientHeader;
        }

        public void setClientHeader(String clientHeader) {
            this.clientHeader = clientHeader;
        }
    }
}
