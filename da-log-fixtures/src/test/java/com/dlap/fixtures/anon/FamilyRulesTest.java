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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.fixtures.anon.FamilyRules.Facts;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Known-answer tests for the facts every seed case's expected.json is built from. The records are
 * invented; the expected values are written out by hand from docs/specs/log-event.md, so a mistake in
 * the rules shows up here and not only as a changed fixture.
 */
class FamilyRulesTest {

    private static final String CT = """
            {"eventVersion":"1.08","userIdentity":{"type":"IAMUser","principalId":"AIDAEXAMPLE05MFU5TEUQ",
              "arn":"arn:aws:iam::123456789012:user/user-ab12cd","accountId":"123456789012","userName":"user-ab12cd"},
             "eventTime":"2023-07-10T11:42:03Z","eventSource":"s3.amazonaws.com","eventName":"CreateBucket",
             "awsRegion":"us-east-1","sourceIPAddress":"10.0.0.1","requestParameters":{"bucketName":"b1"},
             "responseElements":null,"requestID":"R1","eventID":"11111111-2222-4333-8444-555555555555",
             "readOnly":false,"eventType":"AwsApiCall","managementEvent":true,"recipientAccountId":"123456789012",
             "eventCategory":"Management","tlsDetails":{"tlsVersion":"TLSv1.3"}}
            """;

    private static JsonNode json(String text) {
        return SeedJson.parse(text);
    }

    // ------------------------------------------------------------------
    // CloudTrail
    // ------------------------------------------------------------------

    @Test
    void cloudTrailBucketCreateByAnIamUser() {
        FamilyRules rules = FamilyRules.of("cloudtrail");
        Facts f = rules.facts(json(CT));
        assertThat(rules.envelope()).isEqualTo("s3-file");
        assertThat(rules.versionOf(json(CT))).isEqualTo("1.08");
        assertThat(f.operation()).isEqualTo("s3.amazonaws.com:CreateBucket");
        assertThat(SeedJson.compact(f.event())).isEqualTo(
                "{\"nativeEventId\":\"11111111-2222-4333-8444-555555555555\",\"eventTime\":\"2023-07-10T11:42:03Z\","
                        + "\"sourceProvider\":\"AWS\",\"sourceService\":\"s3.amazonaws.com\",\"operation\":\"CreateBucket\","
                        + "\"resultStatus\":\"SUCCESS\",\"principal\":{\"id\":\"arn:aws:iam::123456789012:user/user-ab12cd\"},"
                        + "\"eventCategory\":\"EVENT_CATEGORY_MANAGEMENT\",\"sourceFormat\":\"AWS_CLOUDTRAIL/s3-file\","
                        + "\"sourceSchemaVersion\":\"1.08\"}");
        assertThat(f.variants()).containsExactly("uid.iam-user", "account.recipient-account-id", "field.tls-details");
    }

    @Test
    void cloudTrailFailureAssumedRoleAndSharedEvent() {
        JsonNode r = json("""
                {"eventVersion":"1.05","userIdentity":{"type":"AssumedRole","arn":"arn:aws:sts::123456789012:assumed-role/Admin/s1",
                  "sessionContext":{"sessionIssuer":{"type":"Role","arn":"arn:aws:iam::123456789012:role/Admin","userName":"Admin"}}},
                 "eventTime":"2020-02-14T00:43:54Z","eventSource":"s3.amazonaws.com","eventName":"PutBucketPolicy",
                 "errorCode":"AccessDenied","eventID":"e-1","sharedEventID":"s-1","recipientAccountId":"123456789012"}
                """);
        Facts f = FamilyRules.of("cloudtrail").facts(r);
        assertThat(f.event().get("resultStatus").asText()).isEqualTo("FAILURE");
        assertThat(f.event().at("/principal/id").asText()).isEqualTo("arn:aws:iam::123456789012:role/Admin");
        assertThat(f.event().get("eventCategory").asText()).isEqualTo("EVENT_CATEGORY_PERMISSION");
        assertThat(f.event().get("sharedEventId").asText()).isEqualTo("s-1");
        assertThat(f.variants()).contains("uid.assumed-role", "result.error-code-failure", "s3.cross-account-shared-event",
                "field.read-only-management-absent", "field.event-category-absent", "account.recipient-account-id");
    }

