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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reference advisory rate-limit filter for the MCP PEP.
 *
 * <p>The PEP is the natural throttle point in the enforcement path, so this
 * reference deployable is where DLAP demonstrates the rate-limit response
 * contract. The filter adds three <em>advisory</em> headers to every response:
 *
 * <ul>
 *   <li>{@code X-RateLimit-Limit} - the per-window request ceiling.</li>
 *   <li>{@code X-RateLimit-Remaining} - requests left in the current window
 *       (floored at 0).</li>
 *   <li>{@code X-RateLimit-Reset} - epoch-second at which the current window
 *       ends and the counter resets.</li>
 * </ul>
 *
 * <p><b>Advisory only.</b> This filter does NOT reject requests; it never
 * returns 429. It publishes headers so clients can self-pace. The counter is an
 * in-memory {@link ConcurrentHashMap} fixed-window keyed per client, which is
 * process-local: it does not coordinate across PEP replicas and resets on
 * restart. A production deployment swaps this in-memory window for a shared
 * store (Redis / bucket4j) and, if hard throttling is required, enforces 429 at
 * the transport edge. Keeping it in-memory here adds NO new dependency.
 *
 * <p>Client identity is taken from the configured header (default
 * {@code X-Client-Id}) when present, else the remote address. The fixed window
 * is {@code limit} requests per 60 seconds. The {@link Clock} is injectable so
 * the window roll-over is unit-testable without sleeps.
 *
 * <p>Enabled by {@code dlap.mcppep.ratelimit.enabled} (default {@code false});
 * ceiling by {@code dlap.mcppep.ratelimit.limit} (default 600 per minute).
 */
public class RateLimitHeaderFilter extends OncePerRequestFilter {

    /** Fixed window length in seconds. */
    static final long WINDOW_SECONDS = 60L;

    static final String HEADER_LIMIT = "X-RateLimit-Limit";
    static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    static final String HEADER_RESET = "X-RateLimit-Reset";

    private final long limit;
    private final String clientHeader;
    private final Clock clock;

    /** Per-client window state: [windowStartSecond, countInWindow]. */
    private final ConcurrentHashMap<String, AtomicLongArray> windows = new ConcurrentHashMap<>();

    public RateLimitHeaderFilter(long limit, String clientHeader, Clock clock) {
        this.limit = limit <= 0 ? 1 : limit;
        this.clientHeader = (clientHeader == null || clientHeader.isBlank())
                ? "X-Client-Id" : clientHeader;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Snapshot snap = recordAndSnapshot(clientKey(request));
        response.setHeader(HEADER_LIMIT, Long.toString(limit));
        response.setHeader(HEADER_REMAINING, Long.toString(snap.remaining()));
        response.setHeader(HEADER_RESET, Long.toString(snap.resetEpochSecond()));
        filterChain.doFilter(request, response);
    }

    private String clientKey(HttpServletRequest request) {
        String hdr = request.getHeader(clientHeader);
        if (hdr != null && !hdr.isBlank()) {
            return "hdr:" + hdr.trim();
        }
        String remote = request.getRemoteAddr();
        return "ip:" + (remote == null ? "unknown" : remote);
    }

    /**
     * Increment the caller's window counter and return the post-increment
     * advisory snapshot. Package-private so the window logic is unit-testable
     * directly without a servlet request.
     */
    Snapshot recordAndSnapshot(String clientKey) {
        long nowSecond = clock.instant().getEpochSecond();
        long windowStart = nowSecond - Math.floorMod(nowSecond, WINDOW_SECONDS);

        AtomicLongArray state = windows.computeIfAbsent(clientKey,
                k -> new AtomicLongArray(new long[] {windowStart, 0L}));

        long count;
        synchronized (state) {
            if (state.get(0) != windowStart) {
                // Window rolled over: reset for the new window.
                state.set(0, windowStart);
                state.set(1, 0L);
            }
            count = state.incrementAndGet(1);
        }

        long remaining = Math.max(0L, limit - count);
        long resetEpochSecond = windowStart + WINDOW_SECONDS;
        return new Snapshot(limit, remaining, resetEpochSecond);
    }

    /** Advisory rate-limit snapshot emitted as response headers. */
    record Snapshot(long limit, long remaining, long resetEpochSecond) {
    }
}
