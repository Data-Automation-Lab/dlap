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

import static com.dlap.discovery.coverage.CoverageFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.dlap.discovery.coverage.CoverageFixtures;
import com.dlap.discovery.coverage.CoverageHttp;
import com.dlap.discovery.coverage.CoverageHttp.CoverageTransportException;
import com.dlap.discovery.coverage.CoverageLevel;
import com.dlap.discovery.coverage.LocalJsonServer;
import com.dlap.discovery.coverage.ReadCoverageObservation;
import com.google.auth.Credentials;

/**
 * COV-01, GCP: the collector end to end over the real REST client and a local server that answers
 * with the API-reference fixtures, so the URLs, the hierarchy walk and the refusals are exercised
 * together.
 */
class GcpReadCoverageCollectorTest {

    /** Credentials that add a fixed bearer header, as OAuth2 credentials do. */
    static final class FixedCredentials extends Credentials {
        private static final long serialVersionUID = 1L;

        @Override
        public String getAuthenticationType() {
            return "OAuth2";
        }

        @Override
        public Map<String, List<String>> getRequestMetadata(URI uri) {
            return Map.of("Authorization", List.of("Bearer test-token"));
        }

        @Override
        public boolean hasRequestMetadata() {
            return true;
        }

        @Override
        public boolean hasRequestMetadataOnly() {
            return true;
        }

        @Override
        public void refresh() {
        }
    }

    /** The bucket policy reads, with the policy version the client must ask for (Q-474). */
    private static final String WEBSITE_IAM = "/storage/v1/b/dlap-demo-website/iam?optionsRequestedPolicyVersion=3";
    private static final String LEGACY_IAM = "/storage/v1/b/dlap-demo-legacy/iam?optionsRequestedPolicyVersion=3";

    private static String text(String fixture) {
        return json("gcp/" + fixture).toString();
    }

    private static LocalJsonServer server(String orgPolicy) throws IOException {
        return new LocalJsonServer()
                .answer("GET /v3/projects/dlap-demo-data", 200, text("project.json"))
                .answer("GET /v3/folders/200100", 200, text("folder.json"))
                .answer("POST /v3/projects/dlap-demo-data:getIamPolicy", 200, text("policy-project-storage-data-read.json"))
                .answer("POST /v3/folders/200100:getIamPolicy", 200, text("policy-folder-no-audit-configs.json"))
                .answer("POST /v3/organizations/300100:getIamPolicy", orgPolicy == null ? 403 : 200,
                        orgPolicy == null ? "{\"error\":{\"code\":403}}" : text(orgPolicy))
                .answer("GET /storage/v1/b?project=dlap-demo-data", 200, text("buckets.json"))
                .answer("GET " + WEBSITE_IAM, 200, text("bucket-iam-public.json"))
                .answer("GET " + LEGACY_IAM, 200, text("bucket-iam-private.json"));
    }

    private static Map<String, ReadCoverageObservation> collect(LocalJsonServer server, boolean bqVerified) {
        GcpCoverageRestClient client = new GcpCoverageRestClient(CoverageFixtures.http(),
                FixedCredentials::new, server.base(), server.base() + "/");
        return new GcpReadCoverageCollector(client, List.of("dlap-demo-data"), bqVerified).collect().stream()
                .collect(Collectors.toMap(ReadCoverageObservation::rootUri, Function.identity()));
    }