    @Test
    void cloudTrailServiceEventsHaveNoIdentityTypeOnlyInvokedBy() {
        FamilyRules rules = FamilyRules.of("cloudtrail");
        JsonNode r = json(CT.replace("\"eventCategory\":\"Management\",\"tlsDetails\":{\"tlsVersion\":\"TLSv1.3\"}",
                        "\"eventCategory\":\"Management\"")
                .replaceAll("(?s)\"userIdentity\":\\{.*?\"userName\":\"user-ab12cd\"\\}",
                        "\"userIdentity\":{\"accountId\":\"123456789012\",\"invokedBy\":\"ec2.amazonaws.com\"}")
                .replace("\"eventType\":\"AwsApiCall\"", "\"eventType\":\"AwsServiceEvent\""));
        Facts f = rules.facts(r);
        assertThat(f.event().has("principal")).as("no stable principal without a type").isFalse();
        assertThat(f.variants()).containsExactly("uid.invoked-by", "account.recipient-account-id");
    }

    @Test
    void invokedByDoesNotCountWhenTheRecordHasTlsDetailsAndTheHiddenNameIsAVariant() {
        FamilyRules rules = FamilyRules.of("cloudtrail");
        JsonNode withTls = json(CT.replace("\"userName\":\"user-ab12cd\"}",
                "\"userName\":\"HIDDEN_DUE_TO_SECURITY_REASONS\",\"invokedBy\":\"signin.amazonaws.com\"}"));
        assertThat(rules.facts(withTls).variants()).contains("uid.hidden-user-name").doesNotContain("uid.invoked-by");
    }

    @Test
    void aGzipTrailFileIsAVariantOfItsOwn() {
        FamilyRules rules = FamilyRules.of("cloudtrail");
        assertThat(rules.fileVariants(true)).containsExactly("file.gzip-records");
        assertThat(rules.fileVariants(false)).isEmpty();
        assertThat(FamilyRules.of("gcpaudit").fileVariants(true)).isEmpty();
    }

    @Test
    void cloudTrailRecordsNeedTheFieldsTheNativeKeyAndTimeNeed() {
        FamilyRules rules = FamilyRules.of("cloudtrail");
        assertThatThrownBy(() -> rules.facts(json(CT.replace("\"eventID\":\"11111111-2222-4333-8444-555555555555\",", ""))))
                .hasMessageContaining("eventID");
        assertThatThrownBy(() -> rules.facts(json(CT.replace("\"eventTime\"", "\"eventTyme\""))))
                .hasMessageContaining("eventTime");
        assertThatThrownBy(() -> rules.facts(json(CT.replace("\"type\":\"IAMUser\",", ""))))
                .hasMessageContaining("userIdentity.type");
        assertThatThrownBy(() -> rules.facts(json(CT.replace("2023-07-10T11:42:03Z", "yesterday"))))
                .hasMessageContaining("ISO-8601");
    }

    @Test
    void cloudTrailWrapsRecordsTheWayTheS3FileDeliversThem() {
        FamilyRules rules = FamilyRules.of("cloudtrail");
        JsonNode doc = rules.wrap(List.of(json(CT), json(CT)));
        assertThat(doc.get("Records")).hasSize(2);
        assertThat(rules.unwrap(doc)).hasSize(2);
    }

    // ------------------------------------------------------------------
    // GCP Cloud Audit
    // ------------------------------------------------------------------

    private static final String GCP = """
            {"protoPayload":{"@type":"type.googleapis.com/google.cloud.audit.AuditLog","status":{},
               "authenticationInfo":{"principalEmail":"user-0a1b2c3d@example.org"},
               "serviceName":"storage.googleapis.com","methodName":"storage.objects.get",
               "authorizationInfo":[{"resource":"projects/_/buckets/b/objects/o","permission":"storage.objects.get","granted":true}],
               "resourceName":"projects/_/buckets/b/objects/o"},
             "insertId":"hhu2r3dw556","resource":{"type":"gcs_bucket","labels":{"bucket_name":"b","project_id":"proj-0a1b2c3d"}},
             "timestamp":"2021-09-13T03:03:09.008513033Z","severity":"INFO",
             "logName":"projects/proj-0a1b2c3d/logs/cloudaudit.googleapis.com%2Fdata_access",
             "receiveTimestamp":"2021-09-13T03:03:09.758030291Z"}
            """;

