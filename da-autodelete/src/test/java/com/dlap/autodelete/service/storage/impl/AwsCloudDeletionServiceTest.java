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

package com.dlap.autodelete.service.storage.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.dlap.autodelete.service.storage.DeletionResult;
import com.dlap.autodelete.service.storage.DeletionStatus;
import com.dlap.autodelete.service.storage.DeletionType;
import com.dlap.autodelete.service.storage.impl.AwsCloudDeletionService.AwsResourceRef;

/**
 * Parsing and fail-closed handling for AWS deletion locations.
 *
 * <p>A destructive RDS/DynamoDB delete must carry an explicit region. Region-less forms
 * are rejected rather than silently defaulting to {@code us-east-1} (which would delete -
 * or falsely report deleted - the wrong region's resource).
 *
 * <p>Locations also pass through {@code AssetIdNormalizer} (plan rule R8,
 * {@code docs/specs/asset-id.md}): every alias of one S3 object, RDS instance or DynamoDB table
 * is accepted, and anything that is not exactly one such resource is refused.
 */
class AwsCloudDeletionServiceTest {

    private static final String ACCOUNT = "123456789012";

    // ------------------------------------------------------------------
    // Region handling (unchanged behaviour)
    // ------------------------------------------------------------------

    @Test
    void rejectsRegionlessRdsUri() {
        assertNull(AwsResourceRef.parse("rds://my-db-instance"),
            "rds:// without a region must be rejected, not defaulted");
    }

    @Test
    void rejectsRegionlessDynamoUri() {
        assertNull(AwsResourceRef.parse("dynamodb://my-table"),
            "dynamodb:// without a region must be rejected, not defaulted");
    }

    @Test
    void acceptsRdsUriWithExplicitRegion() {
        AwsResourceRef ref = AwsResourceRef.parse("rds://eu-west-1/my-db-instance");
        assertNotNull(ref);
        assertEquals(AwsResourceRef.Kind.RDS_DB_INSTANCE, ref.kind());
        assertEquals("eu-west-1", ref.first());
        assertEquals("my-db-instance", ref.second());
        assertNull(ref.account(), "the short form carries no account");
    }

    @Test
    void acceptsDynamoUriWithExplicitRegion() {
        AwsResourceRef ref = AwsResourceRef.parse("dynamodb://ap-south-1/my-table");
        assertNotNull(ref);
        assertEquals(AwsResourceRef.Kind.DYNAMODB_TABLE, ref.kind());
        assertEquals("ap-south-1", ref.first());
        assertEquals("my-table", ref.second());
        assertNull(ref.account(), "the short form carries no account");
    }

    @Test
    void parsesS3ObjectUri() {
        AwsResourceRef ref = AwsResourceRef.parse("s3://finance-reports/q2/report.csv");
        assertNotNull(ref);
        assertEquals(AwsResourceRef.Kind.S3_OBJECT, ref.kind());
        assertEquals("finance-reports", ref.first());
        assertEquals("q2/report.csv", ref.second());
        assertNull(ref.account());
    }

    @Test
    void deleteDataFailsClosedOnRegionlessRdsUri() {
        // mock-mode true so no real SDK is touched; the region-less URI is rejected during
        // parse (before the mock short-circuit), so the delete fails closed either way.
        AwsCloudDeletionService svc = new AwsCloudDeletionService(true);
        DeletionResult result = svc.deleteData(
            UUID.randomUUID(), "rds://my-db-instance", DeletionType.HARD, Map.of());
        assertEquals(DeletionStatus.FAILED, result.getStatus(),
            "a region-less destructive URI must not be executed");
    }

