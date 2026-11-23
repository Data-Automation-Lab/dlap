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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Deterministic anonymiser for log records that are about to become fixtures.
 *
 * <p>Every identifier that points at a real person, host or cloud resource is replaced by a pseudonym
 * derived with HMAC-SHA256 from a fixed public salt. The same input always gives the same output, on
 * every machine and in every run, so the import can be repeated and diffed. The salt is not a secret:
 * the sources are public datasets, and the point is that the project repository carries no real
 * identifier, not that the mapping cannot be recomputed. Structure survives: a pseudonym keeps the
 * shape of what it replaces, so a parser sees an account number where there was one, an AWS user id
 * with the same prefix and length, an IPv4 address where there was an IPv4 address.
 *
 * <p>Rules (the {@link Result#replaced() report} counts uses per rule):
 * <ul>
 *   <li>{@code account}: twelve digit numbers (AWS accounts, GCP project numbers) become
 *       {@code 9999} plus eight digits; documentation placeholders such as {@code 123456789012} stay;</li>
 *   <li>{@code ipv4}, {@code ipv6}: public addresses become an address in 198.18.0.0/15 or
 *       2001:db8::/32; private, loopback, link local, documentation and similar addresses stay;</li>
 *   <li>{@code email}: addresses become {@code user-xxxxxxxx@example.org}; customer service accounts keep
 *       their {@code .iam.gserviceaccount.com} domain with a pseudonymous project label; Google owned
 *       service accounts and reserved example domains stay;</li>
 *   <li>{@code awsId}: AWS unique ids (AKIA, ASIA, AIDA, AROA ...) keep prefix and length and carry the
 *       marker {@code EXAMPLE} at offset 4, then a digit that no secret scanner's key pattern allows;</li>
 *   <li>{@code project}, {@code name}: project ids and person names found in the same document (project
 *       paths and labels, service account domains, IAM user names, e-mail local parts) become
 *       {@code proj-xxxxxxxx} and {@code user-xxxxxx} wherever they occur in it, including inside longer
 *       strings such as bucket names;</li>
 *   <li>{@code guid}: Azure subscription, tenant and object ids in paths and in well known keys;
 *       placeholder values such as {@code 11111111-1111-1111-1111-111111111111} stay;</li>
 *   <li>{@code sessionToken}, {@code secretKey}: STS session tokens and secret access keys, found by
 *       field name ({@code sessionToken}, {@code secretAccessKey} and their spellings) or by shape. Each
 *       becomes a placeholder of the same length that starts with the token's format header where it has
 *       one, then the marker {@code EXAMPLE} and upper case letters and digits only (see
 *       {@link Credentials}); the CloudTrail redaction {@code HIDDEN_DUE_TO_SECURITY_REASONS} stays;</li>
 *   <li>{@code dashedIp}, {@code orgId}, {@code saKey}: host names built from an address
 *       ({@code ec2-203-0-113-9}), organization and folder numbers, service account key ids.</li>
 * </ul>
 *
 * <p>Applying the anonymiser to its own output changes nothing. Instances are immutable and safe to
 * share between threads.
 */
public final class Anonymiser {

    /** The salt used for every committed fixture. Changing it changes every pseudonym. */
    public static final String DEFAULT_SALT = "dlap-fixtures-anon/1";

    /** Shape of an account number pseudonym. */
    public static final Pattern PSEUDO_ACCOUNT = Pattern.compile("9999[0-9]{8}");

    /** Shape of a person name or user name pseudonym. */
    public static final Pattern PSEUDO_USER = Pattern.compile("user-[0-9a-f]{6,8}");

    /** Shape of a project id pseudonym. */
    public static final Pattern PSEUDO_PROJECT = Pattern.compile("proj-[0-9a-f]{8}");

    /** Shape of a customer service account name pseudonym. */
    public static final Pattern PSEUDO_SERVICE_ACCOUNT = Pattern.compile("sa-[0-9a-f]{8}");

    /** Project ids that are public, Google owned or obvious placeholders; they are not anonymised. */
    static final Set<String> KEPT_PROJECTS = Set.of(
            "bigquery-public-data", "bigquery-samples", "test-project", "my-project", "my-project-id",
            "your-project", "project-id", "example-project", "sample-project", "gcp-project1");

    /** Local parts and user names that are generic words, not people; they are not treated as names. */
    static final Set<String> GENERIC_NAMES = Set.of(
            "user", "users", "admin", "administrator", "root", "test", "tester", "robot", "support", "service",
            "system", "noreply", "no-reply", "info", "security", "billing", "owner", "editor", "viewer",
            "default", "anonymous", "guest", "alias", "name");

    /** Service account domains whose first label is a Google owned namespace, not a customer project. */
    private static final Set<String> GOOGLE_SA_LABELS = Set.of("system", "cloudservices", "developer", "appspot");

    private static final Set<String> GUID_KEYS = Set.of(
            "tenantId", "tid", "subscriptionId", "objectId", "oid", "appid", "appId", "principalId", "applicationId");

    private static final Pattern PROJECT_PATH = Pattern.compile(
            "projects/([a-z][a-z0-9-]{4,28}[a-z0-9])(?=[/:\\s\"%]|$)");
    private static final Pattern PROJECT_ID = Pattern.compile("[a-z][a-z0-9-]{4,28}[a-z0-9]");
    private static final Pattern ARN_USER = Pattern.compile(
            "arn:aws[a-z-]*:iam::[0-9]{12}:user/(?:[^/\\s\":]+/)*([^/\\s\":]+)");
    private static final Pattern GUID = Pattern.compile(
            "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}");
    private static final Pattern HEX32 = Pattern.compile("[0-9A-Fa-f]{32}");
    private static final Pattern GUID_IN_PATH = Pattern.compile(
            "(?i)(?<=/subscriptions/|/tenants/|sts\\.windows\\.net/|login\\.microsoftonline\\.com/)"
                    + "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern DASHED_IP = Pattern.compile(
            "(?<![0-9A-Za-z])(?:ec2|ip)-([0-9]{1,3})-([0-9]{1,3})-([0-9]{1,3})-([0-9]{1,3})(?![0-9])");
    private static final Pattern ORG_ID = Pattern.compile(
            "(?<=organizations/|folders/|accessPolicies/)[0-9]{6,}(?![0-9])");
    private static final Pattern SA_KEY_ID = Pattern.compile("(?<=/keys/)[0-9a-f]{40}(?![0-9a-f])");
    private static final Pattern NUMBER_BASED_LOCAL = Pattern.compile("(?:service-)?o?[0-9]{6,}(?:-[A-Za-z0-9]+)?");

    /** Every service account key id pseudonym starts with this (eight hex digits, then 32 from the hash). */
    static final String SA_KEY_MARKER = "5a5a5a5a";

    /** Every organization, folder and access policy number pseudonym starts with this, so a second pass leaves it alone. */
    static final String ORG_MARKER = "9999";

    /** The last group of every GUID pseudonym starts with this, so a second pass recognises its own output. */
    private static final String GUID_MARKER = "fa17";

    private static final String ID_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final byte[] key;

    /**
     * Creates an anonymiser.
     *
     * @param salt the text that keys the pseudonym derivation; use {@link #DEFAULT_SALT} for fixtures
     */
    public Anonymiser(String salt) {
        this.key = salt.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Creates the anonymiser every committed fixture was made with.
     *
     * @return an anonymiser keyed with {@link #DEFAULT_SALT}
     */
    public static Anonymiser standard() {
        return new Anonymiser(DEFAULT_SALT);
    }

    /**
     * The outcome of anonymising one document.
     *
     * @param tree     the anonymised copy; the input is not modified
     * @param replaced how many values each rule rewrote, by rule name; empty when nothing changed
     */
    public record Result(JsonNode tree, Map<String, Integer> replaced) {
    }

    /** What pass one found in a document: names and project ids to replace wherever they occur. */
    private static final class Found {
        private final Set<String> names = new TreeSet<>();
        private final Set<String> projects = new TreeSet<>();
        private Pattern namePattern;
        private Pattern projectPattern;

        void freeze() {
            namePattern = tokenPattern(names);
            projectPattern = tokenPattern(projects);
        }

        private static Pattern tokenPattern(Set<String> tokens) {
            if (tokens.isEmpty()) {
                return null;
            }
            List<String> sorted = new ArrayList<>(tokens);
            sorted.sort(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()));
            StringBuilder re = new StringBuilder("(?<![A-Za-z0-9])(?:");
            for (int i = 0; i < sorted.size(); i++) {
                if (i > 0) {
                    re.append('|');
                }
                re.append(Pattern.quote(sorted.get(i)));
            }
            return Pattern.compile(re.append(")(?![A-Za-z0-9])").toString());
        }
    }

    /** A planned replacement of {@code [start, end)} by {@code replacement}, tagged with its rule. */
    private record Edit(int start, int end, String replacement, String rule) {
    }

    // ------------------------------------------------------------------
    // Public entry points
    // ------------------------------------------------------------------

    /**
     * Anonymises a JSON document. Field names and string values are rewritten; numbers, booleans and
     * nulls are not. Names and project ids found anywhere in the document are replaced everywhere in it.
     *
     * @param document the document
     * @return the anonymised copy and a per-rule count
     * @throws IllegalStateException when two field names of one object become equal after rewriting
     */
    public Result anonymise(JsonNode document) {
        Found found = new Found();
        discover(document, null, new ArrayDeque<>(), found);
        found.freeze();
        Map<String, Integer> counts = new TreeMap<>();
        JsonNode tree = rewriteNode(document, null, new ArrayDeque<>(), found, counts);
        return new Result(tree, counts);
    }

    /**
     * Anonymises a single piece of text. Names and project ids are taken from the text itself.
     *
     * @param text the text
     * @return the anonymised text
     */
    public String anonymiseText(String text) {
        Found found = new Found();
        discoverText(text, found);
        found.freeze();
        return rewriteString(text, found, new TreeMap<>());
    }

    // ------------------------------------------------------------------
    // Pass one: discover names and project ids
    // ------------------------------------------------------------------

    private void discover(JsonNode node, String key, Deque<String> path, Found found) {
        if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                discoverText(e.getKey(), found);
                JsonNode v = e.getValue();
                if (v.isTextual()) {
                    discoverKeyed(e.getKey(), v.asText(), path, found);
                }
                path.push(e.getKey());
                discover(v, e.getKey(), path, found);
                path.pop();
            }
        } else if (node.isArray()) {
            for (JsonNode v : node) {
                discover(v, key, path, found);
            }
        } else if (node.isTextual()) {
            discoverText(node.asText(), found);
        }
    }

    private void discoverKeyed(String field, String value, Deque<String> path, Found found) {
        if (field.equals("userName") && !path.contains("sessionIssuer")) {
            addName(value, 2, found);
        }
        if ((field.equals("project_id") || field.equals("projectId")) && PROJECT_ID.matcher(value).matches()) {
            addProject(value, found);
        }
    }

    private void discoverText(String text, Found found) {
        Matcher m = PROJECT_PATH.matcher(text);
        while (m.find()) {
            addProject(m.group(1), found);
        }
        m = ARN_USER.matcher(text);
        while (m.find()) {
            addName(m.group(1), 2, found);
        }
        m = Tokens.EMAIL.matcher(text);
        while (m.find()) {
            String email = m.group();
            int at = email.lastIndexOf('@');
            String local = email.substring(0, at);
            String domain = email.substring(at + 1).toLowerCase(Locale.ROOT);
            if (domain.endsWith("gserviceaccount.com")) {
                String label = domain.substring(0, domain.indexOf('.') < 0 ? domain.length() : domain.indexOf('.'));
                if (PROJECT_ID.matcher(label).matches() && !isGoogleOwnedServiceAccount(local, domain)) {
                    addProject(label, found);
                }
            } else if (!Tokens.isExampleDomain(domain)) {
                addName(local, 4, found);
            }
        }
    }

    private static void addName(String name, int minLength, Found found) {
        if (name.length() < minLength || GENERIC_NAMES.contains(name.toLowerCase(Locale.ROOT))
                || PSEUDO_USER.matcher(name).matches() || PSEUDO_SERVICE_ACCOUNT.matcher(name).matches()
                || NUMBER_BASED_LOCAL.matcher(name).matches() || !name.matches("[A-Za-z0-9._-]+")) {
            return;
        }
        found.names.add(name);
    }

    private static void addProject(String id, Found found) {
        if (KEPT_PROJECTS.contains(id) || PSEUDO_PROJECT.matcher(id).matches()) {
            return;
        }
        found.projects.add(id);
        found.projects.add(id.replace('-', '_'));
    }

    // ------------------------------------------------------------------
    // Pass two: rewrite
    // ------------------------------------------------------------------

    private JsonNode rewriteNode(JsonNode node, String field, Deque<String> path, Found found,
                                 Map<String, Integer> counts) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        if (node.isObject()) {
            ObjectNode out = f.objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String newKey = rewriteString(e.getKey(), found, counts);
                if (out.has(newKey)) {
                    throw new IllegalStateException("field names collide after anonymising: " + newKey);
                }
                path.push(e.getKey());
                out.set(newKey, rewriteNode(e.getValue(), e.getKey(), path, found, counts));
                path.pop();
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = f.arrayNode();
            for (JsonNode v : node) {
                out.add(rewriteNode(v, field, path, found, counts));
            }
            return out;
        }
        if (node.isTextual()) {
            String value = node.asText();
            if (field != null) {
                String keyed = rewriteKeyed(field, value, path, counts);
                if (keyed != null) {
                    return TextNode.valueOf(keyed);
                }
            }
            return TextNode.valueOf(rewriteString(value, found, counts));
        }
        return node;
    }

    /** Rewrites a value by the name of its field; null when the field is not special. */
    private String rewriteKeyed(String field, String value, Deque<String> path, Map<String, Integer> counts) {
        if (field.equals("userName") && !path.contains("sessionIssuer")) {
            if (PSEUDO_USER.matcher(value).matches() || value.isEmpty()) {
                return value;
            }
            count(counts, "name");
            return userName(value);
        }
        if (Credentials.isCredentialField(field) && Credentials.isCredentialValue(value)) {
            count(counts, Credentials.isSessionTokenField(field) ? "sessionToken" : "secretKey");
            return credential(value);
        }
        if (GUID_KEYS.contains(field) && (GUID.matcher(value).matches() || HEX32.matcher(value).matches())) {
            String g = guid(value);
            if (!g.equals(value)) {
                count(counts, "guid");
            }
            return g;
        }
        return null;
    }

    private String rewriteString(String s, Found found, Map<String, Integer> counts) {
        if (s.isEmpty()) {
            return s;
        }
        List<Edit> edits = new ArrayList<>();
        collectEmails(s, edits);
        collectIpv6(s, edits);
        collectIpv4(s, edits);
        collectDigits12(s, edits);
        collectAwsIds(s, edits);
        collectCredentials(s, edits);
        collectGuids(s, edits);
        collectSimple(s, DASHED_IP, "dashedIp", edits);
        collectSimple(s, ORG_ID, "orgId", edits);
        collectSimple(s, SA_KEY_ID, "saKey", edits);
        if (found.projectPattern != null) {
            collectTokens(s, found.projectPattern, "project", edits);
        }
        if (found.namePattern != null) {
            collectTokens(s, found.namePattern, "name", edits);
        }
        if (edits.isEmpty()) {
            return s;
        }
        edits.sort(Comparator.comparingInt(Edit::start).thenComparing(Comparator.comparingInt((Edit e) -> e.end() - e.start()).reversed()));
        StringBuilder out = new StringBuilder(s.length() + 16);
        int pos = 0;
        for (Edit e : edits) {
            if (e.start() < pos) {
                continue;
            }
            out.append(s, pos, e.start());
            String original = s.substring(e.start(), e.end());
            out.append(e.replacement());
            if (!e.replacement().equals(original)) {
                count(counts, e.rule());
            }
            pos = e.end();
        }
        out.append(s, pos, s.length());
        return out.toString();
    }

    private static void count(Map<String, Integer> counts, String rule) {
        counts.merge(rule, 1, Integer::sum);
    }

    private void collectEmails(String s, List<Edit> edits) {
        Matcher m = Tokens.EMAIL.matcher(s);
        while (m.find()) {
            edits.add(new Edit(m.start(), m.end(), emailReplacement(m.group()), "email"));
        }
    }

    private String emailReplacement(String email) {
        int at = email.lastIndexOf('@');
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        String dl = domain.toLowerCase(Locale.ROOT);
        if (Tokens.isExampleDomain(dl)) {
            return email;
        }
        if (dl.endsWith("gserviceaccount.com")) {
            int dot = dl.indexOf('.');
            String label = dot < 0 ? "" : dl.substring(0, dot);
            if (isGoogleOwnedServiceAccount(local, dl)) {
                return replaceDigits12(local) + "@" + domain;
            }
            String rest = dot < 0 ? domain : domain.substring(dot);
            String newLabel = PSEUDO_PROJECT.matcher(label).matches() || KEPT_PROJECTS.contains(label)
                    ? label : project(label);
            String newLocal = PSEUDO_SERVICE_ACCOUNT.matcher(local).matches() ? local : serviceAccountName(local);
            return newLocal + "@" + newLabel + rest;
        }
        return userEmail(email);
    }

    private static boolean isGoogleOwnedServiceAccount(String local, String lowerDomain) {
        int dot = lowerDomain.indexOf('.');
        String label = dot < 0 ? lowerDomain : lowerDomain.substring(0, dot);
        return GOOGLE_SA_LABELS.contains(label) || label.startsWith("gcp-sa-")
                || NUMBER_BASED_LOCAL.matcher(local).matches();
    }

    private void collectIpv6(String s, List<Edit> edits) {
        for (Span sp : Tokens.ipv6(s)) {
            int[] g = Tokens.parseIpv6(sp.text());
            String replacement = Tokens.isReservedIpv6(g) ? sp.text() : ipv6(sp.text());
            edits.add(new Edit(sp.start(), sp.end(), replacement, "ipv6"));
        }
    }

    private void collectIpv4(String s, List<Edit> edits) {
        for (Span sp : Tokens.ipv4(s)) {
            String replacement = Tokens.isReservedIpv4(sp.text()) ? sp.text() : ipv4(sp.text());
            edits.add(new Edit(sp.start(), sp.end(), replacement, "ipv4"));
        }
    }

    private void collectDigits12(String s, List<Edit> edits) {
        Matcher m = Tokens.DIGITS12.matcher(s);
        while (m.find()) {
            String digits = m.group();
            if (Tokens.insideLongHex(s, m.start(), m.end()) || Tokens.insideGuid(s, m.start(), m.end())) {
                continue;
            }
            boolean keep = Tokens.PLACEHOLDER_ACCOUNTS.contains(digits) || PSEUDO_ACCOUNT.matcher(digits).matches();
            edits.add(new Edit(m.start(), m.end(), keep ? digits : account(digits), "account"));
        }
    }

    private String replaceDigits12(String text) {
        Matcher m = Tokens.DIGITS12.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String digits = m.group();
            boolean keep = Tokens.PLACEHOLDER_ACCOUNTS.contains(digits) || PSEUDO_ACCOUNT.matcher(digits).matches();
            m.appendReplacement(out, Matcher.quoteReplacement(keep ? digits : account(digits)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private void collectAwsIds(String s, List<Edit> edits) {
        Matcher m = Tokens.AWS_UNIQUE_ID.matcher(s);
        while (m.find()) {
            String prefix = m.group(1);
            String tail = m.group(2);
            boolean done = (prefix + tail).startsWith(prefix + Tokens.AWS_ID_MARKER);
            edits.add(new Edit(m.start(), m.end(), done ? m.group() : awsUniqueId(prefix, m.group()), "awsId"));
        }
    }

    private void collectCredentials(String s, List<Edit> edits) {
        for (Span sp : Credentials.sessionTokens(s)) {
            edits.add(new Edit(sp.start(), sp.end(), credential(sp.text()), "sessionToken"));
        }
        for (Span sp : Credentials.secretKeys(s)) {
            edits.add(new Edit(sp.start(), sp.end(), credential(sp.text()), "secretKey"));
        }
    }

    private void collectGuids(String s, List<Edit> edits) {
        Matcher m = GUID_IN_PATH.matcher(s);
        while (m.find()) {
            edits.add(new Edit(m.start(), m.end(), guid(m.group()), "guid"));
        }
    }

    private void collectSimple(String s, Pattern p, String rule, List<Edit> edits) {
        Matcher m = p.matcher(s);
        while (m.find()) {
            String replacement;
            int start;
            int end;
            switch (rule) {
                case "dashedIp" -> {
                    String dotted = m.group(1) + "." + m.group(2) + "." + m.group(3) + "." + m.group(4);
                    if (Integer.parseInt(m.group(1)) > 255 || Integer.parseInt(m.group(2)) > 255
                            || Integer.parseInt(m.group(3)) > 255 || Integer.parseInt(m.group(4)) > 255) {
                        continue;
                    }
                    start = m.start(1);
                    end = m.end(4);
                    replacement = Tokens.isReservedIpv4(dotted) ? dotted.replace('.', '-') : ipv4(dotted).replace('.', '-');
                }
                case "orgId" -> {
                    start = m.start();
                    end = m.end();
                    String number = m.group();
                    replacement = number.startsWith(ORG_MARKER) ? number
                            : ORG_MARKER + digits(mac("org", number), number.length() - ORG_MARKER.length());
                }
                case "saKey" -> {
                    start = m.start();
                    end = m.end();
                    String keyId = m.group();
                    replacement = keyId.startsWith(SA_KEY_MARKER) ? keyId
                            : SA_KEY_MARKER + HexFormat.of().formatHex(mac("sakey", keyId), 0, 16);
                }
                default -> throw new IllegalStateException("unknown rule " + rule);
            }
            edits.add(new Edit(start, end, replacement, rule));
        }
    }

    private void collectTokens(String s, Pattern p, String rule, List<Edit> edits) {
        Matcher m = p.matcher(s);
        while (m.find()) {
            String token = m.group();
            edits.add(new Edit(m.start(), m.end(), rule.equals("project") ? project(token) : userName(token), rule));
        }
    }

    // ------------------------------------------------------------------
    // Pseudonyms
    // ------------------------------------------------------------------

    private byte[] mac(String kind, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(kind.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static String digits(byte[] hash, int count) {
        String d = new BigInteger(1, hash).mod(BigInteger.TEN.pow(count)).toString();
        return "0".repeat(count - d.length()) + d;
    }

    private String hex(String kind, String value, int bytes) {
        return HexFormat.of().formatHex(mac(kind, value), 0, bytes);
    }

    /**
     * Returns the pseudonym of a twelve digit account or project number.
     *
     * @param number the twelve digits
     * @return {@code 9999} followed by eight digits
     */
    String account(String number) {
        return "9999" + digits(mac("account", number), 8);
    }

    /**
     * Returns the pseudonym of a public IPv4 address, an address in 198.18.0.0/15.
     *
     * @param dotted the address in dotted decimal form
     * @return the pseudonymous address
     */
    String ipv4(String dotted) {
        byte[] h = mac("ipv4", dotted);
        int v = (((h[0] & 0xff) << 16) | ((h[1] & 0xff) << 8) | (h[2] & 0xff)) & 0x1ffff;
        return "198." + (18 + (v >> 16)) + "." + ((v >> 8) & 0xff) + "." + (v & 0xff);
    }

    /**
     * Returns the pseudonym of a public IPv6 address, an address in 2001:db8::/32.
     *
     * @param text the address as written
     * @return the pseudonymous address in full form
     */
    String ipv6(String text) {
        int[] g = Tokens.parseIpv6(text);
        StringBuilder canonical = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            canonical.append(Integer.toHexString(g[i])).append(i < 7 ? ":" : "");
        }
        byte[] h = mac("ipv6", canonical.toString());
        StringBuilder out = new StringBuilder("2001:db8");
        for (int i = 0; i < 6; i++) {
            out.append(':').append(String.format(Locale.ROOT, "%02x%02x", h[2 * i] & 0xff, h[2 * i + 1] & 0xff));
        }
        return out.toString();
    }

    private String userEmail(String email) {
        return "user-" + hex("email", email.toLowerCase(Locale.ROOT), 4) + "@example.org";
    }

    private String serviceAccountName(String local) {
        return "sa-" + hex("sa", local, 4);
    }

    String userName(String name) {
        return "user-" + hex("user", name, 3);
    }

    String project(String id) {
        return "proj-" + hex("project", id, 4);
    }

    /**
     * Returns the pseudonym of an AWS unique id: same prefix, same length, the marker at offset 4 and
     * a digit from 0, 1, 8, 9 right after it, so that no key scanner's base32 key pattern matches.
     *
     * @param prefix the four letter prefix
     * @param id     the whole id, prefix included
     * @return the pseudonymous id
     */
    String awsUniqueId(String prefix, String id) {
        byte[] h = mac("awsid", id);
        int tailLength = id.length() - prefix.length() - Tokens.AWS_ID_MARKER.length();
        StringBuilder out = new StringBuilder(prefix).append(Tokens.AWS_ID_MARKER);
        for (int i = 0; i < tailLength; i++) {
            int b = h[i % h.length] & 0xff;
            out.append(i == 0 ? "0189".charAt(b & 3) : ID_ALPHABET.charAt(b % ID_ALPHABET.length()));
        }
        return out.toString();
    }

    /**
     * Returns the placeholder of a session token or secret access key: the token's format header when it
     * has one, the marker {@code EXAMPLE}, eight hex digits derived from the value (so that two
     * different credentials stay different), then the marker repeated, cut to the length of the
     * original. A placeholder is returned unchanged.
     *
     * @param value the credential
     * @return the placeholder, as long as the credential
     */
    String credential(String value) {
        if (Credentials.isPlaceholder(value)) {
            return value;
        }
        String digits = hex("credential", value, 4).toUpperCase(Locale.ROOT);
        String core = Credentials.headOf(value) + Credentials.MARKER + digits;
        if (core.length() > value.length()) {
            core = Credentials.MARKER + digits;
        }
        return Credentials.fill(core, value.length());
    }

    /**
     * Tells whether a GUID needs no pseudonym: a placeholder that uses at most six distinct hex digits,
     * or a value that already ends in the pseudonym marker.
     *
     * @param value the GUID, dashed or 32 hex digits
     * @return true when the value is left as it is
     */
    static boolean isKeptGuid(String value) {
        String hexOnly = value.replace("-", "").toLowerCase(Locale.ROOT);
        return new LinkedHashSet<>(hexOnly.chars().mapToObj(c -> (char) c).toList()).size() <= 6
                || hexOnly.startsWith(GUID_MARKER, 20);
    }

    /**
     * Tells whether a JSON field name carries a tenant, subscription or object GUID that is anonymised.
     *
     * @param field the field name
     * @return true for the well known GUID keys
     */
    static boolean isGuidKey(String field) {
        return GUID_KEYS.contains(field);
    }

    /**
     * Returns the pseudonym of an Azure GUID (dashed or 32 hex digits). Placeholder values that use at
     * most six distinct hex digits, and GUIDs that already end in the pseudonym marker, are returned
     * unchanged; the case of the input is kept.
     *
     * @param value the GUID
     * @return the pseudonymous GUID, or the input for a placeholder
     */
    String guid(String value) {
        if (isKeptGuid(value)) {
            return value;
        }
        String hexOnly = value.replace("-", "").toLowerCase(Locale.ROOT);
        String h = HexFormat.of().formatHex(mac("guid", hexOnly), 0, 16);
        char[] c = h.toCharArray();
        c[12] = '4';
        c[16] = "89ab".charAt(Character.digit(c[16], 16) & 3);
        for (int i = 0; i < GUID_MARKER.length(); i++) {
            c[20 + i] = GUID_MARKER.charAt(i);
        }
        String plain = new String(c);
        String out = value.contains("-")
                ? plain.substring(0, 8) + "-" + plain.substring(8, 12) + "-" + plain.substring(12, 16) + "-"
                        + plain.substring(16, 20) + "-" + plain.substring(20)
                : plain;
        return value.chars().anyMatch(Character::isUpperCase) ? out.toUpperCase(Locale.ROOT) : out;
    }
}
