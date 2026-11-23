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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Scoped Actor roles (IAM-04): the traces under {@code deployment/iam/<cloud>/actor/permissions.yaml}
 * stay true to the retire executors' code, hold no wildcard object read or delete, and every deploy
 * form (Terraform, CloudFormation) grants exactly the trace on the resources the operator names.
 *
 * <p>The design copies da-discovery {@code ReaderRoleTraceabilityTest}: every permission lists its
 * call sites as file, line and the call text on that line, and every file of the executor modules
 * that imports a cloud client is accounted for. This test reads repository files only; it needs no
 * cloud and no network.
 */
public class ActorRoleTraceabilityTest {

    private static final List<String> CLOUDS = List.of("aws", "gcp", "azure");

    /** The retire executors: module to executor name. */
    private static final Map<String, String> EXECUTOR_MODULES = Map.of(
            "da-autodelete", "delete",
            "da-autoarchival", "archive");

    /** Import prefixes of each cloud's client libraries (the Reader test's list). */
    private static final Map<String, Pattern> SDK_IMPORTS = Map.of(
            "gcp", Pattern.compile("^import (static )?(com\\.google\\.cloud|com\\.google\\.api\\.services|com\\.google\\.api\\.gax"
                    + "|com\\.google\\.auth|com\\.google\\.logging)\\.", Pattern.MULTILINE),
            "aws", Pattern.compile("^import (static )?(software\\.amazon|com\\.amazonaws)\\.", Pattern.MULTILINE),
            "azure", Pattern.compile("^import (static )?(com\\.azure|com\\.microsoft\\.azure)\\.", Pattern.MULTILINE));

    /** Scopes each cloud's modules grant on. */
    private static final Map<String, Set<String>> SCOPES = Map.of(
            "aws", Set.of("bucket", "bucket_objects"),
            "gcp", Set.of("bucket", "bucket_objects", "dataset"),
            "azure", Set.of("storage_account", "container"));

    /** Extra scopes only never_grant may name (probed by the self-check, granted by nobody). */
    private static final Map<String, Set<String>> PROBE_ONLY_SCOPES = Map.of("aws", Set.of("account"));

    /** The one scope per cloud that may hold an object read, write or delete. */
    private static final Map<String, String> OBJECT_SCOPE = Map.of(
            "aws", "bucket_objects",
            "gcp", "bucket_objects",
            "azure", "container");

    /** Permissions that act on object or table content (read, write, delete, restore). */
    private static final Map<String, Pattern> OBJECT_PERMISSION = Map.of(
            "aws", Pattern.compile("^s3:(\\w*Object\\w*|BypassGovernanceRetention)$"),
            "gcp", Pattern.compile("^(storage\\.objects\\.(?!list$)\\w+|bigquery\\.tables\\.(getData|updateData))$"),
            "azure", Pattern.compile("(?i)^Microsoft\\.Storage/storageAccounts/blobServices/containers/blobs/.+$"));

    /** Content permissions no Actor may hold anywhere: the trace must list them under never_grant. */
    private static final Map<String, Set<String>> NEVER_ANYWHERE = Map.of(
            "aws", Set.of("s3:DeleteObjectVersion", "s3:GetObjectVersion", "s3:BypassGovernanceRetention"),
            "gcp", Set.of("bigquery.tables.getData", "bigquery.tables.updateData"),
            "azure", Set.of("Microsoft.Storage/storageAccounts/listKeys/action"));

    /**
     * Content permissions one executor may never hold (IAM-06, Q-761): the delete Actor never reads or writes Azure
     * blobs. The archive Actor holds both, as the single documented exception, so they are no longer in
     * {@link #NEVER_ANYWHERE}; never_grant lists them with {@code executors: [delete]} and an {@code exception}.
     */
    private static final Map<String, Map<String, Set<String>>> NEVER_FOR_EXECUTOR = Map.of(
            "azure", Map.of("delete", Set.of(
                    "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/read",
                    "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/write")));

    private static final String AWS_BUCKET_NAME = "^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$";

    private static final Path ROOT = repositoryRoot();

    // ------------------------------------------------------------------------------ the traces

    @Test
    void everyCloudHasAnActorTraceWithPermissionsPurposesScopesExecutorsAndCallSites() {
        for (String cloud : CLOUDS) {
            Map<String, Object> trace = trace(cloud);
            assertThat(trace.get("cloud")).as(cloud + " trace names its cloud").isEqualTo(cloud);
            Set<String> seen = new TreeSet<>();
            for (Map<String, Object> p : permissions(cloud)) {
                String name = (String) p.get("permission");
                assertThat(seen.add(name)).as(cloud + " lists " + name + " once").isTrue();
                assertThat((String) p.get("purpose")).as(name + " purpose").isNotBlank();
                assertThat(strings(p.get("scopes"))).as(name + " scopes").hasSize(1).isSubsetOf(SCOPES.get(cloud));
                assertThat(strings(p.get("executors"))).as(name + " executors").isNotEmpty()
                        .isSubsetOf(EXECUTOR_MODULES.values());
                assertThat(list(p.get("calls"))).as(name + " call sites").isNotEmpty();
            }
            Set<String> probeScopes = new TreeSet<>(SCOPES.get(cloud));
            probeScopes.addAll(PROBE_ONLY_SCOPES.getOrDefault(cloud, Set.of()));
            for (Map<String, Object> n : list(trace.get("never_grant"))) {
                assertThat(strings(n.get("scopes"))).as(n.get("permission") + " never_grant scopes").isNotEmpty()
                        .isSubsetOf(probeScopes);
                assertThat((String) n.get("reason")).as(n.get("permission") + " reason").isNotBlank();
                assertThat(strings(n.get("executors"))).as(n.get("permission") + " never_grant executors")
                        .isSubsetOf(EXECUTOR_MODULES.values());
            }
        }
    }

