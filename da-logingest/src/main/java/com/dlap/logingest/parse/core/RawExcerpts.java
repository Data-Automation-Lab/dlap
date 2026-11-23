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

package com.dlap.logingest.parse.core;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the redacted, bounded {@code raw_excerpt} of a quarantine record (log-event spec section
 * 9, CLAUDE.md section 11: never log credentials or personal data).
 *
 * <p>Email addresses become {@value #EMAIL_MASK}, IPv4 addresses and IPv6 addresses (written with
 * {@code ::} or at least five colons) become {@value #IP_MASK}. Redaction runs on a window
 * {@value #WINDOW_SLACK} characters wider than the bound before the cut, so an address that
 * straddles the cut is masked rather than left half visible.
 */
public final class RawExcerpts {

    /** Default excerpt bound in UTF-8 bytes. */
    public static final int DEFAULT_MAX_BYTES = 2048;

    /** Replacement for an email address. */
    public static final String EMAIL_MASK = "<email>";

    /** Replacement for an IP address. */
    public static final String IP_MASK = "<ip>";

    /** Extra characters redacted beyond the bound. */
    public static final int WINDOW_SLACK = 256;

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern IPV4 = Pattern.compile("(?<![0-9.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])");
    private static final Pattern IPV6 = Pattern.compile("(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?![0-9A-Fa-f:])");

    private RawExcerpts() {
    }

    /**
     * A redacted excerpt.
     *
     * @param text      the excerpt
     * @param truncated true when the record was cut at the bound
     */
    public record Excerpt(String text, boolean truncated) {
    }

    /**
     * Redacts and bounds a record's text.
     *
     * @param raw      the record text
     * @param maxBytes the bound in UTF-8 bytes, at least 1
     * @return the excerpt
     */
    public static Excerpt excerpt(String raw, int maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        if (raw == null || raw.isEmpty()) {
            return new Excerpt("", false);
        }
        int window = (int) Math.min(raw.length(), (long) maxBytes + WINDOW_SLACK);
        boolean cutWindow = window < raw.length();
        String redacted = redact(raw.substring(0, window));
        String bounded = cut(redacted, maxBytes);
        return new Excerpt(bounded, cutWindow || bounded.length() < redacted.length());
    }

    /**
     * Masks email and IP addresses.
     *
     * @param text the text
     * @return the masked text
     */
    public static String redact(String text) {
        String out = EMAIL.matcher(text).replaceAll(Matcher.quoteReplacement(EMAIL_MASK));
        out = IPV4.matcher(out).replaceAll(Matcher.quoteReplacement(IP_MASK));
        Matcher m = IPV6.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String hit = m.group();
            boolean ipv6 = hit.contains("::") || hit.chars().filter(c -> c == ':').count() >= 5;
            m.appendReplacement(sb, Matcher.quoteReplacement(ipv6 ? IP_MASK : hit));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String cut(String text, int maxBytes) {
        if (text.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return text;
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (used + len > maxBytes) {
                break;
            }
            sb.appendCodePoint(cp);
            used += len;
            i += Character.charCount(cp);
        }
        return sb.toString();
    }
}
