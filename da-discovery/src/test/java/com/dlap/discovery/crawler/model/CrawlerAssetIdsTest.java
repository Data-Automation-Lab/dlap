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

package com.dlap.discovery.crawler.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.assetid.AssetProvider;
import com.dlap.common.assetid.CanonicalAssetId;
import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.aws.AwsProcessingJobResource;
import com.dlap.discovery.crawler.model.aws.EC2Resource;
import com.dlap.discovery.crawler.model.aws.S3Resource;
import com.dlap.discovery.crawler.model.gcp.BigQueryResource;
import com.dlap.discovery.crawler.model.gcp.GcsResource;
import com.dlap.discovery.crawler.model.unity.UnityCatalogResource;

/**
 * Pins how da-discovery turns crawler data into canonical asset ids (ADR-0047, rule R8): the
 * identifier a crawler stores, the hints taken from the connection, what is never guessed, and the
 * re-derivation the crawler-to-spine bridge relies on.
 */
class CrawlerAssetIdsTest {

    private static final String ACCOUNT = "123456789012";
    private static final String PROJECT = "dlap-demo";

    private static ResourceType type(String id, String serviceId, CloudProvider provider) {
        return new ResourceType(id, id, new CloudService(serviceId, serviceId, provider));
    }

    private static S3Resource s3Bucket(String name) {
        return new S3Resource(type("aws_s3_bucket", "s3", CloudProvider.AWS), name, name);
    }

    private static GcsResource gcsBucket(String name) {
        return new GcsResource(type("gcp_gcs_bucket", "gcs", CloudProvider.GCP), name, name);
    }

    // ---- kinds and hints ---------------------------------------------------------------------

    @Test
    void crawlerTypeIdsMapToTheKindsTheyStandFor() {
        assertThat(CrawlerAssetIds.kindOfTypeId("aws_s3_bucket")).contains(AssetKind.S3_BUCKET);
        assertThat(CrawlerAssetIds.kindOfTypeId("GCP_GCS_OBJECT")).contains(AssetKind.GCS_OBJECT);
        assertThat(CrawlerAssetIds.kindOfTypeId("gcp_bigquery_table")).contains(AssetKind.BIGQUERY_TABLE);
        assertThat(CrawlerAssetIds.kindOfTypeId("unity_table")).contains(AssetKind.UNITY_TABLE);
        assertThat(CrawlerAssetIds.kindOfTypeId("databricks_sql_query")).contains(AssetKind.DATABRICKS_JOB);
        assertThat(CrawlerAssetIds.kindOfTypeId("aws_athena_query")).contains(AssetKind.REDSHIFT_QUERY);
    }

    @Test
    void typesThatAreNotAssetKindsHaveNone() {
        // A virtual machine is not a data asset, and Synapse Spark has no canonical scheme (asset-id.md).
        assertThat(CrawlerAssetIds.kindOfTypeId("aws_ec2_instance")).isEmpty();
        assertThat(CrawlerAssetIds.kindOfTypeId("EC2_INSTANCE")).isEmpty();
        assertThat(CrawlerAssetIds.kindOfTypeId("azure_vm_instance")).isEmpty();
        assertThat(CrawlerAssetIds.kindOfTypeId("azure_synapse_spark_job")).isEmpty();
        assertThat(CrawlerAssetIds.kindOfTypeId("oracle_compute_instance")).isEmpty();
        assertThat(CrawlerAssetIds.kindOfTypeId(null)).isEmpty();
        assertThat(CrawlerAssetIds.kindOfTypeId(" ")).isEmpty();
    }

    @Test
    void theConnectionsAccountIdIsTheScopeEachProviderNeeds() {
        assertThat(CrawlerAssetIds.hints(AssetProvider.AWS, AssetKind.RDS_INSTANCE, ACCOUNT, "eu-west-1"))
                .satisfies(h -> {
                    assertThat(h.account()).isEqualTo(ACCOUNT);
                    assertThat(h.region()).isEqualTo("eu-west-1");
                    assertThat(h.kind()).isEqualTo(AssetKind.RDS_INSTANCE);
                });
        assertThat(CrawlerAssetIds.hints(AssetProvider.GCP, null, PROJECT, null).project()).isEqualTo(PROJECT);
        assertThat(CrawlerAssetIds.hints(AssetProvider.AZURE, null, "sub-1", null).subscription()).isEqualTo("sub-1");
        assertThat(CrawlerAssetIds.hints(AssetProvider.UNITY, null, "meta-1", null).metastoreId()).isEqualTo("meta-1");
        assertThat(CrawlerAssetIds.hints(AssetProvider.DATABRICKS, null, "1234567890123456", null).workspaceId())
                .isEqualTo("1234567890123456");
    }

