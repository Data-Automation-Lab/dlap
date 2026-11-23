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

package com.dlap.adminservice.permissioncheck.providers.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.dlap.adminservice.audit.export.HttpCalls;
import com.dlap.adminservice.audit.export.StubHttpServer;
import com.dlap.adminservice.audit.providers.aws.AwsCredentials;
import com.dlap.adminservice.permissioncheck.PermissionCheckReport;
import com.dlap.adminservice.permissioncheck.PermissionCheckReport.Status;
import com.dlap.adminservice.permissioncheck.PermissionCheckService;
import com.dlap.adminservice.permissioncheck.PermissionProbe;
import com.dlap.adminservice.permissioncheck.TraceSource;

/**
 * The AWS probe against a stand-in for the IAM Query API: the request it signs and sends, the
 * simulator response it reads, and the report the self-check builds from it against the committed
 * AWS Actor trace. A live account is the founder's check (docs/guides/cloud-permissions.md).
 */
class AwsPolicySimulatorProbeTest {

    private static final String ROLE = "arn:aws:iam::123456789012:role/dlap-delete-actor";
    private static final String BUCKET_ARN = "arn:aws:s3:::dlap-data";

    private StubHttpServer server;
    private AwsPermissionCheckProperties config;
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void start() {
        server = new StubHttpServer();
        config = new AwsPermissionCheckProperties();
        config.setIamEndpoint(server.url() + "/");
        AwsPermissionCheckProperties.Executor delete = new AwsPermissionCheckProperties.Executor();
        delete.setRoleArn(ROLE);
        delete.setBuckets(List.of("dlap-data"));
        config.getExecutors().put("delete", delete);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private AwsPolicySimulatorProbe probe() {
        return new AwsPolicySimulatorProbe(config, new HttpCalls(Duration.ofSeconds(5)),
                () -> new AwsCredentials("AKIDEXAMPLE", "test-secret-not-real", "test-session", null), clock);
    }

    private PermissionCheckReport check() {
        TraceSource repo = new TraceSource(com.dlap.adminservice.permissioncheck.ActorRoleTraceabilityTest
                .repositoryRoot().resolve("deployment/iam"));
        return new PermissionCheckService(repo, List.<PermissionProbe>of(probe())).check("aws").orElseThrow();
    }

    /** Answers like SimulatePrincipalPolicy: one member per asked action, allowed when the rule says so. */
    private void simulate(BiPredicate<String, String> allowed) {
        server.respond(r -> {
            Map<String, String> form = form(r.text());
            String resource = form.get("ResourceArns.member.1");
            StringBuilder xml = new StringBuilder("<SimulatePrincipalPolicyResponse xmlns=\"https://iam.amazonaws.com/"
                    + "doc/2010-05-08/\"><SimulatePrincipalPolicyResult><IsTruncated>false</IsTruncated>"
                    + "<EvaluationResults>");
            for (int i = 1; form.containsKey("ActionNames.member." + i); i++) {
                String action = form.get("ActionNames.member." + i);
                xml.append("<member><EvalActionName>").append(action).append("</EvalActionName><EvalResourceName>")
                        .append(resource).append("</EvalResourceName><EvalDecision>")
                        .append(allowed.test(action, resource) ? "allowed" : "implicitDeny")
                        .append("</EvalDecision><MatchedStatements><member><SourcePolicyId>p</SourcePolicyId></member>"
                                + "</MatchedStatements><MissingContextValues/></member>");
            }
            xml.append("</EvaluationResults></SimulatePrincipalPolicyResult></SimulatePrincipalPolicyResponse>");
            return StubHttpServer.Response.of(200, xml.toString());
        });
    }

    private static Map<String, String> form(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    void theSelfCheckReportsAMissingAndAnExtraPermissionFromTheSimulatorResponse() {
        List<String> lifecycle = List.of("s3:GetBucketLocation", "s3:GetLifecycleConfiguration",
                "s3:PutLifecycleConfiguration", "s3:PutBucketPolicy");
        simulate((action, resource) -> resource.equals(BUCKET_ARN) && lifecycle.contains(action));

        PermissionCheckReport report = check();

        assertThat(report.status()).isEqualTo(Status.FAIL);
        assertThat(report.method()).isEqualTo("iam:SimulatePrincipalPolicy");
        assertThat(report.missing()).containsExactly("delete: s3:GetBucketVersioning on " + BUCKET_ARN);
        assertThat(report.extra()).containsExactly("delete: s3:PutBucketPolicy on " + BUCKET_ARN);
        assertThat(report.targets()).extracting(PermissionCheckReport.TargetReport::resource).containsExactly(
                BUCKET_ARN, BUCKET_ARN + "/dlap-permission-check-probe",
                "arn:aws:s3:::dlap-permission-check-unnamed-bucket",
                "arn:aws:s3:::dlap-permission-check-unnamed-bucket/dlap-permission-check-probe", "*");
    }

    @Test
    void theReportNamesTheActorRoleArnItCheckedAndSaysItIsNotTheCaller() {
        simulate((action, resource) -> false);
        PermissionCheckReport report = check();
        assertThat(report.identityChecked()).isEqualTo(ROLE);
        assertThat(report.identityBasis()).isEqualTo(PermissionCheckReport.IdentityBasis.NAMED_ROLE);
        assertThat(report.identityNote()).contains("role-arn", "whoever makes the call");
    }

    @Test
    void anExactRoleIsAPassAndAWildcardRoleIsExtraOnTheBucketNobodyNamed() {
        List<String> deleteBucket = List.of("s3:GetBucketLocation", "s3:GetBucketVersioning",
                "s3:GetLifecycleConfiguration", "s3:PutLifecycleConfiguration");
        simulate((action, resource) -> resource.equals(BUCKET_ARN) && deleteBucket.contains(action));
        assertThat(check().status()).isEqualTo(Status.PASS);

        simulate((action, resource) -> !resource.equals("*") && action.startsWith("s3:"));
        PermissionCheckReport wildcard = check();
        assertThat(wildcard.status()).isEqualTo(Status.FAIL);
        assertThat(wildcard.extra()).contains(
                "delete: s3:DeleteObject on " + BUCKET_ARN + "/dlap-permission-check-probe",
                "delete: s3:PutLifecycleConfiguration on arn:aws:s3:::dlap-permission-check-unnamed-bucket"
                        + " (a resource the operator did not name)",
                "delete: s3:DeleteObject on arn:aws:s3:::dlap-permission-check-unnamed-bucket/"
                        + "dlap-permission-check-probe (a resource the operator did not name)");
    }

    @Test
    void signsTheQueryWithSigV4AndAsksAboutTheRoleTheResourceAndEveryCandidate() {
        simulate((action, resource) -> false);
        probe().probe(new PermissionProbe.Target("delete", "bucket", BUCKET_ARN, ROLE, true, true),
                new java.util.TreeSet<>(List.of("s3:GetBucketVersioning", "s3:PutBucketPolicy")), java.util.Set.of());

        StubHttpServer.Request r = server.requests().get(0);
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.headers().get("content-type")).isEqualTo("application/x-www-form-urlencoded; charset=utf-8");
        assertThat(r.headers().get("authorization"))
                .startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261101/us-east-1/iam/aws4_request,");
        assertThat(r.headers().get("x-amz-date")).isEqualTo("20261101T100000Z");
        assertThat(r.headers().get("x-amz-security-token")).isEqualTo("test-session");
        Map<String, String> form = form(r.text());
        assertThat(form).containsEntry("Action", "SimulatePrincipalPolicy").containsEntry("Version", "2010-05-08")
                .containsEntry("PolicySourceArn", ROLE).containsEntry("ResourceArns.member.1", BUCKET_ARN)
                .containsEntry("ActionNames.member.1", "s3:GetBucketVersioning")
                .containsEntry("ActionNames.member.2", "s3:PutBucketPolicy");
        assertThat(r.text()).doesNotContain("test-secret-not-real");
    }

    @Test
    void followsTheMarkerAcrossPages() {
        List<String> markers = new ArrayList<>();
        server.respond(r -> {
            Map<String, String> form = form(r.text());
            markers.add(form.getOrDefault("Marker", ""));
            boolean first = !form.containsKey("Marker");
            String action = first ? "s3:GetBucketVersioning" : "s3:PutBucketPolicy";
            return StubHttpServer.Response.of(200, "<SimulatePrincipalPolicyResponse><SimulatePrincipalPolicyResult>"
                    + "<EvaluationResults><member><EvalActionName>" + action + "</EvalActionName>"
                    + "<EvalDecision>allowed</EvalDecision></member></EvaluationResults>"
                    + (first ? "<IsTruncated>true</IsTruncated><Marker>page-2</Marker>" : "<IsTruncated>false</IsTruncated>")
                    + "</SimulatePrincipalPolicyResult></SimulatePrincipalPolicyResponse>");
        });
        PermissionProbe.Answer a = probe().probe(new PermissionProbe.Target("delete", "bucket", BUCKET_ARN, ROLE, true,
                true), new java.util.TreeSet<>(List.of("s3:GetBucketVersioning", "s3:PutBucketPolicy")), java.util.Set.of());
        assertThat(a.allowed()).containsExactly("s3:GetBucketVersioning", "s3:PutBucketPolicy");
        assertThat(markers).containsExactly("", "page-2");
    }

    @Test
    void aRefusedSimulationIsAnErrorThatNamesTheMissingAdminPermission() {
        server.respond(r -> StubHttpServer.Response.of(403, "<ErrorResponse><Error><Code>AccessDenied</Code></Error>"
                + "</ErrorResponse>"));
        PermissionCheckReport report = check();
        assertThat(report.status()).isEqualTo(Status.ERROR);
        assertThat(report.targets().get(0).error()).contains("iam:SimulatePrincipalPolicy", ROLE);

        server.respond(r -> StubHttpServer.Response.of(500, "boom"));
        assertThat(check().targets().get(0).error()).contains("HTTP 500");
    }

    @Test
    void refusesAHostileXmlDocument() {
        String hostile = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<SimulatePrincipalPolicyResponse><EvaluationResults><member><EvalActionName>&x;</EvalActionName>"
                + "<EvalDecision>allowed</EvalDecision></member></EvaluationResults></SimulatePrincipalPolicyResponse>";
        assertThatThrownBy(() -> AwsPolicySimulatorProbe.parse(hostile.getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("not a readable XML document");
    }

    @Test
    void refusesARoleArnOrBucketThatIsNotOne() {
        config.getExecutors().get("delete").setRoleArn("arn:aws:iam::123456789012:user/someone");
        assertThatThrownBy(() -> probe().targets()).hasMessageContaining("role-arn is not an IAM role ARN");
        config.getExecutors().get("delete").setRoleArn(ROLE);
        config.getExecutors().get("delete").setBuckets(List.of("data-*"));
        assertThatThrownBy(() -> probe().targets()).hasMessageContaining("not a bucket name");
        config.getExecutors().get("delete").setRoleArn(" ");
        assertThat(probe().targets()).as("an executor without a role is skipped").isEmpty();
        assertThat(check().status()).isEqualTo(Status.NOT_CONFIGURED);
    }

    @Test
    void objectActionsOnExpectTheObjectScope() {
        config.getExecutors().get("delete").setObjectActions(true);
        simulate((action, resource) -> resource.startsWith(BUCKET_ARN) && (action.startsWith("s3:Get")
                || action.endsWith("LifecycleConfiguration") || action.equals("s3:DeleteObject"))
                && !action.equals("s3:GetObject") && !action.equals("s3:GetObjectVersion"));
        PermissionCheckReport report = check();
        assertThat(report.missing()).isEmpty();
        assertThat(report.extra()).isEmpty();
        assertThat(report.status()).isEqualTo(Status.PASS);
    }
}
