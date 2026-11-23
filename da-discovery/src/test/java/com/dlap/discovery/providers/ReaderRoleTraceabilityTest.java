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

package com.dlap.discovery.providers;

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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Least-privilege Reader roles (IAM-03): the permission traces under
 * {@code deployment/iam/<cloud>/reader/permissions.yaml} stay true to the code that needs them and
 * hold no write permission.
 *
 * <p>Each trace lists a permission, the scopes it is granted on and the call sites (file, line and
 * the call text on that line) that need it. The Terraform modules read the trace with
 * {@code yamldecode}; the AWS CloudFormation template repeats the list and must match it. This test
 * reads the repository files only; it needs no cloud and no network.
 */
class ReaderRoleTraceabilityTest {

    private static final List<String> CLOUDS = List.of("gcp", "aws", "azure", "databricks");

    /** Modules whose cloud calls the Reader role serves. Write paths belong to the Actor roles (IAM-04). */
    private static final List<String> READER_MODULES = List.of("da-discovery", "da-logingest");

    /** Import prefixes of each cloud's client libraries. */
    private static final Map<String, Pattern> SDK_IMPORTS = Map.of(
            "gcp", Pattern.compile("^import (static )?(com\\.google\\.cloud|com\\.google\\.api\\.services|com\\.google\\.api\\.gax"
                    + "|com\\.google\\.auth|com\\.google\\.logging)\\.", Pattern.MULTILINE),
            "aws", Pattern.compile("^import (static )?(software\\.amazon|com\\.amazonaws)\\.", Pattern.MULTILINE),
            "azure", Pattern.compile("^import (static )?(com\\.azure|com\\.microsoft\\.azure)\\.", Pattern.MULTILINE),
            "databricks", Pattern.compile("^import (static )?(io\\.unitycatalog|com\\.databricks)\\.", Pattern.MULTILINE));

    /** Scopes each cloud's Terraform module knows. */
    private static final Map<String, Set<String>> SCOPES = Map.of(
            "gcp", Set.of("project", "organization", "log_sink_bucket", "log_sink_dataset"),
            "aws", Set.of("account", "log_bucket", "log_bucket_objects"),
            "azure", Set.of("subscription"),
            "databricks", Set.of("catalog"));

    /** GCP verbs that only read. Anything else needs a written {@code write_exception}. */
    private static final Set<String> GCP_READ_VERBS = Set.of("list", "listAll", "get", "getIamPolicy", "getData");

    /** GCP permissions that read customer content, each with the one sink scope it may be granted on. */
    private static final Map<String, String> GCP_CONTENT_READS = Map.of(
            "storage.objects.get", "log_sink_bucket",
            "bigquery.tables.getData", "log_sink_dataset");

    /** Permission prefix each GCP sink scope may hold: its role is granted on that resource type only. */
    private static final Map<String, String> GCP_SINK_SCOPE_SERVICE = Map.of(
            "log_sink_bucket", "storage.",
            "log_sink_dataset", "bigquery.");

    /** AWS actions that read object or query-result content. */
    private static final Pattern AWS_CONTENT_READS = Pattern.compile(
            "^(s3:GetObject.*|athena:GetQueryResults.*|glue:GetPartition.*|rds-data:.*|dynamodb:(Get|Scan|Query).*)$");

    /**
     * AWS content reads the Reader may hold, each with the one log scope it may be granted on: the
     * objects of the log buckets the operator names (Q-685). Every other content read is refused.
     */
    private static final Map<String, String> AWS_CONTENT_READ_SCOPES = Map.of("s3:GetObject", "log_bucket_objects");

    /** AWS log scopes: their statements name the log bucket parameters only, and hold S3 actions only. */
    private static final Set<String> AWS_LOG_SCOPES = Set.of("log_bucket", "log_bucket_objects");

    /** The CloudFormation parameters that name the log buckets. */
    private static final List<String> AWS_LOG_BUCKET_PARAMETERS = List.of("CloudTrailLogBucket", "S3AccessLogBucket");

    /** The Terraform variables that name the log buckets. */
    private static final List<String> AWS_LOG_BUCKET_VARIABLES = List.of("cloudtrail_log_bucket", "s3_access_log_bucket");

    /** A log bucket name, as both deploy forms validate it: a bucket name, never an ARN or a wildcard. */
    private static final String AWS_LOG_BUCKET_NAME = "^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$";

