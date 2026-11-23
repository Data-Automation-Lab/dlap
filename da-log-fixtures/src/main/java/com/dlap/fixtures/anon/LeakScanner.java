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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Finds identifiers in fixture files that the anonymiser should have rewritten.
 *
 * <p>The checks are by shape, not by a list of known real values, so they work without publishing the
 * real values and catch values nobody listed:
 * <ul>
 *   <li>an e-mail address must be at a reserved example domain, a Google owned service account, or a
 *       pseudonymous customer service account;</li>
 *   <li>an IPv4 or IPv6 literal must be in a reserved range (private, loopback, documentation,
 *       benchmarking and similar), which includes every address the anonymiser produces;</li>
 *   <li>a twelve digit number must be a documentation placeholder or an account pseudonym;</li>
 *   <li>an AWS unique id (AKIA, ASIA, AIDA, AROA ...) must carry the {@code EXAMPLE} marker;</li>
 *   <li>a credential must be a placeholder: an STS session token (by its header, or in a session token
 *       field), a secret access key (after {@code secretAccessKey} and similar names, or in a field of
 *       that name) and a PEM private key block are all findings unless they carry the placeholder
 *       shape of {@link Credentials}; a private key block is never a placeholder;</li>
 *   <li>in JSON, a {@code userName} outside a session issuer, a {@code project_id} or {@code projectId},
 *       a {@code projects/<id>} path and an IAM user ARN must name a pseudonym, a generic word or a
 *       public Google project.</li>
 * </ul>
 * Findings never echo the value: they give the kind, the place, the length and the first two characters
 * (for a credential, the length only).
 */
public final class LeakScanner {

    /** What kind of identifier a finding is about. */
    public enum Kind {
        /** An e-mail address outside the allowed domains. */
        EMAIL,
        /** A public IPv4 address. */
        IPV4,
        /** A public IPv6 address. */
        IPV6,
        /** A twelve digit number that is neither a placeholder nor a pseudonym. */
        ACCOUNT_ID,
        /** An AWS unique id without the pseudonym marker. */
        AWS_ID,
        /** An organization, folder or access policy number that is not a pseudonym. */
        ORG_ID,
        /** An Azure tenant, subscription or object GUID that is neither a placeholder nor a pseudonym. */
        GUID,
        /** A service account key id (40 hex digits after {@code /keys/}) that is not a pseudonym. */
        SERVICE_ACCOUNT_KEY,
        /** A user name that is not a pseudonym. */
        USER_NAME,
        /** A project id that is not a pseudonym. */
        PROJECT_ID,
        /** A session token, secret access key or private key block that is not a placeholder. */
        CREDENTIAL
    }

    /**
     * One identifier that should not be in the corpus.
     *
     * @param kind   what it is
     * @param where  the file, and the JSON path or line where known
     * @param sample the value, redacted to its length and first two characters
     */
    public record Finding(Kind kind, String where, String sample) {

