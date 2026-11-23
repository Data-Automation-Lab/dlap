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

package com.dlap.adminservice.permissioncheck.providers.aws;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.dlap.adminservice.audit.export.HttpCalls;
import com.dlap.adminservice.audit.providers.aws.AwsCredentials;
import com.dlap.adminservice.audit.providers.aws.AwsSigV4;
import com.dlap.adminservice.permissioncheck.PermissionProbe;

/**
 * Probes an AWS Actor role with the IAM policy simulator ({@code iam:SimulatePrincipalPolicy}, IAM
 * Query API version 2010-05-08): nothing is called on the buckets and nothing changes. For each
 * executor with a role ARN it simulates, on every named bucket, the bucket scope on the bucket ARN
 * and the bucket_objects scope on a probe key in that bucket; the same on a bucket nobody named, to
 * catch a wildcard grant; and the {@code never_grant} account actions on {@code *}.
 *
 * <p>The simulation covers the role's identity policies and permissions boundary; service control
 * policies and bucket policies are not part of it. The identity da-admin-service runs as needs
 * {@code iam:SimulatePrincipalPolicy} on the Actor role ARNs (docs/guides/cloud-permissions.md).
 */
public final class AwsPolicySimulatorProbe implements PermissionProbe {

    /** A bucket name no operator names: a grant there is a wildcard. */
    static final String UNNAMED_BUCKET = "dlap-permission-check-unnamed-bucket";

    /** The object key the object scope is simulated on. Nothing reads or writes it. */
    static final String PROBE_KEY = "dlap-permission-check-probe";

    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
    private static final Pattern ROLE_ARN = Pattern.compile("arn:[a-z-]+:iam::\\d{12}:role/[\\w+=,.@/-]{1,512}");
    private static final String FORM = "application/x-www-form-urlencoded; charset=utf-8";

    private final AwsPermissionCheckProperties config;
    private final HttpCalls http;
    private final Supplier<AwsCredentials> credentials;
    private final Clock clock;

    /**
     * Creates the probe.
     *
     * @param config      the AWS block
     * @param http        the HTTP client
     * @param credentials the credentials da-admin-service signs with
     * @param clock       the signing clock
     */
    public AwsPolicySimulatorProbe(AwsPermissionCheckProperties config, HttpCalls http,
            Supplier<AwsCredentials> credentials, Clock clock) {
        this.config = config;
        this.http = http;
        this.credentials = credentials;
        this.clock = clock;
    }

    @Override
    public String cloud() {
        return "aws";
    }

    @Override
    public String method() {
        return "iam:SimulatePrincipalPolicy";
    }

    @Override
    public String identityNote() {
        return "iam:SimulatePrincipalPolicy evaluates the policies of the Actor role ARN named in"
                + " dlap.permission-check.aws.executors.<executor>.role-arn, whoever makes the call.";
    }

    @Override
    public List<Target> targets() {
        List<Target> out = new ArrayList<>();
        for (Map.Entry<String, AwsPermissionCheckProperties.Executor> e : new TreeMap<>(config.getExecutors()).entrySet()) {
            AwsPermissionCheckProperties.Executor x = e.getValue();
            String role = x.getRoleArn() == null ? "" : x.getRoleArn().trim();
            if (role.isEmpty()) {
                continue;
            }
            if (!ROLE_ARN.matcher(role).matches()) {
                throw new IllegalArgumentException("dlap.permission-check.aws.executors." + e.getKey()
                        + ".role-arn is not an IAM role ARN");
            }
            for (String bucket : new TreeSet<>(x.getBuckets())) {
                if (!BUCKET.matcher(bucket).matches()) {
                    throw new IllegalArgumentException("dlap.permission-check.aws.executors." + e.getKey()
                            + ".buckets holds a value that is not a bucket name");
                }
                out.add(new Target(e.getKey(), "bucket", bucketArn(bucket), role, true, true));
                out.add(new Target(e.getKey(), "bucket_objects", bucketArn(bucket) + "/" + PROBE_KEY, role,
                        x.isObjectActions(), true));
            }
            out.add(new Target(e.getKey(), "bucket", bucketArn(UNNAMED_BUCKET), role, false, false));
            out.add(new Target(e.getKey(), "bucket_objects", bucketArn(UNNAMED_BUCKET) + "/" + PROBE_KEY, role,
                    false, false));
            out.add(new Target(e.getKey(), "account", "*", role, true, true));
        }
        return out;
    }

