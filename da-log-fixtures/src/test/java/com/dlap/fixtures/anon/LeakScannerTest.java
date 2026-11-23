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

package com.dlap.fixtures.anon;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.fixtures.anon.LeakScanner.Finding;
import com.dlap.fixtures.anon.LeakScanner.Kind;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeakScannerTest {

    @TempDir
    Path tmp;

    private static List<Kind> kinds(List<Finding> findings) {
        return findings.stream().map(Finding::kind).distinct().toList();
    }

    @Test
    void cleanTextHasNoFindings() {
        String text = "10.8.8.10 192.168.10.20 203.0.113.9 198.18.4.4 2001:db8::1 user@example.com 123456789012 "
                + "9999" + "12345678 AKIAEXAMPLE1BPWITOBB Chrome/7.7.7.7 at 12:30:45 arn:aws:iam::123456789012:user/user-ab12cd";
        assertThat(LeakScanner.scanText("t", text)).isEmpty();
    }

    @Test
    void eachKindOfIdentifierIsFound() {
        assertThat(kinds(LeakScanner.scanText("t", "mail someone@corp.example.io"))).containsExactly(Kind.EMAIL);
        assertThat(kinds(LeakScanner.scanText("t", "from 8.8.8.8"))).containsExactly(Kind.IPV4);
        assertThat(kinds(LeakScanner.scanText("t", "from 2a00:1450:4001:81b::200e"))).containsExactly(Kind.IPV6);
        assertThat(kinds(LeakScanner.scanText("t", "owner 731926485107"))).containsExactly(Kind.ACCOUNT_ID);
        assertThat(kinds(LeakScanner.scanText("t", "key AKIAZ7Q2M5X8C4V1B6N3"))).containsExactly(Kind.AWS_ID);
        assertThat(kinds(LeakScanner.scanText("t", "arn:aws:iam::123456789012:user/jane-doe"))).containsExactly(Kind.USER_NAME);
        assertThat(kinds(LeakScanner.scanText("t", "projects/acme-sample-4821/logs/x"))).containsExactly(Kind.PROJECT_ID);
    }

    @Test
    void findingsNeverEchoTheValue() {
        List<Finding> findings = LeakScanner.scanText("file.json", "owner 731926485107");
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).toString()).contains("12 chars").contains("starts '73'").doesNotContain("731926485107");
        assertThat(findings.get(0).where()).isEqualTo("file.json:1");
    }

    @Test
    void jsonChecksUseTheFieldName() {
        JsonNode bad = SeedJson.parse("""
                {"userIdentity":{"userName":"jane-doe","sessionContext":{"sessionIssuer":{"userName":"AWSServiceRoleForRDS"}}},
                 "resource":{"labels":{"project_id":"acme-sample-4821"}}}
                """);
        List<Finding> findings = LeakScanner.scanJson("doc", bad);
        assertThat(findings).extracting(Finding::kind).containsExactlyInAnyOrder(Kind.USER_NAME, Kind.PROJECT_ID);

        JsonNode good = SeedJson.parse("""
                {"userIdentity":{"userName":"user-ab12cd","sessionContext":{"sessionIssuer":{"userName":"AWSServiceRoleForRDS"}}},
                 "resource":{"labels":{"project_id":"proj-0a1b2c3d"}},"other":{"project_id":"bigquery-public-data"}}
                """);
        assertThat(LeakScanner.scanJson("doc", good)).isEmpty();
    }

    @Test
    void fieldNamesAreScannedToo() {
        JsonNode doc = SeedJson.parse("{\"arn:aws:iam::731926485107:role/x\":\"v\"}");
        assertThat(kinds(LeakScanner.scanJson("doc", doc))).containsExactly(Kind.ACCOUNT_ID);
    }

    @Test
    void whatTheAnonymiserWritesScansClean() {
        JsonNode doc = SeedJson.parse("""
                {"userIdentity":{"type":"IAMUser","userName":"jane-doe","accountId":"731926485107",
                  "arn":"arn:aws:iam::731926485107:user/jane-doe","accessKeyId":"AKIAZ7Q2M5X8C4V1B6N3",
                  "principalId":"AIDAZ7Q2M5X8C4V1B6N3K"},
                 "sourceIPAddress":"8.8.4.4","ipv6":"2a00:1450:4001:81b::200e","mail":"someone@corp.example.io",
                 "sa":"deployer@acme-sample-4821.iam.gserviceaccount.com",
                 "google":"service-731926485107@cloud-cdn-fill.iam.gserviceaccount.com",
                 "logName":"projects/acme-sample-4821/logs/cloudaudit.googleapis.com%2Factivity",
                 "labels":{"project_id":"acme-sample-4821"},
                 "host":"ec2-8-8-4-4.compute-1.amazonaws.com","note":"jane-doe wrote from 8.8.4.4"}
                """);
        assertThat(LeakScanner.scanJson("doc", doc)).isNotEmpty();
        JsonNode clean = Anonymiser.standard().anonymise(doc).tree();
        assertThat(LeakScanner.scanJson("doc", clean)).isEmpty();
    }

    @Test
    void scanTreeReadsJsonJsonlGzipAndYamlAndSkipsOtherFiles() throws IOException {
        Files.writeString(tmp.resolve("a.json"), "{\"ip\":\"8.8.8.8\"}\n");
        Files.writeString(tmp.resolve("b.jsonl"), "{\"ok\":1}\n{\"mail\":\"x@corp.example.io\"}\n");
        Files.writeString(tmp.resolve("meta.yaml"), "notes: owner 731926485107\n");
        Files.write(tmp.resolve("c.json.gz"), gzip("{\"acct\":\"731926485107\"}"));
        Files.write(tmp.resolve("image.png"), new byte[] {1, 2, 3});
        Files.writeString(tmp.resolve("clean.json"), "{\"ip\":\"10.0.0.1\"}\n");

        List<Finding> findings = LeakScanner.scanTree(tmp);
        assertThat(findings).extracting(f -> f.where().split("[: ]")[0])
                .containsExactlyInAnyOrder("a.json", "b.jsonl", "meta.yaml", "c.json.gz");
        assertThat(kinds(findings)).containsExactlyInAnyOrder(Kind.IPV4, Kind.EMAIL, Kind.ACCOUNT_ID);
    }

    @Test
    void organizationAndAccessPolicyNumbersMustCarryTheMarker() {
        assertThat(kinds(LeakScanner.scanText("t", "accessPolicies/30507210272/servicePerimeters/p")))
                .containsExactly(Kind.ORG_ID);
        // twelve digits, so it is an account-shaped number as well
        assertThat(kinds(LeakScanner.scanText("t", "organizations/614830067722"))).contains(Kind.ORG_ID);
        assertThat(LeakScanner.scanText("t", "organizations/999961483006 folders/9999123 accessPolicies/9999305072")).isEmpty();
    }

    @Test
    void subscriptionAndTenantGuidsMustBePlaceholdersOrPseudonyms() {
        String real = "0f8c1d52-77ab-4c3e-9a4d-5b6c7d8e9f01";
        assertThat(kinds(LeakScanner.scanText("t", "/subscriptions/" + real + "/rg"))).containsExactly(Kind.GUID);
        assertThat(kinds(LeakScanner.scanJson("t", SeedJson.parse("{\"tenantId\":\"" + real + "\"}")))).containsExactly(Kind.GUID);
        assertThat(LeakScanner.scanText("t", "/subscriptions/00000000-0000-0000-0000-000000000001/rg")).isEmpty();
        String pseudonym = Anonymiser.standard().anonymiseText("/subscriptions/" + real + "/rg");
        assertThat(LeakScanner.scanText("t", pseudonym)).isEmpty();
    }

    @Test
    void theLastGroupOfAGuidIsNotReportedAsAnAccountNumber() {
        assertThat(LeakScanner.scanText("t", "id 00000000-0000-0000-0000-000000000001")).isEmpty();
        assertThat(kinds(LeakScanner.scanText("t", "id 731926485107"))).containsExactly(Kind.ACCOUNT_ID);
    }

    @Test
    void accessKeyIdsOfBothKindsNeedTheMarker() {
        assertThat(kinds(LeakScanner.scanText("t", "key AKIAZ7Q2M5X8C4V1B6N3"))).containsExactly(Kind.AWS_ID);
        assertThat(kinds(LeakScanner.scanText("t", "key ASIAZ7Q2M5X8C4V1B6N4"))).containsExactly(Kind.AWS_ID);
        assertThat(LeakScanner.scanText("t", "keys AKIAEXAMPLE1BPWITOBB ASIAEXAMPLE1A2R0QGHV")).isEmpty();
    }

    @Test
    void aSessionTokenIsFoundByFieldNameByHeaderAndInAUrl() {
        String token = TestCredentials.sessionToken(500, 1);
        JsonNode byName = SeedJson.parse("{\"credentials\":{\"sessionToken\":\"" + "abc".repeat(10) + "\"}}");
        assertThat(kinds(LeakScanner.scanJson("doc", byName))).containsExactly(Kind.CREDENTIAL);
        JsonNode byShape = SeedJson.parse("{\"note\":\"" + token + "\"}");
        assertThat(kinds(LeakScanner.scanJson("doc", byShape))).containsExactly(Kind.CREDENTIAL);
        String url = "https://h/p?X-Amz-Security-Token=" + token.replace("+", "%2B").replace("/", "%2F") + "&x=1";
        assertThat(kinds(LeakScanner.scanText("t", url))).containsExactly(Kind.CREDENTIAL);
    }

    @Test
    void aTokenInASessionTokenFieldIsReportedOnce() {
        JsonNode doc = SeedJson.parse("{\"credentials\":{\"sessionToken\":\"" + TestCredentials.sessionToken(500, 1) + "\"}}");
        assertThat(LeakScanner.scanJson("doc", doc)).hasSize(1);
    }

    @Test
    void aSecretAccessKeyIsFoundByFieldNameAndAfterAnAssignmentButABareValueIsNot() {
        String key = TestCredentials.secretKey(3);
        assertThat(kinds(LeakScanner.scanJson("doc", SeedJson.parse("{\"secretAccessKey\":\"" + key + "\"}"))))
                .containsExactly(Kind.CREDENTIAL);
        assertThat(kinds(LeakScanner.scanText("t", "aws_secret_access_key = " + key))).containsExactly(Kind.CREDENTIAL);
        assertThat(kinds(LeakScanner.scanText("t", "{\"SecretAccessKey\": \"" + key + "\"}"))).containsExactly(Kind.CREDENTIAL);
        // forty characters on their own could be a hash
        assertThat(LeakScanner.scanText("t", "sha " + key)).isEmpty();
    }

    @Test
    void aPrivateKeyBlockIsAlwaysAFinding() {
        String block = "-----BEGIN " + "PRIVATE KEY-----\nAAAA\n-----END " + "PRIVATE KEY-----";
        assertThat(kinds(LeakScanner.scanText("t", block))).containsExactly(Kind.CREDENTIAL);
        assertThat(kinds(LeakScanner.scanText("t", block.replace("BEGIN ", "BEGIN RSA ")))).containsExactly(Kind.CREDENTIAL);
        assertThat(LeakScanner.scanText("t", "-----BEGIN " + "PUBLIC KEY-----\nAAAA\n-----END " + "PUBLIC KEY-----")).isEmpty();
    }

    @Test
    void placeholdersRedactionsMasksAndShortValuesAreNotFindings() {
        String doc = "{\"credentials\":{\"accessKeyId\":\"ASIAZ7Q2M5X8C4V1B6N4\",\"secretAccessKey\":\""
                + TestCredentials.secretKey(3) + "\",\"sessionToken\":\"" + TestCredentials.sessionToken(700, 4) + "\"}}";
        assertThat(kinds(LeakScanner.scanJson("doc", SeedJson.parse(doc)))).containsExactlyInAnyOrder(Kind.AWS_ID, Kind.CREDENTIAL);
        assertThat(LeakScanner.scanJson("doc", Anonymiser.standard().anonymise(SeedJson.parse(doc)).tree())).isEmpty();

        String quiet = "{\"secretAccessKey\":\"HIDDEN_DUE_TO_SECURITY_REASONS\",\"sessionToken\":\"********************\","
                + "\"securityToken\":\"abc\"}";
        assertThat(LeakScanner.scanJson("doc", SeedJson.parse(quiet))).isEmpty();
    }

    @Test
    void aCredentialFindingNeverEchoesAnyCharacterOfTheValue() {
        String token = TestCredentials.sessionToken(500, 2);
        List<Finding> findings = LeakScanner.scanJson("doc", SeedJson.parse("{\"note\":\"" + token + "\"}"));
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).toString()).contains("CREDENTIAL").contains("500 chars").doesNotContain("starts")
                .doesNotContain(token.substring(0, 8));
    }

    @Test
    void scanTreeFindsACredentialInAFile() throws IOException {
        Files.writeString(tmp.resolve("trail.json"), "{\"Records\":[{\"sessionToken\":\"" + TestCredentials.sessionToken(400, 1) + "\"}]}\n");
        Files.writeString(tmp.resolve("creds.txt"), "aws_secret_access_key = " + TestCredentials.secretKey(1) + "\n");
        List<Finding> findings = LeakScanner.scanTree(tmp);
        assertThat(findings).extracting(f -> f.where().split("[: ]")[0]).containsExactlyInAnyOrder("trail.json", "creds.txt");
        assertThat(kinds(findings)).containsExactly(Kind.CREDENTIAL);
    }

    @Test
    void anUnreadableJsonFileIsStillScannedAsText() throws IOException {
        Files.writeString(tmp.resolve("torn.json"), "{\"ip\":\"8.8.8.8\"");
        assertThat(kinds(LeakScanner.scanTree(tmp))).containsExactly(Kind.IPV4);
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
}
