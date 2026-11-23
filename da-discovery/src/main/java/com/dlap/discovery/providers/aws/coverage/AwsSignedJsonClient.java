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

package com.dlap.discovery.providers.aws.coverage;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.dlap.discovery.coverage.CoverageHttp;
import com.dlap.discovery.coverage.CoverageHttp.CoverageTransportException;
import com.dlap.discovery.coverage.MissingPermissionException;
import com.fasterxml.jackson.databind.JsonNode;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;

/**
 * Calls the CloudTrail and STS read operations the coverage collector needs, signed with SigV4
 * (the SDK's {@link AwsV4HttpSigner}; the CloudTrail service module is not a dependency of
 * da-discovery). CloudTrail speaks the AWS JSON 1.1 protocol: a POST to
 * {@code https://cloudtrail.<region>.amazonaws.com/} with the header
 * {@code X-Amz-Target: com.amazonaws.cloudtrail.v20131101.CloudTrail_20131101.<Operation>}.
 *
 * <p>An access-denied answer becomes a {@link MissingPermissionException} naming the IAM action;
 * any other error a {@link CloudTrailApiException} with the error type only.
 */
public class AwsSignedJsonClient implements CloudTrailApi {

    private static final String TARGET_PREFIX = "com.amazonaws.cloudtrail.v20131101.CloudTrail_20131101.";
    private static final String JSON_11 = "application/x-amz-json-1.1";
    private static final Pattern ACCOUNT = Pattern.compile("<Account>(\\d{12})</Account>");
    /** Headers the JDK client sets itself and refuses to take from the caller. */
    private static final Set<String> RESTRICTED = Set.of("host", "content-length", "connection", "expect", "upgrade");

    private final CoverageHttp http;
    private final AwsCredentialsProvider credentials;
    private final Function<String, URI> cloudTrailEndpoint;
    private final Function<String, URI> stsEndpoint;
    private final AwsV4HttpSigner signer = AwsV4HttpSigner.create();