    @Test
    void theProviderFollowsTheKindWhenNoneIsGiven() {
        AssetIdHints h = CrawlerAssetIds.hints(null, AssetKind.GLUE_TABLE, ACCOUNT, "us-east-1");
        assertThat(h.account()).isEqualTo(ACCOUNT);
    }

    // ---- assigning ---------------------------------------------------------------------------

    @Test
    void buildsTheBucketUriFromItsParts() {
        S3Resource bucket = s3Bucket("dlap-demo");

        assertThat(CrawlerAssetIds.assignBuilt(bucket, AssetKind.S3_BUCKET, "dlap-demo")).isPresent();

        assertThat(bucket.getUri()).isEqualTo("s3://dlap-demo");
    }

    @Test
    void anS3BucketKeepsItsCaseBecauseLegacyBucketsMayHaveUpperCaseLetters() {
        S3Resource bucket = s3Bucket("Legacy-Bucket");

        CrawlerAssetIds.assignBuilt(bucket, AssetKind.S3_BUCKET, "Legacy-Bucket");

        assertThat(bucket.getUri()).isEqualTo("s3://Legacy-Bucket");
    }

    @Test
    void aGcsBucketIsLowerCasedAndAnObjectKeepsItsKeyVerbatim() {
        GcsResource bucket = gcsBucket("Demo-Bucket");
        GcsResource object = gcsBucket("ignored");

        CrawlerAssetIds.assignBuilt(bucket, AssetKind.GCS_BUCKET, "Demo-Bucket");
        CrawlerAssetIds.assignBuilt(object, AssetKind.GCS_OBJECT, "demo-bucket", "Reports/2026 Q3/Sales%20Summary.csv");

        assertThat(bucket.getUri()).isEqualTo("gs://demo-bucket");
        assertThat(object.getUri()).isEqualTo("gs://demo-bucket/Reports/2026 Q3/Sales%20Summary.csv");
    }

    @Test
    void aRejectedBuildLeavesTheResourceUntouched() {
        S3Resource bucket = s3Bucket("ab");
        bucket.setUri("keep-me");

        assertThat(CrawlerAssetIds.assignBuilt(bucket, AssetKind.S3_BUCKET, "ab")).isEmpty();

        assertThat(bucket.getUri()).isEqualTo("keep-me");
    }

    @Test
    void aMissingPartIsNeverFilledIn() {
        BigQueryResource table = new BigQueryResource(type("gcp_bigquery_table", "bigquery", CloudProvider.GCP),
                "x", "x");

        // The connection names no project, so the table cannot be placed: nothing is guessed.
        assertThat(CrawlerAssetIds.assignBuilt(table, AssetKind.BIGQUERY_TABLE, null, "sales", "orders")).isEmpty();

        assertThat(table.getUri()).isNull();
    }

    @Test
    void aProviderNativeNameBecomesAnArnWithHintsFromTheConnection() {
        S3Resource db = s3Bucket("unused");

        CanonicalAssetId id = CrawlerAssetIds.assignParsed(db, "Prod-Postgres",
                CrawlerAssetIds.hints(null, AssetKind.RDS_INSTANCE, ACCOUNT, "eu-west-1")).orElseThrow();

        // RDS identifiers are lower-cased; the account and region are part of an ARN.
        assertThat(id.uri()).isEqualTo("arn:aws:rds:eu-west-1:" + ACCOUNT + ":db:prod-postgres");
        assertThat(id.kind()).isEqualTo(AssetKind.RDS_INSTANCE);
        assertThat(db.getUri()).isEqualTo(id.uri());
    }

    @Test
    void theArnPartitionFollowsTheRegion() {
        S3Resource db = s3Bucket("unused");

        CrawlerAssetIds.assignParsed(db, "orders",
                CrawlerAssetIds.hints(null, AssetKind.RDS_INSTANCE, ACCOUNT, "cn-north-1"));

        assertThat(db.getUri()).isEqualTo("arn:aws-cn:rds:cn-north-1:" + ACCOUNT + ":db:orders");
    }

