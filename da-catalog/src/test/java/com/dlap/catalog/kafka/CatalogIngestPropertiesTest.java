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

package com.dlap.catalog.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dlap.common.assetid.AssetIdNormalizer;

/**
 * Governed prefixes (ADR-0046 section 1) and the fail-fast check of the ingest settings.
 */
class CatalogIngestPropertiesTest {

    @Test
    void noPrefixMeansNoObjectIsGoverned() {
        CatalogIngestProperties p = new CatalogIngestProperties();

        assertThat(p.isGoverned(AssetIdNormalizer.normalize("gs://gov-bucket/pii/x.csv").orElseThrow())).isFalse();
    }

    @Test
    void onlyObjectsUnderAPrefixAreGoverned() {
        CatalogIngestProperties p = new CatalogIngestProperties();
        p.setGovernedPrefixes(List.of("gs://gov-bucket/pii/", " s3://lake-bucket/curated/ "));

        assertThat(p.isGoverned(AssetIdNormalizer.normalize("gs://gov-bucket/pii/x.csv").orElseThrow())).isTrue();
        assertThat(p.isGoverned(AssetIdNormalizer.normalize("s3://lake-bucket/curated/t/part-0").orElseThrow())).isTrue();
        assertThat(p.isGoverned(AssetIdNormalizer.normalize("gs://gov-bucket/public/x.csv").orElseThrow())).isFalse();
        assertThat(p.isGoverned(AssetIdNormalizer.normalize("gs://gov-bucket").orElseThrow()))
                .as("a container is never governed").isFalse();
        assertThatCode(p::validate).doesNotThrowAnyException();
    }

    @Test
    void aPrefixThatIsNotAnObjectUriPrefixFailsFast() {
        CatalogIngestProperties bucketOnly = new CatalogIngestProperties();
        bucketOnly.setGovernedPrefixes(List.of("gs://okay-bucket/a/", "projects/_/buckets/gov-bucket"));
        CatalogIngestProperties table = new CatalogIngestProperties();
        table.setGovernedPrefixes(List.of("bq://proj-one/ds/"));

        assertThatThrownBy(bucketOnly::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("governed-prefixes[1]");
        assertThatThrownBy(table::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void retentionAndBatchSizesMustBePositive() {
        CatalogIngestProperties p = new CatalogIngestProperties();
        p.getProcessedEvent().setRetention(Duration.ZERO);

        assertThatThrownBy(p::validate).hasMessageContaining("retention");

        CatalogIngestProperties q = new CatalogIngestProperties();
        q.getMatch().setIndexBatchSize(0);
        assertThatThrownBy(q::validate).hasMessageContaining("index-batch-size");
    }
}