    /** A CloudTrail error that is not a refused permission. */
    public static class CloudTrailApiException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param message the operation and the error type, never the response text
         */
        public CloudTrailApiException(String message) {
            super(message);
        }
    }

    /**
     * Creates the client.
     *
     * @param http               the transport
     * @param credentials        the AWS credentials
     * @param cloudTrailEndpoint the CloudTrail endpoint of a region
     * @param stsEndpoint        the STS endpoint of a region
     */
    public AwsSignedJsonClient(CoverageHttp http, AwsCredentialsProvider credentials,
                               Function<String, URI> cloudTrailEndpoint, Function<String, URI> stsEndpoint) {
        this.http = http;
        this.credentials = credentials;
        this.cloudTrailEndpoint = cloudTrailEndpoint;
        this.stsEndpoint = stsEndpoint;
    }

    /**
     * The public endpoints.
     *
     * @param region an AWS region
     * @return {@code https://cloudtrail.<region>.amazonaws.com/} (with {@code .com.cn} in China)
     */
    public static URI publicCloudTrailEndpoint(String region) {
        return URI.create("https://cloudtrail." + region + "." + domain(region) + "/");
    }

    /**
     * The public regional STS endpoint.
     *
     * @param region an AWS region
     * @return {@code https://sts.<region>.amazonaws.com/}
     */
    public static URI publicStsEndpoint(String region) {
        return URI.create("https://sts." + region + "." + domain(region) + "/");
    }

    private static String domain(String region) {
        return region.toLowerCase(Locale.ROOT).startsWith("cn-") ? "amazonaws.com.cn" : "amazonaws.com";
    }

    /**
     * DescribeTrails with shadow trails: the trails that apply to the region, including
     * multi-region and organization trails homed elsewhere.
     *
     * @param region the region
     * @return the response
     */
    @Override
    public JsonNode describeTrails(String region) {
        return cloudTrail(region, "DescribeTrails", "{\"includeShadowTrails\":true}",
                "cloudtrail:DescribeTrails", "region " + region);
    }

    /**
     * GetEventSelectors of a trail, called in the trail's home region.
     *
     * @param trailArn   the trail
     * @param homeRegion its home region
     * @return the response
     */
    @Override
    public JsonNode getEventSelectors(String trailArn, String homeRegion) {
        return cloudTrail(homeRegion, "GetEventSelectors", body("TrailName", trailArn),
                "cloudtrail:GetEventSelectors", trailArn);
    }

    /**
     * GetTrailStatus of a trail, called in the trail's home region.
     *
     * @param trailArn   the trail
     * @param homeRegion its home region
     * @return the response
     */
    @Override
    public JsonNode getTrailStatus(String trailArn, String homeRegion) {
        return cloudTrail(homeRegion, "GetTrailStatus", body("Name", trailArn), "cloudtrail:GetTrailStatus",
                trailArn);
    }

    /**
     * The account of the credentials (STS GetCallerIdentity, which needs no permission).
     *
     * @param region the region of the STS endpoint
     * @return the 12-digit account id
     */
    @Override
    public String callerAccount(String region) {
        String form = "Action=GetCallerIdentity&Version=2011-06-15";
        SdkHttpRequest request = SdkHttpRequest.builder()
                .method(SdkHttpMethod.POST)
                .uri(stsEndpoint.apply(region))
                .putHeader("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .build();
        HttpRequest signed = sign(request, form, "sts", region);
        CoverageHttp.RawResponse raw = http.sendRaw(signed);
        Matcher m = ACCOUNT.matcher(raw.text());
        if (raw.status() != 200 || !m.find()) {
            throw new CoverageTransportException("STS GetCallerIdentity answered HTTP " + raw.status());
        }
        return m.group(1);
    }

    private JsonNode cloudTrail(String region, String operation, String body, String permission, String resource) {
        SdkHttpRequest request = SdkHttpRequest.builder()
                .method(SdkHttpMethod.POST)
                .uri(cloudTrailEndpoint.apply(region))
                .putHeader("Content-Type", JSON_11)
                .putHeader("X-Amz-Target", TARGET_PREFIX + operation)
                .build();
        CoverageHttp.Response response = http.send(sign(request, body, "cloudtrail", region));
        if (response.ok()) {
            return response.body();
        }
        String type = errorType(response.body());
        if (response.status() == 403 || type.contains("AccessDenied") || type.contains("NotAuthorized")
                || type.contains("UnauthorizedOperation")) {
            throw new MissingPermissionException(permission, resource);
        }
        throw new CloudTrailApiException(operation + " answered HTTP " + response.status()
                + (type.isEmpty() ? "" : " " + type));
    }

    /** {@code __type} of an AWS JSON error, without its namespace. */
    static String errorType(JsonNode body) {
        String type = body.path("__type").asText("");
        int hash = type.lastIndexOf('#');
        return hash >= 0 ? type.substring(hash + 1) : type;
    }

    private HttpRequest sign(SdkHttpRequest request, String body, String service, String region) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        SignedRequest signed = signer.sign(r -> r
                .identity(credentials.resolveCredentials())
                .request(request)
                .payload(() -> new ByteArrayInputStream(bytes))
                .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, service)
                .putProperty(AwsV4HttpSigner.REGION_NAME, region));
        HttpRequest.Builder builder = HttpRequest.newBuilder(signed.request().getUri())
                .timeout(CoverageHttp.TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        for (Map.Entry<String, List<String>> header : signed.request().headers().entrySet()) {
            if (RESTRICTED.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                continue;
            }
            for (String value : header.getValue()) {
                builder.header(header.getKey(), value);
            }
        }
        return builder.build();
    }

    private String body(String key, String value) {
        return http.mapper().createObjectNode().put(key, value).toString();
    }
}
