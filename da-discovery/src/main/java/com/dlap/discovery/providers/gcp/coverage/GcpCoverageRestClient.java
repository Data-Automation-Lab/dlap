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

package com.dlap.discovery.providers.gcp.coverage;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.dlap.discovery.coverage.CoverageHttp;
import com.dlap.discovery.coverage.CoverageHttp.CoverageTransportException;
import com.dlap.discovery.coverage.MissingPermissionException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.auth.Credentials;

/**
 * {@link GcpCoverageApi} over the public REST endpoints, authorised with the discovery service's
 * Google credentials ({@link Credentials#getRequestMetadata(URI)}). HTTP 403 becomes a
 * {@link MissingPermissionException} naming the IAM permission of the call; any other failure a
 * {@link CoverageTransportException} with the status only.
 */
public class GcpCoverageRestClient implements GcpCoverageApi {

    /** At most this many bucket pages are read from one project. */
    static final int MAX_PAGES = 1_000;

    private final CoverageHttp http;
    private final Supplier<Credentials> credentials;
    private final String resourceManager;
    private final String storage;

    /**
     * Creates the client.
     *
     * @param http            the transport
     * @param credentials     the Google credentials, obtained on first use
     * @param resourceManager the Resource Manager base URL
     * @param storage         the Cloud Storage base URL
     */
    public GcpCoverageRestClient(CoverageHttp http, Supplier<Credentials> credentials, String resourceManager, String storage) {
        this.http = http;
        this.credentials = credentials;
        this.resourceManager = trim(resourceManager);
        this.storage = trim(storage);
    }

    @Override
    public JsonNode getProject(String projectId) {
        return get(resourceManager + "/v3/projects/" + enc(projectId), "resourcemanager.projects.get",
                "projects/" + projectId);
    }

    @Override
    public JsonNode getFolder(String folder) {
        return get(resourceManager + "/v3/" + folder, "resourcemanager.folders.get", folder);
    }

    @Override
    public JsonNode getIamPolicy(String resource) {
        String permission = resource.startsWith("folders/") ? "resourcemanager.folders.getIamPolicy"
                : resource.startsWith("organizations/") ? "resourcemanager.organizations.getIamPolicy"
                : "resourcemanager.projects.getIamPolicy";
        URI uri = URI.create(resourceManager + "/v3/" + resource + ":getIamPolicy");
        HttpRequest request = authorised(HttpRequest.newBuilder(uri), uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"options\":{\"requestedPolicyVersion\":3}}"))
                .build();
        return checked(http.send(request), permission, resource);
    }

    @Override
    public List<JsonNode> listBuckets(String projectId) {
        List<JsonNode> out = new ArrayList<>();
        String token = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            String url = storage + "/storage/v1/b?project=" + enc(projectId)
                    + (token == null ? "" : "&pageToken=" + enc(token));
            JsonNode body = get(url, "storage.buckets.list", "projects/" + projectId);
            body.path("items").forEach(out::add);
            token = body.path("nextPageToken").asText("");
            if (token.isEmpty()) {
                return out;
            }
        }
        throw new CoverageTransportException("buckets.list returned more than " + MAX_PAGES + " pages");
    }

    @Override
    public JsonNode getBucketIamPolicy(String bucket) {
        return get(storage + "/storage/v1/b/" + enc(bucket) + BUCKET_POLICY_PATH, "storage.buckets.getIamPolicy",
                "buckets/" + bucket);
    }

    private JsonNode get(String url, String permission, String resource) {
        URI uri = URI.create(url);
        return checked(http.send(authorised(HttpRequest.newBuilder(uri), uri).GET().build()), permission, resource);
    }

    private static JsonNode checked(CoverageHttp.Response response, String permission, String resource) {
        if (response.ok()) {
            return response.body();
        }
        if (response.status() == 403) {
            throw new MissingPermissionException(permission, resource);
        }
        throw new CoverageTransportException(permission + " call for " + resource + " answered HTTP "
                + response.status());
    }

    private HttpRequest.Builder authorised(HttpRequest.Builder builder, URI uri) {
        Map<String, List<String>> headers;
        try {
            headers = credentials.get().getRequestMetadata(uri);
        } catch (IOException e) {
            throw new CoverageTransportException("could not obtain GCP credentials: " + e.getClass().getSimpleName(), e);
        }
        if (headers != null) {
            headers.forEach((name, values) -> values.forEach(v -> builder.header(name, v)));
        }
        return builder.timeout(CoverageHttp.TIMEOUT);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trim(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * The path and query of the bucket policy read. Asking for policy version 3
     * ({@code optionsRequestedPolicyVersion=3}) is what makes a bucket with IAM Conditions readable:
     * without it Cloud Storage answers such a bucket with an error instead of a policy (Q-474). It
     * is declared below the call sites on purpose: {@code deployment/iam/gcp/reader/permissions.yaml}
     * pins those lines.
     */
    static final String BUCKET_POLICY_PATH = "/iam?optionsRequestedPolicyVersion=3";
}
