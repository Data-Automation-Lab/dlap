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

package com.dlap.adminservice.audit.providers.gcp;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
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
 * Exports checkpoints to a Cloud Storage bucket whose retention policy is locked (docs/specs/audit-log.md,
 * section "Export"). A locked bucket retention policy keeps every object for the retention period
 * and cannot be shortened or removed, so the bucket is write-once storage for that period. Objects
 * are uploaded with {@code ifGenerationMatch=0}, so an existing name is never replaced. The
 * preflight reads the bucket's retention policy and refuses one that is not locked or shorter than
 * {@code min-retention-days}.
 *
 * <p>Plain JSON API; the token is read from {@code access-token-file} when set, otherwise from the
 * metadata server for the attached service account. Least privilege:
 * {@code storage.objects.create}, {@code storage.objects.get} and {@code storage.buckets.get}.
 */
public final class GcsBucketLockCheckpointSink implements CheckpointSink {

    private static final Pattern BUCKET = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,220}[a-z0-9]$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AuditProperties.Gcs config;
    private final String prefix;
    private final HttpCalls http;
    private final Clock clock;
    private volatile String cachedToken;
    private volatile Instant cachedUntil = Instant.MIN;

    /**
     * Creates the sink and checks its settings.
     *
     * @param config the GCS settings
     * @param prefix the object name prefix
     * @param http   the HTTP client
     * @param clock  the clock for the token cache
     * @throws IllegalArgumentException for a missing bucket
     */
    public GcsBucketLockCheckpointSink(AuditProperties.Gcs config, String prefix, HttpCalls http, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.prefix = prefix;
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!BUCKET.matcher(config.getBucket()).matches()) {
            throw new IllegalArgumentException("dlap.audit.export.gcs.bucket is not a valid bucket name");
        }
        if (config.getMinRetentionDays() < 1) {
            throw new IllegalArgumentException("dlap.audit.export.gcs.min-retention-days must be at least 1");
        }
    }

    @Override
    public String name() {
        return "gcs";
    }

    @Override
    public void preflight() {
        URI uri = URI.create(endpoint() + "/storage/v1/b/" + enc(config.getBucket()) + "?fields=retentionPolicy");
        HttpResponse<byte[]> r = http.send(authorized(uri).GET().build(), "GCS buckets.get");
        if (r.statusCode() != 200) {
            throw HttpCalls.unexpected("GCS buckets.get " + config.getBucket(), r);
        }
        JsonNode policy = json(r.body()).path("retentionPolicy");
        if (!policy.path("isLocked").asBoolean(false)) {
            throw new CheckpointExportException("bucket " + config.getBucket() + " has no locked retention policy");
        }
        long seconds = policy.path("retentionPeriod").asLong(0);
        if (seconds < config.getMinRetentionDays() * 86_400L) {
            throw new CheckpointExportException("bucket " + config.getBucket() + " retains objects for " + seconds
                    + " s, less than " + config.getMinRetentionDays() + " days");
        }
    }

    @Override
    public String export(SignedCheckpoint checkpoint) {
        upload(checkpoint.signatureName(prefix), checkpoint.signatureBytes(), "text/plain");
        String generation = upload(checkpoint.payloadName(prefix), checkpoint.payloadBytes(), "application/json");
        String location = "gs://" + config.getBucket() + "/" + checkpoint.payloadName(prefix);
        return generation == null ? location : location + "#" + generation;
    }

    private String upload(String name, byte[] body, String contentType) {
        URI uri = URI.create(endpoint() + "/upload/storage/v1/b/" + enc(config.getBucket())
                + "/o?uploadType=media&ifGenerationMatch=0&name=" + enc(name));
        HttpResponse<byte[]> r = http.send(authorized(uri).header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), "GCS objects.insert " + name);
        if (r.statusCode() == 200) {
            String generation = json(r.body()).path("generation").asText("");
            return generation.isEmpty() ? null : generation;
        }
        if (r.statusCode() == 412) {
            URI get = URI.create(endpoint() + "/storage/v1/b/" + enc(config.getBucket()) + "/o/" + enc(name) + "?alt=media");
            HttpResponse<byte[]> existing = http.send(authorized(get).GET().build(), "GCS objects.get " + name);
            if (existing.statusCode() == 200 && Arrays.equals(existing.body(), body)) {
                return null;
            }
            throw new CheckpointExportException("gs://" + config.getBucket() + "/" + name + " already exists with other content");
        }
        throw HttpCalls.unexpected("GCS objects.insert " + name, r);
    }

    private HttpRequest.Builder authorized(URI uri) {
        return http.request(uri).header("Authorization", "Bearer " + token());
    }

    private String token() {
        if (config.getAccessTokenFile() != null && !config.getAccessTokenFile().isBlank()) {
            return HttpCalls.tokenFromFile(config.getAccessTokenFile());
        }
        String t = cachedToken;
        if (t != null && clock.instant().isBefore(cachedUntil)) {
            return t;
        }
        URI uri = URI.create(config.getMetadataEndpoint().replaceAll("/+$", "")
                + "/computeMetadata/v1/instance/service-accounts/default/token");
        HttpResponse<byte[]> r = http.send(http.request(uri).header("Metadata-Flavor", "Google").GET().build(),
                "GCE metadata token");
        if (r.statusCode() != 200) {
            throw HttpCalls.unexpected("GCE metadata token", r);
        }
        JsonNode n = json(r.body());
        t = n.path("access_token").asText("");
        if (t.isEmpty()) {
            throw new CheckpointExportException("the metadata server returned no access token");
        }
        cachedToken = t;
        cachedUntil = clock.instant().plusSeconds(Math.max(0, n.path("expires_in").asLong(0) - 300));
        return t;
    }

    private String endpoint() {
        return config.getEndpoint().replaceAll("/+$", "");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static JsonNode json(byte[] body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new CheckpointExportException("GCS answered with a body that is not JSON", e);
        }
    }
}
