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

package com.dlap.fixtures.anon;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the identifier shapes that the anonymiser rewrites and the leak scanner checks: IPv4 and
 * IPv6 literals, e-mail addresses, twelve digit account numbers and AWS unique ids. Both classes use
 * these finders, so what the scanner flags is exactly what the anonymiser is supposed to have
 * rewritten.
 *
 * <p>Nothing here contacts the network: addresses are parsed by hand.
 */
final class Tokens {

    /** One occurrence of an identifier shape in a text. */
    record Span(int start, int end, String text) {
    }

    /** Four dotted decimal groups that are not part of a longer dotted or numeric token. */
    private static final Pattern IPV4 = Pattern.compile(
            "(?<![0-9])(?<![0-9]\\.)([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})(?![0-9])(?!\\.[0-9])");

    /** A maximal run of hex digits and colons; validated by {@link #parseIpv6(String)}. */
    private static final Pattern IPV6_RUN = Pattern.compile("[0-9A-Fa-f:]*:[0-9A-Fa-f:]*");

    /** An e-mail address. The local part may not contain a colon or a slash, so {@code user:a@b.c} yields {@code a@b.c}. */
    static final Pattern EMAIL = Pattern.compile(
            "(?<![A-Za-z0-9._%+'-])[A-Za-z0-9._%+'-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+");

    /** A standalone run of exactly twelve digits. */
    static final Pattern DIGITS12 = Pattern.compile("(?<![0-9])[0-9]{12}(?![0-9])");

    /** AWS unique id prefixes: access keys, users, roles, groups, policies, instance profiles and the like. */
    static final Pattern AWS_UNIQUE_ID = Pattern.compile(
            "(?<![A-Za-z0-9])(AKIA|ASIA|AIDA|AROA|AIPA|ANPA|ANVA|AGPA|ACCA|ABIA|ASCA|APKA)([A-Z0-9]{16,17})(?![A-Za-z0-9])");

    /** Marker the anonymiser writes at offset 4 of every AWS unique id it produces. */
    static final String AWS_ID_MARKER = "EXAMPLE";

    /** Account numbers that AWS documentation and test suites use as placeholders. */
    static final Set<String> PLACEHOLDER_ACCOUNTS = Set.of(
            "123456789012", "012345678901", "012345678912", "111122223333", "444455556666", "777788889999",
            "000000000000", "111111111111", "222222222222", "333333333333", "444444444444", "555555555555",
            "666666666666", "777777777777", "888888888888", "999999999999", "210987654321", "987654321098");

    private Tokens() {
    }

    /**
     * Finds IPv4 literals. A match that looks like part of a version string (preceded by a letter and
     * a slash or dash, as in {@code Chrome/7.7.7.7} or {@code Linux-6.6.6.6-generic}) is not an address
     * and is left out.
     *
     * @param text the text to search
     * @return the literals in order of appearance
     */
    static List<Span> ipv4(String text) {
        List<Span> out = new ArrayList<>();
        Matcher m = IPV4.matcher(text);
        while (m.find()) {
            boolean valid = true;
            for (int g = 1; g <= 4; g++) {
                if (Integer.parseInt(m.group(g)) > 255) {
                    valid = false;
                }
            }
            if (!valid || looksLikeVersion(text, m.start())) {
                continue;
            }
            out.add(new Span(m.start(), m.end(), m.group()));
        }
        return out;
    }

    private static boolean looksLikeVersion(String text, int start) {
        if (start < 2) {
            return false;
        }
        char sep = text.charAt(start - 1);
        char before = text.charAt(start - 2);
        return (sep == '/' || sep == '-' || sep == '_') && Character.isLetter(before);
    }

