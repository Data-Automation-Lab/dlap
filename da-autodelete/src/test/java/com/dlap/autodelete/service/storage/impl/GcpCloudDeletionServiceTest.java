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
import com.dlap.autodelete.service.storage.impl.GcpCloudDeletionService.GcpResourceRef;

/**
 * Parsing and fail-closed handling for GCP deletion locations.
 *
 * <p>Locations pass through {@code AssetIdNormalizer} (plan rule R8,
 * {@code docs/specs/asset-id.md}): every alias of one GCS object or BigQuery table is accepted,
 * and anything that is not exactly one object or one whole table is refused, because reducing a
 * partition, snapshot, dataset or bucket to a table or object would change what is deleted.
 */
class GcpCloudDeletionServiceTest {

    // ------------------------------------------------------------------
    // Alias convergence
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "gs://my-bucket/dir/file.csv",
            "GS://My-Bucket/dir/file.csv",
            "projects/_/buckets/my-bucket/objects/dir/file.csv",
            "//storage.googleapis.com/projects/_/buckets/my-bucket/objects/dir/file.csv",
            "https://storage.googleapis.com/my-bucket/dir/file.csv",
            "https://my-bucket.storage.googleapis.com/dir/file.csv",
            "https://www.googleapis.com/storage/v1/b/my-bucket/o/dir%2Ffile.csv"})
    void everyAliasOfAGcsObjectParsesToTheSameReference(String alias) {
        GcpResourceRef ref = GcpResourceRef.parse(alias);

        assertNotNull(ref, alias);
        assertEquals(GcpResourceRef.Kind.GCS_OBJECT, ref.kind());
        assertEquals("my-bucket", ref.first());
        assertEquals("dir/file.csv", ref.second());
        assertNull(ref.third());
    }

    @Test
    void aGcsObjectNameKeepsItsCaseSpacesAndSpecialCharacters() {
        GcpResourceRef ref = GcpResourceRef.parse("gs://my-bucket/Dir/My File #1 (final).csv");

        assertNotNull(ref);
        assertEquals("Dir/My File #1 (final).csv", ref.second());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "bq://my-proj/sales/orders",
            "bq://my-proj.sales.orders",
            "bq://my-proj/sales.orders",
            "my-proj.sales.orders",
            "my-proj:sales.orders",
            "`my-proj.sales.orders`",
            "projects/my-proj/datasets/sales/tables/orders",
            "//bigquery.googleapis.com/projects/my-proj/datasets/sales/tables/orders"})
    void everyAliasOfABigQueryTableParsesToTheSameReference(String alias) {
        GcpResourceRef ref = GcpResourceRef.parse(alias);

        assertNotNull(ref, alias);
        assertEquals(GcpResourceRef.Kind.BIGQUERY_TABLE, ref.kind());
        assertEquals("my-proj", ref.first());
        assertEquals("sales", ref.second());
        assertEquals("orders", ref.third());
    }

    @Test
    void aDomainScopedProjectIsKeptWhole() {
        GcpResourceRef ref = GcpResourceRef.parse("example.com:my-proj.sales.orders");

        assertNotNull(ref);
        assertEquals("example.com:my-proj", ref.first());
        assertEquals("sales", ref.second());
        assertEquals("orders", ref.third());
    }

    @Test
    void legacyFormsWithRealisticNamesStillParse() {
        // Forms the retire path accepted before the normalizer must keep parsing with names that
        // occur in practice: domain-named and underscored buckets, underscored BigQuery names.
        GcpResourceRef dotted = GcpResourceRef.parse("gs://data.example.com/exports/2024/part-0001.csv");
        assertNotNull(dotted);
        assertEquals("data.example.com", dotted.first());

        GcpResourceRef underscored = GcpResourceRef.parse("gs://raw_events_eu/2024/01/x.json");
        assertNotNull(underscored);
        assertEquals("raw_events_eu", underscored.first());

        GcpResourceRef table = GcpResourceRef.parse("bq://my-proj/my_dataset/Orders_2024-v2");
        assertNotNull(table);
        assertEquals("my_dataset", table.second());
        assertEquals("Orders_2024-v2", table.third(), "BigQuery table names are case-sensitive");
    }

    // ------------------------------------------------------------------
    // Fail closed: what is not exactly one object or one whole table is refused
    // ------------------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "  ",
            "my-bucket/dir/file.csv",
            "my-bucket",
            "sales.orders",
            "orders",
            "gs://",
            "gs:///key",
            "/local/path/file.csv"})
    void aBareOrMalformedNameIsRefused(String location) {
        assertNull(GcpResourceRef.parse(location), "[" + location + "] must not be a delete target");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "gs://my-bucket",
            "gs://my-bucket/",
            "projects/_/buckets/my-bucket",
            "bq://my-proj/sales",
            "bq://my-proj.sales",
            "projects/my-proj/datasets/sales",
            "bqjob://my-proj/job_abc123",
            "projects/my-proj/jobs/job_abc123",
            "//cloudsql.googleapis.com/projects/my-proj/instances/db1"})
    void aContainerOrAnotherGcpAssetIsNotADeleteTarget(String location) {
        assertNull(GcpResourceRef.parse(location), "[" + location + "] must not be a delete target");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "bq://my-proj/sales/orders$20240101",
            "my-proj:sales.orders$20240101",
            "my-proj.sales.orders$20240101",
            "projects/my-proj/datasets/sales/tables/orders$20240101",
            "my-proj:sales.orders@1700000000000"})
    void aPartitionOrSnapshotDecoratorIsRefusedNotStripped(String location) {
        // The normalizer drops the decorator, so deleting the bare table would destroy far more than
        // the single partition or snapshot that was asked for.
        assertNull(GcpResourceRef.parse(location), "[" + location + "] must not be a delete target");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "bq://my-proj/sales/events_*",
            "my-proj.sales.events_*"})
    void aWildcardTableIsRefused(String location) {
        assertNull(GcpResourceRef.parse(location), "[" + location + "] must not be a delete target");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "s3://my-bucket/dir/file.csv",
            "arn:aws:s3:::my-bucket/dir/file.csv",
            "arn:aws:rds:eu-west-1:123456789012:db:prod-db",
            "az://acct/cont/blob",
            "uc://metastore-1/main/sales/orders"})
    void aLocationOfAnotherCloudIsRefused(String location) {
        assertNull(GcpResourceRef.parse(location), "[" + location + "] must not be a delete target");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://storage.googleapis.com/my-bucket/dir/file.csv?generation=1700000000000000",
            "https://my-bucket.storage.googleapis.com/dir/file.csv#frag",
            "https://storage.googleapis.com/my-bucket/dir/file.csv?X-Goog-Signature=SECRET",
            "\"https://storage.googleapis.com/my-bucket/file.csv?generation=1\""})
    void aUrlThatCanSelectAGenerationIsRefused(String location) {
        assertNull(GcpResourceRef.parse(location));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "gs://my-bucket/dir/file.csv ",
            "gs://my-bucket/ dir/file.csv",
            "gs://my-bucket/dir/file.csv\t"})
    void anObjectNameWithLeadingOrTrailingWhitespaceIsRefused(String location) {
        assertNull(GcpResourceRef.parse(location));
    }

    @Test
    void aQuestionMarkOrHashInAGsObjectNameIsPartOfTheName() {
        GcpResourceRef ref = GcpResourceRef.parse("gs://my-bucket/what?#1.csv");

        assertNotNull(ref);
        assertEquals("what?#1.csv", ref.second());
    }

    // ------------------------------------------------------------------
    // deleteData: refusals are FAILED results that never echo a signature
    // ------------------------------------------------------------------

    @Test
    void deleteDataFailsClosedOnAGenerationSelectingUrlAndNeverEchoesTheQuery() {
        GcpCloudDeletionService svc = new GcpCloudDeletionService(true);

        DeletionResult result = svc.deleteData(UUID.randomUUID(),
            "https://storage.googleapis.com/my-bucket/file.csv?generation=1&X-Goog-Signature=SECRET",
            DeletionType.HARD, Map.of());

        assertEquals(DeletionStatus.FAILED, result.getStatus());
        assertThat(result.getErrorMessage())
            .contains("https://storage.googleapis.com/my-bucket/file.csv")
            .doesNotContain("SECRET")
            .doesNotContain("generation");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "gs://my-bucket",
            "bq://my-proj/sales",
            "bq://my-proj/sales/orders$20240101",
            "s3://my-bucket/file.csv",
            "orders"})
    void deleteDataFailsClosedOnAnythingThatIsNotADeleteTarget(String location) {
        GcpCloudDeletionService svc = new GcpCloudDeletionService(true);

        DeletionResult result = svc.deleteData(UUID.randomUUID(), location, DeletionType.HARD, Map.of());

        assertEquals(DeletionStatus.FAILED, result.getStatus());
    }

    @Test
    void deleteDataAcceptsEveryAliasOfAnObjectAndATable() {
        // mock-mode: the parse runs, the SDK is not touched.
        GcpCloudDeletionService svc = new GcpCloudDeletionService(true);

        for (String alias : new String[] {
                "gs://my-bucket/dir/file.csv",
                "projects/_/buckets/my-bucket/objects/dir/file.csv",
                "bq://my-proj/sales/orders",
                "my-proj:sales.orders",
                "projects/my-proj/datasets/sales/tables/orders"}) {
            assertEquals(DeletionStatus.COMPLETED,
                svc.deleteData(UUID.randomUUID(), alias, DeletionType.HARD, Map.of()).getStatus(), alias);
        }
    }
}
