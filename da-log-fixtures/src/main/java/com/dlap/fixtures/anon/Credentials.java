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

import com.dlap.fixtures.anon.Tokens.Span;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the credential shapes that the anonymiser replaces and the leak scanner refuses: STS session
 * tokens, secret access keys and private key blocks. Both classes use these finders, so what the
 * scanner flags is exactly what the anonymiser is supposed to have replaced.
 *
 * <p>A credential in a public dataset is usually expired, but a secret scanner cannot know that, and a
 * corpus that carries credential-shaped values trains people to ignore the scanner. So the corpus
 * carries none: every such value becomes a placeholder that is obviously fake, keeps the length of the
 * original, and keeps the format prefix where the prefix means something (an STS token starts with a
 * fixed header that names its format). A placeholder is the optional prefix, the marker
 * {@value #MARKER}, then upper case letters and digits only; real credentials are mixed case base64
 * and never look like that.
 *
 * <p>Access key ids (AKIA, ASIA and the other unique id prefixes) are not handled here: the AWS unique id
 * rule of {@link Anonymiser} already keeps their prefix and length and writes the same marker.
 */
final class Credentials {

    /** Written right after the format prefix of every placeholder credential. */
    static final String MARKER = "EXAMPLE";

    /** A keyed value shorter than this is not a credential (a test word, a mask); it is left alone. */
    static final int MIN_LENGTH = 16;

    /** Value that CloudTrail writes when it removes a secret from a record itself. */
    private static final String CLOUDTRAIL_REDACTION = "HIDDEN_DUE_TO_SECURITY_REASONS";

    /**
     * The fixed header of an STS session token, which names its format: the current format
     * ({@code origin_ec} after two version characters) and the two older ones.
     */
    private static final String STS_HEAD = "(?:[A-Za-z0-9]{2}oJb3JpZ2luX2Vj|F[Qw]oGZXIvYXdz|[AF]QoDYXdz)";

    /**
     * An STS session token in free text or a URL: the header, then base64 (plain, URL safe or percent
     * encoded as it appears in a query string).
     */
    private static final Pattern STS_TOKEN = Pattern.compile(
            "(?<![A-Za-z0-9+/_-])" + STS_HEAD + "(?:[A-Za-z0-9+/_-]|%2[BbFf]){40,}(?:=|%3[Dd]){0,2}");

    /** A placeholder: optional token header, the marker, then upper case letters and digits to the end. */
    private static final Pattern PLACEHOLDER = Pattern.compile("(?:" + STS_HEAD + ")?" + MARKER + "[A-Z0-9]*");

    /** The header alone, to carry it over into a placeholder. */
    private static final Pattern HEAD_AT_START = Pattern.compile(STS_HEAD);

    /**
     * An AWS secret access key (exactly 40 characters of base64) on the right of an assignment or a
     * JSON-like pair, as in {@code aws_secret_access_key = ...} or {@code "SecretAccessKey": "..."}.
     * Group 1 is the key.
     */
    private static final Pattern SECRET_KEY_ASSIGNMENT = Pattern.compile(
            "(?i)(?:secret_?access_?key|secret_?key|access_?key_?secret)[\"']?\\s*[:=]\\s*[\"']?"
                    + "([A-Za-z0-9/+]{40})(?![A-Za-z0-9/+])");

    /** The first line of a PEM private key block, whatever the algorithm. */
    private static final Pattern PRIVATE_KEY_HEADER = Pattern.compile("-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----");

    /** A value that only masks a secret. */
    private static final Pattern MASK = Pattern.compile("(?i)\\[?redacted]?|[*xX#.-]+");

    /** JSON field names that hold a session token, compared after {@link #normalise(String)}. */
    private static final Set<String> SESSION_TOKEN_FIELDS = Set.of("sessiontoken", "securitytoken", "xamzsecuritytoken");

    /** JSON field names that hold a secret access key, compared after {@link #normalise(String)}. */
    private static final Set<String> SECRET_KEY_FIELDS =
            Set.of("secretaccesskey", "awssecretaccesskey", "secretkey", "accesskeysecret");

    private Credentials() {
    }

    /**
     * Tells whether a JSON field name holds a session token ({@code sessionToken}, {@code SessionToken},
     * {@code session_token}, {@code securityToken}, {@code X-Amz-Security-Token}).
     *
     * @param field the field name
     * @return true for the session token names
     */
    static boolean isSessionTokenField(String field) {
        return SESSION_TOKEN_FIELDS.contains(normalise(field));
    }

    /**
     * Tells whether a JSON field name holds a secret access key ({@code secretAccessKey},
     * {@code SecretAccessKey}, {@code aws_secret_access_key}, {@code secretKey}).
     *
     * @param field the field name
     * @return true for the secret key names
     */
    static boolean isSecretKeyField(String field) {
        return SECRET_KEY_FIELDS.contains(normalise(field));
    }

    /**
     * Tells whether a JSON field name holds a credential that this class knows.
     *
     * @param field the field name
     * @return true for session token and secret key names
     */
    static boolean isCredentialField(String field) {
        return isSessionTokenField(field) || isSecretKeyField(field);
    }

    private static String normalise(String field) {
        return field.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    /**
     * Tells whether the value of a credential field is a real-looking credential: long enough, not a
     * mask, not CloudTrail's own redaction and not already a placeholder.
     *
     * @param value the field value
     * @return true when the value must be replaced (anonymiser) or reported (scanner)
     */
    static boolean isCredentialValue(String value) {
        return value.length() >= MIN_LENGTH && !CLOUDTRAIL_REDACTION.equals(value) && !MASK.matcher(value).matches()
                && !isPlaceholder(value);
    }

    /**
     * Tells whether a value is a placeholder this class writes.
     *
     * @param value the value
     * @return true for the optional token header, the marker and then upper case letters and digits
     */
    static boolean isPlaceholder(String value) {
        return PLACEHOLDER.matcher(value).matches();
    }

    /**
     * Returns the STS token header a value starts with.
     *
     * @param value the credential
     * @return the header, or the empty string when the value does not start with one
     */
    static String headOf(String value) {
        Matcher m = HEAD_AT_START.matcher(value);
        return m.lookingAt() ? m.group() : "";
    }

    /**
     * Tells whether a text holds a credential by its shape alone, whatever field it came from.
     *
     * @param text the text
     * @return true when {@link #sessionTokens(String)} or {@link #secretKeys(String)} would find something
     */
    static boolean hasShape(String text) {
        return STS_TOKEN.matcher(text).find() || SECRET_KEY_ASSIGNMENT.matcher(text).find();
    }

    /**
     * Finds STS session tokens by their header, placeholders included.
     *
     * @param text the text to search
     * @return the tokens in order of appearance
     */
    static List<Span> sessionTokens(String text) {
        List<Span> out = new ArrayList<>();
        Matcher m = STS_TOKEN.matcher(text);
        while (m.find()) {
            out.add(new Span(m.start(), m.end(), m.group()));
        }
        return out;
    }

    /**
     * Finds secret access keys on the right of an assignment, placeholders and CloudTrail's redaction
     * excluded by the 40 character base64 shape.
     *
     * @param text the text to search
     * @return the keys in order of appearance (the key only, not the name before it)
     */
    static List<Span> secretKeys(String text) {
        List<Span> out = new ArrayList<>();
        Matcher m = SECRET_KEY_ASSIGNMENT.matcher(text);
        while (m.find()) {
            out.add(new Span(m.start(1), m.end(1), m.group(1)));
        }
        return out;
    }

    /**
     * Finds the header line of a PEM private key block.
     *
     * @param text the text to search
     * @return the header lines in order of appearance
     */
    static List<Span> privateKeyHeaders(String text) {
        List<Span> out = new ArrayList<>();
        Matcher m = PRIVATE_KEY_HEADER.matcher(text);
        while (m.find()) {
            out.add(new Span(m.start(), m.end(), m.group()));
        }
        return out;
    }

    /**
     * Builds a placeholder of an exact length: the core, then the marker repeated, cut to length.
     *
     * @param core   how the placeholder starts (optional header, marker, pseudonymous digits)
     * @param length the length of the credential it replaces
     * @return the placeholder
     */
    static String fill(String core, int length) {
        StringBuilder out = new StringBuilder(core);
        while (out.length() < length) {
            out.append(MARKER);
        }
        out.setLength(length);
        return out.toString();
    }
}
