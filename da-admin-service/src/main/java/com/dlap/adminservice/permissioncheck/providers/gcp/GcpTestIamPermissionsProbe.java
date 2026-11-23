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

package com.dlap.adminservice.permissioncheck.providers.gcp;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dlap.adminservice.audit.export.HttpCalls;
import com.dlap.adminservice.permissioncheck.PermissionCheckReport;
import com.dlap.adminservice.permissioncheck.PermissionProbe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Probes a GCP Actor with {@code testIamPermissions}: Cloud Storage
 * {@code GET /storage/v1/b/<bucket>/iam/testPermissions} for the bucket and bucket_objects scopes,
 * BigQuery {@code POST .../tables/<table>:testIamPermissions} for the dataset scope (a table
 * inherits its dataset's grants). The API answers which of the asked permissions the caller holds;
 * nothing is read or changed. The caller is the token's account, so the token must be the Actor's.
 */
public final class GcpTestIamPermissionsProbe implements PermissionProbe {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9._-]{1,220}[a-z0-9]");
    private static final Pattern TABLE = Pattern.compile("([a-z][a-z0-9-]{4,28}[a-z0-9])\\.(\\w{1,1024})\\.([\\w$-]{1,1024})");

    private final GcpPermissionCheckProperties config;
    private final HttpCalls http;
    private final Clock clock;
    private volatile String cachedToken;
    private volatile Instant cachedUntil = Instant.EPOCH;

    /**
     * Creates the probe.
     *
     * @param config the GCP block
     * @param http   the HTTP client
     * @param clock  the token cache clock
     */
    public GcpTestIamPermissionsProbe(GcpPermissionCheckProperties config, HttpCalls http, Clock clock) {
        this.config = config;
        this.http = http;
        this.clock = clock;
    }

    @Override
    public String cloud() {
        return "gcp";
    }

    @Override
    public String method() {
        return "testIamPermissions";
    }

    @Override
    public PermissionCheckReport.IdentityBasis identityBasis() {
        return PermissionCheckReport.IdentityBasis.CALLER;
    }

    @Override
    public String identityNote() {
        return "testIamPermissions answers for the calling identity: the service account da-admin-service runs as, or"
                + " the token in access-token-file. It is the Actor only if that is the Actor's account; nothing is"
                + " impersonated (Q-727).";
    }

    @Override
    public List<Target> targets() {
        List<Target> out = new ArrayList<>();
        String caller = config.getAccessTokenFile() == null || config.getAccessTokenFile().isBlank()
                ? "the attached service account" : "the account of the access token file";
        for (Map.Entry<String, GcpPermissionCheckProperties.Executor> e : new TreeMap<>(config.getExecutors()).entrySet()) {
            GcpPermissionCheckProperties.Executor x = e.getValue();
            for (String bucket : new TreeSet<>(x.getBuckets())) {
                if (!BUCKET.matcher(bucket).matches()) {
                    throw new IllegalArgumentException("dlap.permission-check.gcp.executors." + e.getKey()
                            + ".buckets holds a value that is not a bucket name");
                }
                out.add(new Target(e.getKey(), "bucket", bucket, caller, true, true));
                out.add(new Target(e.getKey(), "bucket_objects", bucket, caller, x.isObjectActions(), true));
            }
            for (String table : new TreeSet<>(x.getBigqueryTables())) {
                if (!TABLE.matcher(table).matches()) {
                    throw new IllegalArgumentException("dlap.permission-check.gcp.executors." + e.getKey()
                            + ".bigquery-tables holds a value that is not project.dataset.table");
                }
                out.add(new Target(e.getKey(), "dataset", table, caller, true, true));
            }
        }
        return out;
    }

    @Override
    public Answer probe(Target target, Set<String> candidates, Set<String> acceptable) {
        Set<String> held = "dataset".equals(target.scope()) ? testTable(target.resource(), candidates)
                : testBucket(target.resource(), candidates);
        held.retainAll(candidates);
        return new Answer(held, Set.of());
    }

    private Set<String> testBucket(String bucket, Set<String> permissions) {
        StringBuilder q = new StringBuilder();
        for (String p : permissions) {
            q.append(q.isEmpty() ? '?' : '&').append("permissions=").append(enc(p));
        }
        URI uri = URI.create(trim(config.getStorageEndpoint()) + "/storage/v1/b/" + enc(bucket) + "/iam/testPermissions" + q);
        HttpResponse<byte[]> r = http.send(http.request(uri).header("Authorization", "Bearer " + token()).GET().build(),
                "GCS testIamPermissions");
        return permissionsOf(r, "GCS testIamPermissions on bucket " + bucket);
    }

    private Set<String> testTable(String table, Set<String> permissions) {
        Matcher m = TABLE.matcher(table);
        if (!m.matches()) {
            throw new IllegalArgumentException("not project.dataset.table");
        }
        URI uri = URI.create(trim(config.getBigqueryEndpoint()) + "/bigquery/v2/projects/" + enc(m.group(1))
                + "/datasets/" + enc(m.group(2)) + "/tables/" + enc(m.group(3)) + ":testIamPermissions");
        ObjectNode body = JSON.createObjectNode();
        ArrayNode list = body.putArray("permissions");
        permissions.forEach(list::add);
        HttpResponse<byte[]> r = http.send(http.request(uri).header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
                "BigQuery testIamPermissions");
        return permissionsOf(r, "BigQuery testIamPermissions on table " + table);
    }

    private static Set<String> permissionsOf(HttpResponse<byte[]> r, String what) {
        if (r.statusCode() != 200) {
            throw HttpCalls.unexpected(what, r);
        }
        Set<String> out = new TreeSet<>();
        json(r.body(), what).path("permissions").forEach(n -> out.add(n.asText()));
        return out;
    }

    private String token() {
        if (config.getAccessTokenFile() != null && !config.getAccessTokenFile().isBlank()) {
            return HttpCalls.tokenFromFile(config.getAccessTokenFile());
        }
        String t = cachedToken;
        if (t != null && clock.instant().isBefore(cachedUntil)) {
            return t;
        }
        URI uri = URI.create(trim(config.getMetadataEndpoint())
                + "/computeMetadata/v1/instance/service-accounts/default/token");
        HttpResponse<byte[]> r = http.send(http.request(uri).header("Metadata-Flavor", "Google").GET().build(),
                "GCE metadata token");
        if (r.statusCode() != 200) {
            throw HttpCalls.unexpected("GCE metadata token", r);
        }
        JsonNode n = json(r.body(), "GCE metadata token");
        t = n.path("access_token").asText("");
        if (t.isEmpty()) {
            throw new IllegalStateException("the metadata server returned no access token");
        }
        cachedToken = t;
        cachedUntil = clock.instant().plusSeconds(Math.max(0, n.path("expires_in").asLong(0) - 300));
        return t;
    }

    private static JsonNode json(byte[] body, String what) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException(what + " answered with a body that is not JSON", e);
        }
    }

    private static String trim(String endpoint) {
        return endpoint.replaceAll("/+$", "");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