    /**
     * Finds IPv6 literals: a run of hex digits and colons that is a well-formed address (eight groups,
     * or fewer with one {@code ::}). Times such as {@code 12:30:45}, MAC addresses and ARNs are not.
     *
     * @param text the text to search
     * @return the literals in order of appearance
     */
    static List<Span> ipv6(String text) {
        List<Span> out = new ArrayList<>();
        Matcher m = IPV6_RUN.matcher(text);
        while (m.find()) {
            int start = m.start();
            int end = m.end();
            if (start > 0 && (Character.isLetterOrDigit(text.charAt(start - 1)) || text.charAt(start - 1) == '.')) {
                continue;
            }
            if (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '.')) {
                continue;
            }
            if (parseIpv6(m.group()) != null) {
                out.add(new Span(start, end, m.group()));
            }
        }
        return out;
    }

    /**
     * Parses an IPv6 literal into its eight 16 bit groups.
     *
     * @param s the text, without brackets or a zone
     * @return the groups, or null when the text is not a well-formed address
     */
    static int[] parseIpv6(String s) {
        if (s.isEmpty() || s.contains(":::") || s.indexOf(':') < 0) {
            return null;
        }
        int dc = s.indexOf("::");
        if (dc >= 0 && s.indexOf("::", dc + 1) >= 0) {
            return null;
        }
        String head = dc < 0 ? s : s.substring(0, dc);
        String tail = dc < 0 ? "" : s.substring(dc + 2);
        List<Integer> a = groups(head);
        List<Integer> b = groups(tail);
        if (a == null || b == null) {
            return null;
        }
        int[] out = new int[8];
        if (dc < 0) {
            if (a.size() != 8) {
                return null;
            }
            for (int i = 0; i < 8; i++) {
                out[i] = a.get(i);
            }
            return out;
        }
        if (a.size() + b.size() > 7) {
            return null;
        }
        for (int i = 0; i < a.size(); i++) {
            out[i] = a.get(i);
        }
        for (int i = 0; i < b.size(); i++) {
            out[8 - b.size() + i] = b.get(i);
        }
        return out;
    }

    private static List<Integer> groups(String part) {
        List<Integer> out = new ArrayList<>();
        if (part.isEmpty()) {
            return out;
        }
        for (String g : part.split(":", -1)) {
            if (g.isEmpty() || g.length() > 4) {
                return null;
            }
            try {
                out.add(Integer.parseInt(g, 16));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return out;
    }

    /**
     * Tells whether an IPv4 address is in a range that identifies no host on the public internet:
     * this network, private, shared address space, loopback, link local, IETF protocol, the three
     * documentation blocks, benchmarking, multicast and reserved.
     *
     * @param dotted the address in dotted decimal form
     * @return true when the address is reserved
     */
    static boolean isReservedIpv4(String dotted) {
        String[] p = dotted.split("\\.");
        int a = Integer.parseInt(p[0]);
        int b = Integer.parseInt(p[1]);
        int c = Integer.parseInt(p[2]);
        return a == 0
                || a == 10
                || (a == 100 && b >= 64 && b <= 127)
                || a == 127
                || (a == 169 && b == 254)
                || (a == 172 && b >= 16 && b <= 31)
                || (a == 192 && b == 0 && c == 0)
                || (a == 192 && b == 0 && c == 2)
                || (a == 192 && b == 168)
                || (a == 198 && (b == 18 || b == 19))
                || (a == 198 && b == 51 && c == 100)
                || (a == 203 && b == 0 && c == 113)
                || a >= 224;
    }

    /**
     * Tells whether an IPv6 address is unspecified, loopback, unique local, link local, multicast,
     * documentation (2001:db8::/32) or an IPv4 mapped address of a reserved IPv4 address.
     *
     * @param g the eight groups
     * @return true when the address is reserved
     */
    static boolean isReservedIpv6(int[] g) {
        boolean allZeroButLast = true;
        for (int i = 0; i < 7; i++) {
            if (g[i] != 0) {
                allZeroButLast = false;
            }
        }
        if (allZeroButLast && (g[7] == 0 || g[7] == 1)) {
            return true;
        }
        if ((g[0] & 0xfe00) == 0xfc00 || (g[0] & 0xffc0) == 0xfe80 || (g[0] & 0xff00) == 0xff00) {
            return true;
        }
        if (g[0] == 0x2001 && g[1] == 0x0db8) {
            return true;
        }
        if (g[0] == 0 && g[1] == 0 && g[2] == 0 && g[3] == 0 && g[4] == 0 && g[5] == 0xffff) {
            String v4 = (g[6] >> 8) + "." + (g[6] & 0xff) + "." + (g[7] >> 8) + "." + (g[7] & 0xff);
            return isReservedIpv4(v4);
        }
        return false;
    }

    /**
     * Tells whether an e-mail domain is reserved for examples (RFC 2606 and RFC 6761): example.com,
     * example.net, example.org and their subdomains, and the .test, .example, .invalid and .localhost
     * top level domains.
     *
     * @param domain the domain part of an address
     * @return true when no real mailbox can exist there
     */
    static boolean isExampleDomain(String domain) {
        String d = domain.toLowerCase(Locale.ROOT);
        for (String base : new String[] {"example.com", "example.net", "example.org"}) {
            if (d.equals(base) || d.endsWith("." + base)) {
                return true;
            }
        }
        int dot = d.lastIndexOf('.');
        String tld = dot < 0 ? d : d.substring(dot + 1);
        return tld.equals("test") || tld.equals("example") || tld.equals("invalid") || tld.equals("localhost");
    }

    /**
     * Tells whether a twelve digit run is the last group of a dashed GUID, as in
     * {@code 00000000-0000-0000-0000-000000000001}. GUIDs have their own rule; they are not account numbers.
     *
     * @param text  the text
     * @param start the start of the twelve digit run
     * @param end   the end of the twelve digit run
     * @return true when the run completes a GUID
     */
    static boolean insideGuid(String text, int start, int end) {
        if (start < 24 || text.charAt(start - 1) != '-') {
            return false;
        }
        return GUID_HEAD.matcher(text).region(start - 24, start).matches()
                && (start - 24 == 0 || !isHex(text.charAt(start - 25)));
    }

    /** The first four groups of a dashed GUID, with the dash that follows. */
    private static final Pattern GUID_HEAD = Pattern.compile(
            "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-");

    /**
     * Tells whether a run of characters is part of a long hexadecimal token (a hash or a key id), in
     * which a twelve digit run is chance, not an account number.
     *
     * @param text  the text
     * @param start the start of the twelve digit run
     * @param end   the end of the twelve digit run
     * @return true when the run sits inside a hex token of at least 20 characters
     */
    static boolean insideLongHex(String text, int start, int end) {
        int s = start;
        while (s > 0 && isHex(text.charAt(s - 1))) {
            s--;
        }
        int e = end;
        while (e < text.length() && isHex(text.charAt(e))) {
            e++;
        }
        return (e - s) >= 20 && (s < start || e > end);
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