    private static final Set<String> DATABRICKS_READER_PRIVILEGES = Set.of("BROWSE", "USE_CATALOG", "USE_SCHEMA");

    /** Permission literals the coverage clients name in MissingPermissionException. */
    private static final Pattern COVERAGE_PERMISSION_LITERAL = Pattern.compile(
            "\"((?:storage|resourcemanager|bigquery|logging|cloudsql)\\.[a-zA-Z]+\\.[a-zA-Z]+"
                    + "|(?:s3|cloudtrail|glue|athena|ec2|rds|elasticmapreduce):[A-Za-z]+"
                    + "|Microsoft\\.[A-Za-z]+/[A-Za-z/]+/read)\"");

    private static final Path ROOT = repositoryRoot();

    @Test
    void everyCloudHasATraceWithPermissionsPurposesScopesAndCallSites() {
        for (String cloud : CLOUDS) {
            Map<String, Object> trace = trace(cloud);
            assertThat(trace.get("cloud")).as(cloud + " trace names its cloud").isEqualTo(cloud);
            List<Map<String, Object>> permissions = permissions(cloud);
            assertThat(permissions).as(cloud + " permissions").isNotEmpty();
            Set<String> seen = new TreeSet<>();
            for (Map<String, Object> p : permissions) {
                String name = (String) p.get("permission");
                assertThat(seen.add(name)).as(cloud + " lists " + name + " once").isTrue();
                assertThat((String) p.get("purpose")).as(name + " purpose").isNotBlank();
                assertThat(scopes(p)).as(name + " scopes").isNotEmpty().isSubsetOf(SCOPES.get(cloud));
                assertThat(calls(p)).as(name + " call sites").isNotEmpty();
            }
        }
    }

    @Test
    void everyCallSiteExistsAndItsLineHoldsTheCall() {
        List<String> wrong = new ArrayList<>();
        for (String cloud : CLOUDS) {
            List<Map<String, Object>> sites = new ArrayList<>();
            permissions(cloud).forEach(p -> sites.addAll(calls(p)));
            sites.addAll(list(trace(cloud).get("no_permission_calls")));
            for (Map<String, Object> site : sites) {
                String source = (String) site.get("source");
                int line = ((Number) site.get("line")).intValue();
                String call = (String) site.get("call");
                assertThat(READER_MODULES.stream().anyMatch(m -> source.startsWith(m + "/")))
                        .as(source + " is in a Reader module").isTrue();
                Path file = ROOT.resolve(source);
                if (!Files.isRegularFile(file)) {
                    wrong.add(cloud + ": " + source + " does not exist");
                    continue;
                }
                List<String> lines = readLines(file);
                if (line < 1 || line > lines.size() || !lines.get(line - 1).contains(call)) {
                    wrong.add(cloud + ": " + source + ":" + line + " does not hold " + call);
                }
            }
        }
        assertThat(wrong).as("stale call sites; update the trace with the code").isEmpty();
    }

    @Test
    void gcpReaderHoldsOnlyReadVerbsOrAWrittenException() {
        for (Map<String, Object> p : permissions("gcp")) {
            String name = (String) p.get("permission");
            Object exception = p.get("write_exception");
            if (exception == null) {
                assertThat(isGcpReadPermission(name)).as(name + " is a read permission").isTrue();
            } else {
                assertThat((String) exception).as(name + " write_exception reason").hasSizeGreaterThan(80);
            }
        }
        assertThat(permissions("gcp").stream().filter(p -> p.get("write_exception") != null)
                .map(p -> (String) p.get("permission")))
                .as("the only GCP exception is the query job that every BigQuery read needs")
                .containsExactly("bigquery.jobs.create");
    }

    @Test
    void gcpContentReadsAreGrantedOnTheLogSinkOnly() {
        for (Map<String, Object> p : permissions("gcp")) {
            String name = (String) p.get("permission");
            if (GCP_CONTENT_READS.containsKey(name)) {
                assertThat(scopes(p)).as(name + " scopes").containsExactly(GCP_CONTENT_READS.get(name));
            }
        }
        Set<String> names = names("gcp");
        assertThat(names).containsAll(GCP_CONTENT_READS.keySet());
    }

