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

import com.dlap.logingest.LogHealthIntegrationBase;
import com.dlap.logingest.parse.core.IngestMode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Q-363 acceptance, on PostgreSQL with the shipped Flyway migrations (Testcontainers; requires
 * Docker): a {@code REPLAY} write of a full event upgrades a stored {@code PARSE_STATUS_PARTIAL}
 * row of the same {@code event_id}, and every other duplicate is still dropped. The pass-through
 * event of a quarantined record and the full event of the corrected parser share one id, so
 * without the upgrade the replay store would keep the pass-through for good.
 */
class LogEventStoreReplayUpgradeTest extends LogHealthIntegrationBase {

    private static final String ID = "a".repeat(64);
    private static final Instant FIRST_INGEST = Instant.parse("2026-10-20T08:00:00Z");
    private static final Instant LATER_INGEST = Instant.parse("2026-10-27T09:30:00Z");

    @Autowired
    private LogEventStore store;

    private final List<DlapLogEvent> published = new ArrayList<>();

    private static Timestamp ts(Instant i) {
        return Timestamp.newBuilder().setSeconds(i.getEpochSecond()).setNanos(i.getNano()).build();
    }

    private static DlapLogEvent passThrough() {
        return DlapLogEvent.newBuilder()
                .setEventId(ID).setEventTime(ts(Instant.parse("2026-10-20T07:59:00Z"))).setIngestTime(ts(FIRST_INGEST))
                .setSourceProvider(SourceProvider.AWS).setSourceService("s3.amazonaws.com")
                .setChangeType(ChangeType.CHANGE_TYPE_UNSPECIFIED).setOperation("GetObject")
                .setResultStatus(ResultStatus.SUCCESS)
                .setPrincipal(Principal.newBuilder().setType(PrincipalType.PRINCIPAL_TYPE_UNSPECIFIED))
                .setTargetResource(TargetResource.getDefaultInstance())
                .setSchemaVersion("v1").setBindingId("aws-it").setNativeEventId("native-1")
                .setEventCategory(EventCategory.EVENT_CATEGORY_UNSPECIFIED).setOrigin(Origin.ORIGIN_CLOUD_LOG)
                .setParseStatus(ParseStatus.PARSE_STATUS_PARTIAL)
                .build();
    }

    private static DlapLogEvent full(Instant ingest, Origin origin) {
        return passThrough().toBuilder()
                .setIngestTime(ts(ingest)).setOrigin(origin)
                .setChangeType(ChangeType.ACCESS)
                .setPrincipal(Principal.newBuilder().setId("arn:aws:iam::111122223333:role/r")
                        .setType(PrincipalType.ROLE))
                .setTargetResource(TargetResource.newBuilder().setId("arn:aws:s3:::b/k")
                        .setCanonicalUri("s3://b/k"))
                .setEventCategory(EventCategory.EVENT_CATEGORY_DATA_READ)
                .setParseStatus(ParseStatus.PARSE_STATUS_WARNINGS)
                .addParseWarnings(ParseWarning.PARSE_WARNING_UNKNOWN_FIELDS)
                .build();
    }