    @Test
    void gcpObjectReadUsesTheDocumentedNativeKey() {
        FamilyRules rules = FamilyRules.of("gcpaudit");
        JsonNode r = json(GCP);
        Facts f = rules.facts(r);
        assertThat(rules.versionOf(r)).isEqualTo("auditlog-2021");
        assertThat(f.operation()).isEqualTo("storage.googleapis.com:storage.objects.get");
        assertThat(f.event().get("nativeEventId").asText()).isEqualTo(
                "projects/proj-0a1b2c3d/logs/cloudaudit.googleapis.com%2Fdata_access|2021-09-13T03:03:09.008513033Z|hhu2r3dw556");
        assertThat(f.event().get("eventTime").asText()).isEqualTo("2021-09-13T03:03:09.008513033Z");
        assertThat(f.event().get("observedTime").asText()).isEqualTo("2021-09-13T03:03:09.758030291Z");
        assertThat(f.event().get("resultStatus").asText()).isEqualTo("SUCCESS");
        assertThat(f.event().get("eventCategory").asText()).isEqualTo("EVENT_CATEGORY_DATA_READ");
        assertThat(f.event().get("sourceSchemaVersion").asText()).isEqualTo("auditlog");
        assertThat(f.event().get("sourceFormat").asText()).isEqualTo("GCP_CLOUD_AUDIT/json");
        assertThat(f.variants()).containsExactly("log.data-access", "actor.principal-email", "gcs.object-resource-name",
                "gcs.resource-labels");
    }

    @Test
    void gcpTimestampsUseTheSameZeroThreeSixOrNineDigitsAsTheProtobufJsonMapping() {
        FamilyRules rules = FamilyRules.of("gcpaudit");
        String whole = GCP.replace("2021-09-13T03:03:09.008513033Z", "2021-09-13T03:03:09Z");
        String millis = GCP.replace("2021-09-13T03:03:09.008513033Z", "2021-09-13T03:03:09.120Z");
        String micros = GCP.replace("2021-09-13T03:03:09.008513033Z", "2021-09-13T03:03:09.276607Z");
        assertThat(rules.facts(json(whole)).event().get("eventTime").asText()).isEqualTo("2021-09-13T03:03:09Z");
        assertThat(rules.facts(json(millis)).event().get("eventTime").asText()).isEqualTo("2021-09-13T03:03:09.120Z");
        assertThat(rules.facts(json(micros)).event().get("eventTime").asText()).isEqualTo("2021-09-13T03:03:09.276607Z");
    }

    @Test
    void gcpDeniedAndNonZeroStatusAreFailures() {
        FamilyRules rules = FamilyRules.of("gcpaudit");
        Facts denied = rules.facts(json(GCP.replace("\"granted\":true", "\"granted\":false")));
        assertThat(denied.event().get("resultStatus").asText()).isEqualTo("FAILURE");
        assertThat(denied.variants()).contains("status.granted-false");
        Facts coded = rules.facts(json(GCP.replace("\"status\":{}", "\"status\":{\"code\":7}")));
        assertThat(coded.event().get("resultStatus").asText()).isEqualTo("FAILURE");
        assertThat(coded.variants()).contains("status.nonzero-code");
    }

    @Test
    void gcpLegacyBigQueryIsRecognisedByItsServiceData() {
        JsonNode r = json("""
                {"insertId":"i","logName":"projects/test-project/logs/cloudaudit.googleapis.com%2Fdata_access",
                 "protoPayload":{"serviceName":"bigquery.googleapis.com","methodName":"jobservice.jobcompleted",
                   "serviceData":{"@type":"type.googleapis.com/google.cloud.bigquery.logging.v1.AuditData",
                     "jobCompletedEvent":{"job":{"jobStatistics":{"referencedTables":[{"tableId":"t"}]}}}},
                   "status":{}},
                 "resource":{"type":"bigquery_resource"},"timestamp":"2021-11-25T21:56:00.276607Z"}
                """);
        FamilyRules rules = FamilyRules.of("gcpaudit");
        assertThat(rules.versionOf(r)).isEqualTo("bq-auditdata-legacy");
        Facts f = rules.facts(r);
        assertThat(f.event().get("sourceSchemaVersion").asText()).isEqualTo("bq-auditdata-legacy");
        assertThat(f.variants()).contains("log.data-access", "entry.protopayload-without-type", "bq1.referenced-tables-objects",
                "bq1.resource-type");
        assertThat(f.event().has("eventCategory")).isFalse();
    }

    @Test
    void gcpNeedsOneEntryAndItsKeyFields() {
        FamilyRules rules = FamilyRules.of("gcpaudit");
        assertThatThrownBy(() -> rules.wrap(List.of(json(GCP), json(GCP)))).hasMessageContaining("exactly one");
        assertThatThrownBy(() -> rules.facts(json(GCP.replace("\"insertId\":\"hhu2r3dw556\",", "")))).hasMessageContaining("insertId");
    }

    // ------------------------------------------------------------------
    // Azure
    // ------------------------------------------------------------------

