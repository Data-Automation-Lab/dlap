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

package com.dlap.logingest.parse.s3accesslog;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.CanonicalEvent;
import com.dlap.logingest.parse.core.CanonicalEventMapper;
import com.dlap.logingest.parse.core.DefaultParserRegistry;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.parse.core.VersionedDeletes;
import com.dlap.logingest.parse.core.contract.AbstractParserContractTest;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.PrincipalType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PARSE-02: S3 server access log records. A lifecycle expiration is a delete of the object by the
 * system, whoever the requester field names; a request is typed from its requester ARN. PARSE-03
 * (D36, Q-651): a delete that names a version, and a delete marker, are updates of the object.
 */
class S3AccessLogParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final MappingSpec MAPPING = AbstractParserContractTest.classpathMapping(S3AccessLogFormat.FAMILY);
    private static final DefaultParserRegistry REGISTRY =
            AbstractParserContractTest.registryOf(MAPPING, List.of(new S3AccessLogParser(MAPPING)));
    private static final BindingConfig BINDING = MAPPING.defaultBinding("sal-test");
    private static final CanonicalEventMapper MAPPER = new CanonicalEventMapper(Clock.fixed(NOW, ZoneOffset.UTC));

    private static ObjectNode record(String operation, String key) {
        ObjectNode r = JSON.createObjectNode();
        r.put("schema_version_id", "V_1_0");
        r.put("bucket_name", "bucket-one");
        r.put("request_time", "2026-11-02T03:20:41.000Z");
        r.put("operation", operation);
        r.put("request_id", "R1");
        r.put("key_name", key);
        r.putNull("requester");
        return r;
    }

    private static ParseOutcome parse(ObjectNode body) {
        RawRecord r = RawRecord.builder().bindingId(BINDING.bindingId()).sourceKind(S3AccessLogFormat.SOURCE_KIND)
                .envelope(S3AccessLogFormat.CWL_JSON).ref(RawRef.of("TEST", "test:record", 0)).mode(IngestMode.STREAM)
                .fetchedAt(NOW).json(body).build();
        return REGISTRY.parse(r, BINDING);
    }

    private static DlapLogEvent only(ParseOutcome o) {
        List<DlapLogEvent> events = MAPPER.events(o);
        assertThat(events).as("events of %s", o.outcomeCode()).hasSize(1);
        return events.get(0);
    }

    @Test
    void anExpirationIsADeleteOfTheObjectBySystemWhateverTheRequesterSays() {
        ObjectNode r = record("S3.EXPIRE.OBJECT", "k/a.csv");
        r.put("requester", "AmazonS3");

        DlapLogEvent e = only(parse(r));

        assertThat(e.getChangeType()).isEqualTo(ChangeType.DELETE);
        assertThat(e.getTargetResource().getCanonicalUri()).isEqualTo("s3://bucket-one/k/a.csv");
        assertThat(e.getPrincipal().getType()).isEqualTo(PrincipalType.SYSTEM);
        assertThat(e.getPrincipal().getId()).isEqualTo("AmazonS3");
        assertThat(e.getNativeEventId())
                .isEqualTo("AWS_S3_SERVER_ACCESS_LOG|bucket-one|2026-11-02T03:20:41Z|S3.EXPIRE.OBJECT|R1|k/a.csv");
        assertThat(e.getOrigin().name()).isEqualTo("ORIGIN_CLOUD_LOG");
    }

    @Test
    void anExpirationOfANoncurrentVersionIsAnUpdateCarryingTheVersion() {
        // Q-651: the lifecycle logging table has no separate noncurrent operation; version_id is the mark.
        ObjectNode r = record("S3.EXPIRE.OBJECT", "k/a.csv");
        r.put("version_id", "v-noncurrent");

        DlapLogEvent e = only(parse(r));

        assertThat(e.getChangeType()).isEqualTo(ChangeType.UPDATE);
        assertThat(e.getEventCategory()).isEqualTo(EventCategory.EVENT_CATEGORY_MANAGEMENT);
        assertThat(e.getTargetResource().getCanonicalUri()).isEqualTo("s3://bucket-one/k/a.csv");
        assertThat(e.getTargetResource().getTechnicalMetadataMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
                VersionedDeletes.ACTION_KEY, VersionedDeletes.DELETE_VERSION,
                VersionedDeletes.VERSION_ID_KEY, "v-noncurrent"));
        assertThat(e.getPrincipal().getType()).isEqualTo(PrincipalType.SYSTEM);
    }

    @Test
    void aDeleteMarkerIsAnUpdateWithOrWithoutItsVersionId() {
        ObjectNode withId = record("S3.CREATE.DELETEMARKER", "k/a.csv");
        withId.put("version_id", "dm-1");
        ObjectNode withoutId = record("S3.CREATE.DELETEMARKER", "k/a.csv");
        withoutId.putNull("version_id");

        DlapLogEvent a = only(parse(withId));
        DlapLogEvent b = only(parse(withoutId));

        assertThat(a.getChangeType()).isEqualTo(ChangeType.UPDATE);
        assertThat(a.getEventCategory()).isEqualTo(EventCategory.EVENT_CATEGORY_MANAGEMENT);
        assertThat(a.getTargetResource().getTechnicalMetadataMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
                VersionedDeletes.ACTION_KEY, VersionedDeletes.CREATE_DELETE_MARKER, VersionedDeletes.VERSION_ID_KEY, "dm-1"));
        assertThat(b.getChangeType()).isEqualTo(ChangeType.UPDATE);
        assertThat(b.getTargetResource().getTechnicalMetadataMap())
                .containsExactly(Map.entry(VersionedDeletes.ACTION_KEY, VersionedDeletes.CREATE_DELETE_MARKER));
    }

    @Test
    void aRequestThatDeletesOneVersionIsAnUpdateAndACurrentDeleteStaysADelete() {
        ObjectNode version = record("REST.DELETE.OBJECT", "k/a.csv");
        version.put("requester", "arn:aws:iam::123456789012:user/u");
        version.put("version_id", "v-1");
        ObjectNode batch = record("BATCH.DELETE.OBJECT", "k/b.csv");
        batch.put("version_id", "v-2");
        ObjectNode current = record("REST.DELETE.OBJECT", "k/c.csv");
        current.put("version_id", "-");

        assertThat(only(parse(version)).getChangeType()).isEqualTo(ChangeType.UPDATE);
        assertThat(only(parse(version)).getPrincipal().getType()).isEqualTo(PrincipalType.USER);
        assertThat(only(parse(batch)).getChangeType()).isEqualTo(ChangeType.UPDATE);
        DlapLogEvent c = only(parse(current));
        assertThat(c.getChangeType()).as("the text format's '-' is no version").isEqualTo(ChangeType.DELETE);
        assertThat(c.getTargetResource().getTechnicalMetadataMap()).isEmpty();
    }

    @Test
    void aVersionOnAnotherOperationChangesNothing() {
        ObjectNode read = record("REST.GET.OBJECT", "k/a.csv");
        read.put("version_id", "v-1");
        ObjectNode bucket = record("REST.DELETE.BUCKET", null);
        bucket.putNull("key_name");
        bucket.put("version_id", "v-1");

        DlapLogEvent r = only(parse(read));
        DlapLogEvent b = only(parse(bucket));

        assertThat(r.getChangeType()).isEqualTo(ChangeType.ACCESS);
        assertThat(r.getTargetResource().getTechnicalMetadataMap()).isEmpty();
        assertThat(b.getChangeType()).as("not an object").isEqualTo(ChangeType.DELETE);
    }

    @Test
    void anExpirationWithoutAKeyCannotNameItsObjectAndIsQuarantined() {
        ObjectNode r = record("S3.EXPIRE.OBJECT", null);
        r.putNull("key_name");
        // s3://bucket-one would name the bucket: an expiration must never read as a delete of it.
        assertThat(parse(r).outcomeCode()).isEqualTo("QUARANTINED:UNRESOLVABLE_ASSET_ID");
    }

    @Test
    void aDashInTheKeyIsTheTextFormatsAbsentValue() {
        ObjectNode r = record("REST.GET.VERSIONING", "-");

        DlapLogEvent e = only(parse(r));

        assertThat(e.getTargetResource().getCanonicalUri()).isEqualTo("s3://bucket-one");
        assertThat(e.getNativeEventId()).endsWith("|R1|");
    }

    @Test
    void twoRecordsOfOneCopyRequestGetTwoIds() {
        DlapLogEvent get = only(parse(record("REST.COPY.OBJECT_GET", "src.csv")));
        DlapLogEvent put = only(parse(record("REST.COPY.OBJECT", "dst.csv")));

        assertThat(get.getEventId()).isNotEqualTo(put.getEventId());
        assertThat(get.getChangeType()).isEqualTo(ChangeType.ACCESS);
        assertThat(put.getChangeType()).isEqualTo(ChangeType.UPDATE);
    }

    @Test
    void aNativeKeyAboveTheLimitIsQuarantined() {
        ObjectNode r = record("REST.GET.OBJECT", "k");
        r.put("request_id", "R".repeat(CanonicalEvent.MAX_NATIVE_KEY_LENGTH));
        assertThat(parse(r).outcomeCode()).isEqualTo("QUARANTINED:FIELD_TYPE_MISMATCH");
    }

    @Test
    void anotherSchemaVersionIsNotRecognized() {
        ObjectNode r = record("S3.EXPIRE.OBJECT", "k");
        r.put("schema_version_id", "V_2_0");
        assertThat(parse(r).outcomeCode()).isEqualTo("QUARANTINED:UNKNOWN_ENVELOPE");
    }

    @Test
    void theRequesterArnGivesTheTypeAndTheAccount() {
        assertThat(S3AccessLogParser.principal("REST.GET.OBJECT", "arn:aws:sts::123456789012:assumed-role/R/s"))
                .isEqualTo(new CanonicalEvent.Actor(PrincipalType.ROLE, "arn:aws:sts::123456789012:assumed-role/R/s",
                        "", "123456789012"));
        assertThat(S3AccessLogParser.principal("REST.GET.OBJECT", "arn:aws:iam::123456789012:role/R").type())
                .isEqualTo(PrincipalType.ROLE);
        assertThat(S3AccessLogParser.principal("REST.GET.OBJECT", "arn:aws:iam::123456789012:user/u").type())
                .isEqualTo(PrincipalType.USER);
        assertThat(S3AccessLogParser.principal("REST.GET.OBJECT", "arn:aws:iam::123456789012:root").type())
                .isEqualTo(PrincipalType.USER);
        assertThat(S3AccessLogParser.principal("REST.GET.OBJECT", "79a59df900b949e5").type())
                .isEqualTo(PrincipalType.PRINCIPAL_TYPE_UNSPECIFIED);
        assertThat(S3AccessLogParser.principal("S3.TRANSITION.OBJECT", null))
                .isEqualTo(new CanonicalEvent.Actor(PrincipalType.SYSTEM, "", "", ""));
        assertThat(S3AccessLogParser.principal(null, "x").type()).isEqualTo(PrincipalType.PRINCIPAL_TYPE_UNSPECIFIED);
    }

    @Test
    void onlyATwelveDigitAccountOfAnArnIsAnAccount() {
        assertThat(S3AccessLogParser.account("arn:aws:iam::123456789012:user/u")).isEqualTo("123456789012");
        assertThat(S3AccessLogParser.account("arn:aws:iam::12345:user/u")).isEmpty();
        assertThat(S3AccessLogParser.account("arn:aws:iam")).isEmpty();
        assertThat(S3AccessLogParser.account("79a59df900b949e5")).isEmpty();
    }
}
