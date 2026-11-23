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

package com.dlap.adminservice.permissioncheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.dlap.adminservice.permissioncheck.PermissionCheckReport.Status;
import com.dlap.adminservice.permissioncheck.PermissionCheckReport.TargetReport;

/** The self-check's comparison of a provider's answer with the committed traces, with a scripted probe. */
class PermissionCheckServiceTest {

    private static final TraceSource REPO = new TraceSource(ActorRoleTraceabilityTest.repositoryRoot()
            .resolve("deployment/iam"));

    /** A probe that answers from a function and records what it was asked. */
    static class ScriptedProbe implements PermissionProbe {
        final String cloud;
        final List<Target> targets;
        final Function<Target, Answer> answers;
        final List<Set<String>> asked = new ArrayList<>();
        final List<Set<String>> acceptable = new ArrayList<>();

        ScriptedProbe(String cloud, List<Target> targets, Function<Target, Answer> answers) {
            this.cloud = cloud;
            this.targets = targets;
            this.answers = answers;
        }

        @Override
        public String cloud() {
            return cloud;
        }

        @Override
        public String method() {
            return "scripted";
        }

        @Override
        public List<Target> targets() {
            return targets;
        }

        @Override
        public Answer probe(Target target, Set<String> candidates, Set<String> acceptable) {
            asked.add(candidates);
            this.acceptable.add(acceptable);
            return answers.apply(target);
        }
    }

    private static PermissionProbe.Target bucket(String executor) {
        return new PermissionProbe.Target(executor, "bucket", "arn:aws:s3:::dlap-data", "role", true, true);
    }

    @Test
    void reportsAMissingAndAnExtraPermissionAgainstTheCommittedTrace() {
        Set<String> held = new TreeSet<>(REPO.load("aws").expected("delete", "bucket"));
        held.remove("s3:GetBucketVersioning");
        held.add("s3:PutBucketPolicy");
        ScriptedProbe probe = new ScriptedProbe("aws", List.of(bucket("delete")), t -> new PermissionProbe.Answer(held, Set.of()));

        PermissionCheckReport report = new PermissionCheckService(REPO, List.of(probe)).check("aws").orElseThrow();

        assertThat(report.status()).isEqualTo(Status.FAIL);
        assertThat(report.missing()).containsExactly("delete: s3:GetBucketVersioning on arn:aws:s3:::dlap-data");
        assertThat(report.extra()).containsExactly("delete: s3:PutBucketPolicy on arn:aws:s3:::dlap-data");
        TargetReport t = report.targets().get(0);
        assertThat(t.expected()).containsExactly("s3:GetBucketLocation", "s3:GetBucketVersioning",
                "s3:GetLifecycleConfiguration", "s3:PutLifecycleConfiguration");
        assertThat(probe.asked.get(0)).as("every permission of the scope and its never_grant entries are probed")
                .contains("s3:ListBucket", "s3:DeleteBucket", "s3:PutBucketPolicy", "s3:GetBucketVersioning");
    }

    @Test
    void exactlyTheTraceIsAPass() {
        ScriptedProbe probe = new ScriptedProbe("aws", List.of(bucket("archive")),
                t -> new PermissionProbe.Answer(REPO.load("aws").expected("archive", "bucket"), Set.of()));
        PermissionCheckReport report = new PermissionCheckService(REPO, List.of(probe)).check("aws").orElseThrow();
        assertThat(report.status()).isEqualTo(Status.PASS);
        assertThat(report.missing()).isEmpty();
        assertThat(report.extra()).isEmpty();
        assertThat(report.method()).isEqualTo("scripted");
    }

    @Test
    void anotherExecutorsPermissionIsReportedAndANeverGrantEntryIsExtra() {
        ScriptedProbe probe = new ScriptedProbe("aws", List.of(bucket("archive")), t -> new PermissionProbe.Answer(
                REPO.load("aws").expected("delete", "bucket"), Set.of()));
        PermissionCheckReport report = new PermissionCheckService(REPO, List.of(probe)).check("aws").orElseThrow();
        assertThat(report.missing()).containsExactly("archive: s3:ListBucket on arn:aws:s3:::dlap-data");
        assertThat(report.status()).isEqualTo(Status.FAIL);
        TargetReport t = report.targets().get(0);
        assertThat(t.extra()).as("a delete-only permission the Reader does not hold").isEmpty();
        assertThat(t.readerHeld()).as("delete-only permissions the Reader also holds are listed apart, not hidden")
                .containsExactly("s3:GetBucketLocation", "s3:GetBucketVersioning");

        Set<String> withObjects = new TreeSet<>(REPO.load("aws").expected("archive", "bucket"));
        withObjects.add("s3:DeleteBucket");
        ScriptedProbe never = new ScriptedProbe("aws", List.of(bucket("archive")),
                x -> new PermissionProbe.Answer(withObjects, Set.of()));
        assertThat(new PermissionCheckService(REPO, List.of(never)).check("aws").orElseThrow().extra())
                .containsExactly("archive: s3:DeleteBucket on arn:aws:s3:::dlap-data");
    }