    private Disposition write(DlapLogEvent e, IngestMode mode, boolean suppressRepublish) {
        return store.write(e, Map.of("rawEvent", Map.of("eventName", "GetObject")), true, mode,
                suppressRepublish, published::add);
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("SELECT parse_status, change_type, event_category, principal_id, "
                + "target_canonical_uri, parse_warnings, origin, ingest_time FROM dlap_logingest.log_events "
                + "WHERE event_id = ?", ID);
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM dlap_logingest.log_events WHERE event_id = ?",
                Integer.class, ID);
    }

    @Test
    void aReplayOfTheFullEventUpgradesTheStoredPassThroughRow() {
        assertThat(write(passThrough(), IngestMode.STREAM, false)).isEqualTo(Disposition.PUBLISHED);
        assertThat(row()).containsEntry("parse_status", "PARSE_STATUS_PARTIAL").containsEntry("principal_id", null);

        Disposition d = write(full(LATER_INGEST, Origin.ORIGIN_REPLAY), IngestMode.REPLAY, false);

        assertThat(d).isEqualTo(Disposition.REPUBLISHED);
        assertThat(rows()).as("upgraded in place, not stored twice").isEqualTo(1);
        assertThat(row())
                .containsEntry("parse_status", "PARSE_STATUS_WARNINGS")
                .containsEntry("change_type", "ACCESS")
                .containsEntry("event_category", "EVENT_CATEGORY_DATA_READ")
                .containsEntry("principal_id", "arn:aws:iam::111122223333:role/r")
                .containsEntry("target_canonical_uri", "s3://b/k")
                .containsEntry("parse_warnings", "PARSE_WARNING_UNKNOWN_FIELDS")
                .containsEntry("origin", "ORIGIN_REPLAY");
        assertThat(((java.sql.Timestamp) row().get("ingest_time")).toInstant())
                .as("the row keeps the ingest time it was first stored under").isEqualTo(FIRST_INGEST);
        assertThat(published).extracting(DlapLogEvent::getParseStatus)
                .containsExactly(ParseStatus.PARSE_STATUS_PARTIAL, ParseStatus.PARSE_STATUS_WARNINGS);
    }

    @Test
    void aFullEventInStreamOrBackfillModeIsStillAPlainDuplicate() {
        write(passThrough(), IngestMode.STREAM, false);
        published.clear();

        assertThat(write(full(LATER_INGEST, Origin.ORIGIN_CLOUD_LOG), IngestMode.STREAM, false))
                .isEqualTo(Disposition.DUPLICATE);
        assertThat(write(full(LATER_INGEST, Origin.ORIGIN_BACKFILL), IngestMode.BACKFILL, false))
                .isEqualTo(Disposition.DUPLICATE);

        assertThat(row()).containsEntry("parse_status", "PARSE_STATUS_PARTIAL");
        assertThat(published).isEmpty();
    }

    @Test
    void aSeedOnceReplayPublishesNothingAndChangesNothing() {
        write(passThrough(), IngestMode.STREAM, false);
        published.clear();

        assertThat(write(full(LATER_INGEST, Origin.ORIGIN_REPLAY), IngestMode.REPLAY, true))
                .isEqualTo(Disposition.DUPLICATE);

        assertThat(row()).containsEntry("parse_status", "PARSE_STATUS_PARTIAL");
        assertThat(published).isEmpty();
    }

    @Test
    void aReplayedPassThroughNeverDowngradesAFullRow() {
        write(full(FIRST_INGEST, Origin.ORIGIN_CLOUD_LOG), IngestMode.STREAM, false);

        assertThat(write(passThrough().toBuilder().setOrigin(Origin.ORIGIN_REPLAY).build(), IngestMode.REPLAY, false))
                .isEqualTo(Disposition.REPUBLISHED);

        assertThat(row()).containsEntry("parse_status", "PARSE_STATUS_WARNINGS")
                .containsEntry("principal_id", "arn:aws:iam::111122223333:role/r");
    }

    @Test
    void aReplayOfAFullEventOverAFullRowLeavesTheRowAsItWas() {
        write(full(FIRST_INGEST, Origin.ORIGIN_CLOUD_LOG), IngestMode.STREAM, false);

        write(full(LATER_INGEST, Origin.ORIGIN_REPLAY).toBuilder().setOperation("Changed").build(),
                IngestMode.REPLAY, false);

        assertThat(jdbc.queryForObject("SELECT operation FROM dlap_logingest.log_events WHERE event_id = ?",
                String.class, ID)).isEqualTo("GetObject");
        assertThat(row()).containsEntry("origin", "ORIGIN_CLOUD_LOG");
    }
}
