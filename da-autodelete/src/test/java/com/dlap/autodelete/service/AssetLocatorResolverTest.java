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

package com.dlap.autodelete.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dlap.autodelete.client.ResilientCatalogAssetClient;
import com.dlap.autodelete.dto.CatalogAssetDTO;

/**
 * Locks {@link AssetLocatorResolver}: catalog first, then identifier parsing, then fail closed.
 *
 * <p>Every identifier passes through {@code AssetIdNormalizer} (plan rule R8,
 * {@code docs/specs/asset-id.md}): all aliases of one resource dispatch identically, and anything
 * unrecognised, contradictory or able to select a version throws, so the caller marks the asset
 * FAILED and never deletes.
 */
@ExtendWith(MockitoExtension.class)
class AssetLocatorResolverTest {

    @Mock
    private ResilientCatalogAssetClient catalogClient;

    private AssetLocatorResolver resolver() {
        return new AssetLocatorResolver(catalogClient, "GCP");
    }

    private static CatalogAssetDTO catalogAsset(String provider, String uri) {
        CatalogAssetDTO dto = new CatalogAssetDTO();
        dto.setCloudProvider(provider);
        dto.setAssetUri(uri);
        dto.setAssetName("obj");
        return dto;
    }

    private AssetLocator resolveViaCatalog(CatalogAssetDTO dto) {
        UUID id = UUID.randomUUID();
        when(catalogClient.getAssetById(id)).thenReturn(Optional.of(dto));
        return resolver().resolve(id.toString());
    }

    // ------------------------------------------------------------------
    // Resolution order (unchanged behaviour)
    // ------------------------------------------------------------------

    @Test
    void resolve_prefersCatalogWhenUuidPresent() {
        UUID id = UUID.randomUUID();
        CatalogAssetDTO dto = catalogAsset("AWS", "s3://my-bucket/k");
        when(catalogClient.getAssetById(id)).thenReturn(Optional.of(dto));

        AssetLocator loc = resolver().resolve(id.toString());

        assertThat(loc.provider()).isEqualTo("AWS");
        assertThat(loc.locatorString()).isEqualTo("s3://my-bucket/k");
        assertThat(loc.displayName()).isEqualTo("obj");
    }

    @Test
    void resolve_fallsBackToUriWhenCatalogEmpty() {
        // Input is not a UUID, so the catalog is never queried; the identifier parser handles it.
        AssetLocator loc = resolver().resolve("s3://mybucket/path/object");

        assertThat(loc.provider()).isEqualTo("AWS");
        assertThat(loc.locatorString()).isEqualTo("s3://mybucket/path/object");
    }

    @Test
    void resolve_throwsWhenTheCatalogHasNoRowForTheUuid() {
        UUID id = UUID.randomUUID();
        when(catalogClient.getAssetById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver().resolve(id.toString()))
                .isInstanceOf(AssetLocatorResolutionException.class);
    }

