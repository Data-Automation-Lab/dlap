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

package com.dlap.autodelete.nativerule;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Test-only endpoints of the retire appliers (RET-08, Q-713, decision D38), bound from
 * {@code dlap.autodelete.test-endpoint.*}. Empty by default: every applier then talks to its cloud's real
 * endpoint with the default credentials. Set, an applier talks to an emulator instead (LocalStack or moto for
 * S3, fake-gcs-server for GCS, a stub of the Azure Resource Manager API for Azure), so a harness can apply,
 * read and remove a real rule. Each applier logs a WARN at start-up while its endpoint is set; a production
 * deployment never sets one.
 *
 * @param s3          the S3 endpoint, for example {@code http://localstack:4566}; empty for AWS
 * @param s3PathStyle path-style requests ({@code http://host/bucket}) against the S3 endpoint, true by default,
 *                    because an emulator on the compose network has no virtual-host names for its buckets
 * @param gcs         the GCS JSON API host, for example {@code http://fake-gcs:4443}; empty for Google Cloud
 * @param azure       the Azure Resource Manager endpoint, for example {@code https://arm-stub:8443/}; empty for
 *                    Azure. HTTPS only: the Azure SDK sends a bearer token only over HTTPS
 */
@ConfigurationProperties(prefix = "dlap.autodelete.test-endpoint")
public record TestEndpoints(
        @DefaultValue("") String s3,
        @DefaultValue("true") boolean s3PathStyle,
        @DefaultValue("") String gcs,
        @DefaultValue("") String azure) {

    /** Rejects an endpoint that is not an absolute http or https URI, and an Azure endpoint that is not https. */
    public TestEndpoints {
        s3 = s3 == null ? "" : s3.trim();
        gcs = gcs == null ? "" : gcs.trim();
        azure = azure == null ? "" : azure.trim();
        check("dlap.autodelete.test-endpoint.s3", s3, false);
        check("dlap.autodelete.test-endpoint.gcs", gcs, false);
        check("dlap.autodelete.test-endpoint.azure", azure, true);
    }

    /**
     * No test endpoint: every applier talks to its cloud.
     *
     * @return the empty settings
     */
    public static TestEndpoints none() {
        return new TestEndpoints("", true, "", "");
    }

    /** @return the S3 test endpoint, when one is set */
    public Optional<URI> s3Endpoint() {
        return s3.isEmpty() ? Optional.empty() : Optional.of(URI.create(s3));
    }

    /** @return the GCS test host, when one is set */
    public Optional<String> gcsHost() {
        return gcs.isEmpty() ? Optional.empty() : Optional.of(gcs);
    }

    /** @return the Azure Resource Manager test endpoint, when one is set */
    public Optional<String> azureResourceManager() {
        return azure.isEmpty() ? Optional.empty() : Optional.of(azure);
    }

    /**
     * The WARN an applier logs at start-up while its endpoint is set.
     *
     * @param platform the applier's platform
     * @param endpoint the endpoint
     * @return the sentence
     */
    public static String warning(NativeRulePlatform platform, String endpoint) {
        return "The " + platform + " applier uses the TEST-ONLY endpoint " + endpoint
                + " (dlap.autodelete.test-endpoint); rules go to that emulator, not to the cloud. Never set this in "
                + "production.";
    }

    private static void check(String property, String value, boolean httpsOnly) {
        if (value.isEmpty()) {
            return;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(property + " is not a URI: " + value, e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean allowed = httpsOnly ? "https".equals(scheme) : "http".equals(scheme) || "https".equals(scheme);
        if (!allowed || uri.getHost() == null) {
            String schemes = httpsOnly ? "https" : "http or https";
            throw new IllegalArgumentException(property + " must be an absolute " + schemes + " URI with a host, was "
                    + value);
        }
    }
}
