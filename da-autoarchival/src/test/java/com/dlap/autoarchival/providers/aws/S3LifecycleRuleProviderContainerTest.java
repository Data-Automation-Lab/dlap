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

package com.dlap.autoarchival.providers.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.dlap.autoarchival.rule.NativeRuleProvider;
import com.dlap.autoarchival.rule.NativeRuleProvider.RuleSpec;
import com.dlap.autoarchival.rule.RuleException;
import com.dlap.autoarchival.rule.RuleScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The S3 rule provider against moto (decision D5: moto first). moto implements
 * {@code Get/PutBucketLifecycleConfiguration} with the same merge-by-replacement semantics as S3, so
 * the property that matters is tested for real here: a write must keep the bucket's other rules.
 * Requires Docker. What moto cannot show is that S3 runs the transition; that is the log proof's job.
 */
@Testcontainers
class S3LifecycleRuleProviderContainerTest {

    @Container
    static final GenericContainer<?> MOTO = new GenericContainer<>(DockerImageName.parse("motoserver/moto:5.1.4"))
            .withExposedPorts(5000)
            .waitingFor(Wait.forHttp("/moto-api/").forPort(5000).forStatusCode(200));

    private static S3Client s3;
    private S3LifecycleRuleProvider provider;
    private String bucket;

    @BeforeAll
    static void connect() {
        s3 = clientFor();
    }