    @Test
    void anyGrantOnAResourceNobodyNamedIsExtraAndNamesTheWildcard() {
        PermissionProbe.Target unnamed = new PermissionProbe.Target("delete", "bucket_objects",
                "arn:aws:s3:::dlap-permission-check-unnamed-bucket/probe", "role", false, false);
        ScriptedProbe probe = new ScriptedProbe("aws", List.of(unnamed),
                t -> new PermissionProbe.Answer(Set.of("s3:DeleteObject"), Set.of()));
        PermissionCheckReport report = new PermissionCheckService(REPO, List.of(probe)).check("aws").orElseThrow();
        assertThat(report.status()).isEqualTo(Status.FAIL);
        assertThat(report.extra()).containsExactly("delete: s3:DeleteObject on "
                + "arn:aws:s3:::dlap-permission-check-unnamed-bucket/probe (a resource the operator did not name)");
        assertThat(report.targets().get(0).expected()).isEmpty();
    }

    @Test
    void objectActionsTurnedOffMakeEveryObjectPermissionExtra() {
        PermissionProbe.Target objects = new PermissionProbe.Target("delete", "bucket_objects",
                "arn:aws:s3:::dlap-data/probe", "role", false, true);
        ScriptedProbe probe = new ScriptedProbe("aws", List.of(objects),
                t -> new PermissionProbe.Answer(Set.of("s3:DeleteObject"), Set.of()));
        PermissionCheckReport report = new PermissionCheckService(REPO, List.of(probe)).check("aws").orElseThrow();
        assertThat(report.missing()).isEmpty();
        assertThat(report.extra()).containsExactly("delete: s3:DeleteObject on arn:aws:s3:::dlap-data/probe");
    }

    @Test
    void readerPermissionsAreListedApartAndInheritedAccountActionsAreAcceptedOnAContainer() {
        String container = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg/providers/"
                + "Microsoft.Storage/storageAccounts/acct1/blobServices/default/containers/logs";
        PermissionProbe.Target target = new PermissionProbe.Target("delete", "container", container, "mi", true, true);
        ScriptedProbe probe = new ScriptedProbe("azure", List.of(target), t -> new PermissionProbe.Answer(
                Set.of("Microsoft.Storage/storageAccounts/blobServices/containers/blobs/delete"),
                Set.of("Microsoft.Storage/storageAccounts/read", "Microsoft.Storage/*")));
        PermissionCheckReport report = new PermissionCheckService(REPO, List.of(probe)).check("azure").orElseThrow();
        TargetReport t = report.targets().get(0);
        assertThat(t.missing()).isEmpty();
        assertThat(t.readerHeld()).containsExactly("Microsoft.Storage/storageAccounts/read");
        assertThat(t.extra()).containsExactly("Microsoft.Storage/*");
        assertThat(probe.acceptable.get(0)).as("the account actions inherited by the container are acceptable")
                .contains("Microsoft.Storage/storageAccounts/managementPolicies/write",
                        "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/delete");
    }

