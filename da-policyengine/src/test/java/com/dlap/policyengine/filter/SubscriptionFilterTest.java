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

package com.dlap.policyengine.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.dlap.policyengine.dto.EventStreamDTO;
import com.dlap.policyengine.model.EventSeverity;
import com.dlap.policyengine.model.EventType;

/**
 * SEC-04: the typed subscription filter that replaced MVEL rule conditions. Empty fields
 * do not constrain, non-empty fields all hold, and a field the event cannot answer does
 * not match.
 */
class SubscriptionFilterTest {

    private static final List<String> KNOWN = List.of("da-catalog", "da-discovery", "da-policyengine");

    private static EventStreamDTO event(EventType type, EventSeverity severity, String source, Map<String, Object> data) {
        EventStreamDTO e = new EventStreamDTO();
        e.setEventType(type);
        e.setSeverity(severity);
        e.setSourceService(source);
        e.setEventData(data);
        return e;
    }

    private static SubscriptionFilter filter(List<EventType> types, List<EventSeverity> severities,
                                             List<String> sources, List<String> prefixes, List<String> labels) {
        return SubscriptionFilter.of(types, severities, sources, prefixes, labels);
    }

    @Test
    void emptyFilterMatchesEveryEventButNull() {
        SubscriptionFilter any = filter(null, null, null, null, null);
        assertThat(any.matches(event(EventType.ASSET_DISCOVERED, null, null, null))).isTrue();
        assertThat(any.matches(null)).isFalse();
    }

    @Test
    void eventTypeSeverityAndSourceAreConjunctive() {
        SubscriptionFilter f = filter(List.of(EventType.COMPLIANCE_VIOLATION), List.of(EventSeverity.HIGH),
                List.of("da-autocompliance"), null, null);
        assertThat(f.matches(event(EventType.COMPLIANCE_VIOLATION, EventSeverity.HIGH, "da-autocompliance", Map.of())))
                .isTrue();
        assertThat(f.matches(event(EventType.ASSET_DISCOVERED, EventSeverity.HIGH, "da-autocompliance", Map.of())))
                .isFalse();
        assertThat(f.matches(event(EventType.COMPLIANCE_VIOLATION, EventSeverity.LOW, "da-autocompliance", Map.of())))
                .isFalse();
        assertThat(f.matches(event(EventType.COMPLIANCE_VIOLATION, EventSeverity.HIGH, "da-catalog", Map.of())))
                .isFalse();
        assertThat(f.matches(event(EventType.COMPLIANCE_VIOLATION, EventSeverity.HIGH, null, Map.of())))
                .as("an event without a source cannot pass a source filter").isFalse();
    }

    @Test
    void missingSeverityCountsAsInfoLikeTheLedger() {
        assertThat(filter(null, List.of(EventSeverity.INFO), null, null, null)
                .matches(event(EventType.ASSET_DISCOVERED, null, null, Map.of()))).isTrue();
        assertThat(filter(null, List.of(EventSeverity.HIGH), null, null, null)
                .matches(event(EventType.ASSET_DISCOVERED, null, null, Map.of()))).isFalse();
    }

    @Test
    void assetPrefixMatchesTheCanonicalUriAsAPlainPrefix() {
        SubscriptionFilter f = filter(null, null, null, List.of("s3://finance/reports/", "bq://analytics-prod/sales"), null);
        assertThat(f.matches(event(EventType.ASSET_DISCOVERED, null, null,
                Map.of("assetUri", "s3://finance/reports/2024/q1.csv")))).isTrue();
        assertThat(f.matches(event(EventType.ASSET_DISCOVERED, null, null,
                Map.of("assetUri", "s3://finance/raw/q1.csv")))).isFalse();
        // An audit-log alias is normalized before the comparison.
        assertThat(f.matches(event(EventType.ASSET_ACCESS_DETECTED, null, null,
                Map.of("assetUri", "projects/analytics-prod/datasets/sales/tables/orders")))).isTrue();
    }

    @Test
    void assetPrefixNeverMatchesAnEventWithoutAResolvableAsset() {
        SubscriptionFilter f = filter(null, null, null, List.of("s3://"), null);
        assertThat(f.matches(event(EventType.SYSTEM_ALERT, null, null, Map.of()))).isFalse();
        assertThat(f.matches(event(EventType.SYSTEM_ALERT, null, null, Map.of("assetUri", "unknown thing"))))
                .isFalse();
    }

