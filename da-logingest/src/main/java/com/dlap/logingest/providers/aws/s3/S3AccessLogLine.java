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

package com.dlap.logingest.providers.aws.s3;

import com.dlap.logingest.parse.s3accesslog.S3AccessLogFormat;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits one line of the S3 server access log text format into the field names of the CloudWatch
 * Logs record (decision D36, Q-641), so the {@code s3accesslog} family's {@code cwl-json} mapping
 * parses it unchanged.
 *
 * <p>The text format (https://docs.aws.amazon.com/AmazonS3/latest/userguide/LogFormat.html, opened
 * 2026-11-01) is space delimited, in this order: bucket owner, bucket, time, remote IP, requester,
 * request ID, operation, key, Request-URI, HTTP status, error code, bytes sent, object size, total
 * time, turn-around time, Referer, User-Agent, version ID, host ID, signature version, cipher suite,
 * authentication type, host header, TLS version, access point ARN, aclRequired, source region. The
 * CloudWatch Logs names of the same fields (sal-cw-log-format) are {@link #FIELDS}.
 * <ul>
 *   <li>Time is {@code [%d/%b/%Y:%H:%M:%S %z]} and becomes an ISO-8601 instant
 *       ({@code 2019-02-06T00:00:38Z}).</li>
 *   <li>Request-URI, Referer and User-Agent are quoted and "may include unescaped quotes from the
 *       user input": a closing quote is a quote followed by a space or the line end, and of several
 *       such quotes the first one after which the next field has its expected shape is taken (an
 *       HTTP status after the Request-URI, a quoted value or {@code -} after the Referer, a signature
 *       version two fields after the User-Agent).</li>
 *   <li>{@code -}, quoted or not, means "unknown, unavailable or not applicable" and becomes JSON
 *       {@code null}; a field the line does not reach (an older, shorter record) is {@code null} too.</li>
 *   <li>The numeric fields (HTTP status, bytes sent, object size, total and turn-around time) become
 *       JSON numbers, as in the CloudWatch Logs record.</li>
 *   <li>Fields after the last known one are ignored: AWS adds fields at the end of the line and asks
 *       parsers to tolerate them (LogFormat, "extensible server access log format").</li>
 *   <li>{@code schema_version_id} is set to {@code V_1_0}, the CloudWatch Logs schema whose field names
 *       the object carries; {@code bucket_arn}, which the text format does not hold, is {@code null}.</li>
 *   <li>The key is kept as written; the text format does not say it is URL encoded.</li>
 * </ul>
 * A line with fewer than eight fields (through the key), an unclosed quote or bracket, or a time that
 * does not parse is malformed; the transport delivers it as text and the registry quarantines it.
 */
public final class S3AccessLogLine {

    /** The CloudWatch Logs field names, in text-format order. */
    public static final List<String> FIELDS = List.of(
            "bucket_owner_id", "bucket_name", "request_time", "remote_ip", "requester", "request_id",
            "operation", "key_name", "request_uri", "http_status", "error_code", "bytes_sent_size",
            "object_size", "total_duration", "turn_around_duration", "referer", "user_agent", "version_id",
            "host_id", "signature_version", "cipher_suite", "authentication_type", "host_header", "tls_version",
            "access_point_arn", "acl_required", "source_region");

    /** Fields through {@code key_name}: fewer is not a record. */
    static final int MIN_FIELDS = 8;

    private static final int TIME = 2;
    private static final int REQUEST_URI = 8;
    private static final int REFERER = 15;
    private static final int USER_AGENT = 16;
    /** The fields the text format quotes; a quote elsewhere (in a key, say) is an ordinary character. */
    private static final Set<Integer> QUOTED = Set.of(REQUEST_URI, REFERER, USER_AGENT);
    private static final Set<String> NUMERIC = Set.of("http_status", "bytes_sent_size", "object_size",
            "total_duration", "turn_around_duration");
    private static final Pattern HTTP_STATUS = Pattern.compile("\\d{3}|-");
    private static final Pattern SIGNATURE = Pattern.compile("SigV\\d|-");
    private static final Pattern DIGITS = Pattern.compile("\\d{1,18}");
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private S3AccessLogLine() {
    }

    /** One field as written: its text without brackets or quotes, and whether it was quoted. */
    private record Token(String text, boolean quoted) {
    }

    /**
     * Parses one line.
     *
     * @param line the line, without its line break
     * @return the record object, or empty for a malformed line
     */
    public static Optional<ObjectNode> parse(String line) {
        if (line == null) {
            return Optional.empty();
        }
        Optional<List<Token>> tokens = tokens(line.strip());
        if (tokens.isEmpty() || tokens.get().size() < MIN_FIELDS) {
            return Optional.empty();
        }
        List<Token> t = tokens.get();
        ObjectNode out = NODES.objectNode();
        out.put("schema_version_id", S3AccessLogFormat.V_1_0);
        out.putNull("bucket_arn");
        for (int i = 0; i < FIELDS.size(); i++) {
            String name = FIELDS.get(i);
            String value = i < t.size() ? t.get(i).text() : null;
            if (value == null || "-".equals(value)) {
                out.putNull(name);
            } else if (i == TIME) {
                Optional<String> instant = instant(value);
                if (instant.isEmpty()) {
                    return Optional.empty();
                }
                out.put(name, instant.get());
            } else if (NUMERIC.contains(name) && DIGITS.matcher(value).matches()) {
                out.put(name, Long.parseLong(value));
            } else {
                out.put(name, value);
            }
        }
        return Optional.of(out);
    }

    /**
     * The access log time as an ISO-8601 instant.
     *
     * @param text the time without its brackets ({@code 06/Feb/2019:00:00:38 +0000})
     * @return the instant text, or empty when it does not parse
     */
    static Optional<String> instant(String text) {
        try {
            return Optional.of(OffsetDateTime.parse(text, TIME_FORMAT).toInstant().toString());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Optional<List<Token>> tokens(String line) {
        List<Token> out = new ArrayList<>();
        int n = line.length();
        int i = 0;
        while (i < n) {
            while (i < n && line.charAt(i) == ' ') {
                i++;
            }
            if (i >= n) {
                break;
            }
            char c = line.charAt(i);
            int field = out.size();
            if (c == '[' && field == TIME) {
                int end = line.indexOf(']', i);
                if (end < 0) {
                    return Optional.empty();
                }
                out.add(new Token(line.substring(i + 1, end), false));
                i = end + 1;
            } else if (c == '"' && QUOTED.contains(field)) {
                int end = closingQuote(line, i, field);
                if (end < 0) {
                    return Optional.empty();
                }
                out.add(new Token(line.substring(i + 1, end), true));
                i = end + 1;
            } else {
                int end = line.indexOf(' ', i);
                end = end < 0 ? n : end;
                out.add(new Token(line.substring(i, end), false));
                i = end;
            }
        }
        return Optional.of(out);
    }

    /**
     * Finds the quote that closes a quoted field.
     *
     * @param line  the line
     * @param open  the opening quote
     * @param field the field's index
     * @return the closing quote's index, or -1 when there is none
     */
    private static int closingQuote(String line, int open, int field) {
        int first = -1;
        for (int k = line.indexOf('"', open + 1); k >= 0; k = line.indexOf('"', k + 1)) {
            if (k + 1 < line.length() && line.charAt(k + 1) != ' ') {
                continue;
            }
            if (first < 0) {
                first = k;
            }
            if (fits(field, line.substring(k + 1).strip())) {
                return k;
            }
        }
        return first;
    }

    /**
     * Whether the rest of a line after a quoted field has the shape the following fields need.
     *
     * @param field the quoted field's index
     * @param rest  the rest of the line, stripped
     * @return true when the candidate closing quote is plausible
     */
    private static boolean fits(int field, String rest) {
        String[] next = rest.isEmpty() ? new String[0] : rest.split(" +");
        return switch (field) {
            case REQUEST_URI -> next.length == 0 || HTTP_STATUS.matcher(next[0]).matches();
            case REFERER -> next.length == 0 || next[0].startsWith("\"") || "-".equals(next[0]);
            case USER_AGENT -> next.length < 3 || SIGNATURE.matcher(next[2]).matches();
            default -> true;
        };
    }
}