    @Test
    void withoutAnAccountAnArnIsNotBuiltInsteadOfWritingNullIntoIt() {
        S3Resource db = s3Bucket("unused");

        // The legacy code wrote arn:aws:rds:region:null:db:id when the account was missing.
        assertThat(CrawlerAssetIds.assignParsed(db, "orders",
                CrawlerAssetIds.hints(null, AssetKind.RDS_INSTANCE, null, "eu-west-1"))).isEmpty();
        assertThat(CrawlerAssetIds.assignParsed(db, "orders",
                CrawlerAssetIds.hints(null, AssetKind.RDS_INSTANCE, "not-an-account", "eu-west-1"))).isEmpty();

        assertThat(db.getUri()).isNull();
    }

    @Test
    void aKindMismatchIsRejected() {
        S3Resource bucket = s3Bucket("unused");

        // An object URI where a bucket is expected is not a bucket.
        assertThat(CrawlerAssetIds.assignParsed(bucket, "s3://b-name/key",
                AssetIdHints.of(AssetKind.S3_BUCKET))).isEmpty();
        assertThat(bucket.getUri()).isNull();
    }

    @Test
    void aUriTooWideForTheInventoryColumnIsReturnedButNotStored() {
        GcsResource object = gcsBucket("unused");
        String key = "k".repeat(300);

        CanonicalAssetId id = CrawlerAssetIds.assignBuilt(object, AssetKind.GCS_OBJECT, "demo-bucket", key)
                .orElseThrow();

        assertThat(id.uri()).isEqualTo("gs://demo-bucket/" + key);
        assertThat(object.getUri()).isNull();
    }

    // ---- resolving for the bridge ------------------------------------------------------------

    @Test
    void aStoredCanonicalUriResolvesToItself() {
        S3Resource bucket = s3Bucket("dlap-demo");
        bucket.setUri("s3://dlap-demo");

        assertThat(CrawlerAssetIds.resolve(bucket)).map(CanonicalAssetId::uri).contains("s3://dlap-demo");
    }

    @Test
    void aLegacyResourceWithOnlyANativeIdResolvesWithHintsFromItself() {
        GcsResource legacyBucket = gcsBucket("demo-bucket");
        legacyBucket.setProjectId(PROJECT);

        BigQueryResource legacyTable = new BigQueryResource(
                type("gcp_bigquery_table", "bigquery", CloudProvider.GCP), "dlap-demo:sales.orders", "sales.orders");
        legacyTable.setProjectId(PROJECT);

        assertThat(CrawlerAssetIds.resolve(legacyBucket)).map(CanonicalAssetId::uri).contains("gs://demo-bucket");
        assertThat(CrawlerAssetIds.resolve(legacyTable)).map(CanonicalAssetId::uri)
                .contains("bq://dlap-demo/sales/orders");
    }

    @Test
    void aBareObjectKeyIsNeverReadAsBucketAndObject() {
        // The resource id of a GCS object is its bare key, and keys collide across buckets.
        GcsResource object = new GcsResource(type("gcp_gcs_object", "gcs", CloudProvider.GCP),
                "reports/2026/q3.csv", "reports/2026/q3.csv");
        object.setProjectId(PROJECT);

        assertThat(CrawlerAssetIds.resolve(object)).isEmpty();
    }

    @Test
    void anObjectWhoseUriWasNotStoredIsReadFromTheSelfDescribingNameTheCrawlerWrites() {
        GcsResource object = new GcsResource(type("gcp_gcs_object", "gcs", CloudProvider.GCP),
                "reports/2026/q3.csv", "gs://demo-bucket/reports/2026/q3.csv");
        object.setProjectId(PROJECT);

        assertThat(CrawlerAssetIds.resolve(object)).map(CanonicalAssetId::uri)
                .contains("gs://demo-bucket/reports/2026/q3.csv");
    }

    @Test
    void theLegacyBigQueryDatasetIdTheCrawlerWroteIsReadBack() {
        BigQueryResource dataset = new BigQueryResource(
                type("gcp_bigquery_dataset", "bigquery", CloudProvider.GCP),
                "GenericData{classInfo=[datasetId, projectId], {datasetId=sales, projectId=dlap-demo}}", "sales");

        assertThat(CrawlerAssetIds.resolve(dataset)).map(CanonicalAssetId::uri).contains("bq://dlap-demo/sales");
    }