    // ------------------------------------------------------------------
    // Alias convergence
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "s3://my-bucket/dir/file.csv",
            "s3a://my-bucket/dir/file.csv",
            "s3n://my-bucket/dir/file.csv",
            "S3://my-bucket/dir/file.csv",
            "arn:aws:s3:::my-bucket/dir/file.csv",
            "https://my-bucket.s3.amazonaws.com/dir/file.csv",
            "https://my-bucket.s3.eu-west-1.amazonaws.com/dir/file.csv",
            "https://s3.amazonaws.com/my-bucket/dir/file.csv",
            "https://s3.eu-west-1.amazonaws.com/my-bucket/dir/file.csv"})
    void everyAliasOfAnS3ObjectParsesToTheSameReference(String alias) {
        AwsResourceRef ref = AwsResourceRef.parse(alias);

        assertNotNull(ref, alias);
        assertEquals(AwsResourceRef.Kind.S3_OBJECT, ref.kind());
        assertEquals("my-bucket", ref.first());
        assertEquals("dir/file.csv", ref.second());
        assertNull(ref.account());
    }

    @Test
    void anS3KeyKeepsItsCaseSpacesAndSpecialCharacters() {
        AwsResourceRef ref = AwsResourceRef.parse("s3://Legacy-Bucket/Dir/My File #1 (final).csv");

        assertNotNull(ref);
        assertEquals("Legacy-Bucket", ref.first(), "bucket case is kept as given");
        assertEquals("Dir/My File #1 (final).csv", ref.second());
    }

    @Test
    void legacyS3FormsWithRealisticNamesStillParse() {
        AwsResourceRef dotted = AwsResourceRef.parse("s3://logs.example.com/2024/01/01/access.log.gz");
        assertNotNull(dotted);
        assertEquals("logs.example.com", dotted.first());
        assertEquals("2024/01/01/access.log.gz", dotted.second());

        AwsResourceRef deep = AwsResourceRef.parse("s3://finance-reports-eu-west-1/q2/2024/Report (v2).xlsx");
        assertNotNull(deep);
        assertEquals("q2/2024/Report (v2).xlsx", deep.second());
    }

    @Test
    void aQuestionMarkInAnS3KeyIsPartOfTheKey() {
        AwsResourceRef ref = AwsResourceRef.parse("s3://my-bucket/what?.csv");

        assertNotNull(ref);
        assertEquals("what?.csv", ref.second());
    }

    @Test
    void anRdsArnParsesToRegionIdAndAccount() {
        AwsResourceRef ref = AwsResourceRef.parse("arn:aws:rds:eu-west-1:" + ACCOUNT + ":db:Prod-DB");

        assertNotNull(ref);
        assertEquals(AwsResourceRef.Kind.RDS_DB_INSTANCE, ref.kind());
        assertEquals("eu-west-1", ref.first());
        assertEquals("prod-db", ref.second(), "RDS identifiers are stored in lower case");
        assertEquals(ACCOUNT, ref.account());
    }

    @Test
    void aDynamoDbArnParsesToRegionTableAndAccount() {
        AwsResourceRef ref = AwsResourceRef.parse("arn:aws:dynamodb:ap-south-1:" + ACCOUNT + ":table/Orders");

        assertNotNull(ref);
        assertEquals(AwsResourceRef.Kind.DYNAMODB_TABLE, ref.kind());
        assertEquals("ap-south-1", ref.first());
        assertEquals("Orders", ref.second(), "DynamoDB table names are case-sensitive");
        assertEquals(ACCOUNT, ref.account());
    }

    // ------------------------------------------------------------------
    // Fail closed: what is not exactly one deletable resource is refused
    // ------------------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "  ",
            "my-bucket/dir/file.csv",
            "my-bucket",
            "prod-db",
            "s3://my-bucket",
            "s3://my-bucket/",
            "arn:aws:s3:::my-bucket",
            "s3://",
            "s3:///key",
            "https://my-bucket.s3.amazonaws.com",
            "/local/path/file.csv"})
    void aBucketOrABareOrMalformedNameIsRefused(String location) {
        assertNull(AwsResourceRef.parse(location), "[" + location + "] must not be a delete target");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "arn:aws:glue:eu-west-1:123456789012:table/sales/orders",
            "arn:aws:glue:eu-west-1:123456789012:database/sales",
            "arn:aws:glue:eu-west-1:123456789012:job/etl",
            "arn:aws:redshift:eu-west-1:123456789012:cluster:warehouse",
            "arn:aws:iam::123456789012:user/alice",
            "arn:aws:kms:eu-west-1:123456789012:key/1234",
            "athena://eu-west-1/123456789012/0a1b2c3d-0000-0000-0000-000000000000"})
    void anAwsResourceThatIsNotADeleteTargetIsRefused(String location) {
        assertNull(AwsResourceRef.parse(location), "[" + location + "] must not be a delete target");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "arn:aws:rds:eu-west-1:null:db:prod-db",
            "arn:aws:rds:eu-west-1:12345:db:prod-db",
            "arn:aws:rds::123456789012:db:prod-db",
            "arn:aws:dynamodb:eu-west-1:123456789012:table/",
            "arn:aws:dynamodb:eu-west-1:123456789012:table/orders/index/by-date"})
    void aMalformedArnIsRefused(String location) {
        assertNull(AwsResourceRef.parse(location), "[" + location + "] must not be a delete target");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "gs://my-bucket/dir/file.csv",
            "bq://proj/dataset/table",
            "az://acct/cont/blob",
            "azure://acct/cont/blob",
            "uc://metastore-1/main/sales/orders"})
    void aLocationOfAnotherCloudIsRefused(String location) {
        assertNull(AwsResourceRef.parse(location), "[" + location + "] must not be a delete target");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://my-bucket.s3.amazonaws.com/dir/file.csv?versionId=3sL4kqtJlcpXroDTDmJ",
            "https://s3.amazonaws.com/my-bucket/dir/file.csv?versionId=abc",
            "https://s3.amazonaws.com/my-bucket/dir/file.csv#frag",
            "https://my-bucket.s3.amazonaws.com/dir/file.csv?X-Amz-Signature=SECRET",
            "\"https://my-bucket.s3.amazonaws.com/file.csv?versionId=abc\""})
    void aUrlThatCanSelectAVersionIsRefused(String location) {
        // The normalizer drops the query; deleting the whole object instead of one version would
        // destroy more than was asked for.
        assertNull(AwsResourceRef.parse(location));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "s3://my-bucket/dir/file.csv ",
            "s3://my-bucket/ dir/file.csv",
            "s3://my-bucket/dir/file.csv\t"})
    void aKeyWithLeadingOrTrailingWhitespaceIsRefused(String location) {
        // Trimming would delete another object; keeping it would miss and S3 answers 204 for a
        // missing key, which reports a false "deleted". Never guess.
        assertNull(AwsResourceRef.parse(location));
    }

    @Test
    void trailingLineBreaksAreNotPartOfTheKey() {
        AwsResourceRef ref = AwsResourceRef.parse("s3://my-bucket/dir/file.csv\r\n");

        assertNotNull(ref);
        assertEquals("dir/file.csv", ref.second());
    }

    // ------------------------------------------------------------------
    // ARN account check
    // ------------------------------------------------------------------

    @Test
    void anArnTargetIsRefusedWhenNoAccountIsAllowListed() {
        AwsCloudDeletionService svc = new AwsCloudDeletionService(true);

        DeletionResult rds = svc.deleteData(UUID.randomUUID(),
            "arn:aws:rds:eu-west-1:" + ACCOUNT + ":db:prod-db", DeletionType.HARD, Map.of());
        DeletionResult dynamo = svc.deleteData(UUID.randomUUID(),
            "arn:aws:dynamodb:eu-west-1:" + ACCOUNT + ":table/orders", DeletionType.HARD, Map.of());

        assertEquals(DeletionStatus.FAILED, rds.getStatus());
        assertThat(rds.getErrorMessage()).contains("allowed-account-ids");
        assertEquals(DeletionStatus.FAILED, dynamo.getStatus());
    }

    @Test
    void anArnTargetIsRefusedWhenItsAccountIsNotAllowListed() {
        // The SDK acts in the account of the ambient credentials and never sees the account in the
        // ARN, so an ARN of another account would delete a same-named resource in the wrong one.
        AwsCloudDeletionService svc = new AwsCloudDeletionService(true, "210987654321");

        DeletionResult result = svc.deleteData(UUID.randomUUID(),
            "arn:aws:rds:eu-west-1:" + ACCOUNT + ":db:prod-db", DeletionType.HARD, Map.of());

        assertEquals(DeletionStatus.FAILED, result.getStatus());
        assertThat(result.getErrorMessage()).contains(ACCOUNT);
    }

    @Test
    void anArnTargetIsAcceptedWhenItsAccountIsAllowListed() {
        // mock-mode: the parse and the account check run, the SDK is not touched.
        AwsCloudDeletionService svc = new AwsCloudDeletionService(true, "210987654321, " + ACCOUNT);

        assertEquals(DeletionStatus.COMPLETED, svc.deleteData(UUID.randomUUID(),
            "arn:aws:rds:eu-west-1:" + ACCOUNT + ":db:prod-db", DeletionType.HARD, Map.of()).getStatus());
        assertEquals(DeletionStatus.COMPLETED, svc.deleteData(UUID.randomUUID(),
            "arn:aws:dynamodb:eu-west-1:" + ACCOUNT + ":table/orders", DeletionType.HARD, Map.of()).getStatus());
    }

    @Test
    void anAllowListEntryThatIsNotATwelveDigitAccountIsIgnored() {
        AwsCloudDeletionService svc = new AwsCloudDeletionService(true, "prod, 12345, *");

        DeletionResult result = svc.deleteData(UUID.randomUUID(),
            "arn:aws:rds:eu-west-1:" + ACCOUNT + ":db:prod-db", DeletionType.HARD, Map.of());

        assertEquals(DeletionStatus.FAILED, result.getStatus());
    }

    @Test
    void theShortFormsAndS3NeedNoAllowList() {
        AwsCloudDeletionService svc = new AwsCloudDeletionService(true);

        assertEquals(DeletionStatus.COMPLETED, svc.deleteData(UUID.randomUUID(),
            "rds://eu-west-1/prod-db", DeletionType.HARD, Map.of()).getStatus());
        assertEquals(DeletionStatus.COMPLETED, svc.deleteData(UUID.randomUUID(),
            "dynamodb://eu-west-1/orders", DeletionType.HARD, Map.of()).getStatus());
        assertEquals(DeletionStatus.COMPLETED, svc.deleteData(UUID.randomUUID(),
            "arn:aws:s3:::my-bucket/dir/file.csv", DeletionType.HARD, Map.of()).getStatus());
    }

    // ------------------------------------------------------------------
    // deleteData: refusals are FAILED results that never echo a signature
    // ------------------------------------------------------------------

    @Test
    void deleteDataFailsClosedOnAVersionSelectingUrlAndNeverEchoesTheQuery() {
        AwsCloudDeletionService svc = new AwsCloudDeletionService(true);

        DeletionResult result = svc.deleteData(UUID.randomUUID(),
            "https://my-bucket.s3.amazonaws.com/file.csv?versionId=abc&X-Amz-Signature=SECRET",
            DeletionType.HARD, Map.of());

        assertEquals(DeletionStatus.FAILED, result.getStatus());
        assertThat(result.getErrorMessage())
            .contains("https://my-bucket.s3.amazonaws.com/file.csv")
            .doesNotContain("SECRET")
            .doesNotContain("versionId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"s3://my-bucket", "gs://my-bucket/file.csv", "prod-db", "arn:aws:glue:eu-west-1:123456789012:table/a/b"})
    void deleteDataFailsClosedOnAnythingThatIsNotADeleteTarget(String location) {
        AwsCloudDeletionService svc = new AwsCloudDeletionService(true);

        DeletionResult result = svc.deleteData(UUID.randomUUID(), location, DeletionType.HARD, Map.of());

        assertEquals(DeletionStatus.FAILED, result.getStatus());
    }

    @Test
    void deleteDataAcceptsEveryAliasOfAnS3Object() {
        AwsCloudDeletionService svc = new AwsCloudDeletionService(true);

        for (String alias : new String[] {
                "s3://my-bucket/dir/file.csv",
                "arn:aws:s3:::my-bucket/dir/file.csv",
                "https://my-bucket.s3.eu-west-1.amazonaws.com/dir/file.csv"}) {
            assertEquals(DeletionStatus.COMPLETED,
                svc.deleteData(UUID.randomUUID(), alias, DeletionType.HARD, Map.of()).getStatus(), alias);
        }
    }
}