    @Test
    void theReportNamesTheIdentityItCheckedAndItsBasisEvenWhenNothingIsConfigured() {
        // Q-727: a PASS is a statement about one identity; the report says which, and whether it was the caller.
        ScriptedProbe named = new ScriptedProbe("aws", List.of(bucket("delete")),
                t -> new PermissionProbe.Answer(Set.of(), Set.of()));
        PermissionCheckReport aws = new PermissionCheckService(REPO, List.of(named)).check("aws").orElseThrow();
        assertThat(aws.identityChecked()).isEqualTo("role");
        assertThat(aws.identityBasis()).isEqualTo(PermissionCheckReport.IdentityBasis.NAMED_ROLE);

        ScriptedProbe caller = new ScriptedProbe("gcp", List.of(), t -> null) {
            @Override
            public PermissionCheckReport.IdentityBasis identityBasis() {
                return PermissionCheckReport.IdentityBasis.CALLER;
            }

            @Override
            public String identityNote() {
                return "Answers for the caller.";
            }
        };
        PermissionCheckReport gcp = new PermissionCheckService(REPO, List.of(caller)).check("gcp").orElseThrow();
        assertThat(gcp.status()).isEqualTo(Status.NOT_CONFIGURED);
        assertThat(gcp.identityChecked()).as("nothing was checked").isNull();
        assertThat(gcp.identityBasis()).isEqualTo(PermissionCheckReport.IdentityBasis.CALLER);
        assertThat(gcp.identityNote()).isEqualTo("Answers for the caller.");

        PermissionProbe.Target a = new PermissionProbe.Target("delete", "bucket", "arn:aws:s3:::a", "role-b", true, true);
        PermissionProbe.Target b = new PermissionProbe.Target("archive", "bucket", "arn:aws:s3:::a", "role-a", true, true);
        ScriptedProbe two = new ScriptedProbe("aws", List.of(a, b, a), t -> new PermissionProbe.Answer(Set.of(), Set.of()));
        assertThat(new PermissionCheckService(REPO, List.of(two)).check("aws").orElseThrow().identityChecked())
                .as("each identity once, sorted").isEqualTo("role-a, role-b");
    }

    @Test
    void aProbeFailureIsAnErrorWithTheReasonNeverAPass() {
        ScriptedProbe probe = new ScriptedProbe("aws", List.of(bucket("delete")), t -> {
            throw new IllegalStateException("IAM answered HTTP 500");
        });
        PermissionCheckReport report = new PermissionCheckService(REPO, List.of(probe)).check("aws").orElseThrow();
        assertThat(report.status()).isEqualTo(Status.ERROR);
        assertThat(report.targets().get(0).error()).isEqualTo("IAM answered HTTP 500");
        assertThat(report.missing()).isEmpty();
    }

    @Test
    void aCloudWithoutTargetsIsNotConfiguredAndAnUnknownCloudHasNoReport() {
        ScriptedProbe aws = new ScriptedProbe("aws", List.of(), t -> null);
        ScriptedProbe gcp = new ScriptedProbe("gcp", List.of(), t -> null);
        PermissionCheckService service = new PermissionCheckService(REPO, List.of(gcp, aws));
        assertThat(service.clouds()).containsExactly("aws", "gcp");
        assertThat(service.checkAll()).extracting(PermissionCheckReport::status)
                .containsExactly(Status.NOT_CONFIGURED, Status.NOT_CONFIGURED);
        assertThat(service.check("oracle")).isEmpty();
    }

    @Test
    void aScopeWithNothingToProbeIsNotAskedAndAnEmptyAnswerMeansMissing() {
        PermissionProbe.Target account = new PermissionProbe.Target("delete", "storage_account",
                "/subscriptions/x", "mi", true, true);
        ScriptedProbe probe = new ScriptedProbe("azure", List.of(account), t -> new PermissionProbe.Answer(null, null));
        PermissionCheckReport report = new PermissionCheckService(REPO, List.of(probe)).check("azure").orElseThrow();
        assertThat(report.targets().get(0).missing()).containsExactly(
                "Microsoft.Storage/storageAccounts/blobServices/read",
                "Microsoft.Storage/storageAccounts/managementPolicies/delete",
                "Microsoft.Storage/storageAccounts/managementPolicies/read",
                "Microsoft.Storage/storageAccounts/managementPolicies/write");

        PermissionProbe.Target nothing = new PermissionProbe.Target("delete", "unknown_scope", "x", "mi", true, true);
        ScriptedProbe unasked = new ScriptedProbe("azure", List.of(nothing), t -> {
            throw new AssertionError("a scope with no candidate is never probed");
        });
        assertThat(new PermissionCheckService(REPO, List.of(unasked)).check("azure").orElseThrow().status())
                .isEqualTo(Status.PASS);
    }

    @Test
    void theJarCarriesEveryActorAndReaderTrace() {
        TraceSource jar = new TraceSource(null);
        for (String cloud : List.of("aws", "gcp", "azure")) {
            ActorTrace trace = jar.load(cloud);
            assertThat(trace.cloud()).isEqualTo(cloud);
            assertThat(trace.permissions()).isNotEmpty();
            assertThat(trace.readerPermissions()).as(cloud + " reader trace").isNotEmpty();
        }
        assertThatThrownBy(() -> jar.load("databricks")).hasMessageContaining("no Actor trace");
        assertThatThrownBy(() -> jar.load("../etc")).hasMessageContaining("not a cloud name");
    }
}