    @Test
    void eachGcpSinkRoleHoldsOnlyThePermissionsOfItsResourceType() {
        for (Map<String, Object> p : permissions("gcp")) {
            String name = (String) p.get("permission");
            for (String scope : scopes(p)) {
                String service = GCP_SINK_SCOPE_SERVICE.get(scope);
                if (service != null) {
                    assertThat(name).as(name + " in the " + scope + " role").startsWith(service);
                }
            }
        }
        String main = read("deployment/iam/gcp/reader/main.tf");
        assertThat(main).as("the bucket grant uses the bucket role").containsPattern(Pattern.compile(
                "\"google_storage_bucket_iam_member\" \"log_sink_reader\" \\{[^}]*role\\s*=\\s*"
                        + "google_project_iam_custom_role\\.log_sink_bucket_reader\\[0]\\.id"));
        assertThat(main).as("the dataset grant uses the dataset role").containsPattern(Pattern.compile(
                "\"google_bigquery_dataset_iam_member\" \"log_sink_reader\" \\{[^}]*role\\s*=\\s*"
                        + "google_project_iam_custom_role\\.log_sink_dataset_reader\\[0]\\.id"));
    }

    @Test
    void awsReaderHoldsOnlyListGetDescribeAndLookupActionsAndReadsContentOnTheLogBucketsOnly() {
        for (Map<String, Object> p : permissions("aws")) {
            String action = (String) p.get("permission");
            assertThat(isAwsReadAction(action)).as(action + " is a read action").isTrue();
            assertThat(isAllowedAwsGrant(action, scopes(p)))
                    .as(action + " on " + scopes(p) + " reads no content outside the log buckets").isTrue();
        }
    }

    @Test
    void awsContentReadsAreGrantedOnTheLogBucketsOnly() {
        for (Map<String, Object> p : permissions("aws")) {
            String name = (String) p.get("permission");
            if (AWS_CONTENT_READ_SCOPES.containsKey(name)) {
                assertThat(scopes(p)).as(name + " scopes").containsExactly(AWS_CONTENT_READ_SCOPES.get(name));
            }
            for (String scope : scopes(p)) {
                if (AWS_LOG_SCOPES.contains(scope)) {
                    assertThat(name).as(name + " in the " + scope + " scope").startsWith("s3:");
                    assertThat(scopes(p)).as(name + " is granted on the log buckets only").containsOnly(scope);
                }
            }
        }
        assertThat(names("aws")).containsAll(AWS_CONTENT_READ_SCOPES.keySet());
    }

    @Test
    void awsContentReadRuleAllowsGetObjectOnTheLogBucketObjectsOnly() {
        assertThat(isAllowedAwsGrant("s3:GetObject", List.of("log_bucket_objects"))).isTrue();
        assertThat(isAllowedAwsGrant("s3:GetObject", List.of("account"))).isFalse();
        assertThat(isAllowedAwsGrant("s3:GetObject", List.of("log_bucket"))).isFalse();
        assertThat(isAllowedAwsGrant("s3:GetObject", List.of("log_bucket_objects", "account"))).isFalse();
        assertThat(isAllowedAwsGrant("s3:GetObject", List.of())).isFalse();
        assertThat(isAllowedAwsGrant("s3:GetObjectVersion", List.of("log_bucket_objects"))).isFalse();
        assertThat(isAllowedAwsGrant("athena:GetQueryResults", List.of("log_bucket_objects"))).isFalse();
        assertThat(isAllowedAwsGrant("s3:ListBucket", List.of("log_bucket"))).isTrue();
        assertThat(isAllowedAwsGrant("s3:GetBucketLocation", List.of("account"))).isTrue();

        String trail = "arn:${AWS::Partition}:s3:::${CloudTrailLogBucket}";
        assertThat(isAwsLogBucketResource(logBucketIf("HasCloudTrailLogBucket", trail + "/*"), true)).isTrue();
        assertThat(isAwsLogBucketResource(logBucketIf("HasCloudTrailLogBucket", trail), false)).isTrue();
        assertThat(isAwsLogBucketResource(logBucketIf("HasCloudTrailLogBucket", trail), true))
                .as("the bucket ARN is not the objects' ARN").isFalse();
        assertThat(isAwsLogBucketResource(logBucketIf("HasS3AccessLogBucket", trail + "/*"), true))
                .as("a condition of another parameter").isFalse();
        assertThat(isAwsLogBucketResource("*", true)).as("every object").isFalse();
        assertThat(isAwsLogBucketResource("arn:aws:s3:::customer-data/*", true)).as("a named data bucket").isFalse();
        assertThat(isAwsLogBucketResource(Map.of("Fn::Sub", "arn:${AWS::Partition}:s3:::*/*"), true))
                .as("a wildcard bucket").isFalse();
        assertThat(isAwsLogBucketResource(logBucketIf("HasDataBucket", "arn:${AWS::Partition}:s3:::${DataBucket}/*"),
                true)).as("a bucket parameter that is not a log bucket").isFalse();

        Pattern bucketName = Pattern.compile(AWS_LOG_BUCKET_NAME);
        assertThat(bucketName.matcher("trail-logs.example").matches()).isTrue();
        for (String bad : List.of("*", "trail-*", "arn:aws:s3:::trail-logs", "trail-logs/*", "")) {
            assertThat(bucketName.matcher(bad).matches()).as(bad + " is not a bucket name").isFalse();
        }
    }