    @Test
    void azureStorageResourceLogCarriesTheStatusAndTheSchemaVersion() {
        JsonNode r = json("""
                {"time":"2024-12-19T19:15:07.295Z","resourceId":"/subscriptions/12345678-1234-1234-1234-123456789abc/x",
                 "category":"StorageRead","operationName":"GetBlobMetadata","operationVersion":"2024-11-04",
                 "schemaVersion":"1.0","statusCode":409,"identity":{"type":"SAS","tokenHash":"key1(EXAMPLE_HASH)"},
                 "properties":{"objectKey":"/acct/c/o.txt"}}
                """);
        FamilyRules rules = FamilyRules.of("azstoragelog");
        Facts f = rules.facts(r);
        assertThat(rules.versionOf(r)).isEqualTo("1.0");
        assertThat(f.operation()).isEqualTo("StorageBlobLogs:GetBlobMetadata");
        assertThat(f.event().get("resultStatus").asText()).isEqualTo("FAILURE");
        assertThat(f.event().get("eventTime").asText()).isEqualTo("2024-12-19T19:15:07.295Z");
        assertThat(f.event().get("sourceSchemaVersion").asText()).isEqualTo("1.0");
        assertThat(f.variants()).containsExactly("envelope.records-object", "status.non-2xx-failure", "actor.sas-unattributed",
                "object.key-leading-slash", "version.operation-version-values");
        assertThat(rules.wrap(List.of(r)).get("records")).hasSize(1);
    }

    @Test
    void azureActivityOperationIdsAreProviderAndLowerCaseName() {
        JsonNode r = json("""
                {"time":"2025-04-15T10:16:32.9873441Z","resourceId":"/SUBSCRIPTIONS/11111111-1111-1111-1111-111111111111/X",
                 "operationName":"MICROSOFT.INSIGHTS/DIAGNOSTICSETTINGS/WRITE","category":"Administrative","resultType":"Start",
                 "Stamp":"FDWeb"}
                """);
        FamilyRules rules = FamilyRules.of("azactivity");
        Facts f = rules.facts(r);
        assertThat(f.operation()).isEqualTo("microsoft.insights:microsoft.insights/diagnosticsettings/write");
        assertThat(f.event().get("eventTime").asText()).isEqualTo("2025-04-15T10:16:32.987344100Z");
        assertThat(f.variants()).containsExactly("envelope.records-object", "category.administrative", "result.start",
                "case.operation-name-upper", "extra.top-level-keys");
        assertThatThrownBy(() -> rules.facts(json(SeedJson.compact(r).replace("MICROSOFT.INSIGHTS/DIAGNOSTICSETTINGS/WRITE",
                "Add member to role"))))
                .hasMessageContaining("whitespace");
    }

    // ------------------------------------------------------------------
    // Databricks
    // ------------------------------------------------------------------

    @Test
    void databricksDeliveredRecordUsesEpochMillisAndTheRequestKey() {
        JsonNode r = json("""
                {"version":"2.0","auditLevel":"WORKSPACE_LEVEL","timestamp":1234567890000,"workspaceId":"1234567890123456",
                 "sourceIPAddress":"198.51.100.1","sessionId":"s","requestId":"req-123","serviceName":"accounts",
                 "actionName":"login","userIdentity":{"email":"user@example.com"},"response":{"statusCode":200}}
                """);
        FamilyRules rules = FamilyRules.of("dbxaudit");
        Facts f = rules.facts(r);
        assertThat(rules.versionOf(r)).isEqualTo("2.0");
        assertThat(rules.inputExtension()).isEqualTo("jsonl");
        assertThat(f.operation()).isEqualTo("accounts:login");
        assertThat(f.event().get("nativeEventId").asText()).isEqualTo("DATABRICKS_AUDIT|req-123|1234567890000|login");
        assertThat(f.event().get("eventTime").asText()).isEqualTo("2009-02-13T23:31:30Z");
        assertThat(f.event().get("sourceProvider").asText()).isEqualTo("DATABRICKS");
        assertThat(f.event().get("resultStatus").asText()).isEqualTo("SUCCESS");
        assertThat(f.variants()).containsExactly("time.epoch-milliseconds", "actor.email", "scope.workspace-level");
        assertThatThrownBy(() -> rules.facts(json(SeedJson.compact(r).replace("\"requestId\":\"req-123\",", ""))))
                .hasMessageContaining("requestId");
    }

    @Test
    void unknownFamiliesAreRefused() {
        assertThatThrownBy(() -> FamilyRules.of("nope")).isInstanceOf(IllegalArgumentException.class);
    }
}