    @Test
    void aUnityTableResolvesOnlyInsideItsMetastore() {
        UnityCatalogResource withMetastore = new UnityCatalogResource(
                type("unity_table", "unity_catalog", CloudProvider.UNITY), "main", "sales", "orders");
        withMetastore.setAccountId("meta-1");
        UnityCatalogResource without = new UnityCatalogResource(
                type("unity_table", "unity_catalog", CloudProvider.UNITY), "main", "sales", "orders");

        assertThat(CrawlerAssetIds.resolve(withMetastore)).map(CanonicalAssetId::uri)
                .contains("uc://meta-1/main/sales/orders");
        assertThat(CrawlerAssetIds.resolve(without)).isEmpty();
    }

    @Test
    void aGluePythonShellJobKeepsItsKindThroughTheArn() {
        AwsProcessingJobResource job = new AwsProcessingJobResource(
                type("aws_glue_job", "glue", CloudProvider.AWS), "py-shell", "py-shell");
        job.setJobType("PYTHON_JOB");
        job.setUri("arn:aws:glue:us-east-1:" + ACCOUNT + ":job/py-shell");

        assertThat(CrawlerAssetIds.kindOf(job)).contains(AssetKind.PYTHON_JOB);
        assertThat(CrawlerAssetIds.resolve(job)).map(CanonicalAssetId::kind).contains(AssetKind.PYTHON_JOB);
    }

    @Test
    void aVirtualMachineResolvesToNothing() {
        EC2Resource vm = new EC2Resource(type("aws_ec2_instance", "ec2", CloudProvider.AWS), "i-0abc123", "web");
        vm.setAccountId(ACCOUNT);
        vm.setRegion("us-east-1");

        assertThat(CrawlerAssetIds.resolve(vm)).isEmpty();
    }

    @Test
    void aRawIdentifierFromAChangeRecordResolvesWithHintsFromTheChange() {
        assertThat(CrawlerAssetIds.resolveRaw("GCP", "gcp_gcs_bucket", "demo-bucket", PROJECT, null))
                .map(CanonicalAssetId::uri).contains("gs://demo-bucket");
        // A self-describing identifier needs no hints at all.
        assertThat(CrawlerAssetIds.resolveRaw(null, null, "s3://dlap-demo", null, null))
                .map(CanonicalAssetId::uri).contains("s3://dlap-demo");
        // A bare name with no provider and no kind is ambiguous: never guessed.
        assertThat(CrawlerAssetIds.resolveRaw(null, null, "demo-bucket", null, null)).isEmpty();
        assertThat(CrawlerAssetIds.resolveRaw("GCP", "gcp_gcs_bucket", "s3://other", PROJECT, null)).isEmpty();
    }

    // ---- sizes -------------------------------------------------------------------------------

    @Test
    void anObservedSizeIsRecordedAndReadBack() {
        GcsResource object = gcsBucket("unused");

        CrawlerAssetIds.recordSize(object, 10_485_760L);

        assertThat(CrawlerAssetIds.observedSizeBytes(object)).contains(10_485_760L);
    }

    @Test
    void zeroIsARealSizeButUnknownIsNotRecordedAsZero() {
        GcsResource empty = gcsBucket("unused");
        GcsResource unknown = gcsBucket("unused");

        CrawlerAssetIds.recordSize(empty, 0L);
        CrawlerAssetIds.recordSize(unknown, null);
        CrawlerAssetIds.recordSize(unknown, -1L);

        assertThat(CrawlerAssetIds.observedSizeBytes(empty)).contains(0L);
        assertThat(CrawlerAssetIds.observedSizeBytes(unknown)).isEmpty();
        assertThat(unknown.getTechnicalMetadata()).doesNotContainKey(CrawlerAssetIds.SIZE_BYTES_KEY);
    }

    @Test
    void aSizeThatIsNotANonNegativeNumberIsIgnored() {
        GcsResource object = gcsBucket("unused");
        CloudResource resource = object;

        resource.getTechnicalMetadata().put(CrawlerAssetIds.SIZE_BYTES_KEY, "lots");
        assertThat(CrawlerAssetIds.observedSizeBytes(resource)).isEmpty();
        resource.getTechnicalMetadata().put(CrawlerAssetIds.SIZE_BYTES_KEY, "-5");
        assertThat(CrawlerAssetIds.observedSizeBytes(resource)).isEmpty();
        resource.getTechnicalMetadata().put(CrawlerAssetIds.SIZE_BYTES_KEY, " 42 ");
        assertThat(CrawlerAssetIds.observedSizeBytes(resource)).contains(42L);
    }
}
