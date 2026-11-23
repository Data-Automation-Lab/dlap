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


package com.dlap.autodelete.providers.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dlap.autodelete.nativerule.BucketVersioning;
import com.dlap.autodelete.nativerule.NativeRule;
import com.dlap.autodelete.nativerule.TestEndpoints;
import com.dlap.common.assetid.AssetIdNormalizer;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketLifecycleConfiguration;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.LifecycleExpiration;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.LifecycleRuleFilter;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The S3 applier's apply and remove against moto (decision D5: moto first). moto implements
 * {@code Get/Put/DeleteBucketLifecycle(Configuration)}, so what a restore of a rule in force depends on is
 * tested for real: DLAP's rule goes in beside the owner's, comes out again without touching the owner's, and
 * the last rule going takes the lifecycle configuration with it (S3 refuses an empty list). What moto cannot
 * show is that S3 itself expires objects; that is the log proof's job (README, "What a proof means").
 * Requires Docker.
 */
@Testcontainers
class S3LifecycleRuleApplierContainerTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:15:00Z");

    @Container
    static final GenericContainer<?> MOTO = new GenericContainer<>(DockerImageName.parse("motoserver/moto:5.1.4"))
            .withExposedPorts(5000)
            .waitingFor(Wait.forHttp("/moto-api/").forPort(5000).forStatusCode(200));

    private static S3Client s3;
    private String bucket;
    private S3LifecycleRuleApplier applier;

    private static S3Client clientFor() {
        return S3Client.builder().region(Region.US_EAST_1)
                .endpointOverride(URI.create("http://" + MOTO.getHost() + ":" + MOTO.getMappedPort(5000)))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    @BeforeAll
    static void connect() {
        s3 = clientFor();
    }

    @AfterAll
    static void close() {
        if (s3 != null) {
            s3.close();
        }
    }

    @BeforeEach
    void bucket() {
        bucket = "ret04-" + Long.toHexString(System.nanoTime());
        s3.createBucket(b -> b.bucket(bucket));
        applier = new S3LifecycleRuleApplier(name -> clientFor());
    }

    private NativeRule rule() {
        return new S3LifecycleRuleGenerator().generate(
                AssetIdNormalizer.normalize("s3://" + bucket).orElseThrow(),
                UUID.fromString("abcdabcd-0000-4000-8000-0000000000c1"), NOW);
    }

    private List<LifecycleRule> stored() {
        return s3.getBucketLifecycleConfiguration(b -> b.bucket(bucket)).rules();
    }

    private static LifecycleRule owners() {
        return LifecycleRule.builder().id("team-expire-tmp").status(ExpirationStatus.ENABLED)
                .filter(LifecycleRuleFilter.builder().prefix("tmp/").build())
                .expiration(LifecycleExpiration.builder().days(7).build()).build();
    }

    /** Q-631: a restore of a rule in force removes DLAP's rule and the owner's rule is still there. */
    @Test
    void removeTakesOutDlapsRuleAndLeavesTheOwnersRuleInTheBucket() {
        s3.putBucketLifecycleConfiguration(b -> b.bucket(bucket)
                .lifecycleConfiguration(BucketLifecycleConfiguration.builder().rules(owners()).build()));
        applier.apply(rule());
        assertThat(stored()).extracting(LifecycleRule::id)
                .containsExactlyInAnyOrder("team-expire-tmp", rule().ruleId());

        applier.remove(rule());

        assertThat(stored()).extracting(LifecycleRule::id).containsExactly("team-expire-tmp");
    }

    @Test
    void removeOfTheOnlyRuleLeavesABucketWithoutALifecycleConfiguration() {
        applier.apply(rule());
        assertThat(stored()).hasSize(1);

        applier.remove(rule());

        assertThatThrownBy(this::stored).isInstanceOf(S3Exception.class)
                .satisfies(e -> assertThat(((S3Exception) e).awsErrorDetails().errorCode())
                        .isEqualTo("NoSuchLifecycleConfiguration"));
    }

    /** Q-682 (RET-06): the second removal finds no rule, says so, and changes nothing. */
    @Test
    void removingTwiceIsHarmlessAndTheSecondRemovalSaysTheRuleWasNotFound() {
        applier.apply(rule());
        assertThat(applier.remove(rule())).isTrue();
        assertThat(applier.remove(rule())).isFalse();
        assertThatThrownBy(this::stored).isInstanceOf(S3Exception.class);
    }

    /** Q-677 (RET-06): moto stores the age-based rule as S3 would: Expiration.Days 1 and no date. */
    @Test
    void theAgeBasedRuleIsStoredWithExpirationDays() {
        applier.apply(rule());

        LifecycleRule stored = stored().get(0);
        assertThat(stored.expiration().days()).isEqualTo(1);
        assertThat(stored.expiration().date()).isNull();
    }

    /**
     * Q-656 (RET-06): GetBucketVersioning against moto. A bucket whose versioning was never turned on is
     * UNVERSIONED; Enabled and Suspended are versioned, which the executor refuses.
     */
    @Test
    void theApplierReadsTheBucketsVersioningNeverEnabledSuspended() {
        assertThat(applier.versioning(rule())).isEqualTo(BucketVersioning.UNVERSIONED);

        s3.putBucketVersioning(b -> b.bucket(bucket).versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
        assertThat(applier.versioning(rule())).isEqualTo(BucketVersioning.ENABLED);
        assertThat(applier.versioning(rule()).versioned()).isTrue();

        s3.putBucketVersioning(b -> b.bucket(bucket).versioningConfiguration(v -> v.status(BucketVersioningStatus.SUSPENDED)));
        assertThat(applier.versioning(rule())).isEqualTo(BucketVersioning.SUSPENDED);
        assertThat(applier.versioning(rule()).versioned()).isTrue();
    }

    /**
     * Q-713 (RET-08, decision D38): the applier Spring builds from {@code dlap.autodelete.test-endpoint.s3}
     * (path-style) reaches the emulator with no client of the test's own: it reads versioning, applies the age-based
     * rule, and removes it again. The start-up WARN names the endpoint.
     */
    @Test
    void theTestEndpointSettingAppliesReadsAndRemovesARuleOnTheEmulator() {
        String endpoint = "http://" + MOTO.getHost() + ":" + MOTO.getMappedPort(5000);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        Logger log = (Logger) LoggerFactory.getLogger(S3LifecycleRuleApplier.class);
        logs.start();
        log.addAppender(logs);
        S3LifecycleRuleApplier viaSetting;
        try {
            viaSetting = new S3LifecycleRuleApplier(new TestEndpoints(endpoint, true, "", ""));
        } finally {
            log.detachAppender(logs);
        }
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("TEST-ONLY").contains(endpoint).contains("path-style");
        });

        assertThat(viaSetting.versioning(rule())).isEqualTo(BucketVersioning.UNVERSIONED);
        viaSetting.apply(rule());
        assertThat(stored()).extracting(LifecycleRule::id).containsExactly(rule().ruleId());
        assertThat(stored().get(0).expiration().days()).isEqualTo(1);

        assertThat(viaSetting.remove(rule())).isTrue();
        assertThatThrownBy(this::stored).isInstanceOf(S3Exception.class);
    }

    /** Q-713: without the setting, the production applier logs no test-endpoint warning. */
    @Test
    void noTestEndpointMeansNoWarning() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        Logger log = (Logger) LoggerFactory.getLogger(S3LifecycleRuleApplier.class);
        logs.start();
        log.addAppender(logs);
        try {
            new S3LifecycleRuleApplier(TestEndpoints.none());
        } finally {
            log.detachAppender(logs);
        }
        assertThat(logs.list).noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains("TEST-ONLY"));
    }

    /** Q-656: a bucket the platform cannot answer for is UNKNOWN, never UNVERSIONED. */
    @Test
    void aBucketThatCannotBeReadIsUnknownVersioning() {
        S3LifecycleRuleApplier missing = new S3LifecycleRuleApplier(name -> clientFor());
        NativeRule other = new S3LifecycleRuleGenerator().generate(
                AssetIdNormalizer.normalize("s3://no-such-bucket-" + Long.toHexString(System.nanoTime())).orElseThrow(),
                UUID.fromString("abcdabcd-0000-4000-8000-0000000000c2"), NOW);

        assertThat(missing.versioning(other)).isEqualTo(BucketVersioning.UNKNOWN);
    }
}