    @Override
    public Answer probe(Target target, Set<String> candidates, Set<String> acceptable) {
        Set<String> allowed = new TreeSet<>();
        String marker = null;
        int pages = 0;
        do {
            Page page = simulate(target.principal(), target.resource(), candidates, marker);
            allowed.addAll(page.allowed());
            marker = page.marker();
        } while (marker != null && ++pages < 20);
        allowed.retainAll(candidates);
        return new Answer(allowed, Set.of());
    }

    private String bucketArn(String bucket) {
        return "arn:" + config.getPartition() + ":s3:::" + bucket;
    }

    /** One page of simulation results: the allowed actions and the next marker, or {@code null}. */
    record Page(Set<String> allowed, String marker) {
    }

    private Page simulate(String role, String resource, Set<String> actions, String marker) {
        StringBuilder body = new StringBuilder("Action=SimulatePrincipalPolicy&Version=2010-05-08")
                .append("&PolicySourceArn=").append(enc(role))
                .append("&ResourceArns.member.1=").append(enc(resource))
                .append("&MaxItems=1000");
        int i = 1;
        for (String a : actions) {
            body.append("&ActionNames.member.").append(i++).append('=').append(enc(a));
        }
        if (marker != null) {
            body.append("&Marker=").append(enc(marker));
        }
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        URI uri = URI.create(config.getIamEndpoint());
        Map<String, String> headers = AwsSigV4.sign("POST", uri, Map.of("content-type", FORM), sha256Hex(payload),
                credentials.get(), config.getSigningRegion(), "iam", clock.instant());
        HttpRequest.Builder request = http.request(uri).POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        headers.forEach(request::header);
        HttpResponse<byte[]> response = http.send(request.build(), "IAM SimulatePrincipalPolicy");
        if (response.statusCode() == 403) {
            throw new IllegalStateException("IAM refused SimulatePrincipalPolicy on " + role + " (HTTP 403): the identity"
                    + " of da-admin-service needs iam:SimulatePrincipalPolicy on the Actor role");
        }
        if (response.statusCode() != 200) {
            throw HttpCalls.unexpected("IAM SimulatePrincipalPolicy", response);
        }
        return parse(response.body());
    }

    /**
     * Reads a SimulatePrincipalPolicy response: every {@code EvaluationResults/member} whose
     * {@code EvalDecision} is {@code allowed} contributes its {@code EvalActionName}.
     *
     * @param xml the response body
     * @return the allowed actions and the next marker
     */
    static Page parse(byte[] xml) {
        Document doc = document(xml);
        Set<String> allowed = new TreeSet<>();
        NodeList results = doc.getElementsByTagName("EvaluationResults");
        for (int r = 0; r < results.getLength(); r++) {
            for (Element member : children((Element) results.item(r), "member")) {
                String action = text(member, "EvalActionName");
                if (action != null && "allowed".equals(text(member, "EvalDecision"))) {
                    allowed.add(action);
                }
            }
        }
        String marker = null;
        NodeList truncated = doc.getElementsByTagName("IsTruncated");
        if (truncated.getLength() > 0 && "true".equals(truncated.item(0).getTextContent().trim())) {
            NodeList m = doc.getElementsByTagName("Marker");
            marker = m.getLength() > 0 ? m.item(0).getTextContent().trim() : null;
        }
        return new Page(allowed, marker == null || marker.isEmpty() ? null : marker);
    }

    private static Document document(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalStateException("IAM answered with a body that is not a readable XML document", e);
        }
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getTagName())) {
                out.add(e);
            }
        }
        return out;
    }

    private static String text(Element parent, String name) {
        List<Element> c = children(parent, name);
        return c.isEmpty() ? null : c.get(0).getTextContent().trim();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String sha256Hex(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