        @Override
        public String toString() {
            return kind + " at " + where + " (" + sample + ")";
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PROJECT_PATH = Pattern.compile(
            "projects/([A-Za-z][A-Za-z0-9-]{4,28}[A-Za-z0-9])(?=[/:\\s\"%]|$)");
    private static final Pattern ARN_USER = Pattern.compile(
            "arn:aws[a-z-]*:iam::[0-9]{12}:user/(?:[^/\\s\":]+/)*([^/\\s\":]+)");
    private static final Pattern GUID_IN_PATH = Pattern.compile(
            "(?i)(?<=/subscriptions/|/tenants/|sts\\.windows\\.net/|login\\.microsoftonline\\.com/)"
                    + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern GUID_VALUE = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{32}");
    private static final Pattern SA_KEY_ID = Pattern.compile("(?<=/keys/)[0-9a-f]{40}(?![0-9a-f])");
    private static final Pattern ORG_NUMBER = Pattern.compile(
            "(?<=organizations/|folders/|accessPolicies/)[0-9]{6,}(?![0-9])");
    private static final Pattern NUMBER_BASED_LOCAL = Pattern.compile("(?:service-)?o?[0-9]{6,}(?:-[A-Za-z0-9]+)?");
    private static final List<String> TEXT_SUFFIXES = List.of(".json", ".jsonl", ".yaml", ".yml", ".md", ".txt", ".log", ".csv");

    private LeakScanner() {
    }

    /**
     * Scans a piece of text for identifiers by shape.
     *
     * @param where a label for the findings, for example a file name
     * @param text  the text
     * @return the findings; empty for clean text
     */
    public static List<Finding> scanText(String where, String text) {
        List<Finding> out = new ArrayList<>();
        Matcher m = Tokens.EMAIL.matcher(text);
        while (m.find()) {
            String email = m.group();
            int at = email.lastIndexOf('@');
            if (!isAllowedEmail(email.substring(0, at), email.substring(at + 1))) {
                out.add(finding(Kind.EMAIL, where + ":" + lineOf(text, m.start()), email));
            }
        }
        for (Span s : Tokens.ipv6(text)) {
            if (!Tokens.isReservedIpv6(Tokens.parseIpv6(s.text()))) {
                out.add(finding(Kind.IPV6, where + ":" + lineOf(text, s.start()), s.text()));
            }
        }
        for (Span s : Tokens.ipv4(text)) {
            if (!Tokens.isReservedIpv4(s.text())) {
                out.add(finding(Kind.IPV4, where + ":" + lineOf(text, s.start()), s.text()));
            }
        }
        m = Tokens.DIGITS12.matcher(text);
        while (m.find()) {
            String d = m.group();
            if (Tokens.insideLongHex(text, m.start(), m.end())
                    || Tokens.insideGuid(text, m.start(), m.end())
                    || Tokens.PLACEHOLDER_ACCOUNTS.contains(d)
                    || Anonymiser.PSEUDO_ACCOUNT.matcher(d).matches()) {
                continue;
            }
            out.add(finding(Kind.ACCOUNT_ID, where + ":" + lineOf(text, m.start()), d));
        }
        m = Tokens.AWS_UNIQUE_ID.matcher(text);
        while (m.find()) {
            if (!m.group(2).startsWith(Tokens.AWS_ID_MARKER)) {
                out.add(finding(Kind.AWS_ID, where + ":" + lineOf(text, m.start()), m.group()));
            }
        }
        scanCredentials(where, text, out);
        m = GUID_IN_PATH.matcher(text);
        while (m.find()) {
            if (!Anonymiser.isKeptGuid(m.group())) {
                out.add(finding(Kind.GUID, where + ":" + lineOf(text, m.start()), m.group()));
            }
        }
        m = SA_KEY_ID.matcher(text);
        while (m.find()) {
            if (!m.group().startsWith(Anonymiser.SA_KEY_MARKER)) {
                out.add(finding(Kind.SERVICE_ACCOUNT_KEY, where + ":" + lineOf(text, m.start()), m.group()));
            }
        }
        m = ORG_NUMBER.matcher(text);
        while (m.find()) {
            if (!m.group().startsWith(Anonymiser.ORG_MARKER)) {
                out.add(finding(Kind.ORG_ID, where + ":" + lineOf(text, m.start()), m.group()));
            }
        }
        m = ARN_USER.matcher(text);
        while (m.find()) {
            if (!isAllowedUserName(m.group(1))) {
                out.add(finding(Kind.USER_NAME, where + ":" + lineOf(text, m.start(1)), m.group(1)));
            }
        }
        m = PROJECT_PATH.matcher(text);
        while (m.find()) {
            if (!isAllowedProject(m.group(1))) {
                out.add(finding(Kind.PROJECT_ID, where + ":" + lineOf(text, m.start(1)), m.group(1)));
            }
        }
        return out;
    }

    private static void scanCredentials(String where, String text, List<Finding> out) {
        for (Span s : Credentials.sessionTokens(text)) {
            if (!Credentials.isPlaceholder(s.text())) {
                out.add(finding(Kind.CREDENTIAL, where + ":" + lineOf(text, s.start()), s.text()));
            }
        }
        for (Span s : Credentials.secretKeys(text)) {
            if (!Credentials.isPlaceholder(s.text())) {
                out.add(finding(Kind.CREDENTIAL, where + ":" + lineOf(text, s.start()), s.text()));
            }
        }
        for (Span s : Credentials.privateKeyHeaders(text)) {
            out.add(finding(Kind.CREDENTIAL, where + ":" + lineOf(text, s.start()), s.text()));
        }
    }

    /**
     * Scans a JSON document: the shape checks on every string and field name, plus the checks that
     * need the field name ({@code userName}, {@code project_id}, {@code projectId}).
     *
     * @param where a label for the findings
     * @param tree  the document
     * @return the findings; empty for a clean document
     */
    public static List<Finding> scanJson(String where, JsonNode tree) {
        List<Finding> out = new ArrayList<>();
        walk(where, "$", tree, new ArrayDeque<>(), out);
        return out;
    }

    private static void walk(String where, String path, JsonNode node, Deque<String> ancestors, List<Finding> out) {
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String childPath = path + "." + e.getKey();
                out.addAll(scanText(where + " " + childPath + " (field name)", e.getKey()));
                JsonNode v = e.getValue();
                if (v.isTextual()) {
                    checkKeyed(where, childPath, e.getKey(), v.asText(), ancestors, out);
                }
                ancestors.push(e.getKey());
                walk(where, childPath, v, ancestors, out);
                ancestors.pop();
            }
        } else if (node.isArray()) {
            int i = 0;
            for (JsonNode v : node) {
                walk(where, path + "[" + i++ + "]", v, ancestors, out);
            }
        } else if (node.isTextual()) {
            out.addAll(scanText(where + " " + path, node.asText()));
        }
    }

