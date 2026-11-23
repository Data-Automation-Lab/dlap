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

/**
 * The archive location an S3 Glacier restore starts from is parsed through
 * {@code AssetIdNormalizer} (plan rule R8): every alias of one object gives the same key, and
 * anything that is not exactly one object in the configured bucket is refused before any S3
 * call is made.
 */
class AwsS3GlacierArchivalProviderTest {

    private static final String BUCKET = "archive-bucket";

    @ParameterizedTest
    @ValueSource(strings = {
            "s3://archive-bucket/archives/a1/file.parquet",
            "s3a://archive-bucket/archives/a1/file.parquet",
            "s3n://archive-bucket/archives/a1/file.parquet",
            "arn:aws:s3:::archive-bucket/archives/a1/file.parquet",
            "https://archive-bucket.s3.amazonaws.com/archives/a1/file.parquet",
            "https://archive-bucket.s3.eu-west-1.amazonaws.com/archives/a1/file.parquet",
            "https://s3.amazonaws.com/archive-bucket/archives/a1/file.parquet",
            "https://s3.eu-west-1.amazonaws.com/archive-bucket/archives/a1/file.parquet"})
    void everyAliasOfTheArchivedObjectGivesTheSameKey(String location) throws ArchivalException {
        assertThat(AwsS3GlacierArchivalProvider.parseKeyFromLocation(location, BUCKET))
                .isEqualTo("archives/a1/file.parquet");
    }

    @Test
    void aKeyKeepsItsCaseAndSpecialCharacters() throws ArchivalException {
        assertThat(AwsS3GlacierArchivalProvider.parseKeyFromLocation("s3://archive-bucket/Dir/My File #1.csv", BUCKET))
                .isEqualTo("Dir/My File #1.csv");
    }

    @Test
    void theBucketNameIsCaseSensitive() {
        // Buckets created in us-east-1 before 2018-03-01 may contain upper case, so a bucket that
        // differs only in case could be a different bucket: refuse rather than assume.
        assertThatThrownBy(() -> AwsS3GlacierArchivalProvider.parseKeyFromLocation(
                "s3://Archive-Bucket/file.parquet", BUCKET))
                .isInstanceOf(ArchivalException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "  ",
            "archive-bucket/archives/a1/file.parquet",
            "s3://archive-bucket",
            "s3://archive-bucket/",
            "arn:aws:s3:::archive-bucket",
            "s3://",
            "gs://archive-bucket/archives/a1/file.parquet",
            "az://acct/cont/blob",
            "/local/path/file.parquet"})
    void anythingThatIsNotOneS3ObjectIsRefused(String location) {
        assertThatThrownBy(() -> AwsS3GlacierArchivalProvider.parseKeyFromLocation(location, BUCKET))
                .isInstanceOf(ArchivalException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://archive-bucket.s3.amazonaws.com/file.parquet?versionId=3sL4kqtJlcpXroDTDmJ",
            "https://s3.amazonaws.com/archive-bucket/file.parquet#frag",
            "https://archive-bucket.s3.amazonaws.com/file.parquet?X-Amz-Signature=SECRET"})
    void aUrlThatCanSelectAVersionIsRefusedAndNeverEchoed(String location) {
        assertThatThrownBy(() -> AwsS3GlacierArchivalProvider.parseKeyFromLocation(location, BUCKET))
                .isInstanceOf(ArchivalException.class)
                .hasMessageNotContaining("SECRET")
                .hasMessageNotContaining("versionId=");
    }

    @Test
    void aLocationInAnotherBucketIsRefused() {
        assertThatThrownBy(() -> AwsS3GlacierArchivalProvider.parseKeyFromLocation(
                "s3://other-bucket/file.parquet", BUCKET))
                .isInstanceOf(ArchivalException.class)
                .hasMessageContaining("other-bucket");
    }

    @Test
    void aKeyWithLeadingOrTrailingWhitespaceIsRefused() {
        assertThatThrownBy(() -> AwsS3GlacierArchivalProvider.parseKeyFromLocation(
                "s3://archive-bucket/file.parquet ", BUCKET))
                .isInstanceOf(ArchivalException.class)
                .hasMessageContaining("whitespace");
    }

    @Test
    void initiateRestoreFailsClosedOnAnUnparseableLocationBeforeAnyS3Call() {
        ArchivalTask task = new ArchivalTask("task-1", "asset-1");
        task.setArchiveStorageLocation("gs://archive-bucket/archives/a1/file.parquet");
        task.setProviderConfiguration(Map.of("s3.bucket", BUCKET));

        assertThatThrownBy(() -> new AwsS3GlacierArchivalProvider().initiateRestore(task, Map.of()))
                .isInstanceOf(ArchivalException.class)
                .hasMessageContaining("Invalid s3 location");
    }
}