    @Test
    void observesBigQueryAndEveryBucketOfTheProject() throws IOException {
        try (LocalJsonServer server = server("policy-folder-no-audit-configs.json")) {
            Map<String, ReadCoverageObservation> out = collect(server, true);

            assertThat(out).containsOnlyKeys("bq://dlap-demo-data", "gs://dlap-demo-curated", "gs://dlap-demo-website",
                    "gs://dlap-demo-legacy");
            ReadCoverageObservation bq = out.get("bq://dlap-demo-data");
            assertThat(bq.appliesTo()).containsExactly("BIGQUERY_DATASET", "BIGQUERY_TABLE");
            assertThat(bq.channel()).isEqualTo("GCP_CLOUD_AUDIT");
            assertThat(bq.scopeId()).isEqualTo("dlap-demo-data");
            assertThat(bq.verdict().level()).isEqualTo(CoverageLevel.FULL);

            assertThat(out.get("gs://dlap-demo-curated").verdict().level()).as("public access prevention enforced")
                    .isEqualTo(CoverageLevel.FULL);
            assertThat(out.get("gs://dlap-demo-website").verdict().level()).as("allUsers can read")
                    .isEqualTo(CoverageLevel.PARTIAL);
            assertThat(out.get("gs://dlap-demo-legacy").verdict().level()).as("fine-grained ACLs")
                    .isEqualTo(CoverageLevel.PARTIAL);
            assertThat(out.get("gs://dlap-demo-curated").appliesTo()).containsExactly("GCS_BUCKET", "GCS_OBJECT");

            assertThat(server.requests()).allMatch(r -> "Bearer test-token".equals(r.authorization()));
            assertThat(server.requests()).filteredOn(r -> r.method().equals("POST"))
                    .allMatch(r -> r.body().equals("{\"options\":{\"requestedPolicyVersion\":3}}"));
            assertThat(server.requests()).as("no IAM read for a bucket whose prevention is enforced")
                    .noneMatch(r -> r.pathAndQuery().equals("/storage/v1/b/dlap-demo-curated/iam"));
        }
    }

    @Test
    void aRefusedOrganizationPolicyMakesEveryVerdictThatDependsOnItUnknown() throws IOException {
        try (LocalJsonServer server = server(null)) {
            Map<String, ReadCoverageObservation> out = collect(server, true);
            assertThat(out.get("bq://dlap-demo-data").verdict().known()).isFalse();
            assertThat(out.get("bq://dlap-demo-data").verdict().reasons()).containsExactly(
                    "missing permission resourcemanager.organizations.getIamPolicy on organizations/300100");
            assertThat(out.get("gs://dlap-demo-curated").verdict().known()).isFalse();
        }
    }

    @Test
    void anExemptionAtTheOrganizationIsPartialForBothServices() throws IOException {
        try (LocalJsonServer server = server("policy-org-all-services-exempt.json")) {
            Map<String, ReadCoverageObservation> out = collect(server, true);
            assertThat(out.get("bq://dlap-demo-data").verdict().level()).isEqualTo(CoverageLevel.PARTIAL);
            assertThat(out.get("gs://dlap-demo-curated").verdict().level()).isEqualTo(CoverageLevel.PARTIAL);
        }
    }

    @Test
    void refusedBucketListingLeavesOnlyTheBigQueryObservation() throws IOException {
        try (LocalJsonServer server = server("policy-folder-no-audit-configs.json")
                .answer("GET /storage/v1/b?project=dlap-demo-data", 403, "{}")) {
            Map<String, ReadCoverageObservation> out = collect(server, false);
            assertThat(out).containsOnlyKeys("bq://dlap-demo-data");
            assertThat(out.get("bq://dlap-demo-data").verdict().reasons())
                    .containsExactly(GcpAuditConfigEvaluator.BIGQUERY_CROSS_PROJECT_UNVERIFIED);
        }
    }

    @Test
    void aRefusedProjectGetEndsTheAncestryWithAnUnknownLevel() throws IOException {
        try (LocalJsonServer server = server("policy-folder-no-audit-configs.json")
                .answer("GET /v3/projects/dlap-demo-data", 403, "{}")) {
            Map<String, ReadCoverageObservation> out = collect(server, true);
            assertThat(out.get("bq://dlap-demo-data").verdict().reasons()).containsExactly(
                    "missing permission resourcemanager.projects.get on projects/dlap-demo-data");
        }
    }

