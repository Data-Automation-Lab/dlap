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

package com.dlap.logingest.service.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.dlap.logingest.model.LogEventEntity;
import com.dlap.logingest.repository.LogEventRepository;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.Origin;
import com.dlap.proto.log.v1.ParseStatus;
import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.Principal;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.SourceProvider;
import com.dlap.proto.log.v1.TargetResource;
import com.google.protobuf.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The row the registry path stores: every registry column filled from the wire event, empty
 * strings stored as null, raw kept only on request, and text longer than its column shortened in
 * the row only.
 */
class LogEventStoreTest {

    private final LogEventStore store = new LogEventStore(mock(LogEventRepository.class),
            new RegistryIngestServiceTest.NoTransactions());

    private static Timestamp ts(String iso) {
        Instant i = Instant.parse(iso);
        return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
    }

    private static DlapLogEvent event(String operation) {
        return DlapLogEvent.newBuilder()
                .setEventId("e".repeat(64)).setEventTime(ts("2026-10-01T10:00:00.123Z"))
                .setIngestTime(ts("2026-10-19T12:00:00Z")).setObservedTime(ts("2026-10-01T10:00:04Z"))
                .setSourceProvider(SourceProvider.AWS).setSourceService("s3.amazonaws.com")
                .setChangeType(ChangeType.ACCESS).setOperation(operation).setResultStatus(ResultStatus.SUCCESS)
                .setPrincipal(Principal.newBuilder().setId("arn:aws:iam::111122223333:role/r").setType(PrincipalType.ROLE))
                .setTargetResource(TargetResource.newBuilder().setId("arn:aws:s3:::b/k").setCanonicalUri("s3://b/k"))
                .setSchemaVersion("v1").setSourceFormat("AWS_CLOUDTRAIL/s3-file").setSourceSchemaVersion("1.11")
                .setParserVersion("cloudtrail-record@1.0.0").setBindingId("aws-111111111111").setNativeEventId("guid-1")
                .setEventCategory(EventCategory.EVENT_CATEGORY_DATA_READ).setOrigin(Origin.ORIGIN_CLOUD_LOG)
                .setParseStatus(ParseStatus.PARSE_STATUS_WARNINGS)
                .addParseWarnings(ParseWarning.PARSE_WARNING_UNKNOWN_FIELDS)
                .addParseWarnings(ParseWarning.PARSE_WARNING_NEWER_MINOR)
                .build();
    }

    @Test
    void everyRegistryColumnIsFilled() {
        LogEventEntity row = store.toEntity(event("GetObject"), Map.of("rawEvent", Map.of("eventName", "GetObject")),
                true);

        assertThat(row.getEventId()).isEqualTo("e".repeat(64));
        assertThat(row.getEventTime()).isEqualTo(Instant.parse("2026-10-01T10:00:00.123Z"));
        assertThat(row.getIngestTime()).isEqualTo(Instant.parse("2026-10-19T12:00:00Z"));
        assertThat(row.getObservedTime()).isEqualTo(Instant.parse("2026-10-01T10:00:04Z"));
        assertThat(row.getSourceProvider()).isEqualTo("AWS");
        assertThat(row.getChangeType()).isEqualTo("ACCESS");
        assertThat(row.getResultStatus()).isEqualTo("SUCCESS");
        assertThat(row.getPrincipalType()).isEqualTo("ROLE");
        assertThat(row.getTargetResourceId()).isEqualTo("arn:aws:s3:::b/k");
        assertThat(row.getTargetCanonicalUri()).isEqualTo("s3://b/k");
        assertThat(row.getBindingId()).isEqualTo("aws-111111111111");
        assertThat(row.getSourceFormat()).isEqualTo("AWS_CLOUDTRAIL/s3-file");
        assertThat(row.getSourceSchemaVersion()).isEqualTo("1.11");
        assertThat(row.getParserVersion()).isEqualTo("cloudtrail-record@1.0.0");
        assertThat(row.getNativeEventId()).isEqualTo("guid-1");
        assertThat(row.getEventCategory()).isEqualTo("EVENT_CATEGORY_DATA_READ");
        assertThat(row.getOrigin()).isEqualTo("ORIGIN_CLOUD_LOG");
        assertThat(row.getParseStatus()).isEqualTo("PARSE_STATUS_WARNINGS");
        assertThat(row.getParseWarnings()).isEqualTo("PARSE_WARNING_UNKNOWN_FIELDS,PARSE_WARNING_NEWER_MINOR");
        assertThat(row.getRawEvent()).containsEntry("eventName", "GetObject");
        assertThat(row.getTargetProject()).as("empty strings are stored as null").isNull();
        assertThat(row.getSharedEventId()).isNull();
        assertThat(row.getSupersedesEventId()).isNull();
    }

    @Test
    void rawIsKeptOnlyOnRequestAndLongTextIsShortenedInTheRowOnly() {
        LogEventEntity row = store.toEntity(event("Op".repeat(200)), Map.of("rawEvent", Map.of("a", 1)), false);

        assertThat(row.getRawEvent()).isNull();
        assertThat(row.getOperation()).hasSize(256);
    }
}
