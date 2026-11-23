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

package com.dlap.autoarchival.rule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuleScopeTest {

    private static RuleScope parse(String provider, String uri, String extra) {
        return RuleScope.parse(provider, uri, extra, 1024);
    }

    private static RuleException.Reason reasonOf(Runnable r) {
        try {
            r.run();
        } catch (RuleException e) {
            return e.reason();
        }
        throw new AssertionError("no RuleException");
    }

    @Test
    void aBucketAssetIsTheWholeBucket() {
        RuleScope s = parse("AWS", "s3://pilot-data", null);
        assertThat(s.bucket()).isEqualTo("pilot-data");
        assertThat(s.prefix()).isEmpty();
        assertThat(s.wholeBucket()).isTrue();
        assertThat(s.singleObject()).isFalse();
        assertThat(parse("GCP", "gs://b/", null).wholeBucket()).isTrue();
    }

    @Test
    void aBucketAssetIsNarrowedByTheActionPrefix() {
        RuleScope s = parse("GCP", "gs://b", "2019/");
        assertThat(s.prefix()).isEqualTo("2019/");
        assertThat(s.wholeBucket()).isFalse();
        assertThat(s.uri()).isEqualTo("gs://b/2019/");
    }

    @Test
    void aFolderKeyIsAPrefixAndTheActionPrefixExtendsIt() {
        assertThat(parse("AWS", "s3://b/finance/", null).prefix()).isEqualTo("finance/");
        RuleScope s = parse("AWS", "s3://b/finance/", "2019/");
        assertThat(s.prefix()).isEqualTo("finance/2019/");
        assertThat(s.singleObject()).isFalse();
    }

    @Test
    void anObjectKeyIsOneObjectNotAPrefixThatWouldCatchItsNeighbours() {
        RuleScope s = parse("AWS", "s3://b/finance/q1.parquet", null);
        assertThat(s.singleObject()).isTrue();
        assertThat(s.prefix()).isEqualTo("finance/q1.parquet");
        assertThat(s.wholeBucket()).isFalse();
    }

    @Test
    void aPrefixCannotNarrowASingleObject() {
        assertThat(reasonOf(() -> parse("AWS", "s3://b/finance/q1.parquet", "x/")))
                .isEqualTo(RuleException.Reason.UNSAFE_LOCATION);
    }

    @Test
    void azureNamesTheAccountAndTheContainer() {
        RuleScope s = parse("AZURE", "az://acct/raw/2019/", null);
        assertThat(s.account()).isEqualTo("acct");
        assertThat(s.bucket()).isEqualTo("raw");
        assertThat(s.prefix()).isEqualTo("2019/");
        assertThat(s.uri()).isEqualTo("az://acct/raw/2019/");
        // the transitional adapter form of AssetLocationResolver
        assertThat(parse("AZURE", "azure://acct/raw", null).bucket()).isEqualTo("raw");
        assertThat(reasonOf(() -> parse("AZURE", "az://acct", null)))
                .isEqualTo(RuleException.Reason.UNSUPPORTED_ASSET);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bq://proj/dataset.table", "projects/p/datasets/d", "s3a-ish", "https://x.example/o", ""})
    void refusesAnAssetThatIsNotAStorageLocation(String uri) {
        assertThat(reasonOf(() -> parse("AWS", uri, null))).isIn(RuleException.Reason.UNSUPPORTED_ASSET);
        assertThat(reasonOf(() -> parse("GCP", uri, null))).isEqualTo(RuleException.Reason.UNSUPPORTED_ASSET);
    }

    @Test
    void refusesAUrlThatCarriesAQueryBecauseItCouldSelectAVersion() {
        assertThat(reasonOf(() -> parse("AZURE", "https://a.blob.core.windows.net/c/b?sv=2020&sig=SECRET", null)))
                .isEqualTo(RuleException.Reason.UNSAFE_LOCATION);
        assertThatThrownBy(() -> parse("AWS", "https://b.s3.amazonaws.com/k?versionId=1", null))
                .hasMessageNotContaining("versionId=1");
    }

    @Test
    void refusesWhitespaceAroundTheKeyAndControlCharactersInThePrefix() {
        assertThat(reasonOf(() -> parse("AWS", "s3://b/ key/", null))).isEqualTo(RuleException.Reason.UNSAFE_LOCATION);
        assertThat(reasonOf(() -> parse("AWS", "s3://b", "a\nb"))).isEqualTo(RuleException.Reason.UNSAFE_LOCATION);
        assertThat(reasonOf(() -> parse("AWS", "s3://b", "/abs"))).isEqualTo(RuleException.Reason.UNSAFE_LOCATION);
    }

    @Test
    void refusesAPrefixLongerThanTheLimitInBytesNotCharacters() {
        String ok = "a".repeat(1024);
        assertThat(parse("AWS", "s3://b", ok).prefix()).hasSize(1024);
        assertThat(reasonOf(() -> parse("AWS", "s3://b", ok + "a"))).isEqualTo(RuleException.Reason.PREFIX_TOO_LONG);
        // 600 two-byte characters are 1200 bytes
        assertThat(reasonOf(() -> parse("AWS", "s3://b", "é".repeat(600))))
                .isEqualTo(RuleException.Reason.PREFIX_TOO_LONG);
        assertThat(reasonOf(() -> RuleScope.parse("AWS", "s3://b", "abcd", 3)))
                .isEqualTo(RuleException.Reason.PREFIX_TOO_LONG);
    }

    @Test
    void anUnknownProviderIsRefused() {
        assertThat(reasonOf(() -> parse("OCI", "s3://b", null))).isEqualTo(RuleException.Reason.UNSUPPORTED_PROVIDER);
        assertThat(reasonOf(() -> parse(null, "s3://b", null))).isEqualTo(RuleException.Reason.UNSUPPORTED_PROVIDER);
    }
}