    @Test
    void theBucketPolicyReadAsksForPolicyVersion3() throws IOException {
        try (LocalJsonServer server = server("policy-folder-no-audit-configs.json")) {
            Map<String, ReadCoverageObservation> out = collect(server, true);

            assertThat(server.requests()).filteredOn(r -> r.pathAndQuery().startsWith("/storage/v1/b/dlap-demo-"))
                    .filteredOn(r -> r.pathAndQuery().contains("/iam"))
                    .isNotEmpty()
                    .allMatch(r -> r.pathAndQuery().endsWith("/iam?optionsRequestedPolicyVersion=3"));
            // The mock answers only the version-3 URL, so a PARTIAL here proves the policy was read.
            assertThat(out.get("gs://dlap-demo-website").verdict().level()).isEqualTo(CoverageLevel.PARTIAL);
            assertThat(out.get("gs://dlap-demo-website").verdict().reasons()).anyMatch(r -> r.contains("allUsers"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 429, 500, 503})
    void aBucketPolicyErrorMakesThatBucketUnknownAndTheRunContinues(int status) throws IOException {
        try (LocalJsonServer server = server("policy-folder-no-audit-configs.json")
                .answer("GET " + WEBSITE_IAM, status, "{\"error\":{\"code\":" + status + "}}")) {
            Map<String, ReadCoverageObservation> out = collect(server, true);

            assertThat(out).containsOnlyKeys("bq://dlap-demo-data", "gs://dlap-demo-curated", "gs://dlap-demo-website",
                    "gs://dlap-demo-legacy");
            ReadCoverageObservation failed = out.get("gs://dlap-demo-website");
            assertThat(failed.verdict().known()).isFalse();
            assertThat(failed.verdict().reasons()).singleElement().asString()
                    .contains("storage.buckets.getIamPolicy").contains("buckets/dlap-demo-website")
                    .contains("HTTP " + status);
            assertThat(out.get("gs://dlap-demo-curated").verdict().level()).as("bucket before").isEqualTo(CoverageLevel.FULL);
            assertThat(out.get("gs://dlap-demo-legacy").verdict().level()).as("bucket after").isEqualTo(CoverageLevel.PARTIAL);
            assertThat(out.get("bq://dlap-demo-data").verdict().level()).isEqualTo(CoverageLevel.FULL);
            long reads = server.requests().stream().filter(r -> r.pathAndQuery().equals(WEBSITE_IAM)).count();
            assertThat(reads).as("429 and 5xx are read 1 + %d times (Q-829); 404 once", CoverageHttp.MAX_RETRIES)
                    .isEqualTo(status == 404 ? 1 : 1 + CoverageHttp.MAX_RETRIES);
        }
    }

    @Test
    void aBucketThatIsAlreadyPartialStaysPartialWhenItsPolicyCannotBeRead() throws IOException {
        try (LocalJsonServer server = server("policy-folder-no-audit-configs.json")
                .answer("GET " + LEGACY_IAM, 429, "{}")) {
            Map<String, ReadCoverageObservation> out = collect(server, true);

            assertThat(out.get("gs://dlap-demo-legacy").verdict().level())
                    .as("the fine-grained ACL gap already makes it PARTIAL; the policy can only add gaps")
                    .isEqualTo(CoverageLevel.PARTIAL);
        }
    }

    @Test
    void aFailureOfTheWholeCollectorStillPublishesNothing() throws IOException {
        try (LocalJsonServer listing = server("policy-folder-no-audit-configs.json")
                .answer("GET /storage/v1/b?project=dlap-demo-data", 500, "{}")) {
            assertThatThrownBy(() -> collect(listing, true)).isInstanceOf(CoverageTransportException.class)
                    .hasMessageContaining("storage.buckets.list");
        }
        try (LocalJsonServer policy = server("policy-folder-no-audit-configs.json")
                .answer("POST /v3/projects/dlap-demo-data:getIamPolicy", 503, "{}")) {
            assertThatThrownBy(() -> collect(policy, true)).isInstanceOf(CoverageTransportException.class);
        }
    }

    @Test
    void aPolicyErrorFromAnInterruptedRunIsNotSwallowedAsAnUnknownBucket() {
        GcpCoverageApi api = new GcpCoverageApi() {
            @Override
            public com.fasterxml.jackson.databind.JsonNode getProject(String projectId) {
                return json("gcp/project.json");
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode getFolder(String folder) {
                return json("gcp/folder.json");
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode getIamPolicy(String resource) {
                return json("gcp/policy-project-storage-data-read.json");
            }

            @Override
            public List<com.fasterxml.jackson.databind.JsonNode> listBuckets(String projectId) {
                return List.of(json("gcp/buckets.json").path("items").get(1));
            }

            @Override
            public com.fasterxml.jackson.databind.JsonNode getBucketIamPolicy(String bucket) {
                Thread.currentThread().interrupt();
                throw new CoverageTransportException("interrupted calling " + bucket);
            }
        };
        try {
            assertThatThrownBy(() -> new GcpReadCoverageCollector(api, List.of("dlap-demo-data"), true).collect())
                    .isInstanceOf(CoverageTransportException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
