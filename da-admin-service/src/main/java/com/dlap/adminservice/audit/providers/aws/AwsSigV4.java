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

package com.dlap.adminservice.audit.providers.aws;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.dlap.common.audit.AuditChain;

/**
 * AWS Signature Version 4 for header-based authentication of S3 REST calls (AWS documentation
 * "Authenticating Requests: Using the Authorization Header"). JDK HMAC-SHA256 only; the class
 * holds no state and never logs.
 */
public final class AwsSigV4 {

    /** The SigV4 algorithm name. */
    public static final String ALGORITHM = "AWS4-HMAC-SHA256";

    /** {@code x-amz-date} format. */
    public static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private AwsSigV4() {
    }

    /**
     * Signs a request and returns the headers to send with it.
     *
     * @param method        the HTTP method
     * @param uri           the full request URI; its raw path and raw query are signed
     * @param headers       further headers to sign and send (names in any case), without
     *                      {@code host}, {@code x-amz-date}, {@code x-amz-security-token} and
     *                      {@code authorization}
     * @param payloadSha256 hex SHA-256 of the body, also sent as {@code x-amz-content-sha256}
     * @param credentials   the credentials
     * @param region        the region
     * @param service       the service, {@code s3}
     * @param now           the signing time
     * @return every header to set on the request (all but {@code host}, which the HTTP client
     *         sends from the URI), including {@code authorization}
     */
    public static Map<String, String> sign(String method, URI uri, Map<String, String> headers,
            String payloadSha256, AwsCredentials credentials, String region, String service, Instant now) {
        SortedMap<String, String> signed = new TreeMap<>();
        for (Map.Entry<String, String> h : headers.entrySet()) {
            signed.put(h.getKey().toLowerCase(Locale.ROOT), h.getValue().trim());
        }
        String amzDate = AMZ_DATE.format(now);
        signed.put("x-amz-date", amzDate);
        signed.put("x-amz-content-sha256", payloadSha256);
        if (credentials.sessionToken() != null && !credentials.sessionToken().isBlank()) {
            signed.put("x-amz-security-token", credentials.sessionToken());
        }
        signed.put("host", hostHeader(uri));
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String authorization = authorization(method, path, canonicalQuery(uri.getRawQuery()), signed,
                payloadSha256, credentials, region, service, amzDate);
        Map<String, String> out = new TreeMap<>(signed);
        out.remove("host");
        out.put("authorization", authorization);
        return out;
    }

    /**
     * The {@code Authorization} header value for a request whose canonical parts are given.
     *
     * @param method         the HTTP method
     * @param canonicalUri   the URI-encoded path
     * @param canonicalQuery the canonical query string
     * @param headers        every signed header, lower-case names, trimmed values, including host
     * @param payloadSha256  hex SHA-256 of the body
     * @param credentials    the credentials
     * @param region         the region
     * @param service        the service
     * @param amzDate        the {@code x-amz-date} value
     * @return the header value
     */
    public static String authorization(String method, String canonicalUri, String canonicalQuery,
            SortedMap<String, String> headers, String payloadSha256, AwsCredentials credentials, String region,
            String service, String amzDate) {
        StringBuilder canonicalHeaders = new StringBuilder();
        for (Map.Entry<String, String> h : headers.entrySet()) {
            canonicalHeaders.append(h.getKey()).append(':').append(h.getValue()).append('\n');
        }
        String signedHeaders = String.join(";", headers.keySet());
        String canonicalRequest = method + '\n' + canonicalUri + '\n' + canonicalQuery + '\n' + canonicalHeaders
                + '\n' + signedHeaders + '\n' + payloadSha256;
        String date = amzDate.substring(0, 8);
        String scope = date + '/' + region + '/' + service + "/aws4_request";
        String stringToSign = ALGORITHM + '\n' + amzDate + '\n' + scope + '\n' + AuditChain.sha256Hex(canonicalRequest);
        byte[] key = hmac(("AWS4" + credentials.secretAccessKey()).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, service);
        key = hmac(key, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(key, stringToSign));
        return ALGORITHM + " Credential=" + credentials.accessKeyId() + '/' + scope + ", SignedHeaders="
                + signedHeaders + ", Signature=" + signature;
    }

    /**
     * URI-encodes text the way SigV4 wants: every byte except {@code A-Z a-z 0-9 - _ . ~} as
     * {@code %XX} with upper-case hex; {@code /} kept when {@code keepSlash}.
     *
     * @param text      the text
     * @param keepSlash whether {@code /} stays as is (object key paths)
     * @return the encoded text
     */
    public static String uriEncode(String text, boolean keepSlash) {
        StringBuilder out = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~' || (keepSlash && c == '/')) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return out.toString();
    }

    /**
     * The canonical query string: parameters decoded, re-encoded, sorted by name then value,
     * a parameter without a value written as {@code name=}.
     *
     * @param rawQuery the raw query, or {@code null}
     * @return the canonical query, empty when there is none
     */
    static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        List<String[]> pairs = new ArrayList<>();
        for (String part : rawQuery.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            int eq = part.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            pairs.add(new String[] {uriEncode(name, false), uriEncode(value, false)});
        }
        pairs.sort((a, b) -> a[0].equals(b[0]) ? a[1].compareTo(b[1]) : a[0].compareTo(b[0]));
        List<String> joined = new ArrayList<>();
        for (String[] p : pairs) {
            joined.add(p[0] + '=' + p[1]);
        }
        return String.join("&", joined);
    }

    private static String hostHeader(URI uri) {
        int port = uri.getPort();
        boolean defaultPort = port == -1 || ("https".equals(uri.getScheme()) && port == 443)
                || ("http".equals(uri.getScheme()) && port == 80);
        return defaultPort ? uri.getHost() : uri.getHost() + ':' + port;
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by every Java platform", e);
        }
    }
}