    @Test
    void everyActorPermissionTracesToACallSiteWhoseLineHoldsTheCall() {
        List<String> wrong = new ArrayList<>();
        int sites = 0;
        for (String cloud : CLOUDS) {
            for (Map<String, Object> site : allSites(cloud)) {
                sites++;
                String source = (String) site.get("source");
                int line = ((Number) site.get("line")).intValue();
                String call = (String) site.get("call");
                if (EXECUTOR_MODULES.keySet().stream().noneMatch(m -> source.startsWith(m + "/src/main/java/"))) {
                    wrong.add(cloud + ": " + source + " is not in a retire executor module");
                    continue;
                }
                Path file = ROOT.resolve(source);
                if (!Files.isRegularFile(file)) {
                    wrong.add(cloud + ": " + source + " does not exist");
                    continue;
                }
                List<String> lines = readLines(file);
                if (line < 1 || line > lines.size() || !lines.get(line - 1).contains(call)) {
                    List<Integer> now = new ArrayList<>();
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).contains(call)) {
                            now.add(i + 1);
                        }
                    }
                    wrong.add(cloud + ": " + source + ":" + line + " does not hold " + call
                            + (now.isEmpty() ? " (the call is gone)" : " (now on line " + now + ")"));
                }
            }
        }
        assertThat(sites).as("call sites checked").isGreaterThan(40);
        assertThat(wrong).as("stale call sites; update deployment/iam/<cloud>/actor/permissions.yaml with the code")
                .isEmpty();
    }

    @Test
    void everyPermissionsExecutorsAreTheModulesOfItsCallSites() {
        for (String cloud : CLOUDS) {
            for (Map<String, Object> p : permissions(cloud)) {
                Set<String> fromCalls = new TreeSet<>();
                for (Map<String, Object> c : list(p.get("calls"))) {
                    String source = (String) c.get("source");
                    EXECUTOR_MODULES.forEach((module, executor) -> {
                        if (source.startsWith(module + "/")) {
                            fromCalls.add(executor);
                        }
                    });
                }
                assertThat(new TreeSet<>(strings(p.get("executors")))).as(cloud + " " + p.get("permission") + " executors")
                        .isEqualTo(fromCalls);
            }
        }
    }

    @Test
    void everyCloudClientFileOfTheRetireExecutorsIsAccountedFor() {
        List<String> unscoped = new ArrayList<>();
        for (String cloud : CLOUDS) {
            Set<String> scoped = new TreeSet<>();
            allSites(cloud).forEach(s -> scoped.add((String) s.get("source")));
            for (String key : List.of("no_permission_sources", "unreachable_sources", "out_of_role_sources")) {
                list(trace(cloud).get(key)).forEach(s -> scoped.add((String) s.get("source")));
            }
            for (String module : EXECUTOR_MODULES.keySet()) {
                for (Path file : javaSources(ROOT.resolve(module).resolve("src/main/java"))) {
                    String relative = ROOT.relativize(file).toString().replace('\\', '/');
                    if (SDK_IMPORTS.get(cloud).matcher(read(relative)).find() && !scoped.contains(relative)) {
                        unscoped.add(cloud + ": " + relative);
                    }
                }
            }
        }
        assertThat(unscoped).as("files of da-autodelete or da-autoarchival that import a cloud client but are neither"
                + " cited nor listed; add their permissions to deployment/iam/<cloud>/actor/permissions.yaml").isEmpty();
    }

    @Test
    void everyListedSourceExistsImportsThatCloudAndGivesAReason() {
        for (String cloud : CLOUDS) {
            Map<String, Object> trace = trace(cloud);
            for (String key : List.of("no_permission_sources", "unreachable_sources", "out_of_role_sources",
                    "tolerated_denials", "unreachable_calls", "out_of_role_calls")) {
                for (Map<String, Object> s : list(trace.get(key))) {
                    String source = (String) s.get("source");
                    assertThat(ROOT.resolve(source)).as(source).isRegularFile();
                    assertThat(SDK_IMPORTS.get(cloud).matcher(read(source)).find()).as(source + " imports " + cloud)
                            .isTrue();
                    assertThat((String) s.get("reason")).as(key + " " + source + " reason").hasSizeGreaterThan(30);
                }
            }
            for (Map<String, Object> d : list(trace.get("tolerated_denials"))) {
                String source = (String) d.get("source");
                String executor = EXECUTOR_MODULES.get(source.substring(0, source.indexOf('/')));
                Set<String> held = new TreeSet<>();
                SCOPES.get(cloud).forEach(scope -> held.addAll(expected(cloud, executor, scope)));
                assertThat(held).as(d.get("permission") + " is tolerated, so the " + executor + " Actor does not hold it")
                        .doesNotContain((String) d.get("permission"));
            }
        }
    }

    // ------------------------------------------------------------- no wildcard object read or delete

    @Test
    void noActorRoleGrantsAnObjectReadOrDeleteOnAWildcard() {
        for (String cloud : CLOUDS) {
            for (Map<String, Object> p : permissions(cloud)) {
                String name = (String) p.get("permission");
                assertThat(name).as(cloud + " " + name + " holds no wildcard").doesNotContain("*");
                assertThat(isAllowedActorGrant(cloud, name, strings(p.get("scopes"))))
                        .as(cloud + " " + name + " on " + p.get("scopes") + " is an object action only on the object scope")
                        .isTrue();
            }
            assertThat(names(cloud)).as(cloud + " grants no content permission that no executor may hold")
                    .doesNotContainAnyElementsOf(NEVER_ANYWHERE.get(cloud));
            assertThat(neverGrantNames(cloud)).as(cloud + " never_grant lists them, so the self-check probes them")
                    .containsAll(NEVER_ANYWHERE.get(cloud));
            for (String executor : EXECUTOR_MODULES.values()) {
                Set<String> held = new TreeSet<>();
                SCOPES.get(cloud).forEach(scope -> held.addAll(expected(cloud, executor, scope)));
                assertThat(held).as(cloud + " " + executor + " Actor holds none of its never_grant entries")
                        .doesNotContainAnyElementsOf(neverGrantNames(cloud, executor));
                Set<String> forbidden = NEVER_FOR_EXECUTOR.getOrDefault(cloud, Map.of()).getOrDefault(executor, Set.of());
                for (String permission : forbidden) {
                    assertThat(held).as(cloud + " " + executor + " never holds " + permission).doesNotContain(permission);
                    assertThat(neverGrantNames(cloud, executor)).as(cloud + " never_grant lists it for " + executor)
                            .contains(permission);
                }
            }
        }
        awsTerraformGrantsTheNamedBucketsOnly();
        awsCloudFormationGrantsTheNamedBucketsOnly();
        gcpTerraformGrantsTheNamedBucketsAndDatasetsOnly();
        azureTerraformAssignsTheNamedAccountsAndContainersOnly();
    }

    @Test
    void theObjectRuleRefusesWildcardsAndObjectActionsOutsideTheObjectScope() {
        assertThat(isAllowedActorGrant("aws", "s3:DeleteObject", List.of("bucket_objects"))).isTrue();
        assertThat(isAllowedActorGrant("aws", "s3:DeleteObject", List.of("bucket"))).isFalse();
        assertThat(isAllowedActorGrant("aws", "s3:GetObject", List.of("bucket"))).isFalse();
        assertThat(isAllowedActorGrant("aws", "s3:*", List.of("bucket_objects"))).isFalse();
        assertThat(isAllowedActorGrant("aws", "s3:Delete*", List.of("bucket_objects"))).isFalse();
        assertThat(isAllowedActorGrant("aws", "s3:PutLifecycleConfiguration", List.of("bucket"))).isTrue();
        assertThat(isAllowedActorGrant("gcp", "storage.objects.delete", List.of("bucket"))).isFalse();
        assertThat(isAllowedActorGrant("gcp", "storage.objects.list", List.of("bucket"))).isTrue();
        assertThat(isAllowedActorGrant("gcp", "storage.objects.get", List.of("bucket_objects"))).isTrue();
        assertThat(isAllowedActorGrant("gcp", "bigquery.tables.getData", List.of("dataset"))).isFalse();
        assertThat(isAllowedActorGrant("azure", "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/delete",
                List.of("storage_account"))).isFalse();
        assertThat(isAllowedActorGrant("azure", "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/delete",
                List.of("container"))).isTrue();
        assertThat(isAllowedActorGrant("azure", "Microsoft.Storage/storageAccounts/managementPolicies/write",
                List.of("container"))).as("a control-plane action on the container scope").isFalse();
        assertThat(isAllowedActorGrant("azure", "Microsoft.Storage/*", List.of("storage_account"))).isFalse();

        Pattern bucket = Pattern.compile(AWS_BUCKET_NAME);
        for (String bad : List.of("*", "data-*", "arn:aws:s3:::data", "data/*", "")) {
            assertThat(bucket.matcher(bad).matches()).as(bad + " is not a bucket name").isFalse();
        }
        Pattern container = Pattern.compile(terraformRegex("azure", "container_ids"));
        String account = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg/providers/"
                + "Microsoft.Storage/storageAccounts/acct1";
        assertThat(container.matcher(account + "/blobServices/default/containers/logs").matches()).isTrue();
        for (String bad : List.of(account, account + "/blobServices/default/containers/*",
                account + "/blobServices/default/containers/lo*", "/subscriptions/*")) {
            assertThat(container.matcher(bad).matches()).as(bad + " is not one container").isFalse();
        }
        Pattern accounts = Pattern.compile(terraformRegex("azure", "storage_account_ids"));
        assertThat(accounts.matcher(account).matches()).isTrue();
        assertThat(accounts.matcher(account.replace("acct1", "*")).matches()).isFalse();
        assertThat(accounts.matcher("/subscriptions/00000000-0000-0000-0000-000000000000").matches()).isFalse();
    }

    private static void awsTerraformGrantsTheNamedBucketsOnly() {
        String main = read("deployment/iam/aws/actor/main.tf");
        assertThat(main).as("the bucket statement holds the bucket scope on the named bucket ARNs only")
                .containsPattern(Pattern.compile("\"DlapActorBuckets\"[^}]*actions\\s*=\\s*local\\.bucket_actions"
                        + "\\s+resources\\s*=\\s*local\\.bucket_arns\\s"));
        assertThat(main).as("the object statement holds the bucket_objects scope on the named buckets' objects only")
                .containsPattern(Pattern.compile("\"DlapActorObjects\"[^}]*actions\\s*=\\s*local\\.object_actions"
                        + "\\s+resources\\s*=\\s*local\\.object_arns\\s"));
        assertThat(count(main, Pattern.compile("\\bresources\\s*="))).as("no other statement").isEqualTo(2);
        assertThat(main).contains(
                "bucket_actions = sort([for p in local.trace.permissions : p.permission if contains(p.scopes, \"bucket\")"
                        + " && contains(p.executors, var.executor)])",
                "object_actions = sort([for p in local.trace.permissions : p.permission if contains(p.scopes,"
                        + " \"bucket_objects\") && contains(p.executors, var.executor)])",
                "bucket_arns = [for b in local.buckets : \"arn:${data.aws_partition.current.partition}:s3:::${b}\"]",
                "object_arns = [for b in local.buckets : \"arn:${data.aws_partition.current.partition}:s3:::${b}/*\"]",
                "grant_objects   = var.object_actions && length(local.object_actions) > 0");
        assertThat(read("deployment/iam/aws/actor/variables.tf")).as("buckets are validated as bucket names")
                .contains("can(regex(\"" + AWS_BUCKET_NAME + "\", b))");
    }

    @SuppressWarnings("unchecked")
    private static void awsCloudFormationGrantsTheNamedBucketsOnly() {
        Map<String, Object> template = new Yaml().load(read("deployment/iam/aws/actor/actor-role.cfn.yaml"));
        assertThat(template.get("Transform")).isEqualTo("AWS::LanguageExtensions");
        Map<String, Object> bucketNames = map(map(template.get("Parameters")).get("BucketNames"));
        assertThat(bucketNames.get("Type")).isEqualTo("CommaDelimitedList");
        assertThat(bucketNames.get("AllowedPattern")).as("each bucket name is validated").isEqualTo(AWS_BUCKET_NAME);
        assertThat(bucketNames).as("no default bucket").doesNotContainKey("Default");
        Map<String, Object> resources = map(template.get("Resources"));
        for (Map.Entry<String, Object> r : resources.entrySet()) {
            if (r.getKey().startsWith("Fn::ForEach::")) {
                continue;
            }
            assertThat(map(r.getValue()).get("Type")).as(r.getKey() + " is no policy outside the per-bucket loop")
                    .isIn("AWS::IAM::Role", "AWS::IAM::InstanceProfile");
            assertThat(map(map(r.getValue()).get("Properties"))).as(r.getKey() + " holds no inline policy")
                    .doesNotContainKeys("Policies", "ManagedPolicyArns");
        }
        List<Object> loop = (List<Object>) resources.get("Fn::ForEach::BucketPolicies");
        assertThat(loop.get(0)).isEqualTo("BucketName");
        assertThat(loop.get(1)).as("the loop runs over the named buckets").isEqualTo(Map.of("Ref", "BucketNames"));
        Map<String, Object> body = map(loop.get(2));
        assertThat(body).hasSize(2);
        for (Map.Entry<String, Object> r : body.entrySet()) {
            Map<String, Object> policy = map(r.getValue());
            assertThat(policy.get("Type")).isEqualTo("AWS::IAM::RolePolicy");
            for (Map<String, Object> s : list(map(map(policy.get("Properties")).get("PolicyDocument")).get("Statement"))) {
                Object resource = s.get("Resource");
                boolean objects = isCfnBucketResource(resource, true);
                assertThat(objects || isCfnBucketResource(resource, false)).as(r.getKey() + " grants on " + resource)
                        .isTrue();
                if (objects) {
                    assertThat(policy.get("Condition")).as(r.getKey() + " exists only with ObjectActions=true")
                            .isEqualTo("GrantObjects");
                }
            }
        }
        assertThat(isCfnBucketResource("*", true)).isFalse();
        assertThat(isCfnBucketResource(Map.of("Fn::Sub", "arn:${AWS::Partition}:s3:::*/*"), true)).isFalse();
        assertThat(isCfnBucketResource(Map.of("Fn::Sub", "arn:aws:s3:::customer-data/*"), true)).isFalse();
        assertThat(isCfnBucketResource(Map.of("Fn::Sub", "arn:${AWS::Partition}:s3:::${BucketName}"), true)).isFalse();
    }

    private static void gcpTerraformGrantsTheNamedBucketsAndDatasetsOnly() {
        String main = read("deployment/iam/gcp/actor/main.tf");
        for (String broad : List.of("google_project_iam_member", "google_project_iam_binding", "google_project_iam_policy",
                "google_folder_iam", "google_organization_iam", "google_storage_bucket_iam_binding",
                "google_storage_bucket_iam_policy", "google_bigquery_dataset_iam_binding")) {
            assertThat(main).as("the Actor is never granted with " + broad).doesNotContain(broad);
        }
        assertThat(main).as("the object role is granted on the named buckets only").containsPattern(Pattern.compile(
                "\"google_storage_bucket_iam_member\" \"objects\" \\{\\s*for_each\\s*=\\s*local\\.grant_objects \\?"
                        + " local\\.buckets : toset\\(\\[]\\)\\s+bucket\\s*=\\s*each\\.value\\s+role\\s*="
                        + "\\s*google_project_iam_custom_role\\.objects\\[0]\\.id"));
        assertThat(main).as("the bucket role is granted on the named buckets only").containsPattern(Pattern.compile(
                "\"google_storage_bucket_iam_member\" \"bucket\" \\{\\s*for_each\\s*=\\s*local\\.grant_buckets \\?"
                        + " local\\.buckets : toset\\(\\[]\\)\\s+bucket\\s*=\\s*each\\.value\\s+role\\s*="
                        + "\\s*google_project_iam_custom_role\\.bucket\\[0]\\.id"));
        assertThat(main).as("the dataset role is granted on the named datasets only").containsPattern(Pattern.compile(
                "\"google_bigquery_dataset_iam_member\" \"dataset\" \\{\\s*for_each\\s*=\\s*local\\.grant_datasets \\?"
                        + " local\\.datasets : toset\\(\\[]\\)[^}]*dataset_id\\s*=\\s*each\\.value\\s+role\\s*="
                        + "\\s*google_project_iam_custom_role\\.dataset\\[0]\\.id"));
        assertThat(main).contains("grant_objects  = var.object_actions && length(local.buckets) > 0"
                + " && length(local.object_permissions) > 0");
        Pattern bucket = Pattern.compile(terraformRegex("gcp", "buckets"));
        assertThat(bucket.matcher("dlap-data").matches()).isTrue();
        for (String bad : List.of("*", "data-*", "gs://data", "data/*")) {
            assertThat(bucket.matcher(bad).matches()).as(bad + " is not a bucket name").isFalse();
        }
    }

    private static void azureTerraformAssignsTheNamedAccountsAndContainersOnly() {
        String main = read("deployment/iam/azure/actor/main.tf");
        assertThat(main).as("the account role is assignable to the named accounts only")
                .contains("assignable_scopes = sort(tolist(local.storage_account_ids))");
        assertThat(main).as("the container role is assignable to the named containers only")
                .contains("assignable_scopes = sort(tolist(local.container_ids))");
        assertThat(count(main, Pattern.compile("assignable_scopes\\s*="))).isEqualTo(2);
        assertThat(main).containsPattern(Pattern.compile("\"azurerm_role_assignment\" \"account\" \\{\\s*for_each\\s*="
                + "\\s*local\\.grant_accounts && local\\.assign \\? local\\.storage_account_ids : toset\\(\\[]\\)\\s+"
                + "scope\\s*=\\s*each\\.value\\s"));
        assertThat(main).containsPattern(Pattern.compile("\"azurerm_role_assignment\" \"container\" \\{\\s*for_each\\s*="
                + "\\s*local\\.grant_containers && local\\.assign \\? local\\.container_ids : toset\\(\\[]\\)\\s+"
                + "scope\\s*=\\s*each\\.value\\s"));
        assertThat(main).as("the account role holds no data action").containsPattern(Pattern.compile(
                "actions\\s*=\\s*local\\.account_actions\\s+not_actions\\s*=\\s*\\[]\\s+data_actions\\s*=\\s*\\[]"));
        assertThat(main).as("the container role holds data actions only").containsPattern(Pattern.compile(
                "actions\\s*=\\s*\\[]\\s+not_actions\\s*=\\s*\\[]\\s+data_actions\\s*=\\s*local\\.container_data_actions"));
    }

    // --------------------------------------------------------------------- deploy forms equal the trace

    @Test
    @SuppressWarnings("unchecked")
    void awsCloudFormationGrantsExactlyTheTracePerExecutorAndScope() {
        Map<String, Object> template = new Yaml().load(read("deployment/iam/aws/actor/actor-role.cfn.yaml"));
        Map<String, Object> body = map(((List<Object>) map(template.get("Resources")).get("Fn::ForEach::BucketPolicies"))
                .get(2));
        Map<String, Set<String>> granted = new LinkedHashMap<>();
        for (Object resource : body.values()) {
            for (Map<String, Object> s : list(map(map(map(resource).get("Properties")).get("PolicyDocument"))
                    .get("Statement"))) {
                assertThat(s.get("Effect")).isEqualTo("Allow");
                assertThat(s).doesNotContainKeys("NotAction", "NotResource");
                String scope = isCfnBucketResource(s.get("Resource"), true) ? "bucket_objects" : "bucket";
                List<Object> branches = (List<Object>) map(s.get("Action")).get("Fn::If");
                assertThat(branches.get(0)).isEqualTo("IsDelete");
                granted.computeIfAbsent("delete/" + scope, k -> new TreeSet<>()).addAll((List<String>) branches.get(1));
                granted.computeIfAbsent("archive/" + scope, k -> new TreeSet<>()).addAll((List<String>) branches.get(2));
            }
        }
        for (String executor : EXECUTOR_MODULES.values()) {
            for (String scope : SCOPES.get("aws")) {
                assertThat(granted.getOrDefault(executor + "/" + scope, Set.of()))
                        .as("CloudFormation " + executor + " on " + scope + " equals permissions.yaml")
                        .isEqualTo(expected("aws", executor, scope));
            }
        }
    }

    @Test
    void terraformModulesReadTheTraceAndHardCodeNoPermission() {
        Pattern literal = Pattern.compile("\"([a-z]+\\.[a-zA-Z]+\\.[a-zA-Z]+|[a-z0-9-]+:[A-Z][A-Za-z]+|Microsoft\\.[^\"]+)\"");
        for (String cloud : CLOUDS) {
            String main = read("deployment/iam/" + cloud + "/actor/main.tf");
            assertThat(main).as(cloud + " main.tf reads the trace")
                    .contains("yamldecode(file(\"${path.module}/permissions.yaml\"))");
            List<String> hardCoded = new ArrayList<>();
            for (String tf : List.of("main.tf", "variables.tf", "outputs.tf", "versions.tf")) {
                Matcher m = literal.matcher(read("deployment/iam/" + cloud + "/actor/" + tf));
                while (m.find()) {
                    hardCoded.add(tf + ": " + m.group(1));
                }
            }
            // The trust policy (sts:AssumeRole, sts:ExternalId, the EC2 service principal) is not an Actor permission.
            hardCoded.removeIf(s -> s.endsWith(": sts:AssumeRole") || s.endsWith(": sts:ExternalId")
                    || s.endsWith(".amazonaws.com"));
            assertThat(hardCoded).as(cloud + " Terraform hard-codes no permission").isEmpty();
            assertThat(read("deployment/iam/" + cloud + "/actor/variables.tf")).as(cloud + " executor variable")
                    .contains("contains([\"delete\", \"archive\"], var.executor)");
        }
    }

    @Test
    void theGuideNamesEveryActorPermissionAndTheSelfCheck() {
        String guide = read("docs/guides/cloud-permissions.md");
        for (String cloud : CLOUDS) {
            for (String name : names(cloud)) {
                assertThat(guide).as("guide names " + name).contains("`" + name + "`");
            }
        }
        assertThat(guide).contains("/api/v1/permission-check", "sts:AssumeRole", "sts:ExternalId");
    }

    // ------------------------------------------------------------ Q-743: the Azure versioning read

    private static final String AZURE_BLOB_SERVICES_READ = "Microsoft.Storage/storageAccounts/blobServices/read";

    @Test
    void theAzureDeleteActorHoldsBlobServicesReadOnTheNamedStorageAccountsOnlyAndItIsTraced() {
        Map<String, Object> grant = permissions("azure").stream()
                .filter(p -> AZURE_BLOB_SERVICES_READ.equals(p.get("permission"))).findFirst().orElseThrow();
        assertThat(strings(grant.get("scopes"))).as("a control-plane read of account settings")
                .containsExactly("storage_account");
        assertThat(strings(grant.get("executors"))).as("only the delete executor reads versioning")
                .containsExactly("delete");
        assertThat(isAllowedActorGrant("azure", AZURE_BLOB_SERVICES_READ, strings(grant.get("scopes")))).isTrue();
        assertThat(isAllowedActorGrant("azure", AZURE_BLOB_SERVICES_READ, List.of("container")))
                .as("never on the container scope, which holds blob data actions").isFalse();
        assertThat(neverGrantNames("azure")).doesNotContain(AZURE_BLOB_SERVICES_READ);

        // The call site is RET-08's versioning read: getServiceProperties inside versioning(NativeRule).
        List<Map<String, Object>> calls = list(grant.get("calls"));
        assertThat(calls).hasSize(1);
        Map<String, Object> call = calls.get(0);
        String source = (String) call.get("source");
        assertThat(source).isEqualTo("da-autodelete/src/main/java/com/dlap/autodelete/providers/azure/"
                + "AzureBlobLifecycleRuleApplier.java");
        List<String> lines = readLines(ROOT.resolve(source));
        int line = ((Number) call.get("line")).intValue();
        assertThat(lines.get(line - 1)).contains((String) call.get("call"), "getServiceProperties");
        assertThat(String.join("\n", lines.subList(Math.max(0, line - 6), line))).as("the call sits in versioning()")
                .contains("public BucketVersioning versioning(NativeRule rule)");

        // What Terraform grants: the account role for delete holds it; the archive Actor holds no account action
        // (IAM-06: its only role is the container role, which holds blob data actions only).
        assertThat(expected("azure", "delete", "storage_account")).contains(AZURE_BLOB_SERVICES_READ);
        assertThat(expected("azure", "delete", "container")).doesNotContain(AZURE_BLOB_SERVICES_READ);
        assertThat(expected("azure", "archive", "storage_account")).isEmpty();
        assertThat(expected("azure", "archive", "container")).doesNotContain(AZURE_BLOB_SERVICES_READ);
        String main = read("deployment/iam/azure/actor/main.tf");
        assertThat(main).contains("account_actions        = sort([for p in local.trace.permissions : p.permission"
                + " if contains(p.scopes, \"storage_account\") && contains(p.executors, var.executor)])");
        assertThat(main).as("the role holding it is assignable to the named storage accounts only")
                .contains("assignable_scopes = sort(tolist(local.storage_account_ids))");

        // The self-check expects it of the delete Actor on each named account, and not of a container.
        TraceSource repo = new TraceSource(ROOT.resolve("deployment/iam"));
        assertThat(repo.load("azure").expected("delete", "storage_account")).contains(AZURE_BLOB_SERVICES_READ);
        assertThat(repo.load("azure").expected("delete", "container")).doesNotContain(AZURE_BLOB_SERVICES_READ);

        // The guide row names the permission, its scope and the same call site.
        String row = read("docs/guides/cloud-permissions.md").lines()
                .filter(l -> l.contains("`" + AZURE_BLOB_SERVICES_READ + "`") && l.startsWith("|")).findFirst()
                .orElseThrow();
        assertThat(row).contains("storage_account", "each named account", "delete",
                "AzureBlobLifecycleRuleApplier.java:" + line);
        assertThat(read("deployment/iam/azure/actor/permissions.yaml")).as("the header no longer says it is not granted")
                .doesNotContain("Not granted yet");
    }

    // ------------------------------------------- the runtime identity and the Actor trust (ADR-0113 point 6)

    @Test
    @SuppressWarnings("unchecked")
    void theAwsRuntimeIdentityIsTracedToAnAssumeRoleCallWithAnExternalIdAndTheActorTrustWritesIt() {
        Map<String, Object> runtime = map(trace("aws").get("runtime_identity"));
        assertThat(runtime.get("permission")).isEqualTo("sts:AssumeRole");
        assertThat((String) runtime.get("holder")).contains("runtime identity").contains("never an Actor");
        String resources = (String) runtime.get("resources");
        assertThat(resources).as("role ARNs, one per Actor role, never a wildcard")
                .contains("arn:aws:iam::", ":role/", "never *");
        assertThat(resources).doesNotContain("arn:aws:iam::*");
        assertThat(runtime).as("EXEC-01 has merged, so no pending note is left").doesNotContainKey("executor_call_sites");
        Map<String, Object> trust = map(runtime.get("trust"));
        assertThat((String) trust.get("principal")).contains("never the account root");
        assertThat((String) trust.get("condition")).contains("sts:ExternalId");
        assertThat(((Number) trust.get("max_session_seconds")).intValue()).isEqualTo(3600);

        List<Map<String, Object>> calls = list(runtime.get("calls"));
        assertThat(calls).as("the assume-role call of each executor (EXEC-01): builder, role ARN, external id")
                .hasSize(6);
        for (Map<String, Object> c : calls) {
            String source = (String) c.get("source");
            assertThat(source).as("the call is in a retire executor")
                    .matches("^(da-autodelete|da-autoarchival)/src/main/java/.+");
            List<String> lines = readLines(ROOT.resolve(source));
            int line = ((Number) c.get("line")).intValue();
            assertThat(line).isBetween(1, lines.size());
            assertThat(lines.get(line - 1)).as(source + ":" + line).contains((String) c.get("call"));
        }
        assertThat(calls).extracting(c -> ((String) c.get("source")).split("/")[0]).as("both executors assume")
                .contains("da-autodelete", "da-autoarchival");
        assertThat(calls).extracting(c -> (String) c.get("call")).as("a role ARN plus an external id")
                .anyMatch(c -> c.contains("roleArn(")).anyMatch(c -> c.contains("externalId("));

        // The Actor roles trust the runtime role ARN only, with the external id, for at most an hour.
        String main = read("deployment/iam/aws/actor/main.tf");
        assertThat(main).contains("identifiers = [var.trusted_principal_arn]", "variable = \"sts:ExternalId\"",
                "values   = [var.external_id]", "max_session_duration = 3600").doesNotContain(":root");
        Map<String, Object> template = new Yaml().load(read("deployment/iam/aws/actor/actor-role.cfn.yaml"));
        Map<String, Object> role = map(map(map(template.get("Resources")).get("ActorRole")).get("Properties"));
        assertThat(role.get("MaxSessionDuration")).isEqualTo(3600);
        List<Object> ifTrust = (List<Object>) map(list(map(role.get("AssumeRolePolicyDocument")).get("Statement"))
                .get(0)).get("Fn::If");
        Map<String, Object> trusted = map(ifTrust.get(1));
        assertThat(map(trusted.get("Principal")).get("AWS")).isEqualTo(Map.of("Ref", "TrustedPrincipalArn"));
        assertThat(trusted.get("Action")).isEqualTo("sts:AssumeRole");
        assertThat(map(map(trusted.get("Condition")).get("StringEquals")).get("sts:ExternalId"))
                .isEqualTo(Map.of("Ref", "ExternalId"));
        assertThat(read("deployment/iam/aws/actor/actor-role.cfn.yaml")).doesNotContain(":root");
        assertThat(map(map(template.get("Rules")).get("ExternalIdWithPrincipal")))
                .as("CloudFormation refuses a principal without an external id").containsKey("Assertions");
    }

    // ----------------------------------------------- Q-727: the guide says whose permissions the check reads

    @Test
    void theGuideSaysTheGcpAndAzureSelfCheckReportsTheCallingIdentityAndNothingIsImpersonated() {
        String guide = read("docs/guides/cloud-permissions.md");
        assertThat(guide).contains("`identityChecked`", "`identityBasis`", "`identityNote`", "`NAMED_ROLE`", "`CALLER`",
                "checks the calling identity", "Nothing is impersonated", "DEP-03", "`runtime_identity`",
                "ActorCredentials.java:175,191,193", "EXEC-01");
    }

    // ------------------------------------------------------ IAM-06 (Q-761): the Azure archive Actor

    private static final String AZURE_BLOBS = "Microsoft.Storage/storageAccounts/blobServices/containers/blobs/";
    private static final String AZURE_BLOB_READ = AZURE_BLOBS + "read";
    private static final String AZURE_BLOB_WRITE = AZURE_BLOBS + "write";
    private static final String AZURE_BLOB_DELETE = AZURE_BLOBS + "delete";
    private static final String ARCHIVE_STORAGE = "da-autoarchival/src/main/java/com/dlap/autoarchival/service/storage/impl/"
            + "AzureCloudStorageService.java";
    private static final String ARCHIVE_RULES = "da-autoarchival/src/main/java/com/dlap/autoarchival/providers/azure/"
            + "AzureManagementPolicyRuleProvider.java";
    private static final String ABAC_PAGE = "https://learn.microsoft.com/en-us/azure/storage/blobs/storage-auth-abac-attributes";

    @Test
    void theAzureArchiveActorHoldsExactlyBlobReadAndWriteOnTheNamedContainersOnly() {
        assertThat(expected("azure", "archive", "container")).as("the two data actions Azure requires, nothing else")
                .containsExactly(AZURE_BLOB_READ, AZURE_BLOB_WRITE);
        assertThat(expected("azure", "archive", "storage_account")).as("no control-plane action: the policy is rendered")
                .isEmpty();
        for (String name : List.of(AZURE_BLOB_READ, AZURE_BLOB_WRITE)) {
            Map<String, Object> grant = azureGrant(name);
            assertThat(strings(grant.get("scopes"))).as(name + " is on the container scope only")
                    .containsExactly("container");
            assertThat(strings(grant.get("executors"))).as(name + " is the archive Actor's only").containsExactly("archive");
            assertThat(isAllowedActorGrant("azure", name, List.of("container"))).isTrue();
            assertThat(isAllowedActorGrant("azure", name, List.of("storage_account")))
                    .as(name + " is never granted on an account").isFalse();
        }
        // Terraform builds it into the container role, assignable to and assigned on the named containers only,
        // and only with object_actions = true: the same grant gate as the delete Actor's single-blob delete.
        String main = read("deployment/iam/azure/actor/main.tf");
        assertThat(main).contains("container_data_actions = sort([for p in local.trace.permissions : p.permission"
                        + " if contains(p.scopes, \"container\") && contains(p.executors, var.executor)])",
                "grant_containers = var.object_actions && length(local.container_ids) > 0"
                        + " && length(local.container_data_actions) > 0",
                "assignable_scopes = sort(tolist(local.container_ids))");
        Pattern container = Pattern.compile(terraformRegex("azure", "container_ids"));
        String account = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg/providers/"
                + "Microsoft.Storage/storageAccounts/acct1";
        assertThat(container.matcher(account + "/blobServices/default/containers/archive-me").matches()).isTrue();
        assertThat(container.matcher(account + "/blobServices/default/containers/*").matches())
                .as("no wildcard container").isFalse();
        assertThat(container.matcher(account).matches()).as("never the whole account").isFalse();
        // The self-check expects exactly these of the archive Actor on a named container.
        TraceSource repo = new TraceSource(ROOT.resolve("deployment/iam"));
        assertThat(repo.load("azure").expected("archive", "container")).containsExactly(AZURE_BLOB_READ, AZURE_BLOB_WRITE);
        assertThat(repo.load("azure").inherited("archive", "container")).as("nothing inherited from an account role")
                .isEmpty();
    }

    @Test
    void everyArchiveCallIsTracedListSetTierGetPropertiesAndRestore() {
        Map<String, List<String>> sites = new LinkedHashMap<>();
        for (String name : List.of(AZURE_BLOB_READ, AZURE_BLOB_WRITE)) {
            for (Map<String, Object> c : list(azureGrant(name).get("calls"))) {
                String source = (String) c.get("source");
                int line = ((Number) c.get("line")).intValue();
                String text = readLines(ROOT.resolve(source)).get(line - 1);
                assertThat(text).as(source + ":" + line).contains((String) c.get("call"));
                sites.computeIfAbsent(name, k -> new ArrayList<>()).add(source + ":" + line + " " + text.trim());
            }
        }
        List<String> reads = sites.get(AZURE_BLOB_READ);
        List<String> writes = sites.get(AZURE_BLOB_WRITE);
        assertThat(reads).as("List Blobs (the plan preview) needs blobs/read")
                .anyMatch(s -> s.startsWith(ARCHIVE_RULES + ":") && s.contains("container.listBlobs("));
        assertThat(reads).as("Get Blob Properties (the size after the tier change) needs blobs/read")
                .anyMatch(s -> s.startsWith(ARCHIVE_STORAGE + ":") && s.contains("blobClient.getProperties()"));
        assertThat(writes).as("Set Blob Tier (archive) needs blobs/write")
                .anyMatch(s -> s.startsWith(ARCHIVE_STORAGE + ":") && s.contains("setAccessTier(accessTier)"));
        assertThat(writes).as("Set Blob Tier back to Hot (restore) needs blobs/write")
                .anyMatch(s -> s.startsWith(ARCHIVE_STORAGE + ":") && s.contains("setAccessTier(AccessTier.HOT)"));
        assertThat(reads).hasSize(2);
        assertThat(writes).hasSize(2);

        // The Hot tier call is the restore, the other tier call the archive, the read the size after archiving.
        List<String> storage = readLines(ROOT.resolve(ARCHIVE_STORAGE));
        assertThat(nearestMethod(storage, lineOf(writes, "AccessTier.HOT"))).isEqualTo("restoreData");
        assertThat(nearestMethod(storage, lineOf(writes, "setAccessTier(accessTier)"))).isEqualTo("archiveData");
        assertThat(nearestMethod(storage, lineOf(reads, "blobClient.getProperties()"))).isEqualTo("archiveData");
        assertThat(nearestMethod(readLines(ROOT.resolve(ARCHIVE_RULES)), lineOf(reads, "container.listBlobs(")))
                .isEqualTo("list");

        // Every Azure SDK call of the archive executor is a permission's call site or unreachable: nothing is out of
        // role any more, because the connection string is no longer the way in (EXEC-01).
        assertThat(trace("azure")).as("no out_of_role_calls left").doesNotContainKey("out_of_role_calls");
        List<Map<String, Object>> unreachable = list(trace("azure").get("unreachable_calls")).stream()
                .filter(u -> ARCHIVE_STORAGE.equals(u.get("source"))).toList();
        assertThat(unreachable).as("the two status reads nothing calls").hasSize(2);
        for (Map<String, Object> u : unreachable) {
            String method = nearestMethod(storage, ((Number) u.get("line")).intValue());
            assertThat(method).isIn("getArchivalStatus", "getRestorationStatus");
            for (Path file : javaSources(ROOT.resolve("da-autoarchival/src/main/java"))) {
                String relative = ROOT.relativize(file).toString().replace('\\', '/');
                assertThat(read(relative)).as(relative + " calls " + method).doesNotContain("." + method + "(");
            }
        }
    }

    @Test
    void theArchiveWriteIsNarrowedToSetBlobTierByARoleAssignmentConditionThatCitesMicrosoft() {
        Map<String, Object> write = azureGrant(AZURE_BLOB_WRITE);
        String condition = (String) write.get("condition");
        assertThat(condition).as("the condition targets every blob write except the Blob.Write.Tier suboperation")
                .contains("!(ActionMatches{'" + AZURE_BLOB_WRITE + "'}", "AND NOT SubOperationMatches{'Blob.Write.Tier'}")
                .as("and its expression is never true, so no other blob write passes")
                .contains("OR (@Environment[UtcNow] DateTimeLessThan '2000-01-01T00:00:00.0Z')")
                .doesNotContain("\n");
        assertThat(count(condition, Pattern.compile("\\(")))
                .as("balanced parentheses").isEqualTo(count(condition, Pattern.compile("\\)")));
        assertThat((String) write.get("condition_source")).as("the Microsoft page that defines the suboperation")
                .isEqualTo(ABAC_PAGE + "#sets-the-access-tier-on-a-blob");
        assertThat(azureGrant(AZURE_BLOB_READ)).as("the read cannot be narrowed: no suboperation splits Get Blob"
                + " Properties from Get Blob").doesNotContainKey("condition");
        assertThat((String) azureGrant(AZURE_BLOB_READ).get("purpose")).contains("not narrowed");
        assertThat(azureGrant(AZURE_BLOB_DELETE)).as("the delete Actor's grant carries no condition")
                .doesNotContainKey("condition");

        // Terraform puts the executor's container conditions on its container assignments, version 2.0, and none
        // on the account assignments.
        String main = read("deployment/iam/azure/actor/main.tf");
        assertThat(main).contains("container_conditions = [for p in local.trace.permissions : p.condition if"
                        + " contains(p.scopes, \"container\") && contains(p.executors, var.executor)"
                        + " && try(p.condition, \"\") != \"\"]",
                "container_condition  = length(local.container_conditions) > 0 ? join(\" AND \","
                        + " local.container_conditions) : null");
        assertThat(main).containsPattern(Pattern.compile("\"azurerm_role_assignment\" \"container\" \\{[^}]*"
                + "condition\\s*=\\s*local\\.container_condition\\s+condition_version\\s*=\\s*local\\.container_condition"
                + " == null \\? null : \"2\\.0\"\\s*}"));
        assertThat(count(main, Pattern.compile("\\bcondition\\s*=\\s*local\\."))).as("only the container assignment")
                .isEqualTo(1);
        assertThat(conditions("archive")).containsExactly(condition);
        assertThat(conditions("delete")).as("the delete Actor's assignment stays unconditioned").isEmpty();
        assertThat(read("docs/guides/cloud-permissions.md")).contains(ABAC_PAGE, "Blob.Write.Tier",
                "cannot be narrowed");
    }

    @Test
    void theDeleteActorIsUnchangedAndStillNeverReadsOrWritesBlobs() {
        // The delete Actor's grants as they stood before IAM-06 (517e0c1d).
        assertThat(expected("azure", "delete", "storage_account")).containsExactly(
                "Microsoft.Storage/storageAccounts/blobServices/read",
                "Microsoft.Storage/storageAccounts/managementPolicies/delete",
                "Microsoft.Storage/storageAccounts/managementPolicies/read",
                "Microsoft.Storage/storageAccounts/managementPolicies/write");
        assertThat(expected("azure", "delete", "container")).containsExactly(AZURE_BLOB_DELETE);
        assertThat(neverGrantNames("azure", "delete")).contains(AZURE_BLOB_READ, AZURE_BLOB_WRITE,
                AZURE_BLOBS + "deleteBlobVersion/action", "Microsoft.Storage/storageAccounts/listKeys/action");
        // Its size probe still tolerates a denied read instead of holding blobs/read.
        assertThat(list(trace("azure").get("tolerated_denials"))).anyMatch(d -> AZURE_BLOB_READ.equals(d.get("permission"))
                && ((String) d.get("source")).startsWith("da-autodelete/"));
        // The account assignment is written exactly as before, and the role names keep their form.
        String main = read("deployment/iam/azure/actor/main.tf");
        assertThat(main).containsPattern(Pattern.compile("\"azurerm_role_assignment\" \"account\" \\{\\s*for_each[^\\n]*\\n"
                + "\\s*scope[^\\n]*\\n\\s*role_definition_id[^\\n]*\\n\\s*principal_id\\s*=\\s*var\\.principal_object_id\\s*}"));
        assertThat(main).contains("name        = \"${var.role_name} (account)\"",
                "name        = \"${var.role_name} (container)\"");
    }

    @Test
    void theArchiveActorIsTheSingleDocumentedNeverGrantException() {
        List<String> scoped = new ArrayList<>();
        for (String cloud : CLOUDS) {
            for (Map<String, Object> n : list(trace(cloud).get("never_grant"))) {
                List<String> executors = strings(n.get("executors"));
                if (executors.isEmpty()) {
                    assertThat(n).as(cloud + " " + n.get("permission") + " binds every executor")
                            .doesNotContainKey("exception");
                    continue;
                }
                scoped.add(cloud + " " + n.get("permission"));
                assertThat(executors).as(n.get("permission") + " binds the delete Actor").containsExactly("delete");
                assertThat((String) n.get("exception")).as(n.get("permission") + " names the exception and why")
                        .contains("archive Actor is the single documented exception", "Q-761", "named containers");
            }
        }
        assertThat(scoped).containsExactlyInAnyOrder("azure " + AZURE_BLOB_READ, "azure " + AZURE_BLOB_WRITE);
        // Where a never_grant permission is held at all, the archive Actor holds it, and only these two.
        List<String> held = new ArrayList<>();
        for (String cloud : CLOUDS) {
            for (Map<String, Object> p : permissions(cloud)) {
                if (neverGrantNames(cloud).contains((String) p.get("permission"))) {
                    held.add(cloud + " " + p.get("permission") + " " + strings(p.get("executors")));
                }
            }
        }
        assertThat(held).containsExactlyInAnyOrder("azure " + AZURE_BLOB_READ + " [archive]",
                "azure " + AZURE_BLOB_WRITE + " [archive]");
    }

    @Test
    void theArchiveAndDeleteActorsAreNeverMerged() {
        for (String scope : SCOPES.get("azure")) {
            Set<String> shared = new TreeSet<>(expected("azure", "delete", scope));
            shared.retainAll(expected("azure", "archive", scope));
            assertThat(shared).as("no Azure permission on " + scope + " belongs to both Actors").isEmpty();
        }
        for (Map<String, Object> p : permissions("azure")) {
            assertThat(strings(p.get("executors"))).as(p.get("permission") + " serves one executor").hasSize(1);
        }
        // One module instance per executor, and the archive role's name must say archive, so the two role
        // definitions (custom role names are unique in a tenant) can never be one.
        String main = read("deployment/iam/azure/actor/main.tf");
        assertThat(main).contains("condition     = var.executor != \"archive\" || strcontains(lower(var.role_name),"
                + " \"archive\")");
        assertThat(read("deployment/iam/azure/actor/variables.tf")).contains("Give each executor its own name");
    }

    @Test
    void theGuideRecommendsTheNativeManagementPolicyPathAndSaysWhenEachArchivePathIsUsed() {
        String guide = read("docs/guides/cloud-permissions.md");
        String section = guide.substring(guide.indexOf("### Azure Actor"), guide.indexOf("## The permission self-check"));
        assertThat(section).contains("#### Azure archive Actor", "Recommended: the native lifecycle management policy",
                "`Microsoft.Storage/storageAccounts/managementPolicies/write`", "no blob data access",
                "Per-blob tiering", "single-object", "restore", "`object_actions = true`", "Blob.Write.Tier",
                "-var executor=archive", "DLAP Archive Actor", "never merged", "single documented exception");
        for (String name : List.of(AZURE_BLOB_READ, AZURE_BLOB_WRITE)) {
            String row = section.lines().filter(l -> l.startsWith("|") && l.contains("`" + name + "`")
                    && l.contains("| archive |")).findFirst().orElseThrow();
            assertThat(row).contains("container", "each named container, only with `object_actions`");
        }
        assertThat(section).as("Q-761: the stale connection-string paragraph is gone")
                .doesNotContain("The archive executor holds no Azure role", "reaches Azure only through");
        assertThat(read("deployment/iam/azure/actor/permissions.yaml")).as("nor does the trace header say it")
                .doesNotContain("reaches Azure only with").doesNotContain("holds no Azure role");
    }

    private static Map<String, Object> azureGrant(String name) {
        return permissions("azure").stream().filter(p -> name.equals(p.get("permission"))).findFirst().orElseThrow();
    }

    /** The conditions Terraform puts on an executor's container assignments (main.tf's container_conditions). */
    private static List<String> conditions(String executor) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> p : permissions("azure")) {
            if (strings(p.get("scopes")).contains("container") && strings(p.get("executors")).contains(executor)
                    && p.get("condition") instanceof String c && !c.isEmpty()) {
                out.add(c);
            }
        }
        return out;
    }

    private static int lineOf(List<String> sites, String call) {
        String site = sites.stream().filter(s -> s.contains(call)).findFirst().orElseThrow();
        String location = site.substring(0, site.indexOf(' '));
        return Integer.parseInt(location.substring(location.lastIndexOf(':') + 1));
    }

    /** The name of the method whose declaration is nearest above a 1-based line. */
    private static String nearestMethod(List<String> lines, int line) {
        Pattern declaration = Pattern.compile("^\\s*public\\s+[\\w<>\\[\\]., ]+\\s+(\\w+)\\s*\\(");
        for (int i = line - 1; i >= 0; i--) {
            Matcher m = declaration.matcher(lines.get(i));
            if (m.find()) {
                return m.group(1);
            }
        }
        return "";
    }

    // ---------------------------------------------------------------------------------- rules

    /**
     * An Actor grant: no wildcard, an object read, write or delete only on the cloud's object scope,
     * and on Azure a blob data action on the container scope and a control-plane action elsewhere.
     */
    static boolean isAllowedActorGrant(String cloud, String permission, List<String> scopes) {
        if (permission.contains("*") || scopes.isEmpty()) {
            return false;
        }
        boolean object = OBJECT_PERMISSION.get(cloud).matcher(permission).matches();
        boolean onObjectScope = scopes.equals(List.of(OBJECT_SCOPE.get(cloud)));
        if (NEVER_ANYWHERE.get(cloud).contains(permission)) {
            return false;
        }
        if (object) {
            return onObjectScope;
        }
        return !"azure".equals(cloud) || !scopes.contains(OBJECT_SCOPE.get(cloud));
    }

    /**
     * A CloudFormation Resource that names the loop's bucket and nothing else:
     * {@code Fn::Sub "arn:${AWS::Partition}:s3:::${BucketName}"}, with "/*" for objects.
     */
    static boolean isCfnBucketResource(Object resource, boolean objects) {
        if (!(resource instanceof Map<?, ?> m) || m.size() != 1 || !(m.get("Fn::Sub") instanceof String arn)) {
            return false;
        }
        String bucket = "arn:${AWS::Partition}:s3:::${BucketName}";
        return arn.equals(objects ? bucket + "/*" : bucket);
    }

    // -------------------------------------------------------------------------------- helpers

    private static Set<String> expected(String cloud, String executor, String scope) {
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> p : permissions(cloud)) {
            if (strings(p.get("scopes")).contains(scope) && strings(p.get("executors")).contains(executor)) {
                out.add((String) p.get("permission"));
            }
        }
        return out;
    }

    /** Every cited call: permissions' call sites plus the tolerated, unreachable and out-of-role calls. */
    private static List<Map<String, Object>> allSites(String cloud) {
        List<Map<String, Object>> sites = new ArrayList<>();
        permissions(cloud).forEach(p -> sites.addAll(list(p.get("calls"))));
        Map<String, Object> trace = trace(cloud);
        for (String key : List.of("tolerated_denials", "unreachable_calls", "out_of_role_calls")) {
            sites.addAll(list(trace.get(key)));
        }
        return sites;
    }

    /** The Java form of the RE2 pattern a Terraform variable validation applies to its values. */
    private static String terraformRegex(String cloud, String variable) {
        String variables = read("deployment/iam/" + cloud + "/actor/variables.tf");
        Matcher block = Pattern.compile("variable \"" + variable + "\" \\{.*?can\\(regex\\(\"((?:[^\"\\\\]|\\\\.)*)\"",
                Pattern.DOTALL).matcher(variables);
        assertThat(block.find()).as(variable + " is validated with a regex").isTrue();
        return block.group(1).replace("\\\\", "\\");
    }

    private static int count(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static Map<String, Object> trace(String cloud) {
        return new Yaml().load(read("deployment/iam/" + cloud + "/actor/permissions.yaml"));
    }

    private static List<Map<String, Object>> permissions(String cloud) {
        return list(trace(cloud).get("permissions"));
    }

    private static Set<String> names(String cloud) {
        Set<String> out = new TreeSet<>();
        permissions(cloud).forEach(p -> out.add((String) p.get("permission")));
        return out;
    }

    private static Set<String> neverGrantNames(String cloud) {
        Set<String> out = new TreeSet<>();
        list(trace(cloud).get("never_grant")).forEach(p -> out.add((String) p.get("permission")));
        return out;
    }

    /** The never_grant entries that bind one executor: those that name no executors, and those that name it. */
    private static Set<String> neverGrantNames(String cloud, String executor) {
        Set<String> out = new TreeSet<>();
        for (Map<String, Object> n : list(trace(cloud).get("never_grant"))) {
            List<String> executors = strings(n.get("executors"));
            if (executors.isEmpty() || executors.contains(executor)) {
                out.add((String) n.get("permission"));
            }
        }
        return out;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> l) {
            l.forEach(v -> out.add(String.valueOf(v)));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return value == null ? List.of() : (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static String read(String relative) {
        try {
            return Files.readString(ROOT.resolve(relative), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + relative, e);
        }
    }

    private static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private static List<Path> javaSources(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk " + dir, e);
        }
    }

    /**
     * The repository root: the nearest ancestor of the working directory with settings.gradle.kts.
     *
     * @return the root
     */
    public static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
            dir = dir.getParent();
        }
        if (dir == null || !Files.isDirectory(dir.resolve("deployment/iam"))) {
            throw new IllegalStateException("repository root with deployment/iam not found above "
                    + Path.of("").toAbsolutePath());
        }
        return dir;
    }
}
