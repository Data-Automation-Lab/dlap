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

package com.dlap.autodelete.nativerule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Q-677 (RET-06): a container rule must be age-based with a day count of at least 1. A date-based or zero-day
 * rule is what the executor refuses at planning with DATE_RULE_UNSUPPORTED.
 */
class ContainerRuleAgeTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private static NativeRule rule(NativeRulePlatform platform, String document) {
        return new NativeRule(platform, "x://t", "dlap-retire-x", NOW, document);
    }

    private static NativeRule s3(String expiration) {
        return rule(NativeRulePlatform.S3_LIFECYCLE, "{\"bucket\":\"b\",\"rules\":[{\"ID\":\"dlap-retire-x\","
                + "\"Expiration\":" + expiration + ",\"NoncurrentVersionExpiration\":{\"NoncurrentDays\":1}}]}");
    }

    private static NativeRule gcs(String condition) {
        return rule(NativeRulePlatform.GCS_LIFECYCLE, "{\"bucket\":\"b\",\"lifecycle\":{\"rule\":[{\"action\":"
                + "{\"type\":\"Delete\"},\"condition\":" + condition + "}]}}");
    }

    private static NativeRule azure(String baseBlobDelete, String snapshotDelete) {
        return rule(NativeRulePlatform.AZURE_BLOB_LIFECYCLE, "{\"rules\":[{\"name\":\"dlapretirex\",\"definition\":"
                + "{\"actions\":{\"baseBlob\":{\"delete\":" + baseBlobDelete + "},\"snapshot\":{\"delete\":"
                + snapshotDelete + "}}}}]}");
    }

    @Test
    void anS3RuleNeedsExpirationDaysOfAtLeastOneAndNoDate() {
        assertThat(ContainerRuleAge.violation(s3("{\"Days\":1}"))).isEmpty();
        assertThat(ContainerRuleAge.violation(s3("{\"Days\":30}"))).isEmpty();
        assertThat(ContainerRuleAge.violation(s3("{\"Date\":\"2026-11-02T00:00:00Z\"}")))
                .hasValueSatisfying(r -> assertThat(r).contains("expires at a date"));
        assertThat(ContainerRuleAge.violation(s3("{\"Days\":0}"))).isPresent();
        assertThat(ContainerRuleAge.violation(s3("{}"))).isPresent();
        assertThat(ContainerRuleAge.violation(rule(NativeRulePlatform.S3_LIFECYCLE,
                "{\"rules\":[{\"ID\":\"r\",\"Expiration\":{\"Days\":1},\"NoncurrentVersionExpiration\":"
                        + "{\"NoncurrentDays\":0}}]}"))).as("a zero-day noncurrent expiration").isPresent();
        assertThat(ContainerRuleAge.violation(rule(NativeRulePlatform.S3_LIFECYCLE,
                "{\"rules\":[{\"ID\":\"r\",\"Expiration\":{\"Days\":1},\"Transitions\":[{\"Date\":\"2026-12-01\"}]}]}")))
                .as("a dated transition").isPresent();
    }

    @Test
    void aGcsRuleNeedsAnAgeOfAtLeastOneAndNoDateCondition() {
        assertThat(ContainerRuleAge.violation(gcs("{\"age\":1}"))).isEmpty();
        assertThat(ContainerRuleAge.violation(gcs("{\"isLive\":false,\"daysSinceNoncurrentTime\":1}"))).isEmpty();
        assertThat(ContainerRuleAge.violation(gcs("{\"createdBefore\":\"2026-11-02\"}")))
                .hasValueSatisfying(r -> assertThat(r).contains("createdBefore"));
        assertThat(ContainerRuleAge.violation(gcs("{\"age\":1,\"customTimeBefore\":\"2026-11-02\"}"))).isPresent();
        assertThat(ContainerRuleAge.violation(gcs("{\"age\":0}"))).isPresent();
        assertThat(ContainerRuleAge.violation(gcs("{\"isLive\":false,\"daysSinceNoncurrentTime\":0}"))).isPresent();
        assertThat(ContainerRuleAge.violation(gcs("{\"matchesPrefix\":[\"a/\"]}"))).as("no age at all").isPresent();
    }

    @Test
    void anAzureRuleNeedsDaysAfterModificationOfAtLeastOne() {
        assertThat(ContainerRuleAge.violation(azure("{\"daysAfterModificationGreaterThan\":1}",
                "{\"daysAfterCreationGreaterThan\":1}"))).isEmpty();
        assertThat(ContainerRuleAge.violation(azure("{\"daysAfterModificationGreaterThan\":0}",
                "{\"daysAfterCreationGreaterThan\":1}"))).as("a zero-day base blob delete").isPresent();
        assertThat(ContainerRuleAge.violation(azure("{\"daysAfterModificationGreaterThan\":1}",
                "{\"daysAfterCreationGreaterThan\":0}"))).as("a zero-day snapshot delete").isPresent();
        assertThat(ContainerRuleAge.violation(azure("{\"daysAfterCreationGreaterThan\":1}",
                "{\"daysAfterCreationGreaterThan\":1}"))).as("the base blob by creation, the pre-RET-06 form")
                .isPresent();
    }

    @Test
    void aTableExpirationIsNotAContainerRuleAndIsNotChecked() {
        assertThat(ContainerRuleAge.violation(rule(NativeRulePlatform.BIGQUERY_TABLE_EXPIRATION,
                "{\"expirationTime\":\"1\"}"))).isEmpty();
        assertThat(ContainerRuleAge.violation(null)).isEmpty();
    }

    @Test
    void aDocumentThatIsNotJsonOrHasNoRulesIsRefused() {
        assertThat(ContainerRuleAge.violation(rule(NativeRulePlatform.S3_LIFECYCLE, "not json"))).isPresent();
        assertThat(ContainerRuleAge.violation(rule(NativeRulePlatform.GCS_LIFECYCLE, "{}"))).isPresent();
        assertThat(ContainerRuleAge.violation(rule(NativeRulePlatform.AZURE_BLOB_LIFECYCLE, "{\"rules\":[]}")))
                .isPresent();
    }
}
