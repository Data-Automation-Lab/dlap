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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class TierResolverTest {

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
        // neutral tiers
        "AWS, COOL, STANDARD_IA", "AWS, COLD, GLACIER_IR", "AWS, ARCHIVE, DEEP_ARCHIVE",
        "GCP, COOL, NEARLINE", "GCP, COLD, COLDLINE", "GCP, ARCHIVE, ARCHIVE",
        "AZURE, COOL, Cool", "AZURE, COLD, Cold", "AZURE, ARCHIVE, Archive",
        // provider classes
        "AWS, GLACIER_IR, GLACIER_IR", "AWS, GLACIER, GLACIER", "AWS, DEEP_ARCHIVE, DEEP_ARCHIVE",
        "AWS, ONEZONE_IA, ONEZONE_IA", "GCP, NEARLINE, NEARLINE", "GCP, COLDLINE, COLDLINE",
        // the AWS-flavoured names the shared configuration used, on the other clouds
        "GCP, GLACIER, ARCHIVE", "GCP, DEEP_ARCHIVE, ARCHIVE", "AZURE, GLACIER, Archive",
        "AZURE, STANDARD_IA, Cool", "GCP, STANDARD_IA, NEARLINE",
        // case and spaces do not matter
        "aws, ' cold ', GLACIER_IR", "gcp, coldline, COLDLINE"})
    void mapsATierToTheProvidersClass(String provider, String requested, String expected) {
        assertThat(TierResolver.resolve(provider, requested).name()).isEqualTo(expected);
    }

    @Test
    void theDefaultTierIsColdAndIsValidOnEveryCloud() {
        assertThat(TierResolver.DEFAULT_TIER).isEqualTo("COLD");
        assertThat(TierResolver.resolve("AWS", null).name()).isEqualTo("GLACIER_IR");
        assertThat(TierResolver.resolve("GCP", " ").name()).isEqualTo("COLDLINE");
        assertThat(TierResolver.resolve("AZURE", null).name()).isEqualTo("Cold");
    }

    @ParameterizedTest
    @ValueSource(strings = {"STANDARD_ARCHIVE", "STANDARD", "HOT", "REGIONAL", "MULTI_REGIONAL", "FROZEN", "glacier-ir"})
    void refusesNamesThatAreNotAnArchiveClassOnTheCloud(String requested) {
        // STANDARD_ARCHIVE was the shipped configuration default and is not an S3 storage class: every AWS task
        // that used it failed at the provider (RET-03 evidence, ArchivalConfigServiceImpl.java:50-56).
        assertThatThrownBy(() -> TierResolver.resolve("AWS", requested))
                .isInstanceOfSatisfying(RuleException.class, e -> assertThat(e.reason())
                        .isEqualTo(RuleException.Reason.INVALID_TIER));
        assertThatThrownBy(() -> TierResolver.resolve("GCP", requested)).isInstanceOf(RuleException.class);
        assertThatThrownBy(() -> TierResolver.resolve("AZURE", requested)).isInstanceOf(RuleException.class);
    }

    @Test
    void anAwsClassIsNotAcceptedOnGcpWhenItHasNoCounterpart() {
        assertThatThrownBy(() -> TierResolver.resolve("GCP", "ONEZONE_IA")).isInstanceOf(RuleException.class);
        assertThatThrownBy(() -> TierResolver.resolve("AZURE", "ONEZONE_IA")).isInstanceOf(RuleException.class);
    }

    @Test
    void anUnknownProviderIsRefused() {
        assertThatThrownBy(() -> TierResolver.resolve("OCI", "COLD")).isInstanceOfSatisfying(RuleException.class,
                e -> assertThat(e.reason()).isEqualTo(RuleException.Reason.UNSUPPORTED_PROVIDER));
        assertThatThrownBy(() -> TierResolver.resolve(null, "COLD")).isInstanceOf(RuleException.class);
    }

    @Test
    void s3InfrequentAccessNeedsThirtyDaysAndTheOthersNone() {
        assertThat(TierResolver.resolve("AWS", "COOL").minDays()).isEqualTo(30);
        assertThat(TierResolver.resolve("AWS", "ONEZONE_IA").minDays()).isEqualTo(30);
        assertThat(TierResolver.resolve("AWS", "GLACIER_IR").minDays()).isZero();
        assertThat(TierResolver.resolve("AWS", "DEEP_ARCHIVE").minDays()).isZero();
        assertThat(TierResolver.resolve("GCP", "NEARLINE").minDays()).isZero();
    }
}