    private static void checkKeyed(String where, String path, String field, String value, Deque<String> ancestors,
                                   List<Finding> out) {
        if (field.equals("userName") && !ancestors.contains("sessionIssuer") && !isAllowedUserName(value)) {
            out.add(finding(Kind.USER_NAME, where + " " + path, value));
        }
        if ((field.equals("project_id") || field.equals("projectId")) && !isAllowedProject(value)) {
            out.add(finding(Kind.PROJECT_ID, where + " " + path, value));
        }
        if (Credentials.isCredentialField(field) && Credentials.isCredentialValue(value) && !Credentials.hasShape(value)) {
            out.add(finding(Kind.CREDENTIAL, where + " " + path, value));
        }
        if (Anonymiser.isGuidKey(field) && GUID_VALUE.matcher(value).matches() && !Anonymiser.isKeptGuid(value)) {
            out.add(finding(Kind.GUID, where + " " + path, value));
        }
    }

    /**
     * Scans every text file under a directory. {@code .gz} files are decompressed first, and JSON files
     * get the field-aware checks as well as the text checks.
     *
     * @param root the directory, normally one family of the corpus or the whole corpus
     * @return the findings, in file name order; empty for a clean tree
     */
    public static List<Finding> scanTree(Path root) {
        List<Finding> out = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk " + root, e);
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            boolean gz = name.endsWith(".gz");
            String base = gz ? name.substring(0, name.length() - 3) : name;
            if (TEXT_SUFFIXES.stream().noneMatch(base::endsWith)) {
                continue;
            }
            String where = root.relativize(file).toString().replace('\\', '/');
            String text = read(file, gz);
            if (base.endsWith(".json") || base.endsWith(".jsonl")) {
                out.addAll(scanJsonText(where, text, base.endsWith(".jsonl")));
            } else {
                out.addAll(scanText(where, text));
            }
        }
        return out;
    }

    private static List<Finding> scanJsonText(String where, String text, boolean lines) {
        List<Finding> out = new ArrayList<>();
        try {
            if (lines) {
                int n = 1;
                for (String line : text.split("\n")) {
                    if (!line.isBlank()) {
                        out.addAll(scanJson(where + ":" + n, JSON.readTree(line)));
                    }
                    n++;
                }
            } else {
                out.addAll(scanJson(where, JSON.readTree(text)));
            }
        } catch (IOException e) {
            out.addAll(scanText(where, text));
        }
        return out;
    }

    private static String read(Path file, boolean gz) {
        try {
            byte[] raw = Files.readAllBytes(file);
            if (!gz) {
                return new String(raw, StandardCharsets.UTF_8);
            }
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    // ------------------------------------------------------------------
    // Allow rules
    // ------------------------------------------------------------------

    private static boolean isAllowedEmail(String local, String domain) {
        String d = domain.toLowerCase(Locale.ROOT);
        if (Tokens.isExampleDomain(d)) {
            return true;
        }
        if (!d.endsWith("gserviceaccount.com")) {
            return false;
        }
        int dot = d.indexOf('.');
        String label = dot < 0 ? "" : d.substring(0, dot);
        if ((Anonymiser.PSEUDO_PROJECT.matcher(label).matches() || Anonymiser.KEPT_PROJECTS.contains(label))
                && Anonymiser.PSEUDO_SERVICE_ACCOUNT.matcher(local).matches()) {
            return true;
        }
        return label.equals("system") || label.equals("cloudservices") || label.equals("developer")
                || label.equals("appspot") || label.startsWith("gcp-sa-")
                || NUMBER_BASED_LOCAL.matcher(local).matches() && Anonymiser.PSEUDO_ACCOUNT.matcher(digitsOf(local)).matches();
    }

    private static String digitsOf(String local) {
        Matcher m = Pattern.compile("[0-9]{6,}").matcher(local);
        return m.find() ? m.group() : "";
    }

    private static boolean isAllowedUserName(String name) {
        return name.isEmpty()
                || Anonymiser.PSEUDO_USER.matcher(name).matches()
                || Anonymiser.GENERIC_NAMES.contains(name.toLowerCase(Locale.ROOT))
                || name.equals("HIDDEN_DUE_TO_SECURITY_REASONS");
    }

    private static boolean isAllowedProject(String id) {
        return Anonymiser.KEPT_PROJECTS.contains(id)
                || Anonymiser.PSEUDO_PROJECT.matcher(id).matches()
                || Anonymiser.PSEUDO_ACCOUNT.matcher(id).matches()
                || id.equals("_")
                || id.isEmpty();
    }

    private static Finding finding(Kind kind, String where, String value) {
        if (kind == Kind.CREDENTIAL) {
            return new Finding(kind, where, value.length() + " chars");
        }
        String head = value.length() <= 2 ? value : value.substring(0, 2);
        return new Finding(kind, where, value.length() + " chars, starts '" + head + "'");
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