    @Test
    void awsCloudFormationGrantsEachScopeOnItsResourcesOnly() {
        Map<String, Object> template = new Yaml().load(read("deployment/iam/aws/reader/reader-role.cfn.yaml"));
        Map<String, Object> parameters = map(template.get("Parameters"));
        for (String parameter : AWS_LOG_BUCKET_PARAMETERS) {
            assertThat(map(parameters.get(parameter)).get("AllowedPattern")).as(parameter + " pattern")
                    .isEqualTo("^$|" + AWS_LOG_BUCKET_NAME);
            assertThat(map(parameters.get(parameter)).get("Default")).as(parameter + " default").isEqualTo("");
        }
        Map<String, List<String>> scopesByAction = new LinkedHashMap<>();
        permissions("aws").forEach(p -> scopesByAction.put((String) p.get("permission"), scopes(p)));
        Set<String> granted = new TreeSet<>();
        for (Map.Entry<String, Object> resource : map(template.get("Resources")).entrySet()) {
            Map<String, Object> r = map(resource.getValue());
            if (!"AWS::IAM::ManagedPolicy".equals(r.get("Type")) && !"AWS::IAM::Policy".equals(r.get("Type"))) {
                continue;
            }
            Map<String, Object> document = map(map(r.get("Properties")).get("PolicyDocument"));
            for (Map<String, Object> statement : list(document.get("Statement"))) {
                Object sid = statement.get("Sid");
                Object action = statement.get("Action");
                List<String> actions = action instanceof String a ? List.of(a)
                        : ((List<?>) action).stream().map(o -> (String) o).toList();
                Set<String> statementScopes = new TreeSet<>();
                for (String a : actions) {
                    List<String> sc = scopesByAction.getOrDefault(a, List.of());
                    statementScopes.add(sc.size() == 1 ? sc.get(0) : a + " on " + sc);
                }
                assertThat(statementScopes).as(sid + " holds one scope").hasSize(1);
                String scope = statementScopes.iterator().next();
                Object resources = statement.get("Resource");
                if ("account".equals(scope)) {
                    assertThat(resources).as(sid + " Resource").isEqualTo("*");
                } else {
                    assertThat(AWS_LOG_SCOPES).as(sid + " scope").contains(scope);
                    assertThat(r.get("Condition")).as(resource.getKey() + " exists only with a log bucket")
                            .isEqualTo("HasLogBucket");
                    assertThat(resources).as(sid + " Resource").isInstanceOf(List.class);
                    assertThat((List<?>) resources).as(sid + " Resource").hasSize(AWS_LOG_BUCKET_PARAMETERS.size());
                    for (Object one : (List<?>) resources) {
                        assertThat(isAwsLogBucketResource(one, "log_bucket_objects".equals(scope)))
                                .as(sid + " grants " + one).isTrue();
                    }
                }
                granted.addAll(actions);
            }
        }
        assertThat(granted).isEqualTo(names("aws"));
    }