    private static S3Client clientFor() {
        return S3Client.builder().region(Region.US_EAST_1)
                .endpointOverride(URI.create("http://" + MOTO.getHost() + ":" + MOTO.getMappedPort(5000)))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    @AfterAll
    static void close() {
        if (s3 != null) {
            s3.close();
        }
    }

    @BeforeEach
    void bucket() {
        bucket = "pilot-" + Long.toHexString(System.nanoTime());
        s3.createBucket(b -> b.bucket(bucket));
        provider = new S3LifecycleRuleProvider(() -> clientFor());
    }

    private static RuleSpec spec(String bucket, String prefix, String cls, int days) {
        return new RuleSpec("dlap-archive-" + Integer.toHexString((prefix + cls + days).hashCode()) + "0000",
                new RuleScope("AWS", "", bucket, prefix, false), cls, days);
    }

    private List<LifecycleRule> stored() {
        return s3.getBucketLifecycleConfiguration(b -> b.bucket(bucket)).rules();
    }

    @Test
    void writesTheTransitionRuleAndReadsItBack() {
        RuleSpec spec = spec(bucket, "finance/", "GLACIER_IR", 0);

        NativeRuleProvider.ApplyResult result = provider.apply(spec);

        assertThat(result.alreadyPresent()).isFalse();
        assertThat(result.rulesBefore()).isZero();
        assertThat(result.rulesAfter()).isEqualTo(1);
        LifecycleRule r = stored().get(0);
        assertThat(r.id()).isEqualTo(spec.ruleId());
        assertThat(r.status()).isEqualTo(ExpirationStatus.ENABLED);
        assertThat(r.filter().prefix()).isEqualTo("finance/");
        assertThat(r.transitions()).singleElement().satisfies(t -> {
            assertThat(t.storageClassAsString()).isEqualTo("GLACIER_IR");
            assertThat(t.days()).isZero();
        });
    }

    @Test
    void keepsTheRulesTheBucketAlreadyHad() {
        LifecycleRule foreign = LifecycleRule.builder().id("team-expire-tmp").status(ExpirationStatus.ENABLED)
                .filter(LifecycleRuleFilter.builder().prefix("tmp/").build())
                .expiration(LifecycleExpiration.builder().days(7).build()).build();
        s3.putBucketLifecycleConfiguration(b -> b.bucket(bucket)
                .lifecycleConfiguration(BucketLifecycleConfiguration.builder().rules(foreign).build()));

        NativeRuleProvider.ApplyResult result = provider.apply(spec(bucket, "finance/", "GLACIER_IR", 0));

        assertThat(result.rulesBefore()).isEqualTo(1);
        assertThat(result.rulesAfter()).isEqualTo(2);
        assertThat(stored()).extracting(LifecycleRule::id).contains("team-expire-tmp");
        LifecycleRule kept = stored().stream().filter(r -> "team-expire-tmp".equals(r.id())).findFirst().orElseThrow();
        assertThat(kept.expiration().days()).isEqualTo(7);
        assertThat(kept.filter().prefix()).isEqualTo("tmp/");
    }

    @Test
    void aRepeatedApplyIsANoOp() {
        RuleSpec spec = spec(bucket, "finance/", "DEEP_ARCHIVE", 90);
        provider.apply(spec);

        NativeRuleProvider.ApplyResult again = provider.apply(spec);

        assertThat(again.alreadyPresent()).isTrue();
        assertThat(stored()).hasSize(1);
    }

    @Test
    void differentPrefixesAreDifferentRulesAndRemoveTakesOutOnlyTheNamedOne() {
        RuleSpec a = spec(bucket, "a/", "GLACIER_IR", 0);
        RuleSpec b = spec(bucket, "b/", "GLACIER_IR", 0);
        provider.apply(a);
        provider.apply(b);
        assertThat(stored()).hasSize(2);

        assertThat(provider.remove(a)).isTrue();
        assertThat(stored()).extracting(LifecycleRule::id).containsExactly(b.ruleId());
        assertThat(provider.remove(a)).isFalse();

        assertThat(provider.remove(b)).isTrue();
        assertThatThrownBy(() -> s3.getBucketLifecycleConfiguration(g -> g.bucket(bucket)))
                .isInstanceOf(S3Exception.class);
        assertThat(provider.inventory(a).totalRules()).isZero();
    }

    @Test
    void inventoryOfABucketWithoutLifecycleConfigurationIsZeroNotAnError() {
        NativeRuleProvider.Inventory inv = provider.inventory(spec(bucket, "x/", "GLACIER_IR", 0));

        assertThat(inv.totalRules()).isZero();
        assertThat(inv.providerMax()).isEqualTo(1000);
        assertThat(inv.specPresent()).isFalse();
    }

    @Test
    void inventoryRecognisesTheRuleOnceItIsApplied() {
        RuleSpec spec = spec(bucket, "x/", "GLACIER_IR", 0);
        provider.apply(spec);

        assertThat(provider.inventory(spec).specPresent()).isTrue();
        assertThat(provider.inventory(spec(bucket, "x/", "GLACIER_IR", 1)).specPresent()).isFalse();
    }

    @Test
    void theListingIsBoundedAndKeepsToThePrefix() {
        for (int i = 0; i < 5; i++) {
            s3.putObject(b -> b.bucket(bucket).key("finance/f.csv"), RequestBody.fromString("x"));
        }
        for (int i = 0; i < 5; i++) {
            String key = "finance/part-" + i + ".csv";
            s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromString("12345"));
        }
        s3.putObject(b -> b.bucket(bucket).key("other/o.csv"), RequestBody.fromString("x"));

        NativeRuleProvider.Listing all = provider.list(new RuleScope("AWS", "", bucket, "finance/", false), 100);
        NativeRuleProvider.Listing some = provider.list(new RuleScope("AWS", "", bucket, "finance/", false), 3);

        assertThat(all.entries()).hasSize(6).allSatisfy(e -> assertThat(e.key()).startsWith("finance/"));
        assertThat(all.truncated()).isFalse();
        assertThat(some.entries()).hasSize(3);
        assertThat(some.truncated()).isTrue();
        assertThat(some.available()).isTrue();
    }

    @Test
    void aMissingBucketIsAProviderErrorNotAnEmptyAnswer() {
        assertThatThrownBy(() -> provider.inventory(spec("no-such-bucket-xyz", "x/", "GLACIER_IR", 0)))
                .isInstanceOfSatisfying(RuleException.class,
                        e -> assertThat(e.reason()).isEqualTo(RuleException.Reason.PROVIDER_ERROR));
        assertThatThrownBy(() -> provider.apply(spec("no-such-bucket-xyz", "x/", "GLACIER_IR", 0)))
                .isInstanceOf(RuleException.class);
    }

    @Test
    void theRenderedDocumentIsWhatPutBucketLifecycleConfigurationTakes() throws Exception {
        JsonNode rule = new ObjectMapper().readTree(provider.render(spec(bucket, "finance/", "DEEP_ARCHIVE", 30)))
                .path("Rules").get(0);

        assertThat(rule.path("Status").asText()).isEqualTo("Enabled");
        assertThat(rule.path("Filter").path("Prefix").asText()).isEqualTo("finance/");
        assertThat(rule.path("Transitions").get(0).path("Days").asInt()).isEqualTo(30);
        assertThat(rule.path("Transitions").get(0).path("StorageClass").asText()).isEqualTo("DEEP_ARCHIVE");
    }
}
