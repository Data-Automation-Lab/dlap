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

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dlap.discovery.coverage.CoverageLevel;
import com.dlap.discovery.coverage.CoverageVerdict;
import com.dlap.discovery.coverage.MissingPermissionException;
import com.dlap.discovery.providers.gcp.coverage.GcpAuditConfigEvaluator.PolicyAt;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * COV-01, GCP: audit-config and bucket rules over responses written from the Resource Manager and
 * Cloud Storage API references ({@code src/test/resources/coverage/gcp/meta.yaml}).
 */
class GcpAuditConfigEvaluatorTest {

    private static PolicyAt at(String resource, String fixture) {
        return new PolicyAt(resource, json("gcp/" + fixture), null);
    }

    private static PolicyAt denied(String resource, String permission) {
        return new PolicyAt(resource, null, new MissingPermissionException(permission, resource));
    }

    private static final PolicyAt FOLDER = at("folders/200100", "policy-folder-no-audit-configs.json");

    private static JsonNode bucket(int i) {
        return json("gcp/buckets.json").path("items").get(i);
    }

    @Test
    void storageDataReadEnabledAtTheProjectWithNoExemptionsIsFull() {
        CoverageVerdict v = GcpAuditConfigEvaluator.dataRead(GcpAuditConfigEvaluator.STORAGE, List.of(
                at("projects/dlap-demo-data", "policy-project-storage-data-read.json"), FOLDER,
                at("organizations/300100", "policy-folder-no-audit-configs.json")), false);
        assertThat(v.level()).isEqualTo(CoverageLevel.FULL);
        assertThat(v.reasons()).containsExactly("storage.googleapis.com DATA_READ audit logs are recorded "
                + "(enabled at projects/dlap-demo-data (storage.googleapis.com)) with no exempted principals");
    }

    @Test
    void anAllServicesConfigAtTheOrganizationIsInherited() {
        CoverageVerdict v = GcpAuditConfigEvaluator.dataRead(GcpAuditConfigEvaluator.STORAGE, List.of(
                at("projects/dlap-demo-data", "policy-project-no-audit-configs.json"), FOLDER,
                at("organizations/300100", "policy-org-all-services-data-read.json")), false);
        assertThat(v.level()).isEqualTo(CoverageLevel.FULL);
        assertThat(v.reasons().get(0)).contains("enabled at organizations/300100 (allServices)");
    }

    @Test
    void anExemptionAnywhereInTheHierarchyIsPartialAndQuotesNoPrincipal() {
        CoverageVerdict v = GcpAuditConfigEvaluator.dataRead(GcpAuditConfigEvaluator.STORAGE, List.of(
                at("projects/dlap-demo-data", "policy-project-storage-data-read.json"), FOLDER,
                at("organizations/300100", "policy-org-all-services-exempt.json")), false);
        assertThat(v.level()).isEqualTo(CoverageLevel.PARTIAL);
        assertThat(v.reasons()).containsExactly("1 principal(s) exempted from storage.googleapis.com DATA_READ "
                + "audit logs at organizations/300100 (allServices)");
        assertThat(String.join(" ", v.reasons())).doesNotContain("etl@");
    }

    @Test
    void noDataReadConfigAnywhereIsNoneForStorage() {
        CoverageVerdict v = GcpAuditConfigEvaluator.dataRead(GcpAuditConfigEvaluator.STORAGE, List.of(
                at("projects/dlap-demo-data", "policy-project-no-audit-configs.json"), FOLDER,
                at("organizations/300100", "policy-folder-no-audit-configs.json")), false);
        assertThat(v.level()).isEqualTo(CoverageLevel.NONE);
    }

    @Test
    void anUnreadableAncestorMakesItUnknownAndNamesThePermission() {
        CoverageVerdict v = GcpAuditConfigEvaluator.dataRead(GcpAuditConfigEvaluator.STORAGE, List.of(
                at("projects/dlap-demo-data", "policy-project-storage-data-read.json"), FOLDER,
                denied("organizations/300100", "resourcemanager.organizations.getIamPolicy")), false);
        assertThat(v.known()).isFalse();
        assertThat(v.reasons()).containsExactly(
                "missing permission resourcemanager.organizations.getIamPolicy on organizations/300100");
    }

    @Test
    void anExemptionIsProvablePartialEvenWithAnUnreadableLevel() {
        CoverageVerdict v = GcpAuditConfigEvaluator.dataRead(GcpAuditConfigEvaluator.STORAGE, List.of(
                denied("projects/dlap-demo-data", "resourcemanager.projects.getIamPolicy"), FOLDER,
                at("organizations/300100", "policy-org-all-services-exempt.json")), false);
        assertThat(v.level()).isEqualTo(CoverageLevel.PARTIAL);
    }

