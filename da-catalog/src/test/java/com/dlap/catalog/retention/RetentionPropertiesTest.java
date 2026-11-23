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

package com.dlap.catalog.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.dlap.common.model.RetentionBasis;

/**
 * Binding and validation of {@code dlap.catalog.retention.*}: the defaults are the ones the
 * service ships, label defaults bind from indexed keys, and an invalid value stops the service at
 * startup instead of misbehaving in the sweep.
 */
class RetentionPropertiesTest {

    private static RetentionProperties bind(Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("dlap.catalog.retention", RetentionProperties.class)
                .orElseGet(RetentionProperties::new);
    }

    @Test
    void defaultsAreValidAndMatchWhatTheServiceShips() {
        RetentionProperties p = new RetentionProperties();

        p.validate();

        assertThat(p.getPageSize()).isEqualTo(200);
        assertThat(p.getEvaluatedAtRefresh()).isEqualTo(Duration.ofDays(1));
        assertThat(p.getLabelDefaults()).isEmpty();
        assertThat(p.getLegalHoldLabel()).isEmpty();
        assertThat(p.getPublish().getBatchSize()).isEqualTo(100);
        assertThat(p.getPublish().getOutboxRetention()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void labelDefaultsBindFromIndexedKeysAndBecomeLabelDefaultRules() {
        RetentionProperties p = bind(Map.of(
                "dlap.catalog.retention.label-defaults[0].label", "PII",
                "dlap.catalog.retention.label-defaults[0].days", "365",
                "dlap.catalog.retention.label-defaults[1].label", "Finance",
                "dlap.catalog.retention.label-defaults[1].days", "2555",
                "dlap.catalog.retention.label-defaults[1].basis", "CREATED",
                "dlap.catalog.retention.legal-hold-label", "LEGAL_HOLD"));

        p.validate();

        List<RetentionProperties.LabelDefault> defaults = p.getLabelDefaults();
        assertThat(defaults).hasSize(2);
        assertThat(defaults.get(0).toRule())
                .isEqualTo(RetentionRule.ofDays(RuleSource.LABEL_DEFAULT, RetentionBasis.LAST_ACCESSED, 365));
        assertThat(defaults.get(1).toRule())
                .isEqualTo(RetentionRule.ofDays(RuleSource.LABEL_DEFAULT, RetentionBasis.CREATED, 2555));
        assertThat(p.getLegalHoldLabel()).isEqualTo("LEGAL_HOLD");
    }

    @Test
    void anInvalidLabelDefaultFailsFast() {
        assertThatThrownBy(() -> bind(Map.of("dlap.catalog.retention.label-defaults[0].label", "PII")).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PII");
        assertThatThrownBy(() -> bind(Map.of(
                "dlap.catalog.retention.label-defaults[0].label", "PII",
                "dlap.catalog.retention.label-defaults[0].days", "-1")).validate())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bind(Map.of(
                "dlap.catalog.retention.label-defaults[0].label", "PII",
                "dlap.catalog.retention.label-defaults[0].days", "400000")).validate())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bind(Map.of(
                "dlap.catalog.retention.label-defaults[0].label", "PII",
                "dlap.catalog.retention.label-defaults[0].days", "5",
                "dlap.catalog.retention.label-defaults[0].basis", "FIXED_DATE")).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("day-based");
    }

    @Test
    void aLabelNamedTwiceIgnoringCaseFailsFast() {
        assertThatThrownBy(() -> bind(Map.of(
                "dlap.catalog.retention.label-defaults[0].label", "PII",
                "dlap.catalog.retention.label-defaults[0].days", "5",
                "dlap.catalog.retention.label-defaults[1].label", "pii",
                "dlap.catalog.retention.label-defaults[1].days", "9")).validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("twice");
    }

    @Test
    void sizesAndDurationsMustBeInRange() {
        assertThatThrownBy(() -> bind(Map.of("dlap.catalog.retention.page-size", "0")).validate())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bind(Map.of("dlap.catalog.retention.page-size", "5001")).validate())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bind(Map.of("dlap.catalog.retention.evaluated-at-refresh", "-PT1H")).validate())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bind(Map.of("dlap.catalog.retention.publish.batch-size", "0")).validate())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bind(Map.of("dlap.catalog.retention.publish.send-timeout", "PT0S")).validate())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> bind(Map.of("dlap.catalog.retention.publish.outbox-retention", "PT0S")).validate())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void zeroRefreshIsAllowedAndMeansEveryRun() {
        RetentionProperties p = bind(Map.of("dlap.catalog.retention.evaluated-at-refresh", "PT0S"));

        p.validate();

        assertThat(p.getEvaluatedAtRefresh()).isEqualTo(Duration.ZERO);
    }
}
