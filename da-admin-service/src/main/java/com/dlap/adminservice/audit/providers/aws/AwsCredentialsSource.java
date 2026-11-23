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
import java.time.Instant;
import java.util.Objects;
import java.util.function.Function;

import com.dlap.adminservice.audit.export.CheckpointExportException;
import com.dlap.adminservice.audit.export.HttpCalls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Finds AWS credentials, in this order: the standard environment variables
 * ({@code AWS_ACCESS_KEY_ID}, {@code AWS_SECRET_ACCESS_KEY}, optional {@code AWS_SESSION_TOKEN}),
 * then the EC2 instance profile through IMDSv2 (a session token first, then the role's temporary
 * credentials), cached until five minutes before they expire. Web identity (EKS IRSA) and ECS task
 * roles are not read yet (docs/specs/audit-log.md, section "Export").
 */
public final class AwsCredentialsSource {

    private static final Duration REFRESH_BEFORE_EXPIRY = Duration.ofMinutes(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Function<String, String> env;
    private final HttpCalls http;
    private final URI imds;
    private final Clock clock;
    private volatile AwsCredentials cached;

    /**
     * Creates the source.
     *
     * @param env   reads an environment variable ({@code System::getenv} in production)
     * @param http  the HTTP client
     * @param imds  the instance metadata endpoint, normally {@code http://169.254.169.254}
     * @param clock the clock for the cache
     */
    public AwsCredentialsSource(Function<String, String> env, HttpCalls http, URI imds, Clock clock) {
        this.env = Objects.requireNonNull(env, "env");
        this.http = Objects.requireNonNull(http, "http");
        this.imds = Objects.requireNonNull(imds, "imds");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Current credentials.
     *
     * @return the credentials
     * @throws CheckpointExportException when no source has any
     */
    public AwsCredentials get() {
        String id = env.apply("AWS_ACCESS_KEY_ID");
        String secret = env.apply("AWS_SECRET_ACCESS_KEY");
        if (id != null && !id.isBlank() && secret != null && !secret.isBlank()) {
            String token = env.apply("AWS_SESSION_TOKEN");
            return new AwsCredentials(id.trim(), secret.trim(), token == null || token.isBlank() ? null : token.trim(), null);
        }
        AwsCredentials c = cached;
        if (c != null && (c.expiration() == null || clock.instant().plus(REFRESH_BEFORE_EXPIRY).isBefore(c.expiration()))) {
            return c;
        }
        c = fromInstanceProfile();
        cached = c;
        return c;
    }

    private AwsCredentials fromInstanceProfile() {
        HttpResponse<byte[]> token = http.send(http.request(imds.resolve("/latest/api/token"))
                .header("X-aws-ec2-metadata-token-ttl-seconds", "21600")
                .PUT(HttpRequest.BodyPublishers.noBody()).build(), "IMDSv2 token");
        if (token.statusCode() != 200) {
            throw new CheckpointExportException("no AWS credentials: AWS_ACCESS_KEY_ID is unset and the instance "
                    + "metadata service answered HTTP " + token.statusCode());
        }
        String imdsToken = new String(token.body(), StandardCharsets.UTF_8).trim();
        HttpResponse<byte[]> roles = http.send(http.request(imds.resolve("/latest/meta-data/iam/security-credentials/"))
                .header("X-aws-ec2-metadata-token", imdsToken).GET().build(), "IMDSv2 role list");
        if (roles.statusCode() != 200) {
            throw HttpCalls.unexpected("IMDSv2 role list", roles);
        }
        String role = new String(roles.body(), StandardCharsets.UTF_8).trim().split("\\s+")[0];
        if (role.isEmpty() || !role.matches("[\\w+=,.@-]{1,128}")) {
            throw new CheckpointExportException("no instance profile role is attached");
        }
        HttpResponse<byte[]> creds = http.send(http.request(imds.resolve("/latest/meta-data/iam/security-credentials/" + role))
                .header("X-aws-ec2-metadata-token", imdsToken).GET().build(), "IMDSv2 credentials");
        if (creds.statusCode() != 200) {
            throw HttpCalls.unexpected("IMDSv2 credentials", creds);
        }
        try {
            JsonNode n = JSON.readTree(creds.body());
            return new AwsCredentials(n.path("AccessKeyId").asText(), n.path("SecretAccessKey").asText(),
                    n.path("Token").asText(null), Instant.parse(n.path("Expiration").asText()));
        } catch (java.io.IOException | RuntimeException e) {
            throw new CheckpointExportException("IMDSv2 credentials are not readable", e);
        }
    }
}