    @Test
    void bigQueryIsOnByDefaultButCrossProjectReadsKeepItPartialUntilVerified() {
        List<PolicyAt> levels = List.of(at("projects/dlap-demo-data", "policy-project-no-audit-configs.json"), FOLDER,
                at("organizations/300100", "policy-folder-no-audit-configs.json"));
        CoverageVerdict unverified = GcpAuditConfigEvaluator.bigQuery(levels, false);
        assertThat(unverified.level()).isEqualTo(CoverageLevel.PARTIAL);
        assertThat(unverified.reasons()).containsExactly(GcpAuditConfigEvaluator.BIGQUERY_CROSS_PROJECT_UNVERIFIED);

        CoverageVerdict verified = GcpAuditConfigEvaluator.bigQuery(levels, true);
        assertThat(verified.level()).isEqualTo(CoverageLevel.FULL);
        assertThat(verified.reasons().get(0)).contains("on by default");
    }

    @Test
    void bigQueryExemptionsArePartial() {
        CoverageVerdict v = GcpAuditConfigEvaluator.bigQuery(List.of(
                at("projects/dlap-demo-data", "policy-project-no-audit-configs.json"),
                at("organizations/300100", "policy-org-all-services-exempt.json")), true);
        assertThat(v.level()).isEqualTo(CoverageLevel.PARTIAL);
        assertThat(v.reasons().get(0)).contains("bigquery.googleapis.com");
    }

    @Test
    void aBucketWithEnforcedPublicAccessPreventionKeepsTheProjectVerdict() {
        CoverageVerdict full = CoverageVerdict.full(List.of("evidence"));
        assertThat(GcpAuditConfigEvaluator.gcsBucket(full, bucket(0), null, null)).isEqualTo(full);
    }

    @Test
    void aPublicBucketIsPartialBecausePublicObjectReadsAreNotLogged() {
        CoverageVerdict v = GcpAuditConfigEvaluator.gcsBucket(CoverageVerdict.full(List.of("evidence")), bucket(1),
                json("gcp/bucket-iam-public.json"), null);
        assertThat(v.level()).isEqualTo(CoverageLevel.PARTIAL);
        assertThat(v.reasons()).containsExactly("the bucket grants access to allUsers or allAuthenticatedUsers: "
                + "reads of public objects are not logged");

        CoverageVerdict privateBucket = GcpAuditConfigEvaluator.gcsBucket(CoverageVerdict.full(List.of("evidence")),
                bucket(1), json("gcp/bucket-iam-private.json"), null);
        assertThat(privateBucket.level()).isEqualTo(CoverageLevel.FULL);
    }

    @Test
    void fineGrainedAclsWithoutEnforcedPreventionArePartial() {
        CoverageVerdict v = GcpAuditConfigEvaluator.gcsBucket(CoverageVerdict.full(List.of("evidence")), bucket(2),
                json("gcp/bucket-iam-private.json"), null);
        assertThat(v.level()).isEqualTo(CoverageLevel.PARTIAL);
        assertThat(v.reasons().get(0)).startsWith("fine-grained object ACLs");
    }

    @Test
    void anUnreadableBucketPolicyMakesAFullProjectUnknown() {
        CoverageVerdict v = GcpAuditConfigEvaluator.gcsBucket(CoverageVerdict.full(List.of("evidence")), bucket(1),
                null, new MissingPermissionException("storage.buckets.getIamPolicy", "buckets/dlap-demo-website"));
        assertThat(v.known()).isFalse();
        assertThat(v.reasons()).containsExactly(
                "missing permission storage.buckets.getIamPolicy on buckets/dlap-demo-website");
    }

    @Test
    void anUnreadableBucketPolicyWithAnyReasonMakesAFullProjectUnknownWithThatReason() {
        CoverageVerdict v = GcpAuditConfigEvaluator.gcsBucketWithReason(CoverageVerdict.full(List.of("evidence")),
                bucket(1), null, "storage.buckets.getIamPolicy call for buckets/b answered HTTP 429");
        assertThat(v.known()).isFalse();
        assertThat(v.reasons()).containsExactly("storage.buckets.getIamPolicy call for buckets/b answered HTTP 429");

        CoverageVerdict noReason = GcpAuditConfigEvaluator.gcsBucketWithReason(
                CoverageVerdict.full(List.of("evidence")), bucket(1), null, null);
        assertThat(noReason.reasons()).containsExactly("the bucket IAM policy could not be read");
    }

    @Test
    void aProjectWithoutStorageDataReadStaysNoneWhateverTheBucket() {
        CoverageVerdict none = CoverageVerdict.none(List.of("off"));
        assertThat(GcpAuditConfigEvaluator.gcsBucket(none, bucket(1), json("gcp/bucket-iam-public.json"), null))
                .isEqualTo(none);
    }
}
