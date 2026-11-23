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

package com.dlap.common.logevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Idempotency keys. Expected hashes were computed outside the JVM
 * ({@code python3 hashlib.sha256}) so the test checks bytes, not this code
 * against itself.
 */
class LogEventIdsTest {

    @Test
    void eventIdIsTheLegacyLogIngestFormula() {
        // LogIngestServiceImpl.computeEventId: SHA-256(sourceProvider + "|" + requestId), lowercase hex.
        assertThat(LogEventIds.eventId(SourceProvider.AWS, "3038ebd2-c98a-4c65-9b6e-e22506292313"))
                .isEqualTo("99952aa076d4d73891f1d6315d576a3c3d272cb79bbdeaeacf0e194e36b05166")
                .isEqualTo(LogEventIds.eventId("AWS", LogEventIds.cloudTrailNativeKey(
                        "3038ebd2-c98a-4c65-9b6e-e22506292313")));
    }

    @Test
    void gcpNativeKeyUsesTheFullLogNameAndInstantText() {
        Instant whole = Instant.parse("2026-10-18T09:15:02Z");
        Instant micros = Instant.parse("2026-10-18T09:15:02.123456Z");
        String logName = "projects/dlap-demo/logs/cloudaudit.googleapis.com%2Fdata_access";

        assertThat(LogEventIds.gcpAuditNativeKey(logName, whole, "i1"))
                .isEqualTo(logName + "|2026-10-18T09:15:02Z|i1");
        assertThat(LogEventIds.gcpAuditNativeKey(logName, micros, "i1"))
                .isEqualTo(logName + "|2026-10-18T09:15:02.123456Z|i1");
        assertThat(LogEventIds.gcpAuditNativeKey("organizations/123/logs/cloudaudit.googleapis.com%2Factivity",
                whole, "i2")).startsWith("organizations/123/logs/");
        // The Java client can hand back the short log id; the key needs the full name.
        assertThatThrownBy(() -> LogEventIds.gcpAuditNativeKey("cloudaudit.googleapis.com%2Fdata_access", whole, "i"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void namespacedAndFanOutKeysKeepSourcesAndTargetsApart() {
        assertThat(LogEventIds.namespacedNativeKey("DATABRICKS_LINEAGE", "rec-1"))
                .isEqualTo("DATABRICKS_LINEAGE|rec-1");
        assertThat(LogEventIds.namespacedNativeKey("AZURE_STORAGE_RESOURCE_LOG", "corr", "2"))
                .isEqualTo("AZURE_STORAGE_RESOURCE_LOG|corr|2");
        assertThat(LogEventIds.fanOutNativeKey("evt-1", "s3://b/k2")).isEqualTo("evt-1#s3://b/k2");
        assertThat(LogEventIds.eventId(SourceProvider.DATABRICKS, "rec-1"))
                .isNotEqualTo(LogEventIds.eventId(SourceProvider.DATABRICKS, "DATABRICKS_LINEAGE|rec-1"));
    }

    @Test
    void usageKeyMergesCrossAccountCopiesPerTarget() {
        DlapLogEvent requesterCopy = read("e-requester", "5e1f7c0a-0b6d-4a51-9f1e-2c8d7a3b4c5d",
                "s3://dlap-demo-bucket/reports/q3.csv");
        DlapLogEvent ownerCopy = read("e-owner", "5e1f7c0a-0b6d-4a51-9f1e-2c8d7a3b4c5d",
                "s3://dlap-demo-bucket/reports/q3.csv");
        DlapLogEvent otherObject = read("e-other", "5e1f7c0a-0b6d-4a51-9f1e-2c8d7a3b4c5d",
                "s3://dlap-demo-bucket/reports/q4.csv");
        DlapLogEvent unshared = read("e-plain", "", "s3://dlap-demo-bucket/reports/q3.csv");

        assertThat(LogEventIds.usageDedupKey(requesterCopy))
                .isEqualTo(LogEventIds.usageDedupKey(ownerCopy))
                .isEqualTo("3845c8a13cee6bc1b0f6febf2228f3a7ea29a3b2dab07c7474905da072f07291")
                .isNotEqualTo(LogEventIds.usageDedupKey(otherObject));
        assertThat(LogEventIds.usageDedupKey(unshared)).isEqualTo("e-plain");
    }

    @Test
    void blankOrUnknownInputsAreRejected() {
        assertThatThrownBy(() -> LogEventIds.eventId(SourceProvider.SOURCE_PROVIDER_UNSPECIFIED, "k"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LogEventIds.eventId("AWS", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LogEventIds.cloudTrailNativeKey("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LogEventIds.namespacedNativeKey("K")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LogEventIds.fanOutNativeKey("k", "")).isInstanceOf(IllegalArgumentException.class);
    }

    private static DlapLogEvent read(String eventId, String sharedEventId, String uri) {
        return DlapLogEvent.newBuilder().setEventId(eventId).setSharedEventId(sharedEventId)
                .setSourceProvider(SourceProvider.AWS)
                .setTargetResource(TargetResource.newBuilder().setId("ignored-native-id").setCanonicalUri(uri))
                .build();
    }
}
