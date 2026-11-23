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

package com.dlap.autoarchival.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.dlap.autoarchival.exception.ArchivalException;
import com.dlap.autoarchival.model.ArchivalTask;
import com.dlap.autoarchival.model.RestoreResult;

/**
 * The archive location a GCS restore starts from is parsed through {@code AssetIdNormalizer}
 * (plan rule R8): every alias of one object gives the same object name, and anything that is
 * not exactly one object in the configured bucket is refused.
 */
class GcsArchiveStorageProviderTest {

    private static final String BUCKET = "archive-bucket";

    @ParameterizedTest
    @ValueSource(strings = {
            "gs://archive-bucket/archives/a1/file.parquet",
            "projects/_/buckets/archive-bucket/objects/archives/a1/file.parquet",
            "//storage.googleapis.com/projects/_/buckets/archive-bucket/objects/archives/a1/file.parquet",
            "https://storage.googleapis.com/archive-bucket/archives/a1/file.parquet",
            "https://archive-bucket.storage.googleapis.com/archives/a1/file.parquet",
            "GS://Archive-Bucket/archives/a1/file.parquet"})
    void everyAliasOfTheArchivedObjectGivesTheSameObjectName(String location) throws ArchivalException {
        assertThat(GcsArchiveStorageProvider.parseObjectFromGsUri(location, BUCKET))
                .isEqualTo("archives/a1/file.parquet");
    }

    @Test
    void anObjectNameKeepsItsCaseAndSpecialCharacters() throws ArchivalException {
        assertThat(GcsArchiveStorageProvider.parseObjectFromGsUri("gs://archive-bucket/Dir/My File #1.csv", BUCKET))
                .isEqualTo("Dir/My File #1.csv");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "  ",
            "archive-bucket/archives/a1/file.parquet",
            "gs://archive-bucket",
            "gs://archive-bucket/",
            "gs://",
            "s3://archive-bucket/archives/a1/file.parquet",
            "az://acct/cont/blob",
            "bq://proj/dataset/table",
            "/local/path/file.parquet"})
    void anythingThatIsNotOneGcsObjectIsRefused(String location) {
        assertThatThrownBy(() -> GcsArchiveStorageProvider.parseObjectFromGsUri(location, BUCKET))
                .isInstanceOf(ArchivalException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://storage.googleapis.com/archive-bucket/file.parquet?generation=1700000000000000",
            "https://archive-bucket.storage.googleapis.com/file.parquet#frag",
            "https://storage.googleapis.com/archive-bucket/file.parquet?X-Goog-Signature=SECRET"})
    void aUrlThatCanSelectAGenerationIsRefusedAndNeverEchoed(String location) {
        assertThatThrownBy(() -> GcsArchiveStorageProvider.parseObjectFromGsUri(location, BUCKET))
                .isInstanceOf(ArchivalException.class)
                .hasMessageNotContaining("SECRET")
                .hasMessageNotContaining("generation=");
    }

    @Test
    void aLocationInAnotherBucketIsRefused() {
        assertThatThrownBy(() -> GcsArchiveStorageProvider.parseObjectFromGsUri("gs://other-bucket/file.parquet", BUCKET))
                .isInstanceOf(ArchivalException.class)
                .hasMessageContaining("other-bucket");
    }

    @Test
    void anObjectNameWithLeadingOrTrailingWhitespaceIsRefused() {
        assertThatThrownBy(() -> GcsArchiveStorageProvider.parseObjectFromGsUri("gs://archive-bucket/file.parquet ", BUCKET))
                .isInstanceOf(ArchivalException.class)
                .hasMessageContaining("whitespace");
        assertThatThrownBy(() -> GcsArchiveStorageProvider.parseObjectFromGsUri("gs://archive-bucket/ file.parquet", BUCKET))
                .isInstanceOf(ArchivalException.class);
    }

    @Test
    void initiateRestoreAcceptsAnAliasOfTheArchiveLocation() throws ArchivalException {
        ArchivalTask task = taskWith("projects/_/buckets/archive-bucket/objects/archives/a1/file.parquet");

        RestoreResult result = new GcsArchiveStorageProvider().initiateRestore(task, Map.of());

        assertThat(result.getStatusMessage()).contains("archive-bucket/archives/a1/file.parquet");
    }

    @Test
    void initiateRestoreFailsClosedOnAnUnparseableArchiveLocation() {
        ArchivalTask task = taskWith("s3://archive-bucket/archives/a1/file.parquet");

        assertThatThrownBy(() -> new GcsArchiveStorageProvider().initiateRestore(task, Map.of()))
                .isInstanceOf(ArchivalException.class);
    }

    @Test
    void anExplicitObjectInTheProviderConfigurationNeedsNoLocation() throws ArchivalException {
        ArchivalTask task = taskWith(null);

        RestoreResult result = new GcsArchiveStorageProvider()
                .initiateRestore(task, Map.of("gcs.object", "archives/explicit.parquet"));

        assertThat(result.getStatusMessage()).contains("archive-bucket/archives/explicit.parquet");
    }

    private static ArchivalTask taskWith(String archiveStorageLocation) {
        ArchivalTask task = new ArchivalTask("task-1", "asset-1");
        task.setArchiveStorageLocation(archiveStorageLocation);
        task.setProviderConfiguration(Map.of("gcs.bucket", BUCKET));
        return task;
    }
}