    @Test
    void awsTerraformGrantsEachScopeOnItsResourcesOnly() {
        String main = read("deployment/iam/aws/reader/main.tf");
        assertThat(main).as("the account statement holds the account scope on *").containsPattern(Pattern.compile(
                "\"DlapReaderMetadataAndLogs\"[^}]*actions\\s*=\\s*local\\.account_actions\\s+resources\\s*=\\s*\\[\"\\*\"]"));
        assertThat(main).as("the log bucket statement holds the log_bucket scope on the bucket ARNs only")
                .containsPattern(Pattern.compile("\"DlapReaderLogBuckets\"[^}]*actions\\s*=\\s*local\\.log_bucket_actions"
                        + "\\s+resources\\s*=\\s*local\\.log_bucket_arns\\s"));
        assertThat(main).as("the log object statement holds the log_bucket_objects scope on the objects only")
                .containsPattern(Pattern.compile("\"DlapReaderLogObjects\"[^}]*actions\\s*=\\s*local\\.log_object_actions"
                        + "\\s+resources\\s*=\\s*local\\.log_object_arns\\s"));
        assertThat(main).contains(
                "account_actions    = sort([for p in local.trace.permissions : p.permission if contains(p.scopes, \"account\")])",
                "log_bucket_actions = sort([for p in local.trace.permissions : p.permission if contains(p.scopes, \"log_bucket\")])",
                "log_object_actions = sort([for p in local.trace.permissions : p.permission"
                        + " if contains(p.scopes, \"log_bucket_objects\")])",
                "log_buckets     = sort(distinct(compact([var.cloudtrail_log_bucket, var.s3_access_log_bucket])))",
                "log_bucket_arns = [for b in local.log_buckets : \"arn:${data.aws_partition.current.partition}:s3:::${b}\"]",
                "log_object_arns = [for b in local.log_buckets : \"arn:${data.aws_partition.current.partition}:s3:::${b}/*\"]");
        String variables = read("deployment/iam/aws/reader/variables.tf");
        for (String variable : AWS_LOG_BUCKET_VARIABLES) {
            assertThat(variables).as(variable + " is validated as a bucket name").contains(
                    "var." + variable + " == \"\" || can(regex(\"" + AWS_LOG_BUCKET_NAME + "\", var." + variable + "))");
        }
    }

    @Test
    void azureReaderHoldsOnlyReadActions() {
        for (String action : names("azure")) {
            assertThat(isAzureReadAction(action)).as(action + " ends in /read").isTrue();
        }
        assertThat(read("deployment/iam/azure/reader/main.tf")).contains("data_actions = []");
    }

    @Test
    void databricksReaderHoldsNoDataPrivilege() {
        assertThat(names("databricks")).isSubsetOf(DATABRICKS_READER_PRIVILEGES);
    }

    @Test
    void readRulesRejectWritePermissions() {
        for (String write : List.of("storage.buckets.create", "storage.objects.delete", "bigquery.tables.updateData",
                "resourcemanager.projects.setIamPolicy", "logging.sinks.update", "bigquery.jobs.create")) {
            assertThat(isGcpReadPermission(write)).as(write).isFalse();
        }
        for (String read : List.of("storage.buckets.list", "bigquery.jobs.listAll", "resourcemanager.folders.getIamPolicy")) {
            assertThat(isGcpReadPermission(read)).as(read).isTrue();
        }
        for (String write : List.of("s3:PutObject", "s3:DeleteBucket", "glue:UpdateTable", "cloudtrail:StopLogging",
                "ec2:TerminateInstances", "iam:PassRole")) {
            assertThat(isAwsReadAction(write)).as(write).isFalse();
        }
        assertThat(AWS_CONTENT_READS.matcher("s3:GetObject").matches()).isTrue();
        assertThat(isAllowedAwsGrant("s3:GetObject", List.of("account"))).as("s3:GetObject on every bucket").isFalse();
        assertThat(AWS_CONTENT_READS.matcher("athena:GetQueryResults").matches()).isTrue();
        assertThat(isAzureReadAction("Microsoft.Storage/storageAccounts/write")).isFalse();
        assertThat(isAzureReadAction("Microsoft.Storage/storageAccounts/listKeys/action")).isFalse();
        assertThat(isAzureReadAction("*/read")).isFalse();
    }

    @Test
    void awsCloudFormationTemplateGrantsExactlyTheTrace() {
        Map<String, Object> template = new Yaml().load(read("deployment/iam/aws/reader/reader-role.cfn.yaml"));
        Map<String, Object> resources = map(template.get("Resources"));
        Set<String> granted = new TreeSet<>();
        for (Object resource : resources.values()) {
            Map<String, Object> r = map(resource);
            if (!"AWS::IAM::ManagedPolicy".equals(r.get("Type")) && !"AWS::IAM::Policy".equals(r.get("Type"))) {
                continue;
            }
            Map<String, Object> document = map(map(r.get("Properties")).get("PolicyDocument"));
            for (Map<String, Object> statement : list(document.get("Statement"))) {
                assertThat(statement.get("Effect")).isEqualTo("Allow");
                assertThat(statement).doesNotContainKey("NotAction");
                Object action = statement.get("Action");
                if (action instanceof String s) {
                    granted.add(s);
                } else {
                    ((List<?>) action).forEach(a -> granted.add((String) a));
                }
            }
        }
        assertThat(granted).as("CloudFormation actions equal permissions.yaml").isEqualTo(names("aws"));
    }

