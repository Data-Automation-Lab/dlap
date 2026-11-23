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

import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class AnonymiserTest {

    private final Anonymiser anon = Anonymiser.standard();

    private static JsonNode json(String text) {
        return SeedJson.parse(text);
    }

    // ------------------------------------------------------------------
    // Determinism
    // ------------------------------------------------------------------

    @Test
    void sameInputGivesSameOutputInEveryRun() {
        String doc = "{\"ip\":\"8.8.4.4\",\"acct\":\"731926485107\",\"mail\":\"someone@corp.example.io\"}";
        JsonNode first = new Anonymiser(Anonymiser.DEFAULT_SALT).anonymise(json(doc)).tree();
        JsonNode second = new Anonymiser(Anonymiser.DEFAULT_SALT).anonymise(json(doc)).tree();
        assertThat(SeedJson.compact(first)).isEqualTo(SeedJson.compact(second));
    }

    @Test
    void knownAnswersStayPutAcrossReleases() {
        // Pinned on purpose. If one of these changes, every committed fixture changes with it: do that
        // deliberately, with a new salt and a regenerated corpus (SeedImporter check shows the damage).
        assertThat(anon.anonymiseText("731926485107")).isEqualTo("999945893740");
        assertThat(anon.anonymiseText("8.8.4.4")).isEqualTo("198.19.103.75");
        assertThat(anon.anonymiseText("2a00:1450:4001:81b::200e")).isEqualTo("2001:db8:a9ef:7233:3244:c978:ab8b:3e29");
        assertThat(anon.anonymiseText("jane@corp.example.io")).isEqualTo("user-631c5c1e@example.org");
        assertThat(anon.anonymiseText("AKIAZ7Q2M5X8C4V1B6N3")).isEqualTo("AKIAEXAMPLE1BPWITOBB");
        assertThat(anon.anonymiseText("AIDAZ7Q2M5X8C4V1B6N3K")).isEqualTo("AIDAEXAMPLE05MFU5TEUQ");
        assertThat(anon.anonymiseText("deployer@acme-sample-4821.iam.gserviceaccount.com"))
                .isEqualTo("sa-618cafff@proj-10308991.iam.gserviceaccount.com");
        assertThat(anon.anonymiseText("projects/acme-sample-4821/logs/x")).isEqualTo("projects/proj-10308991/logs/x");
        assertThat(anon.anonymiseText("ec2-8-8-4-4.compute-1.amazonaws.com")).isEqualTo("ec2-198-19-103-75.compute-1.amazonaws.com");
        assertThat(anon.anonymiseText("/subscriptions/0f8c1d52-77ab-4c3e-9a4d-5b6c7d8e9f01/rg"))
                .isEqualTo("/subscriptions/ca5a4716-3fd6-40fe-92d3-fa17b54ba97e/rg");
    }

    @Test
    void aDifferentSaltGivesDifferentPseudonyms() {
        String text = "owner 731926485107 from 8.8.4.4";
        assertThat(new Anonymiser("another salt").anonymiseText(text)).isNotEqualTo(anon.anonymiseText(text));
    }

    @Test
    void theInputDocumentIsNotModified() {
        JsonNode doc = json("{\"a\":\"731926485107\"}");
        anon.anonymise(doc);
        assertThat(doc.get("a").asText()).isEqualTo("731926485107");
    }

    @Test
    void anonymisingTwiceChangesNothing() {
        JsonNode doc = json("""
                {"userIdentity":{"type":"IAMUser","userName":"jane-doe","accountId":"731926485107",
                  "arn":"arn:aws:iam::731926485107:user/jane-doe","accessKeyId":"AKIAZ7Q2M5X8C4V1B6N3",
                  "principalId":"AIDAZ7Q2M5X8C4V1B6N3K"},
                 "sourceIPAddress":"8.8.4.4","ipv6":"2a00:1450:4001:81b::200e",
                 "mail":"someone@corp.example.io","sa":"terraform@acme-sample-4821.iam.gserviceaccount.com",
                 "logName":"projects/acme-sample-4821/logs/cloudaudit.googleapis.com%2Factivity",
                 "host":"ec2-3-225-16-109.compute-1.amazonaws.com",
                 "key":"//iam.googleapis.com/projects/p-sample-1/serviceAccounts/x/keys/1ebd8fdfad580ff261c75c42ef9aea76893ba6ab",
                 "policy":"accessPolicies/30507210272 organizations/614830067722 folders/1234567",
                 "path":"/subscriptions/0f8c1d52-77ab-4c3e-9a4d-5b6c7d8e9f01/resourceGroups/rg"}
                """);
        JsonNode once = anon.anonymise(doc).tree();
        assertThat(once.get("key").asText()).doesNotContain("1ebd8fdfad").contains("/keys/5a5a5a5a");
        JsonNode twice = anon.anonymise(once).tree();
        assertThat(SeedJson.compact(twice)).isEqualTo(SeedJson.compact(once));
    }

    // ------------------------------------------------------------------
    // Account numbers
    // ------------------------------------------------------------------

    @Test
    void accountNumbersBecomePseudonymsEverywhereTheyOccur() {
        String out = anon.anonymiseText("arn:aws:iam::731926485107:role/x and recipient 731926485107");
        assertThat(out).doesNotContain("731926485107");
        java.util.regex.Matcher m = Pattern.compile("9999[0-9]{8}").matcher(out);
        assertThat(m.find()).isTrue();
        String pseudonym = m.group();
        assertThat(out).isEqualTo("arn:aws:iam::" + pseudonym + ":role/x and recipient " + pseudonym);
    }

    @Test
    void placeholderAccountsAndLongerNumbersStay() {
        assertThat(anon.anonymiseText("123456789012 and 111122223333")).isEqualTo("123456789012 and 111122223333");
        assertThat(anon.anonymiseText("epoch 1628888166900227 and 1234567890123")).isEqualTo("epoch 1628888166900227 and 1234567890123");
    }

    @Test
    void aTwelveDigitRunInsideAHashIsNotAnAccount() {
        String hash = "5eb6839ec5107877b7b781841fc83ae6a1b2c3d4";
        String withRun = "a3fb" + "123456789012" + "bc9d8e7f6a5b4c3d2e1f";
        assertThat(anon.anonymiseText(hash)).isEqualTo(hash);
        assertThat(anon.anonymiseText(withRun)).isEqualTo(withRun);
    }

    @Test
    void aProjectNumberInAServiceAccountNameIsRewritten() {
        String out = anon.anonymiseText("service-731926485107@cloud-cdn-fill.iam.gserviceaccount.com");
        assertThat(out).matches("service-9999[0-9]{8}@cloud-cdn-fill\\.iam\\.gserviceaccount\\.com");
    }

    // ------------------------------------------------------------------
    // Addresses
    // ------------------------------------------------------------------

    @Test
    void publicIpv4BecomesBenchmarkingSpaceAndReservedStays() {
        String out = anon.anonymiseText("from 8.8.8.8 via 10.8.8.10, 192.168.10.20, 127.0.0.1, 203.0.113.50 and 100.64.1.2");
        assertThat(out).startsWith("from 198.");
        assertThat(out).endsWith(" via 10.8.8.10, 192.168.10.20, 127.0.0.1, 203.0.113.50 and 100.64.1.2");
        assertThat(out).doesNotContain("8.8.8.8");
    }

    @Test
    void anIpv4WithAPortKeepsItsPort() {
        assertThat(anon.anonymiseText("5.5.5.5:28915")).matches("198\\.(18|19)\\.[0-9]+\\.[0-9]+:28915");
    }

    @Test
    void versionStringsThatLookLikeAddressesAreLeftAlone() {
        String ua = "Mozilla/5.0 Chrome/7.7.7.7 Safari/537.36 Linux-6.6.6.6-generic Python/3.12.9 Chrome/92.0.4515.131";
        assertThat(anon.anonymiseText(ua)).isEqualTo(ua);
    }

    @Test
    void outOfRangeOctetsAreNotAnAddress() {
        assertThat(anon.anonymiseText("version 300.1.1.1 and 1.2.3.456")).isEqualTo("version 300.1.1.1 and 1.2.3.456");
    }

    @Test
    void publicIpv6BecomesDocumentationSpaceAndReservedStays() {
        String out = anon.anonymiseText("a 2a00:1450:4001:81b::200e b 2600::1 c ::1 d fe80::1 e 2001:db8::7");
        assertThat(out).doesNotContain("2a00:1450").doesNotContain("2600::1");
        assertThat(out).contains(" c ::1 d fe80::1 e 2001:db8::7");
        assertThat(out).matches("a 2001:db8(:[0-9a-f]{4}){6} b 2001:db8(:[0-9a-f]{4}){6} c .*");
    }

    @Test
    void timesMacAddressesAndArnsAreNotIpv6() {
        String text = "at 12:30:45 on aa:bb:cc:dd:ee:ff for arn:aws:iam::123456789012:user/x on 2025-09-17T14:22:45Z";
        assertThat(anon.anonymiseText(text)).isEqualTo(text);
    }

    @Test
    void hostNamesBuiltFromAnAddressAreRewritten() {
        String out = anon.anonymiseText("ec2-3-225-16-109.compute-1.amazonaws.com and ip-10-0-0-5.ec2.internal");
        assertThat(out).doesNotContain("3-225-16-109");
        assertThat(out).startsWith("ec2-198-").endsWith(" and ip-10-0-0-5.ec2.internal");
    }

    // ------------------------------------------------------------------
    // E-mail
    // ------------------------------------------------------------------

    @Test
    void emailsBecomeExampleOrgAddresses() {
        String out = anon.anonymiseText("principal user:alice.smith@corp.example.io, other bob@domain.com");
        assertThat(out).doesNotContain("alice").doesNotContain("bob").doesNotContain("corp.example.io");
        assertThat(out).matches("principal user:user-[0-9a-f]{8}@example\\.org, other user-[0-9a-f]{8}@example\\.org");
    }

    @Test
    void emailsAtReservedExampleDomainsStay() {
        assertThat(anon.anonymiseText("user@example.com a@sub.example.org b@x.test")).isEqualTo("user@example.com a@sub.example.org b@x.test");
    }

    @Test
    void customerServiceAccountsKeepTheirKindAndGoogleOwnedOnesStay() {
        String out = anon.anonymiseText("deployer@acme-sample-4821.iam.gserviceaccount.com");
        assertThat(out).matches("sa-[0-9a-f]{8}@proj-[0-9a-f]{8}\\.iam\\.gserviceaccount\\.com");
        assertThat(anon.anonymiseText("cloud-storage-millwheel@system.gserviceaccount.com"))
                .isEqualTo("cloud-storage-millwheel@system.gserviceaccount.com");
        assertThat(anon.anonymiseText("o731926485107-000712@gcp-sa-logging.iam.gserviceaccount.com"))
                .matches("o9999[0-9]{8}-000712@gcp-sa-logging\\.iam\\.gserviceaccount\\.com");
    }

    // ------------------------------------------------------------------
    // AWS unique ids
    // ------------------------------------------------------------------

    @Test
    void awsUniqueIdsKeepPrefixAndLengthAndEscapeKeyScanners() {
        for (String id : new String[] {"AKIAZ7Q2M5X8C4V1B6N3", "ASIAZ7Q2M5X8C4V1B6N4", "AIDAZ7Q2M5X8C4V1B6N3K", "AROAZ7Q2M5X8C4V1B6N3K"}) {
            String out = anon.anonymiseText(id);
            assertThat(out).hasSize(id.length()).startsWith(id.substring(0, 4)).matches(id.substring(0, 4) + "EXAMPLE[0189][A-Z0-9]+");
            assertThat(out).doesNotMatch("(AKIA|ASIA)[A-Z2-7]{16}");
        }
    }

    @Test
    void anAwsIdInsideAPrincipalIdKeepsItsSessionName() {
        String out = anon.anonymiseText("AROAZ7Q2M5X8C4V1B6N3K:i-0dbc91f429e48eeed");
        assertThat(out).startsWith("AROAEXAMPLE").endsWith(":i-0dbc91f429e48eeed");
    }

    // ------------------------------------------------------------------
    // Credentials (Q-220)
    // ------------------------------------------------------------------

    private static String credentialsDoc(String token, String secret) {
        return "{\"responseElements\":{\"credentials\":{\"accessKeyId\":\"ASIAZ7Q2M5X8C4V1B6N4\","
                + "\"secretAccessKey\":\"" + secret + "\",\"sessionToken\":\"" + token + "\","
                + "\"expiration\":\"Jul 10, 2023, 12:55:24 PM\"}}}";
    }

    @Test
    void aSessionTokenBecomesAPlaceholderOfTheSameLengthWithTheSameFormatHeader() {
        String token = TestCredentials.sessionToken(1400, 7);
        JsonNode out = anon.anonymise(json(credentialsDoc(token, TestCredentials.secretKey(8)))).tree();
        String placeholder = out.at("/responseElements/credentials/sessionToken").asText();
        assertThat(placeholder).hasSize(1400).startsWith(TestCredentials.STS_HEAD + "EXAMPLE")
                .matches("[A-Za-z0-9]{16}EXAMPLE[A-Z0-9]+");
        int head = TestCredentials.STS_HEAD.length();
        assertThat(placeholder).doesNotContain(token.substring(head, head + 24));
    }

    @Test
    void aSecretAccessKeyBecomesAFortyCharacterPlaceholderAndTheAccessKeyIdKeepsItsPrefix() {
        JsonNode out = anon.anonymise(json(credentialsDoc(TestCredentials.sessionToken(300, 1), TestCredentials.secretKey(8)))).tree();
        assertThat(out.at("/responseElements/credentials/secretAccessKey").asText()).hasSize(40).matches("EXAMPLE[A-Z0-9]{33}");
        assertThat(out.at("/responseElements/credentials/accessKeyId").asText()).hasSize(20).startsWith("ASIAEXAMPLE");
    }

    @Test
    void credentialPlaceholdersAreDeterministicDistinctAndStableOnASecondPass() {
        String doc = credentialsDoc(TestCredentials.sessionToken(900, 1), TestCredentials.secretKey(2));
        Anonymiser.Result first = anon.anonymise(json(doc));
        assertThat(SeedJson.compact(new Anonymiser(Anonymiser.DEFAULT_SALT).anonymise(json(doc)).tree()))
                .isEqualTo(SeedJson.compact(first.tree()));
        assertThat(first.replaced()).containsEntry("sessionToken", 1).containsEntry("secretKey", 1).containsEntry("awsId", 1);

        Anonymiser.Result again = anon.anonymise(first.tree());
        assertThat(SeedJson.compact(again.tree())).isEqualTo(SeedJson.compact(first.tree()));
        assertThat(again.replaced()).isEmpty();

        JsonNode other = anon.anonymise(json(credentialsDoc(TestCredentials.sessionToken(900, 3), TestCredentials.secretKey(4)))).tree();
        assertThat(other.at("/responseElements/credentials/sessionToken").asText())
                .isNotEqualTo(first.tree().at("/responseElements/credentials/sessionToken").asText());
        assertThat(other.at("/responseElements/credentials/secretAccessKey").asText())
                .isNotEqualTo(first.tree().at("/responseElements/credentials/secretAccessKey").asText());
        JsonNode salted = new Anonymiser("another salt").anonymise(json(doc)).tree();
        assertThat(salted.at("/responseElements/credentials/sessionToken").asText())
                .isNotEqualTo(first.tree().at("/responseElements/credentials/sessionToken").asText());
    }

    @Test
    void credentialFieldsAreRecognisedInTheirCommonSpellings() {
        String token = TestCredentials.sessionToken(300, 5);
        for (String field : new String[] {"sessionToken", "SessionToken", "session_token", "securityToken", "X-Amz-Security-Token"}) {
            JsonNode out = anon.anonymise(json("{\"" + field + "\":\"" + token + "\"}")).tree();
            assertThat(out.get(field).asText()).as(field).hasSize(300).startsWith(TestCredentials.STS_HEAD + "EXAMPLE");
        }
        String key = TestCredentials.secretKey(6);
        for (String field : new String[] {"secretAccessKey", "SecretAccessKey", "aws_secret_access_key", "secretKey"}) {
            JsonNode out = anon.anonymise(json("{\"" + field + "\":\"" + key + "\"}")).tree();
            assertThat(out.get(field).asText()).as(field).hasSize(40).startsWith("EXAMPLE");
        }
    }

    @Test
    void redactionsMasksShortValuesAndOtherFieldsStayAsTheyAre() {
        String doc = "{\"secretAccessKey\":\"HIDDEN_DUE_TO_SECURITY_REASONS\",\"sessionToken\":\"********************\","
                + "\"securityToken\":\"abc\",\"tokenHash\":\"" + TestCredentials.secretKey(9) + "\"}";
        Anonymiser.Result out = anon.anonymise(json(doc));
        assertThat(SeedJson.compact(out.tree())).isEqualTo(SeedJson.compact(json(doc)));
        assertThat(out.replaced()).isEmpty();
    }

    @Test
    void aSessionTokenOutsideAKnownFieldIsFoundByItsHeaderEvenInAUrl() {
        String token = TestCredentials.sessionToken(600, 10);
        String prefix = "https://host/path?X-Amz-Algorithm=AWS4&X-Amz-Security-Token=";
        String url = prefix + token.replace("+", "%2B").replace("/", "%2F") + "&X-Amz-Expires=3600";
        String out = anon.anonymiseText(url);
        assertThat(out).hasSize(url.length()).startsWith(prefix + TestCredentials.STS_HEAD + "EXAMPLE").endsWith("&X-Amz-Expires=3600");
        assertThat(anon.anonymiseText(out)).isEqualTo(out);
    }

    @Test
    void aSecretKeyAfterAnAssignmentIsFoundInText() {
        String key = TestCredentials.secretKey(11);
        String out = anon.anonymiseText("[default]\naws_access_key_id = AKIAZ7Q2M5X8C4V1B6N3\naws_secret_access_key = " + key + "\n");
        assertThat(out).contains("aws_access_key_id = AKIAEXAMPLE").contains("aws_secret_access_key = EXAMPLE").doesNotContain(key);
        assertThat(anon.anonymiseText(out)).isEqualTo(out);
        // a bare 40 character value could be a hash, so it is left alone
        assertThat(anon.anonymiseText("sha " + key)).isEqualTo("sha " + key);
    }

    // ------------------------------------------------------------------
    // Names and projects
    // ------------------------------------------------------------------

    @Test
    void iamUserNamesAreRewrittenInFieldsAndArnsButRoleNamesStay() {
        JsonNode out = anon.anonymise(json("""
                {"userIdentity":{"type":"IAMUser","userName":"jane-doe","arn":"arn:aws:iam::123456789012:user/jane-doe"},
                 "sessionContext":{"sessionIssuer":{"userName":"AWSServiceRoleForRDS",
                   "arn":"arn:aws:iam::123456789012:role/aws-service-role/rds.amazonaws.com/AWSServiceRoleForRDS"}},
                 "requestParameters":{"userName":"jane-doe"},"note":"created by jane-doe"}
                """)).tree();
        String text = SeedJson.compact(out);
        assertThat(text).doesNotContain("jane-doe");
        assertThat(out.at("/userIdentity/userName").asText()).matches("user-[0-9a-f]{6}");
        assertThat(out.at("/userIdentity/arn").asText()).isEqualTo("arn:aws:iam::123456789012:user/" + out.at("/userIdentity/userName").asText());
        assertThat(out.at("/sessionContext/sessionIssuer/userName").asText()).isEqualTo("AWSServiceRoleForRDS");
        assertThat(out.at("/note").asText()).isEqualTo("created by " + out.at("/userIdentity/userName").asText());
    }

    @Test
    void projectIdsAndEmailLocalPartsAreRewrittenWhereverTheyAppear() {
        JsonNode out = anon.anonymise(json("""
                {"logName":"projects/acme-sample-4821/logs/cloudaudit.googleapis.com%2Factivity",
                 "protoPayload":{"authenticationInfo":{"principalEmail":"janedoe@organization.com"},
                   "resourceName":"projects/_/buckets/usage-logs-test-janedoe",
                   "serviceData":{"member":"projectEditor:acme-sample-4821"}},
                 "resource":{"type":"gcs_bucket","labels":{"project_id":"acme-sample-4821",
                   "bucket_name":"usage-logs-test-janedoe"}}}
                """)).tree();
        String text = SeedJson.compact(out);
        assertThat(text).doesNotContain("acme-sample-4821").doesNotContain("janedoe").doesNotContain("organization.com");
        assertThat(out.at("/resource/labels/project_id").asText()).matches("proj-[0-9a-f]{8}");
        assertThat(out.at("/logName").asText()).startsWith("projects/" + out.at("/resource/labels/project_id").asText() + "/logs/");
        assertThat(out.at("/protoPayload/resourceName").asText()).startsWith("projects/_/buckets/usage-logs-test-user-");
        assertThat(out.at("/protoPayload/authenticationInfo/principalEmail").asText()).matches("user-[0-9a-f]{8}@example\\.org");
        assertThat(out.at("/protoPayload/serviceData/member").asText())
                .isEqualTo("projectEditor:" + out.at("/resource/labels/project_id").asText());
    }

    @Test
    void publicAndPlaceholderProjectsStay() {
        JsonNode out = anon.anonymise(json("""
                {"referencedTables":["projects/bigquery-public-data/datasets/google_trends/tables/top_terms"],
                 "logName":"projects/test-project/logs/cloudaudit.googleapis.com%2Fdata_access",
                 "resourceName":"projects/_/buckets/b"}
                """)).tree();
        assertThat(SeedJson.compact(out)).contains("bigquery-public-data").contains("projects/test-project/").contains("projects/_/buckets/b");
    }

    @Test
    void genericLocalPartsAreNotTreatedAsPersonNames() {
        String out = anon.anonymiseText("the admin contacted support@corp.example.io about the test account");
        assertThat(out).startsWith("the admin contacted user-").endsWith(" about the test account");
    }

    // ------------------------------------------------------------------
    // Azure ids
    // ------------------------------------------------------------------

    @Test
    void azureSubscriptionAndTenantIdsAreRewrittenButPlaceholdersStay() {
        JsonNode out = anon.anonymise(json("""
                {"resourceId":"/SUBSCRIPTIONS/0F8C1D52-77AB-4C3E-9A4D-5B6C7D8E9F01/RESOURCEGROUPS/RG",
                 "tenantId":"a1b2c3d4-e5f6-4789-8abc-def012345678",
                 "iss":"https://sts.windows.net/22222222-2222-2222-2222-222222222222/",
                 "other":"/subscriptions/11111111-1111-1111-1111-111111111111/x",
                 "eventId":"a1b2c3d4-e5f6-4789-8abc-def012345678x"}
                """)).tree();
        assertThat(out.get("resourceId").asText()).doesNotContain("0F8C1D52").matches("/SUBSCRIPTIONS/[0-9A-F-]{36}/RESOURCEGROUPS/RG");
        assertThat(out.get("tenantId").asText()).isNotEqualTo("a1b2c3d4-e5f6-4789-8abc-def012345678")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-fa17[0-9a-f]{8}");
        assertThat(out.get("iss").asText()).isEqualTo("https://sts.windows.net/22222222-2222-2222-2222-222222222222/");
        assertThat(out.get("other").asText()).isEqualTo("/subscriptions/11111111-1111-1111-1111-111111111111/x");
        assertThat(out.get("eventId").asText()).isEqualTo("a1b2c3d4-e5f6-4789-8abc-def012345678x");
    }

    // ------------------------------------------------------------------
    // Organizations, access policies, placeholders (added with the seed import)
    // ------------------------------------------------------------------

    @Test
    void organizationFolderAndAccessPolicyNumbersKeepTheirLengthAndGetTheMarker() {
        String out = anon.anonymiseText("accessPolicies/30507210272/servicePerimeters/p organizations/614830067722 folders/1234567");
        assertThat(out).doesNotContain("30507210272").doesNotContain("614830067722").doesNotContain("1234567 ");
        assertThat(out).contains("accessPolicies/9999").contains("organizations/9999").contains("folders/9999");
        assertThat(out.split(" ")[0]).matches("accessPolicies/9999[0-9]{7}/servicePerimeters/p");
        assertThat(out.split(" ")[1]).matches("organizations/9999[0-9]{8}");
        assertThat(out.split(" ")[2]).matches("folders/9999[0-9]{3}");
        assertThat(anon.anonymiseText(out)).isEqualTo(out);
    }

    @Test
    void thePseudonymOfAnOrganizationNumberIsTheSameEveryTime() {
        assertThat(anon.anonymiseText("organizations/614830067722")).isEqualTo(anon.anonymiseText("organizations/614830067722"));
        assertThat(anon.anonymiseText("organizations/614830067722")).isNotEqualTo(anon.anonymiseText("organizations/614830067723"));
    }

    @Test
    void theLastGroupOfAGuidIsNotAnAccountNumber() {
        String guid = "00000000-0000-0000-0000-000000000001";
        assertThat(anon.anonymiseText("role " + guid + " id")).isEqualTo("role " + guid + " id");
        assertThat(anon.anonymiseText("x 12345678-1234-1234-1234-123456789012 y")).isEqualTo("x 12345678-1234-1234-1234-123456789012 y");
        // a bare twelve digit run next to a dash is still an account number
        assertThat(anon.anonymiseText("a-731926485107")).startsWith("a-9999");
    }

    @Test
    void aPlaceholderProjectInAServiceAccountAddressStaysAndTheNameIsStillPseudonymised() {
        String out = anon.anonymiseText("robot@test-project.iam.gserviceaccount.com");
        assertThat(out).matches("sa-[0-9a-f]{8}@test-project\\.iam\\.gserviceaccount\\.com");
        assertThat(LeakScanner.scanText("t", out)).isEmpty();
    }

    @Test
    void aProjectIdIsAlsoReplacedInItsUnderscoreForm() {
        JsonNode out = anon.anonymise(json("{\"project_id\":\"acme-sample-4821\",\"perimeter\":\"acme_sample_4821\"}")).tree();
        assertThat(out.get("project_id").asText()).matches("proj-[0-9a-f]{8}");
        assertThat(out.get("perimeter").asText()).matches("proj-[0-9a-f]{8}").isNotEqualTo(out.get("project_id").asText());
    }

    // ------------------------------------------------------------------
    // Report and structure
    // ------------------------------------------------------------------

    @Test
    void theReportCountsWhatWasRewritten() {
        Anonymiser.Result r = anon.anonymise(json("{\"a\":\"8.8.8.8 8.8.4.4\",\"b\":\"731926485107\",\"c\":\"10.0.0.1\"}"));
        assertThat(r.replaced()).containsEntry("ipv4", 2).containsEntry("account", 1).doesNotContainKey("email");
    }

    @Test
    void numbersBooleansAndNullsAreNotTouched() {
        JsonNode out = anon.anonymise(json("{\"n\":731926485107,\"b\":true,\"z\":null,\"d\":1.50}")).tree();
        assertThat(SeedJson.compact(out)).isEqualTo("{\"n\":731926485107,\"b\":true,\"z\":null,\"d\":1.50}");
    }
}