    @Test
    void resolve_throwsWhenNothingUsable() {
        assertThatThrownBy(() -> resolver().resolve("not-a-uri"))
                .isInstanceOf(AssetLocatorResolutionException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void resolve_throwsOnABlankId(String id) {
        assertThatThrownBy(() -> resolver().resolve(id)).isInstanceOf(AssetLocatorResolutionException.class);
    }

    // ------------------------------------------------------------------
    // Alias convergence on the identifier path (no catalog, no hints)
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "s3://my-bucket/dir/file.csv",
            "s3a://my-bucket/dir/file.csv",
            "s3n://my-bucket/dir/file.csv",
            "arn:aws:s3:::my-bucket/dir/file.csv",
            "https://my-bucket.s3.amazonaws.com/dir/file.csv",
            "https://my-bucket.s3.eu-west-1.amazonaws.com/dir/file.csv",
            "https://s3.amazonaws.com/my-bucket/dir/file.csv",
            "https://s3.eu-west-1.amazonaws.com/my-bucket/dir/file.csv"})
    void everyAliasOfAnS3ObjectDispatchesToTheSameLocator(String alias) {
        AssetLocator loc = resolver().resolve(alias);

        assertThat(loc.provider()).isEqualTo("AWS");
        assertThat(loc.locatorString()).isEqualTo("s3://my-bucket/dir/file.csv");
        assertThat(AssetLocatorResolver.inferAction(loc.provider(), loc.locatorString()))
                .isEqualTo("DELETE_S3_OBJECT");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "gs://my-bucket/dir/file.csv",
            "projects/_/buckets/my-bucket/objects/dir/file.csv",
            "//storage.googleapis.com/projects/_/buckets/my-bucket/objects/dir/file.csv",
            "https://storage.googleapis.com/my-bucket/dir/file.csv",
            "https://my-bucket.storage.googleapis.com/dir/file.csv",
            "https://www.googleapis.com/storage/v1/b/my-bucket/o/dir%2Ffile.csv"})
    void everyAliasOfAGcsObjectDispatchesToTheSameLocator(String alias) {
        AssetLocator loc = resolver().resolve(alias);

        assertThat(loc.provider()).isEqualTo("GCP");
        assertThat(loc.locatorString()).isEqualTo("gs://my-bucket/dir/file.csv");
        assertThat(AssetLocatorResolver.inferAction(loc.provider(), loc.locatorString()))
                .isEqualTo("DELETE_GCS_OBJECT");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "bq://my-proj/sales/orders",
            "bq://my-proj.sales.orders",
            "projects/my-proj/datasets/sales/tables/orders",
            "//bigquery.googleapis.com/projects/my-proj/datasets/sales/tables/orders"})
    void everyAliasOfABigQueryTableDispatchesToTheSameLocator(String alias) {
        AssetLocator loc = resolver().resolve(alias);

        assertThat(loc.provider()).isEqualTo("GCP");
        assertThat(loc.locatorString()).isEqualTo("bq://my-proj/sales/orders");
        assertThat(AssetLocatorResolver.inferAction(loc.provider(), loc.locatorString()))
                .isEqualTo("DELETE_BQ_TABLE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "az://acct/cont/dir/file.csv",
            "azure://acct/cont/dir/file.csv",
            "abfss://cont@acct.dfs.core.windows.net/dir/file.csv",
            "wasbs://cont@acct.blob.core.windows.net/dir/file.csv",
            "https://acct.blob.core.windows.net/cont/dir/file.csv",
            "https://acct.dfs.core.windows.net/cont/dir/file.csv"})
    void everyAliasOfAnAzureBlobDispatchesToTheFormTheAzureServiceParses(String alias) {
        // The Azure deletion service parses azure://, not the canonical az:// (transitional adapter).
        AssetLocator loc = resolver().resolve(alias);

        assertThat(loc.provider()).isEqualTo("AZURE");
        assertThat(loc.locatorString()).isEqualTo("azure://acct/cont/dir/file.csv");
        assertThat(AssetLocatorResolver.inferAction(loc.provider(), loc.locatorString()))
                .isEqualTo("DELETE_BLOB");
    }

    @Test
    void anRdsArnDispatchesAsAnRdsDeleteInTheCanonicalArnForm() {
        AssetLocator loc = resolver().resolve("arn:aws:rds:eu-west-1:123456789012:db:Prod-DB");

        assertThat(loc.provider()).isEqualTo("AWS");
        // RDS instance identifiers are stored in lower case.
        assertThat(loc.locatorString()).isEqualTo("arn:aws:rds:eu-west-1:123456789012:db:prod-db");
        assertThat(AssetLocatorResolver.inferAction(loc.provider(), loc.locatorString()))
                .isEqualTo("DELETE_RDS_INSTANCE");
    }

    @Test
    void aDynamoDbArnDispatchesAsADynamoDbDelete() {
        AssetLocator loc = resolver().resolve("arn:aws:dynamodb:us-east-1:123456789012:table/Orders");

        assertThat(loc.provider()).isEqualTo("AWS");
        assertThat(loc.locatorString()).isEqualTo("arn:aws:dynamodb:us-east-1:123456789012:table/Orders");
        assertThat(AssetLocatorResolver.inferAction(loc.provider(), loc.locatorString()))
                .isEqualTo("DELETE_DYNAMODB_TABLE");
    }

    @Test
    void theShortFormsStillDriveTheRightAuditAction() {
        assertThat(AssetLocatorResolver.inferAction("AWS", "rds://eu-west-1/db1")).isEqualTo("DELETE_RDS_INSTANCE");
        assertThat(AssetLocatorResolver.inferAction("AWS", "dynamodb://eu-west-1/t1"))
                .isEqualTo("DELETE_DYNAMODB_TABLE");
        assertThat(AssetLocatorResolver.inferAction("AWS", "s3://my-bucket/k")).isEqualTo("DELETE_S3_OBJECT");
        assertThat(AssetLocatorResolver.inferAction("GCP", "bq://p/d/t")).isEqualTo("DELETE_BQ_TABLE");
        assertThat(AssetLocatorResolver.inferAction("GCP", "gs://my-bucket/k")).isEqualTo("DELETE_GCS_OBJECT");
        assertThat(AssetLocatorResolver.inferAction("AZURE", "azure://a/c/p")).isEqualTo("DELETE_BLOB");
        assertThat(AssetLocatorResolver.inferAction("ONPREM", "nfs://share/f")).isEqualTo("DELETE_UNKNOWN");
        assertThat(AssetLocatorResolver.inferAction("AWS", null)).isEqualTo("DELETE_S3_OBJECT");
    }

    // ------------------------------------------------------------------
    // Alias convergence on the catalog path (provider, type and region as hints)
    // ------------------------------------------------------------------

    @Test
    void aCatalogAssetWithAnAliasDispatchesToTheCanonicalLocator() {
        assertThat(resolveViaCatalog(catalogAsset("AWS", "arn:aws:s3:::my-bucket/key.csv")).locatorString())
                .isEqualTo("s3://my-bucket/key.csv");
        assertThat(resolveViaCatalog(catalogAsset("GCP", "projects/_/buckets/my-bucket/objects/key.csv"))
                .locatorString()).isEqualTo("gs://my-bucket/key.csv");
        assertThat(resolveViaCatalog(catalogAsset("AZURE", "abfss://cont@acct.dfs.core.windows.net/key.csv"))
                .locatorString()).isEqualTo("azure://acct/cont/key.csv");
    }

    @Test
    void theCatalogProviderMayBeSpelledWithAnyOfTheKnownAliases() {
        AssetLocator loc = resolveViaCatalog(catalogAsset("google_cloud", "gs://my-bucket/key.csv"));

        assertThat(loc.provider()).isEqualTo("GCP");
    }

    @Test
    void aBareCatalogNameResolvesWhenTheAssetTypeSaysWhatItIs() {
        CatalogAssetDTO dto = catalogAsset("GCP", "my-bucket/dir/file.csv");
        dto.setAssetType("GCS_OBJECT");

        AssetLocator loc = resolveViaCatalog(dto);

        assertThat(loc.provider()).isEqualTo("GCP");
        assertThat(loc.locatorString()).isEqualTo("gs://my-bucket/dir/file.csv");
    }

    @Test
    void anRdsShortFormWithoutAnAccountReachesTheProviderServiceUnchanged() {
        // rds://region/id carries no account, so it cannot become an ARN. The provider service
        // parses its own short forms and is the final judge, so the string is not rewritten.
        CatalogAssetDTO dto = catalogAsset("AWS", "rds://eu-west-1/prod-db");
        dto.setAssetType("RDS_INSTANCE");

        AssetLocator loc = resolveViaCatalog(dto);

        assertThat(loc.provider()).isEqualTo("AWS");
        assertThat(loc.locatorString()).isEqualTo("rds://eu-west-1/prod-db");
        assertThat(AssetLocatorResolver.inferAction(loc.provider(), loc.locatorString()))
                .isEqualTo("DELETE_RDS_INSTANCE");
    }

    @Test
    void aProviderTheNormalizerDoesNotKnowIsPassedThroughForTheFactoryToRefuse() {
        AssetLocator loc = resolveViaCatalog(catalogAsset("ONPREM", "nfs://share/file"));

        assertThat(loc.provider()).isEqualTo("ONPREM");
        assertThat(loc.locatorString()).isEqualTo("nfs://share/file");
    }

    // ------------------------------------------------------------------
    // Fail closed
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "my-bucket/dir/file.csv",
            "my-proj.sales.orders",
            "sales.orders",
            "prod-db",
            "/local/path/file.csv",
            "file:///tmp/x",
            "ftp://host/file",
            "gs://",
            "s3:///key",
            "bq://only-one-part"})
    void anUnrecognisedOrBareIdentifierIsNeverResolved(String raw) {
        assertThatThrownBy(() -> resolver().resolve(raw)).isInstanceOf(AssetLocatorResolutionException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "uc://metastore-1/main/sales/orders",
            "dbx://1234567890123456/jobs/42",
            "snowflake://acct/DB/SCHEMA/TBL"})
    void anIdentifierOfAProviderWithNoDeletionServiceIsNeverResolved(String raw) {
        assertThatThrownBy(() -> resolver().resolve(raw)).isInstanceOf(AssetLocatorResolutionException.class);
    }

    @Test
    void aCatalogProviderThatContradictsTheLocationIsRefused() {
        // The catalog provider is a heuristic (DiscoveryAssetDiscoveredListener defaults to GCP);
        // a URI is not. Deleting on the strength of a contradiction could hit the wrong cloud.
        UUID id = UUID.randomUUID();
        when(catalogClient.getAssetById(id)).thenReturn(Optional.of(catalogAsset("GCP", "s3://my-bucket/k")));

        assertThatThrownBy(() -> resolver().resolve(id.toString()))
                .isInstanceOf(AssetLocatorResolutionException.class)
                .hasMessageContaining("Contradictory evidence");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://my-bucket.s3.amazonaws.com/dir/file.csv?versionId=3sL4kqtJlcpXroDTDmJ",
            "https://storage.googleapis.com/my-bucket/dir/file.csv?generation=1700000000000000",
            "https://acct.blob.core.windows.net/cont/file.csv?snapshot=2024-01-01T00:00:00.0000000Z",
            "https://acct.blob.core.windows.net/cont/file.csv?sv=2022-11-02&sig=SECRET",
            "https://acct.blob.core.windows.net/cont/file.csv#frag",
            "\"https://my-bucket.s3.amazonaws.com/file.csv?versionId=abc\""})
    void aUrlThatCanSelectAVersionIsRefusedAndNeverEchoed(String url) {
        // The normalizer drops the query and fragment; deleting the whole object instead of the
        // selected version or snapshot would destroy more than was asked for.
        assertThatThrownBy(() -> resolver().resolve(url))
                .isInstanceOf(AssetLocatorResolutionException.class)
                .hasMessageNotContaining("SECRET")
                .hasMessageNotContaining("versionId=")
                .hasMessageNotContaining("generation=")
                .hasMessageNotContaining("snapshot=");
    }

    @Test
    void aCatalogUrlThatCanSelectAVersionIsRefusedAndNeverFallsBackToUriParsing() {
        UUID id = UUID.randomUUID();
        when(catalogClient.getAssetById(id)).thenReturn(Optional.of(catalogAsset("AWS",
                "https://my-bucket.s3.amazonaws.com/file.csv?versionId=abc")));

        assertThatThrownBy(() -> resolver().resolve(id.toString()))
                .isInstanceOf(AssetLocatorResolutionException.class)
                .hasMessageNotContaining("versionId=");
    }

    @Test
    void aQuestionMarkInAnObjectKeyIsPartOfTheKey() {
        assertThat(resolver().resolve("s3://my-bucket/what?.csv").locatorString())
                .isEqualTo("s3://my-bucket/what?.csv");
        assertThat(resolver().resolve("gs://my-bucket/tag#1.csv").locatorString())
                .isEqualTo("gs://my-bucket/tag#1.csv");
    }

    @Test
    void aUrlWithoutAQueryStillResolves() {
        assertThat(resolver().resolve("https://my-bucket.s3.amazonaws.com/dir/file.csv").locatorString())
                .isEqualTo("s3://my-bucket/dir/file.csv");
    }
}
