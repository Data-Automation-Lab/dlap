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

package com.dlap.autoarchival.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.dlap.autoarchival.client.CatalogAssetFeignClient;
import com.dlap.autoarchival.dto.CatalogAssetDTO;

/**
 * Locks the resolution order for {@link AssetLocationResolver}: policy-event snapshot,
 * then da-catalog, then fail closed. Nothing may ever be synthesised.
 *
 * <p>Every location also passes through {@code AssetIdNormalizer} (plan rule R8,
 * {@code docs/specs/asset-id.md}): all aliases of one resource resolve to one
 * {@link AssetLocation}, and anything unrecognised, contradictory or able to select a version
 * resolves to nothing, so the caller marks the asset FAILED.
 */
class AssetLocationResolverTest {

    private final CatalogAssetFeignClient catalogClient = mock(CatalogAssetFeignClient.class);
    private final AssetLocationResolver resolver = new AssetLocationResolver(catalogClient);

    private static CatalogAssetDTO catalogAsset(String provider, String uri, String nativeId) {
        CatalogAssetDTO dto = new CatalogAssetDTO();
        dto.setCloudProvider(provider);
        dto.setAssetUri(uri);
        dto.setNativeAssetId(nativeId);
        return dto;
    }

    /** A policy event whose match snapshot carries a location and, optionally, a provider. */
    private static Map<String, Object> eventWith(String assetUri, String cloudProvider) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("assetUri", assetUri);
        if (cloudProvider != null) {
            snapshot.put("cloudProvider", cloudProvider);
        }
        Map<String, Object> event = new HashMap<>();
        event.put("matchSnapshot", snapshot);
        return event;
    }

    private Optional<AssetLocation> resolveEvent(String assetUri, String cloudProvider) {
        return resolver.resolveForPolicyAction(UUID.randomUUID().toString(), eventWith(assetUri, cloudProvider));
    }

    // ------------------------------------------------------------------
    // Resolution order (unchanged behaviour)
    // ------------------------------------------------------------------

    @Test
    void eventSnapshotIsPreferredOverACatalogRoundTrip() {
        String assetId = UUID.randomUUID().toString();
        Map<String, Object> event = new HashMap<>();
        event.put("assetId", assetId);
        event.put("matchSnapshot", Map.of(
                "assetUri", "gs://bucket/warehouse/pii", "cloudProvider", "GCP"));

        Optional<AssetLocation> resolved = resolver.resolveForPolicyAction(assetId, event);

        assertThat(resolved).contains(new AssetLocation("GCP", "gs://bucket/warehouse/pii"));
        verify(catalogClient, never()).getAssetById(any());
    }

    @Test
    void flatEventFieldsAlsoResolve() {
        String assetId = UUID.randomUUID().toString();
        Map<String, Object> event = new HashMap<>();
        event.put("assetId", assetId);
        event.put("assetUri", "s3://bucket/key");
        event.put("cloudProvider", "aws");

        assertThat(resolver.resolveForPolicyAction(assetId, event))
                .contains(new AssetLocation("AWS", "s3://bucket/key"));
    }

    @Test
    void snapshotUriWithoutProviderTakesTheProviderFromTheIdentifier() {
        String assetId = UUID.randomUUID().toString();
        Map<String, Object> event = new HashMap<>();
        event.put("assetId", assetId);
        event.put("matchSnapshot", Map.of("assetUri", "gs://bucket/object"));

        assertThat(resolver.resolveForPolicyAction(assetId, event))
                .contains(new AssetLocation("GCP", "gs://bucket/object"));
    }

    @Test
    void withoutAnEventSnapshotTheCatalogIsAuthoritative() {
        UUID assetId = UUID.randomUUID();
        when(catalogClient.getAssetById(assetId))
                .thenReturn(catalogAsset("GCP", "gs://real-bucket/object", null));

        assertThat(resolver.resolveForPolicyAction(assetId.toString(), Map.of()))
                .contains(new AssetLocation("GCP", "gs://real-bucket/object"));
    }

    @Test
    void nativeAssetIdIsTheSecondaryLocatorSource() {
        UUID assetId = UUID.randomUUID();
        when(catalogClient.getAssetById(assetId))
                .thenReturn(catalogAsset("GCP", null, "bq://project/dataset/table"));

        assertThat(resolver.resolve(assetId.toString()))
                .contains(new AssetLocation("GCP", "bq://project/dataset/table"));
    }

    @Test
    void unknownCatalogProviderTakesTheProviderFromTheIdentifier() {
        UUID assetId = UUID.randomUUID();
        when(catalogClient.getAssetById(assetId))
                .thenReturn(catalogAsset("UNKNOWN", "s3://bucket/key", null));

        assertThat(resolver.resolve(assetId.toString()))
                .contains(new AssetLocation("AWS", "s3://bucket/key"));
    }

    @Test
    void catalogAssetWithoutAUriIsUnresolvable() {
        UUID assetId = UUID.randomUUID();
        when(catalogClient.getAssetById(assetId)).thenReturn(catalogAsset("GCP", null, null));

        assertThat(resolver.resolve(assetId.toString())).isEmpty();
    }

    @Test
    void catalogFailureIsUnresolvableRatherThanInvented() {
        UUID assetId = UUID.randomUUID();
        when(catalogClient.getAssetById(assetId)).thenThrow(new RuntimeException("404 Not Found"));

        assertThat(resolver.resolve(assetId.toString())).isEmpty();
    }

    @Test
    void nonUuidAssetIdIsUnresolvable() {
        assertThat(resolver.resolve("not-a-uuid")).isEmpty();
        verify(catalogClient, never()).getAssetById(any());
    }

    @Test
    void withoutACatalogClientOnlyEventSnapshotsResolve() {
        AssetLocationResolver clientless = new AssetLocationResolver(null);
        String assetId = UUID.randomUUID().toString();

        assertThat(clientless.resolve(assetId)).isEmpty();
        assertThat(clientless.resolveForPolicyAction(assetId,
                Map.of("matchSnapshot", Map.of("assetUri", "s3://my-bucket/k"))))
                .contains(new AssetLocation("AWS", "s3://my-bucket/k"));
    }

    // ------------------------------------------------------------------
    // Alias convergence: one resource, one location, whichever form the source used
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
    void everyAliasOfAnS3ObjectResolvesToTheSameLocation(String alias) {
        assertThat(resolveEvent(alias, "AWS"))
                .contains(new AssetLocation("AWS", "s3://my-bucket/dir/file.csv"));
        // The identifier alone is enough: no provider has to be stated.
        assertThat(resolveEvent(alias, null))
                .contains(new AssetLocation("AWS", "s3://my-bucket/dir/file.csv"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "gs://my-bucket/dir/file.csv",
            "projects/_/buckets/my-bucket/objects/dir/file.csv",
            "//storage.googleapis.com/projects/_/buckets/my-bucket/objects/dir/file.csv",
            "https://storage.googleapis.com/my-bucket/dir/file.csv",
            "https://my-bucket.storage.googleapis.com/dir/file.csv",
            "https://www.googleapis.com/storage/v1/b/my-bucket/o/dir%2Ffile.csv"})
    void everyAliasOfAGcsObjectResolvesToTheSameLocation(String alias) {
        assertThat(resolveEvent(alias, "GCP"))
                .contains(new AssetLocation("GCP", "gs://my-bucket/dir/file.csv"));
        assertThat(resolveEvent(alias, null))
                .contains(new AssetLocation("GCP", "gs://my-bucket/dir/file.csv"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "bq://my-proj/sales/orders",
            "bq://my-proj.sales.orders",
            "my-proj:sales.orders",
            "projects/my-proj/datasets/sales/tables/orders",
            "//bigquery.googleapis.com/projects/my-proj/datasets/sales/tables/orders"})
    void everyAliasOfABigQueryTableResolvesToTheSameLocation(String alias) {
        assertThat(resolveEvent(alias, "GCP"))
                .contains(new AssetLocation("GCP", "bq://my-proj/sales/orders"));
    }

    @Test
    void aBareDottedBigQueryNameNeedsTheProviderToBeStated() {
        assertThat(resolveEvent("my-proj.sales.orders", "GCP"))
                .contains(new AssetLocation("GCP", "bq://my-proj/sales/orders"));
        // Without the provider the form is ambiguous (it could be a Unity Catalog name): never guess.
        assertThat(resolveEvent("my-proj.sales.orders", null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "az://acct/cont/dir/file.csv",
            "azure://acct/cont/dir/file.csv",
            "abfss://cont@acct.dfs.core.windows.net/dir/file.csv",
            "wasbs://cont@acct.blob.core.windows.net/dir/file.csv",
            "https://acct.blob.core.windows.net/cont/dir/file.csv",
            "https://acct.dfs.core.windows.net/cont/dir/file.csv"})
    void everyAliasOfAnAzureBlobResolvesToTheFormTheAzureServiceParses(String alias) {
        // The Azure storage service parses azure://, not the canonical az:// (transitional adapter).
        assertThat(resolveEvent(alias, "AZURE"))
                .contains(new AssetLocation("AZURE", "azure://acct/cont/dir/file.csv"));
        assertThat(resolveEvent(alias, null))
                .contains(new AssetLocation("AZURE", "azure://acct/cont/dir/file.csv"));
    }

    @Test
    void providerAliasesTheCatalogUsesAreAcceptedAndNormalised() {
        assertThat(resolveEvent("gs://my-bucket/o", "google_cloud"))
                .contains(new AssetLocation("GCP", "gs://my-bucket/o"));
        assertThat(resolveEvent("s3://my-bucket/k", "Amazon"))
                .contains(new AssetLocation("AWS", "s3://my-bucket/k"));
        assertThat(resolveEvent("az://acct/cont/p", "microsoft_azure"))
                .contains(new AssetLocation("AZURE", "azure://acct/cont/p"));
    }

    @Test
    void s3KeysAndBucketCaseAreKeptAsGiven() {
        assertThat(resolveEvent("s3://Legacy-Bucket/Dir/File.CSV", "AWS"))
                .contains(new AssetLocation("AWS", "s3://Legacy-Bucket/Dir/File.CSV"));
    }

    @Test
    void gcsBucketIsLowerCasedAndTheObjectNameIsKept() {
        assertThat(resolveEvent("gs://My-Bucket/Dir/File.CSV", "GCP"))
                .contains(new AssetLocation("GCP", "gs://my-bucket/Dir/File.CSV"));
    }

    @Test
    void aCatalogAssetResolvesAnAliasWithItsTypeAndRegionAsHints() {
        UUID assetId = UUID.randomUUID();
        CatalogAssetDTO dto = catalogAsset("AWS", "arn:aws:s3:::my-bucket/key.csv", null);
        dto.setAssetType("S3_OBJECT");
        dto.setRegion("eu-west-1");
        when(catalogClient.getAssetById(assetId)).thenReturn(dto);

        assertThat(resolver.resolve(assetId.toString()))
                .contains(new AssetLocation("AWS", "s3://my-bucket/key.csv"));
    }

    @Test
    void aBareCatalogBucketNameResolvesWhenTheAssetTypeSaysWhatItIs() {
        UUID assetId = UUID.randomUUID();
        CatalogAssetDTO dto = catalogAsset("GCP", "my-bucket/dir/file.csv", null);
        dto.setAssetType("GCS_OBJECT");
        when(catalogClient.getAssetById(assetId)).thenReturn(dto);

        assertThat(resolver.resolve(assetId.toString()))
                .contains(new AssetLocation("GCP", "gs://my-bucket/dir/file.csv"));
    }

    // ------------------------------------------------------------------
    // Fail closed
    // ------------------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "/local/path/file.csv", "file:///tmp/x", "ftp://host/file", "not a location",
            "my-bucket/dir/file.csv"})
    void anUnrecognisedOrBareLocationIsNeverResolved(String uri) {
        assertThat(resolveEvent(uri, null)).isEmpty();
        assertThat(resolveEvent(uri, "AWS")).isEmpty();
        assertThat(resolveEvent(uri, "GCP")).isEmpty();
    }

    @Test
    void aProviderThatContradictsTheIdentifierIsNeverActedOn() {
        // The catalog provider is a heuristic; a URI is not. Contradictory evidence is refused.
        assertThat(resolveEvent("s3://my-bucket/k", "GCP")).isEmpty();
        assertThat(resolveEvent("gs://my-bucket/o", "AWS")).isEmpty();
        assertThat(resolveEvent("az://acct/cont/p", "AWS")).isEmpty();
        assertThat(resolveEvent("arn:aws:s3:::my-bucket/k", "AZURE")).isEmpty();
    }

    @Test
    void aContradictoryCatalogRowIsUnresolvable() {
        UUID assetId = UUID.randomUUID();
        when(catalogClient.getAssetById(assetId)).thenReturn(catalogAsset("GCP", "s3://my-bucket/k", null));

        assertThat(resolver.resolve(assetId.toString())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://my-bucket.s3.amazonaws.com/dir/file.csv?versionId=3sL4kqtJlcpXroDTDmJ",
            "https://s3.amazonaws.com/my-bucket/dir/file.csv?versionId=abc",
            "https://storage.googleapis.com/my-bucket/dir/file.csv?generation=1700000000000000",
            "https://acct.blob.core.windows.net/cont/file.csv?snapshot=2024-01-01T00:00:00.0000000Z",
            "https://acct.blob.core.windows.net/cont/file.csv?sv=2022-11-02&sig=SECRET",
            "https://acct.blob.core.windows.net/cont/file.csv#frag",
            "\"https://my-bucket.s3.amazonaws.com/file.csv?versionId=abc\""})
    void aUrlThatCanSelectAVersionIsNeverResolved(String url) {
        // The normalizer drops the query and fragment, but they can select a version, a generation
        // or a snapshot; archiving the whole object instead would be a different operation.
        assertThat(resolveEvent(url, null)).isEmpty();
        assertThat(resolveEvent(url, "AWS")).isEmpty();
        assertThat(resolveEvent(url, "GCP")).isEmpty();
        assertThat(resolveEvent(url, "AZURE")).isEmpty();
    }

    @Test
    void aUrlWithoutAQueryStillResolves() {
        assertThat(resolveEvent("https://my-bucket.s3.amazonaws.com/dir/file.csv", null))
                .contains(new AssetLocation("AWS", "s3://my-bucket/dir/file.csv"));
    }

    @Test
    void anIdentifierThatNeedsAHintThatIsMissingIsNeverGuessed() {
        // A bare RDS instance name needs the kind, region and account; the policy event has none.
        assertThat(resolveEvent("prod-postgres", "AWS")).isEmpty();
        // A bare BigQuery dataset.table needs the project.
        assertThat(resolveEvent("sales.orders", "GCP")).isEmpty();
        // An ARN with a non-numeric account (a crawler concatenated a null) is malformed.
        assertThat(resolveEvent("arn:aws:rds:eu-west-1:null:db:prod-postgres", "AWS")).isEmpty();
    }

    @Test
    void aMalformedIdentifierIsNeverResolved() {
        assertThat(resolveEvent("gs://", "GCP")).isEmpty();
        assertThat(resolveEvent("s3:///key", "AWS")).isEmpty();
        assertThat(resolveEvent("bq://only-one-part", "GCP")).isEmpty();
        assertThat(resolveEvent("s3://my-bucket/k\0", "AWS")).isEmpty();
    }

    @Test
    void anIdentifierOfAProviderWithNoRetireServiceStillCarriesItsOwnProvider() {
        // Unity Catalog and Snowflake names normalize, but the provider they carry has no storage
        // service; the dispatch (not this resolver) refuses them, so the provider must be truthful.
        assertThat(resolveEvent("uc://metastore-1/main/sales/orders", null))
                .contains(new AssetLocation("UNITY", "uc://metastore-1/main/sales/orders"));
    }

    @Test
    void aProviderTheNormalizerDoesNotKnowIsPassedThroughForTheDispatchToRefuse() {
        assertThat(resolveEvent("onprem-nfs://share/file", "ONPREM"))
                .contains(new AssetLocation("ONPREM", "onprem-nfs://share/file"));
    }

    @Test
    void aUrlWithAQueryStillFailsClosedForAnUnknownProvider() {
        assertThat(resolveEvent("https://nas.example.com/file?version=2", "ONPREM")).isEmpty();
    }

    @Test
    void aQuestionMarkInAnObjectKeyIsPartOfTheKey() {
        assertThat(resolveEvent("s3://my-bucket/what?.csv", "AWS"))
                .contains(new AssetLocation("AWS", "s3://my-bucket/what?.csv"));
        assertThat(resolveEvent("gs://my-bucket/tag#1.csv", "GCP"))
                .contains(new AssetLocation("GCP", "gs://my-bucket/tag#1.csv"));
    }

    @Test
    void assetLocationRejectsBlankComponents() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AssetLocation(" ", "s3://b/k"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AssetLocation("AWS", ""));
    }
}
