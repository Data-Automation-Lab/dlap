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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/**
 * {@link AwsSigV4} against the worked example of the AWS documentation ("Signature Calculations for
 * the Authorization Header: Transferring Payload in a Single Chunk", example "GET Object"). The
 * access key and secret are the documentation's published example values, split so that secret
 * scanners do not mistake them for real ones.
 */
class AwsSigV4Test {

    static final AwsCredentials DOC_EXAMPLE = new AwsCredentials("AKIAIOSFODNN7" + "EXAMPLE",
            "wJalrXUtnFEMI/K7MDENG/" + "bPxRfiCYEXAMPLEKEY", null, null);

    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void theDocumentationGetObjectExampleGivesThePublishedSignature() {
        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("range", "bytes=0-9");
        headers.put("x-amz-content-sha256", EMPTY_SHA256);
        headers.put("x-amz-date", "20130524T000000Z");
        String auth = AwsSigV4.authorization("GET", "/test.txt", "", headers, EMPTY_SHA256, DOC_EXAMPLE,
                "us-east-1", "s3", "20130524T000000Z");
        assertThat(auth).isEqualTo("AWS4-HMAC-SHA256 Credential=" + DOC_EXAMPLE.accessKeyId() + "/20130524/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
    }

    @Test
    void signAddsDateHashTokenAndAuthorizationButNotHost() {
        AwsCredentials temporary = new AwsCredentials("ASIAEXAMPLE", "secret", "session-token", null);
        Map<String, String> out = AwsSigV4.sign("GET", URI.create("https://examplebucket.s3.amazonaws.com/test.txt"),
                Map.of("Range", "bytes=0-9"), EMPTY_SHA256, temporary, "us-east-1", "s3",
                Instant.parse("2013-05-24T00:00:00Z"));
        assertThat(out).containsEntry("x-amz-date", "20130524T000000Z")
                .containsEntry("x-amz-content-sha256", EMPTY_SHA256)
                .containsEntry("x-amz-security-token", "session-token")
                .containsEntry("range", "bytes=0-9")
                .doesNotContainKey("host");
        assertThat(out.get("authorization"))
                .contains("SignedHeaders=host;range;x-amz-content-sha256;x-amz-date;x-amz-security-token");
        assertThat(temporary.toString()).doesNotContain("secret").doesNotContain("session-token");
    }

    @Test
    void signWithTheDocumentationValuesReproducesTheExample() {
        Map<String, String> out = AwsSigV4.sign("GET", URI.create("https://examplebucket.s3.amazonaws.com/test.txt"),
                Map.of("range", "bytes=0-9"), EMPTY_SHA256, DOC_EXAMPLE, "us-east-1", "s3",
                Instant.parse("2013-05-24T00:00:00Z"));
        assertThat(out.get("authorization")).endsWith(
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
    }

    @Test
    void encodingAndCanonicalQueryFollowTheSigV4Rules() {
        assertThat(AwsSigV4.uriEncode("a b/c~d*e+f", true)).isEqualTo("a%20b/c~d%2Ae%2Bf");
        assertThat(AwsSigV4.uriEncode("a/b", false)).isEqualTo("a%2Fb");
        assertThat(AwsSigV4.uriEncode("\u00e9", false)).isEqualTo("%C3%A9");
        assertThat(AwsSigV4.canonicalQuery("object-lock")).isEqualTo("object-lock=");
        assertThat(AwsSigV4.canonicalQuery("b=2&a=1&a=0")).isEqualTo("a=0&a=1&b=2");
        assertThat(AwsSigV4.canonicalQuery(null)).isEmpty();
        assertThat(AwsSigV4.sign("GET", URI.create("http://localhost:9000/b/k"), Map.of(), EMPTY_SHA256, DOC_EXAMPLE,
                "eu-west-1", "s3", Instant.EPOCH).get("authorization")).contains("/19700101/eu-west-1/s3/aws4_request");
    }
}
