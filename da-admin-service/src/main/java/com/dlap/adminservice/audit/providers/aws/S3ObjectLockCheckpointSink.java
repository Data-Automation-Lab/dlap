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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.dlap.adminservice.audit.AuditProperties;
import com.dlap.adminservice.audit.export.CheckpointExportException;
import com.dlap.adminservice.audit.export.CheckpointSink;
import com.dlap.adminservice.audit.export.HttpCalls;
import com.dlap.adminservice.audit.export.SignedCheckpoint;
import com.dlap.common.audit.AuditChain;

/**
 * Exports checkpoints to an S3 bucket with Object Lock (docs/specs/audit-log.md, section
 * "Export"). Each object is written with {@code x-amz-object-lock-mode} (COMPLIANCE by default)
 * and a retain-until date {@code retention-days} ahead, a SHA-256 checksum, and
 * {@code If-None-Match: *} so an existing key is never replaced. The preflight reads the bucket's
 * Object Lock configuration and refuses a bucket where Object Lock is not enabled.
 *
 * <p>Plain REST with SigV4 ({@link AwsSigV4}); no AWS SDK, so the service's dependency set does
 * not change. Least privilege: {@code s3:PutObject}, {@code s3:PutObjectRetention},
 * {@code s3:GetObject} and {@code s3:GetBucketObjectLockConfiguration} on the bucket.
 */
public final class S3ObjectLockCheckpointSink implements CheckpointSink {

    private static final Pattern BUCKET = Pattern.compile("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$");
    private static final Pattern REGION = Pattern.compile("^[a-z]{2}(-[a-z]+)+-\\d$");
    private static final String SERVICE = "s3";
    private static final String EMPTY_SHA256 = AuditChain.sha256Hex("");

    private final AuditProperties.S3 config;
    private final String prefix;
    private final HttpCalls http;
    private final AwsCredentialsSource credentials;
    private final Clock clock;

    /**
     * Creates the sink and checks its settings.
     *
     * @param config      the S3 settings
     * @param prefix      the object name prefix
     * @param http        the HTTP client
     * @param credentials the credentials
     * @param clock       the clock for signing and the retain-until date
     * @throws IllegalArgumentException for a missing bucket, region, retention or a bad mode
     */
    public S3ObjectLockCheckpointSink(AuditProperties.S3 config, String prefix, HttpCalls http,
            AwsCredentialsSource credentials, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.prefix = prefix;
        this.http = Objects.requireNonNull(http, "http");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!BUCKET.matcher(config.getBucket()).matches()) {
            throw new IllegalArgumentException("dlap.audit.export.s3.bucket is not a valid bucket name");
        }
        if (!REGION.matcher(config.getRegion()).matches()) {
            throw new IllegalArgumentException("dlap.audit.export.s3.region is not a region such as eu-west-1");
        }
        if (config.getRetentionDays() < 1) {
            throw new IllegalArgumentException("dlap.audit.export.s3.retention-days must be at least 1");
        }
        if (!"COMPLIANCE".equals(config.getMode()) && !"GOVERNANCE".equals(config.getMode())) {
            throw new IllegalArgumentException("dlap.audit.export.s3.mode must be COMPLIANCE or GOVERNANCE");
        }
    }

    @Override
    public String name() {
        return "s3";
    }

    @Override
    public void preflight() {
        URI uri = URI.create(bucketBase() + "?object-lock");
        HttpResponse<byte[]> r = send("GET", uri, Map.of(), new byte[0], "S3 GetObjectLockConfiguration");
        String body = new String(r.body(), StandardCharsets.UTF_8);
        if (r.statusCode() != 200) {
            throw new CheckpointExportException("bucket " + config.getBucket() + " has no Object Lock configuration (HTTP "
                    + r.statusCode() + ": " + HttpCalls.excerpt(r.body()) + ")");
        }
        if (!body.replaceAll("\\s+", "").contains("<ObjectLockEnabled>Enabled</ObjectLockEnabled>")) {
            throw new CheckpointExportException("bucket " + config.getBucket() + " does not have Object Lock enabled");
        }
    }

    @Override
    public String export(SignedCheckpoint checkpoint) {
        put(checkpoint.signatureName(prefix), checkpoint.signatureBytes(), "text/plain");
        String version = put(checkpoint.payloadName(prefix), checkpoint.payloadBytes(), "application/json");
        String location = "s3://" + config.getBucket() + "/" + checkpoint.payloadName(prefix);
        return version == null ? location : location + "#" + version;
    }

    private String put(String key, byte[] body, String contentType) {
        URI uri = objectUri(key);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", contentType);
        headers.put("if-none-match", "*");
        headers.put("x-amz-object-lock-mode", config.getMode());
        headers.put("x-amz-object-lock-retain-until-date", DateTimeFormatter.ISO_INSTANT.format(
                clock.instant().plus(Duration.ofDays(config.getRetentionDays())).truncatedTo(ChronoUnit.SECONDS)));
        headers.put("x-amz-sdk-checksum-algorithm", "SHA256");
        headers.put("x-amz-checksum-sha256", Base64.getEncoder().encodeToString(AuditChain.sha256(body)));
        HttpResponse<byte[]> r = send("PUT", uri, headers, body, "S3 PutObject " + key);
        if (r.statusCode() == 200) {
            return r.headers().firstValue("x-amz-version-id").orElse(null);
        }
        if (r.statusCode() == 412) {
            HttpResponse<byte[]> existing = send("GET", uri, Map.of(), new byte[0], "S3 GetObject " + key);
            if (existing.statusCode() == 200 && Arrays.equals(existing.body(), body)) {
                return existing.headers().firstValue("x-amz-version-id").orElse(null);
            }
            throw new CheckpointExportException("s3://" + config.getBucket() + "/" + key + " already exists with other content");
        }
        throw HttpCalls.unexpected("S3 PutObject " + key, r);
    }

    private HttpResponse<byte[]> send(String method, URI uri, Map<String, String> headers, byte[] body, String what) {
        String payloadHash = body.length == 0 ? EMPTY_SHA256 : HexFormat.of().formatHex(AuditChain.sha256(body));
        Map<String, String> signed = AwsSigV4.sign(method, uri, headers, payloadHash, credentials.get(),
                config.getRegion(), SERVICE, clock.instant());
        HttpRequest.Builder b = http.request(uri).method(method,
                body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        signed.forEach(b::header);
        return http.send(b.build(), what);
    }

    /** The bucket URL with a trailing slash. */
    String bucketBase() {
        String endpoint = config.getEndpoint() == null || config.getEndpoint().isBlank()
                ? "https://s3." + config.getRegion() + ".amazonaws.com"
                : config.getEndpoint().replaceAll("/+$", "");
        if (config.isPathStyle()) {
            return endpoint + "/" + config.getBucket() + "/";
        }
        URI e = URI.create(endpoint);
        String port = e.getPort() == -1 ? "" : ":" + e.getPort();
        return e.getScheme() + "://" + config.getBucket() + "." + e.getHost() + port + "/";
    }

    /** The URL of an object; the key is URI-encoded with its slashes kept. */
    URI objectUri(String key) {
        return URI.create(bucketBase() + AwsSigV4.uriEncode(key, true));
    }
}