    @Test
    void terraformModulesReadTheTraceAndHardCodeNoPermission() {
        Pattern literal = Pattern.compile("\"([a-z]+\\.[a-zA-Z]+\\.[a-zA-Z]+|[a-z0-9-]+:[A-Z][A-Za-z]+|Microsoft\\.[^\"]+"
                + "|BROWSE|USE_CATALOG|USE_SCHEMA|SELECT|MODIFY|ALL_PRIVILEGES)\"");
        for (String cloud : CLOUDS) {
            String main = read("deployment/iam/" + cloud + "/reader/main.tf");
            assertThat(main).as(cloud + " main.tf reads the trace")
                    .contains("yamldecode(file(\"${path.module}/permissions.yaml\"))");
            List<String> hardCoded = new ArrayList<>();
            for (String tf : List.of("main.tf", "variables.tf", "outputs.tf", "versions.tf")) {
                Matcher m = literal.matcher(read("deployment/iam/" + cloud + "/reader/" + tf));
                while (m.find()) {
                    hardCoded.add(tf + ": " + m.group(1));
                }
            }
            // The trust policy (sts:AssumeRole, sts:ExternalId, the EC2 service principal) is not a
            // Reader permission.
            hardCoded.removeIf(s -> s.endsWith(": sts:AssumeRole") || s.endsWith(": sts:ExternalId")
                    || s.endsWith(".amazonaws.com"));
            assertThat(hardCoded).as(cloud + " Terraform hard-codes no permission").isEmpty();
        }
    }

    @Test
    void everyCloudClientFileOfTheReaderServicesIsScoped() {
        List<String> unscoped = new ArrayList<>();
        Map<String, Set<String>> scoped = new LinkedHashMap<>();
        for (String cloud : CLOUDS) {
            Set<String> files = new TreeSet<>();
            permissions(cloud).forEach(p -> calls(p).forEach(c -> files.add((String) c.get("source"))));
            list(trace(cloud).get("no_permission_sources")).forEach(s -> files.add((String) s.get("source")));
            scoped.put(cloud, files);
        }
        for (String module : READER_MODULES) {
            for (Path file : javaSources(ROOT.resolve(module).resolve("src/main/java"))) {
                String relative = ROOT.relativize(file).toString().replace('\\', '/');
                String text = read(relative);
                for (String cloud : CLOUDS) {
                    if (SDK_IMPORTS.get(cloud).matcher(text).find() && !scoped.get(cloud).contains(relative)) {
                        unscoped.add(cloud + ": " + relative);
                    }
                }
            }
        }
        assertThat(unscoped).as("files that import a cloud client but are neither cited nor listed as calling nothing;"
                + " add their permissions to deployment/iam/<cloud>/reader/permissions.yaml").isEmpty();
    }

    @Test
    void everyNoPermissionSourceExistsAndImportsThatCloud() {
        for (String cloud : CLOUDS) {
            for (Map<String, Object> s : list(trace(cloud).get("no_permission_sources"))) {
                String source = (String) s.get("source");
                assertThat(ROOT.resolve(source)).as(source).isRegularFile();
                assertThat(SDK_IMPORTS.get(cloud).matcher(read(source)).find()).as(source + " imports " + cloud).isTrue();
                assertThat((String) s.get("reason")).as(source + " reason").isNotBlank();
            }
        }
    }

    @Test
    void everyPermissionACoverageClientNamesIsInTheTrace() {
        Set<String> all = new TreeSet<>();
        CLOUDS.forEach(c -> all.addAll(names(c)));
        Set<String> named = new TreeSet<>();
        for (Path file : javaSources(ROOT.resolve("da-discovery/src/main/java/com/dlap/discovery/providers"))) {
            if (!file.toString().replace('\\', '/').contains("/coverage/")) {
                continue;
            }
            Matcher m = COVERAGE_PERMISSION_LITERAL.matcher(read(ROOT.relativize(file).toString()));
            while (m.find()) {
                if (!m.group(1).endsWith(".googleapis.com")) {
                    named.add(m.group(1));
                }
            }
        }
        assertThat(named).as("permissions named by the coverage clients").isNotEmpty();
        assertThat(all).as("the Reader roles hold every permission a coverage client asks for").containsAll(named);
    }

