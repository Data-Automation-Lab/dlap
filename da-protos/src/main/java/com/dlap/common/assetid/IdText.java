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

package com.dlap.common.assetid;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Package-internal text helpers and result factories shared by the schemes. */
final class IdText {

    private IdText() {
    }

    // ---- results -------------------------------------------------------------------------

    static Optional<AssetIdParseResult> ok(CanonicalAssetId id) {
        return Optional.of(new AssetIdParseResult.Normalized(id, null));
    }

    static Optional<AssetIdParseResult> reject(AssetIdParseResult.Reason reason, String detail) {
        return Optional.of(new AssetIdParseResult.Rejected(reason, detail, null));
    }

    static Optional<AssetIdParseResult> malformed(String detail) {
        return reject(AssetIdParseResult.Reason.MALFORMED, detail);
    }

    static Optional<AssetIdParseResult> missingHint(String detail) {
        return reject(AssetIdParseResult.Reason.MISSING_HINT, detail);
    }

    static Optional<AssetIdParseResult> unsupported(String detail) {
        return reject(AssetIdParseResult.Reason.UNSUPPORTED_KIND, detail);
    }

    static Optional<AssetIdParseResult> ambiguous(String detail) {
        return reject(AssetIdParseResult.Reason.AMBIGUOUS, detail);
    }

    static AssetIdParseResult withRaw(AssetIdParseResult result, String raw) {
        if (result instanceof AssetIdParseResult.Normalized n) {
            return new AssetIdParseResult.Normalized(n.canonical(), raw);
        }
        AssetIdParseResult.Rejected r = (AssetIdParseResult.Rejected) result;
        return new AssetIdParseResult.Rejected(r.reason(), r.detail(), raw);
    }

    // ---- strings -------------------------------------------------------------------------

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }

    static boolean startsWithIgnoreCase(String s, String prefix) {
        return s.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /** The text after {@code prefix} when {@code s} starts with it (ignoring case), else null. */
    static String afterPrefix(String s, String prefix) {
        return startsWithIgnoreCase(s, prefix) ? s.substring(prefix.length()) : null;
    }

    static boolean isAllDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Splits on {@code sep} keeping empty segments. */
    static List<String> split(String s, char sep) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == sep) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    static boolean anyEmpty(List<String> parts) {
        for (String p : parts) {
            if (p == null || p.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** True when the string contains any ISO control character. */
    static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Removes a query string and fragment from a URL path. */
    static String stripQueryAndFragment(String s) {
        int q = s.indexOf('?');
        int h = s.indexOf('#');
        int cut = s.length();
        if (q >= 0) {
            cut = Math.min(cut, q);
        }
        if (h >= 0) {
            cut = Math.min(cut, h);
        }
        return s.substring(0, cut);
    }

    /** Splits an http(s) URL into {@code [host, path]}; the path keeps its leading slash. */
    static String[] hostAndPath(String afterScheme) {
        String rest = stripQueryAndFragment(afterScheme);
        int slash = rest.indexOf('/');
        String host = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "" : rest.substring(slash);
        int at = host.lastIndexOf('@');
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        int colon = host.indexOf(':');
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        return new String[] {lower(host), path};
    }

    /**
     * Strict percent-decoding of a URL path component into UTF-8. A {@code +} stays a {@code +}.
     *
     * @return the decoded text, or empty when an escape or the UTF-8 is invalid
     */
    static Optional<String> percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return Optional.of(s);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(s.length());
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    return Optional.empty();
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    return Optional.empty();
                }
                bytes.write((hi << 4) | lo);
                i += 3;
                continue;
            }
            if (bytes.size() > 0) {
                Optional<String> chunk = utf8(bytes.toByteArray());
                if (chunk.isEmpty()) {
                    return Optional.empty();
                }
                out.append(chunk.get());
                bytes.reset();
            }
            out.append(c);
            i++;
        }
        if (bytes.size() > 0) {
            Optional<String> chunk = utf8(bytes.toByteArray());
            if (chunk.isEmpty()) {
                return Optional.empty();
            }
            out.append(chunk.get());
        }
        return Optional.of(out.toString());
    }

    private static Optional<String> utf8(byte[] b) {
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b))
                    .toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    /** Removes every backtick (BigQuery and Databricks SQL quoting; neither allows one in a name). */
    static String stripBackticks(String s) {
        return s.indexOf('`') < 0 ? s : s.replace("`", "");
    }

    /**
     * The AWS partition for a region: {@code cn-*} is {@code aws-cn}, {@code us-gov-*}
     * is {@code aws-us-gov}, {@code us-iso-*} is {@code aws-iso}, {@code us-isob-*} is
     * {@code aws-iso-b}, otherwise {@code aws}.
     */
    static String awsPartitionForRegion(String region) {
        String r = lower(region);
        if (r.startsWith("cn-")) {
            return "aws-cn";
        }
        if (r.startsWith("us-gov-")) {
            return "aws-us-gov";
        }
        if (r.startsWith("us-isob-")) {
            return "aws-iso-b";
        }
        if (r.startsWith("us-iso-")) {
            return "aws-iso";
        }
        return "aws";
    }
}
