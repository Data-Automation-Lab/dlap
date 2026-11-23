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

import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ParseStatus;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.TargetResource;
import com.google.protobuf.util.Timestamps;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The consumer rules of the spec, one assertion per rule. */
class LogEventSemanticsTest {

    private static final DlapLogEvent READ = DlapLogEvent.newBuilder()
            .setEventId("e").setChangeType(ChangeType.ACCESS).setResultStatus(ResultStatus.SUCCESS)
            .setEventCategory(EventCategory.EVENT_CATEGORY_DATA_READ)
            .setEventTime(Timestamps.fromMillis(1_760_781_600_000L))
            .setIngestTime(Timestamps.fromMillis(1_760_785_200_000L))
            .setTargetResource(TargetResource.newBuilder().setId("projects/_/buckets/b").setCanonicalUri("gs://b"))
            .build();

    @Test
    void businessTimeIsEventTimeNeverIngestTime() {
        assertThat(LogEventSemantics.businessTime(READ)).contains(Instant.ofEpochMilli(1_760_781_600_000L));
        assertThat(LogEventSemantics.businessTime(READ.toBuilder().clearEventTime().build())).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "ACCESS, EVENT_CATEGORY_DATA_READ",
        "PERMISSION, EVENT_CATEGORY_PERMISSION",
        "CREATE, EVENT_CATEGORY_MANAGEMENT",
        "UPDATE, EVENT_CATEGORY_MANAGEMENT",
        "DELETE, EVENT_CATEGORY_MANAGEMENT",
        "ARCHIVE, EVENT_CATEGORY_MANAGEMENT",
        "CHANGE_TYPE_UNSPECIFIED, EVENT_CATEGORY_UNSPECIFIED"
    })
    void legacyEventsDeriveTheirCategoryFromChangeType(ChangeType type, EventCategory expected) {
        DlapLogEvent legacy = DlapLogEvent.newBuilder().setChangeType(type).build();

        assertThat(LogEventSemantics.effectiveCategory(legacy)).isEqualTo(expected);
    }

    @Test
    void anExplicitCategoryWinsOverTheChangeType() {
        DlapLogEvent putObject = DlapLogEvent.newBuilder().setChangeType(ChangeType.CREATE)
                .setEventCategory(EventCategory.EVENT_CATEGORY_DATA_WRITE).build();

        assertThat(LogEventSemantics.effectiveCategory(putObject)).isEqualTo(EventCategory.EVENT_CATEGORY_DATA_WRITE);
    }

    @Test
    void onlySuccessfulReadsWithAnEventTimeMoveLastAccess() {
        assertThat(LogEventSemantics.isSuccessfulRead(READ)).isTrue();
        assertThat(LogEventSemantics.countsAsUsage(READ)).isTrue();
        assertThat(LogEventSemantics.isSuccessfulRead(READ.toBuilder().setResultStatus(ResultStatus.FAILURE).build()))
                .isFalse();
        assertThat(LogEventSemantics.isSuccessfulRead(READ.toBuilder().clearEventTime().build())).isFalse();
        assertThat(LogEventSemantics.isSuccessfulRead(READ.toBuilder()
                .setEventCategory(EventCategory.EVENT_CATEGORY_MANAGEMENT).build())).isFalse();
    }

    @Test
    void observedReadsMoveLastAccessButAreNotUsage() {
        DlapLogEvent observed = READ.toBuilder().setEventCategory(EventCategory.EVENT_CATEGORY_DATA_READ_OBSERVED)
                .build();

        assertThat(LogEventSemantics.isSuccessfulRead(observed)).isTrue();
        assertThat(LogEventSemantics.countsAsUsage(observed)).isFalse();
    }

    @Test
    void partialPassThroughNeverChangesState() {
        DlapLogEvent partial = READ.toBuilder().setParseStatus(ParseStatus.PARSE_STATUS_PARTIAL).build();
        DlapLogEvent unspecified = READ.toBuilder().setChangeType(ChangeType.CHANGE_TYPE_UNSPECIFIED).build();

        assertThat(LogEventSemantics.mayChangeState(partial)).isFalse();
        assertThat(LogEventSemantics.mayChangeState(unspecified)).isFalse();
        assertThat(LogEventSemantics.isSuccessfulRead(partial)).isFalse();
    }

    @Test
    void assetAndPartitionKeysPreferTheCanonicalUri() {
        assertThat(LogEventSemantics.assetKey(READ)).isEqualTo("gs://b");
        DlapLogEvent legacy = READ.toBuilder().setTargetResource(TargetResource.newBuilder().setId("b")).build();
        assertThat(LogEventSemantics.assetKey(legacy)).isEqualTo("b");
        assertThat(LogEventSemantics.partitionKey(READ.toBuilder().clearTargetResource().build())).isEqualTo("e");
    }

    @Test
    void sourceFormatsJoinAndSplit() {
        assertThat(SourceFormats.of("AWS_CLOUDTRAIL", "s3-file")).isEqualTo("AWS_CLOUDTRAIL/s3-file");
        assertThat(SourceFormats.sourceKind(SourceFormats.DLAP_CRAWLER)).contains("DLAP_CRAWLER");
        assertThat(SourceFormats.envelope("GCP_CLOUD_AUDIT/api-proto")).contains("api-proto");
        assertThat(SourceFormats.sourceKind("")).isEmpty();
        assertThat(SourceFormats.envelope("A/b/c")).isEmpty();
        for (String[] bad : new String[][] {{"aws", "s3-file"}, {"AWS", "S3"}, {"AWS/X", "s3"}, {"AWS", "a/b"}}) {
            assertThatThrownBy(() -> SourceFormats.of(bad[0], bad[1]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