    @Test
    void theGuideNamesEveryReaderPermission() {
        String guide = read("docs/guides/cloud-permissions.md");
        for (String cloud : CLOUDS) {
            for (String name : names(cloud)) {
                assertThat(guide).as("guide names " + name).contains("`" + name + "`");
            }
        }
    }

    // ---------------------------------------------------------------------------------- rules

    /** A GCP permission whose verb (last segment) only reads. */
    static boolean isGcpReadPermission(String permission) {
        String[] parts = permission.split("\\.");
        return parts.length == 3 && GCP_READ_VERBS.contains(parts[2]);
    }

    /** An AWS action that only lists, gets, describes or looks up. */
    static boolean isAwsReadAction(String action) {
        int colon = action.indexOf(':');
        if (colon < 1 || action.contains("*")) {
            return false;
        }
        String name = action.substring(colon + 1);
        return name.startsWith("List") || name.startsWith("Get") || name.startsWith("Describe")
                || name.equals("LookupEvents");
    }

    /**
     * An AWS grant the Reader may hold: an action that reads no content, or a content read granted on
     * exactly the one log scope {@link #AWS_CONTENT_READ_SCOPES} names for it.
     */
    static boolean isAllowedAwsGrant(String action, List<String> scopes) {
        if (!AWS_CONTENT_READS.matcher(action).matches()) {
            return true;
        }
        String scope = AWS_CONTENT_READ_SCOPES.get(action);
        return scope != null && scopes.equals(List.of(scope));
    }

    /**
     * A CloudFormation resource entry that names one log bucket parameter and nothing else:
     * {@code Fn::If [Has<Parameter>, Fn::Sub "arn:${AWS::Partition}:s3:::${<Parameter>}", Ref AWS::NoValue]},
     * with "/*" appended for objects. A literal ARN, a wildcard or any other parameter is refused.
     */
    static boolean isAwsLogBucketResource(Object resource, boolean objects) {
        if (!(resource instanceof Map<?, ?> m) || !(m.get("Fn::If") instanceof List<?> branches) || branches.size() != 3) {
            return false;
        }
        if (!(branches.get(1) instanceof Map<?, ?> sub) || !(sub.get("Fn::Sub") instanceof String arn)
                || !(branches.get(2) instanceof Map<?, ?> none) || !"AWS::NoValue".equals(none.get("Ref"))) {
            return false;
        }
        for (String parameter : AWS_LOG_BUCKET_PARAMETERS) {
            String bucket = "arn:${AWS::Partition}:s3:::${" + parameter + "}";
            if (arn.equals(objects ? bucket + "/*" : bucket)) {
                return ("Has" + parameter).equals(branches.get(0));
            }
        }
        return false;
    }

    private static Map<String, Object> logBucketIf(String condition, String arn) {
        return Map.of("Fn::If", List.of(condition, Map.of("Fn::Sub", arn), Map.of("Ref", "AWS::NoValue")));
    }

    /** An Azure control-plane action that only reads one resource type (no wildcard). */
    static boolean isAzureReadAction(String action) {
        return action.startsWith("Microsoft.") && action.endsWith("/read") && !action.contains("*");
    }

    // -------------------------------------------------------------------------------- helpers

    private static Map<String, Object> trace(String cloud) {
        return new Yaml().load(read("deployment/iam/" + cloud + "/reader/permissions.yaml"));
    }

    private static List<Map<String, Object>> permissions(String cloud) {
        return list(trace(cloud).get("permissions"));
    }

    private static Set<String> names(String cloud) {
        return permissions(cloud).stream().map(p -> (String) p.get("permission"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @SuppressWarnings("unchecked")
    private static List<String> scopes(Map<String, Object> permission) {
        Object scopes = permission.get("scopes");
        return scopes == null ? List.of() : (List<String>) scopes;
    }

    private static List<Map<String, Object>> calls(Map<String, Object> permission) {
        return list(permission.get("calls"));
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

    /** The repository root: the nearest ancestor of the working directory with settings.gradle.kts. */
    private static Path repositoryRoot() {
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
