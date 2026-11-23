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

package com.dlap.adminservice.audit.providers.azure;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

import com.dlap.adminservice.audit.AuditProperties;
import com.dlap.adminservice.audit.export.CheckpointExportException;
import com.dlap.adminservice.audit.export.CheckpointSink;
import com.dlap.adminservice.audit.export.HttpCalls;
import com.dlap.adminservice.audit.export.SignedCheckpoint;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Exports checkpoints to an Azure Blob Storage container with version-level immutability
 * (docs/specs/audit-log.md, section "Export"). Each blob is written with a locked immutability
 * policy {@code retention-days} ahead ({@code x-ms-immutability-policy-mode: Locked}), which no
 * one can shorten or remove, and with {@code If-None-Match: *}, so an existing name is never
 * replaced. The preflight reads the container's properties and refuses a container without
 * version-level immutability support.
 *
 * <p>Plain REST (service version 2021-12-02); the token is read from {@code access-token-file}
 * when set, otherwise from the instance metadata service for the managed identity. Least
 * privilege: the Storage Blob Data Contributor role on the container, which includes setting an
 * immutability policy on a new blob version.
 */
public final class AzureImmutableBlobCheckpointSink implements CheckpointSink {

    /** The Blob service REST version: immutability headers need 2020-06-12 or later. */
    public static final String SERVICE_VERSION = "2021-12-02";

    private static final Pattern CONTAINER = Pattern.compile("^[a-z0-9](?!.*--)[a-z0-9-]{1,61}[a-z0-9]$");
    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AuditProperties.Azure config;
    private final String prefix;
    private final HttpCalls http;
    private final Clock clock;
    private volatile String cachedToken;
    private volatile Instant cachedUntil = Instant.MIN;

    /**
     * Creates the sink and checks its settings.
     *
     * @param config the Azure settings
     * @param prefix the blob name prefix
     * @param http   the HTTP client
     * @param clock  the clock for dates and the token cache
     * @throws IllegalArgumentException for a missing account URL, container or retention
     */
    public AzureImmutableBlobCheckpointSink(AuditProperties.Azure config, String prefix, HttpCalls http, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.prefix = prefix;
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
        URI account;
        try {
            account = URI.create(config.getAccountUrl());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("dlap.audit.export.azure.account-url is not a URL", e);
        }
        if (account.getScheme() == null || account.getHost() == null) {
            throw new IllegalArgumentException("dlap.audit.export.azure.account-url must be an absolute URL");
        }
        if (!CONTAINER.matcher(config.getContainer()).matches()) {
            throw new IllegalArgumentException("dlap.audit.export.azure.container is not a valid container name");
        }
        if (config.getRetentionDays() < 1) {
            throw new IllegalArgumentException("dlap.audit.export.azure.retention-days must be at least 1");
        }
    }

    @Override
    public String name() {
        return "azure";
    }

    @Override
    public void preflight() {
        URI uri = URI.create(containerUrl() + "?restype=container");
        HttpResponse<byte[]> r = http.send(authorized(uri).GET().build(), "Azure Get Container Properties");
        if (r.statusCode() != 200) {
            throw HttpCalls.unexpected("Azure Get Container Properties " + config.getContainer(), r);
        }
        boolean worm = r.headers().firstValue("x-ms-immutable-storage-with-versioning-enabled")
                .map("true"::equalsIgnoreCase).orElse(false);
        if (!worm) {
            throw new CheckpointExportException("container " + config.getContainer()
                    + " does not have version-level immutability enabled");
        }
    }

    @Override
    public String export(SignedCheckpoint checkpoint) {
        put(checkpoint.signatureName(prefix), checkpoint.signatureBytes(), "text/plain");
        String version = put(checkpoint.payloadName(prefix), checkpoint.payloadBytes(), "application/json");
        String location = containerUrl() + "/" + checkpoint.payloadName(prefix);
        return version == null ? location : location + "#" + version;
    }

    private String put(String name, byte[] body, String contentType) {
        URI uri = blobUri(name);
        Instant until = clock.instant().plus(Duration.ofDays(config.getRetentionDays()));
        HttpResponse<byte[]> r = http.send(authorized(uri)
                .header("Content-Type", contentType)
                .header("x-ms-blob-type", "BlockBlob")
                .header("If-None-Match", "*")
                .header("x-ms-immutability-policy-until-date", RFC_1123.format(until))
                .header("x-ms-immutability-policy-mode", "Locked")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(), "Azure Put Blob " + name);
        if (r.statusCode() == 201) {
            return r.headers().firstValue("x-ms-version-id").orElse(null);
        }
        if (r.statusCode() == 409 || r.statusCode() == 412) {
            HttpResponse<byte[]> existing = http.send(authorized(uri).GET().build(), "Azure Get Blob " + name);
            if (existing.statusCode() == 200 && Arrays.equals(existing.body(), body)) {
                return existing.headers().firstValue("x-ms-version-id").orElse(null);
            }
            throw new CheckpointExportException(uri + " already exists with other content");
        }
        throw HttpCalls.unexpected("Azure Put Blob " + name, r);
    }

    private HttpRequest.Builder authorized(URI uri) {
        return http.request(uri)
                .header("Authorization", "Bearer " + token())
                .header("x-ms-version", SERVICE_VERSION)
                .header("x-ms-date", RFC_1123.format(clock.instant()));
    }

    private String token() {
        if (config.getAccessTokenFile() != null && !config.getAccessTokenFile().isBlank()) {
            return HttpCalls.tokenFromFile(config.getAccessTokenFile());
        }
        String t = cachedToken;
        if (t != null && clock.instant().isBefore(cachedUntil)) {
            return t;
        }
        StringBuilder q = new StringBuilder("/metadata/identity/oauth2/token?api-version=2018-02-01&resource=")
                .append(enc("https://storage.azure.com/"));
        if (config.getManagedIdentityClientId() != null && !config.getManagedIdentityClientId().isBlank()) {
            q.append("&client_id=").append(enc(config.getManagedIdentityClientId()));
        }
        URI uri = URI.create(config.getImdsEndpoint().replaceAll("/+$", "") + q);
        HttpResponse<byte[]> r = http.send(http.request(uri).header("Metadata", "true").GET().build(),
                "Azure IMDS token");
        if (r.statusCode() != 200) {
            throw HttpCalls.unexpected("Azure IMDS token", r);
        }
        JsonNode n;
        try {
            n = JSON.readTree(r.body());
        } catch (IOException e) {
            throw new CheckpointExportException("Azure IMDS answered with a body that is not JSON", e);
        }
        t = n.path("access_token").asText("");
        if (t.isEmpty()) {
            throw new CheckpointExportException("Azure IMDS returned no access token");
        }
        cachedToken = t;
        long expiresOn = n.path("expires_on").asLong(0);
        cachedUntil = expiresOn > 0 ? Instant.ofEpochSecond(expiresOn).minusSeconds(300) : clock.instant();
        return t;
    }

    private String containerUrl() {
        return config.getAccountUrl().replaceAll("/+$", "") + "/" + config.getContainer();
    }

    private URI blobUri(String name) {
        StringBuilder path = new StringBuilder();
        for (String segment : name.split("/")) {
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(enc(segment));
        }
        return URI.create(containerUrl() + "/" + path);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