    @Test
    void labelsMatchAnyListedLabelWithoutCase() {
        SubscriptionFilter f = filter(null, null, null, null, List.of("PII", "gdpr"));
        assertThat(f.labels()).containsExactlyInAnyOrder("pii", "gdpr");
        assertThat(f.matches(event(EventType.ASSET_CLASSIFICATION_CHANGED, null, null,
                Map.of("labels", List.of("pii"))))).isTrue();
        assertThat(f.matches(event(EventType.ASSET_CLASSIFICATION_CHANGED, null, null,
                Map.of("labelDetails", Map.of("details", Map.of("labels_assigned", "Finance,GDPR"))))))
                .isTrue();
        assertThat(f.matches(event(EventType.ASSET_CLASSIFICATION_CHANGED, null, null,
                Map.of("labels", List.of("Finance"))))).isFalse();
        assertThat(f.matches(event(EventType.ASSET_CLASSIFICATION_CHANGED, null, null, Map.of())))
                .as("an event naming no label cannot pass a label filter").isFalse();
    }

    @Test
    void allFiveFieldsTogether() {
        SubscriptionFilter f = filter(List.of(EventType.ASSET_CLASSIFICATION_CHANGED), List.of(EventSeverity.LOW),
                List.of("da-catalog"), List.of("gs://landing"), List.of("PII"));
        Map<String, Object> data = Map.of(
                "assetSnapshot", Map.of("nativeAssetId", "gs://landing-zone", "cloudProvider", "GCP"),
                "labelDetails", Map.of("details", Map.of("labels_assigned", "PII")));
        assertThat(f.matches(event(EventType.ASSET_CLASSIFICATION_CHANGED, EventSeverity.LOW, "da-catalog", data)))
                .isTrue();
        assertThat(f.matches(event(EventType.ASSET_CLASSIFICATION_CHANGED, EventSeverity.LOW, "da-discovery", data)))
                .isFalse();
    }

    @Test
    void nullValuesAreDroppedAndSetsAreCopied() {
        List<String> labels = new ArrayList<>();
        labels.add(null);
        labels.add("PII");
        SubscriptionFilter f = SubscriptionFilter.of(null, null, null, Collections.singletonList(null), labels);
        assertThat(f.assetPrefixes()).isEmpty();
        assertThat(f.labels()).containsExactly("pii");
        assertThat(new SubscriptionFilter(null, null, null, null, Set.of("X")).labels()).containsExactly("x");
    }

    // ----- input cleaning -------------------------------------------------------------------

    @Test
    void cleanAssetPrefixesStripsLowerCasesTheSchemeAndDeduplicates() {
        assertThat(SubscriptionFilter.cleanAssetPrefixes(List.of("  S3://Finance/Reports/ ", "s3://Finance/Reports/",
                "bq://analytics-prod/sales"))).containsExactly("s3://Finance/Reports/", "bq://analytics-prod/sales");
        assertThat(SubscriptionFilter.cleanAssetPrefixes(null)).isEmpty();
    }

    @Test
    void cleanAssetPrefixesRejectsBlankLongControlAndTooMany() {
        assertThatThrownBy(() -> SubscriptionFilter.cleanAssetPrefixes(List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blank");
        assertThatThrownBy(() -> SubscriptionFilter.cleanAssetPrefixes(
                List.of("s3://" + "a".repeat(SubscriptionFilter.MAX_ASSET_PREFIX_LENGTH))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most");
        assertThatThrownBy(() -> SubscriptionFilter.cleanAssetPrefixes(List.of("s3://b/" + (char) 7)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("control");
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= SubscriptionFilter.MAX_VALUES; i++) {
            many.add("s3://b" + i);
        }
        assertThatThrownBy(() -> SubscriptionFilter.cleanAssetPrefixes(many))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(SubscriptionFilter.MAX_VALUES));
    }

    @Test
    void cleanLabelsDeduplicatesWithoutCaseAndKeepsTheFirstSpelling() {
        assertThat(SubscriptionFilter.cleanLabels(List.of(" PII ", "pii", "Finance"))).containsExactly("PII", "Finance");
        assertThatThrownBy(() -> SubscriptionFilter.cleanLabels(List.of("x".repeat(256))))
                .isInstanceOf(IllegalArgumentException.class);
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> SubscriptionFilter.cleanLabels(withNull)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cleanSourceServicesAcceptsOnlyKnownServices() {
        assertThat(SubscriptionFilter.cleanSourceServices(List.of("da-catalog", " da-catalog "), KNOWN))
                .containsExactly("da-catalog");
        assertThatThrownBy(() -> SubscriptionFilter.cleanSourceServices(List.of("evil-service"), KNOWN))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown source service");
    }

    @Test
    void cleanValuesCapsAndDeduplicates() {
        assertThat(SubscriptionFilter.cleanValues(List.of(EventType.SYSTEM_ALERT, EventType.SYSTEM_ALERT), "eventTypes"))
                .containsExactly(EventType.SYSTEM_ALERT);
        List<EventType> many = new ArrayList<>();
        for (int i = 0; i <= SubscriptionFilter.MAX_VALUES; i++) {
            many.add(EventType.SYSTEM_ALERT);
        }
        assertThatThrownBy(() -> SubscriptionFilter.cleanValues(many, "eventTypes"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eventTypes");
    }
}
