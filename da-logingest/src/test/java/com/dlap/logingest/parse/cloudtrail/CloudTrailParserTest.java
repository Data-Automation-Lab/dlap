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

package com.dlap.logingest.parse.cloudtrail;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlap.logingest.parse.core.BindingConfig;
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
import com.dlap.proto.log.v1.ParseWarning;
import com.dlap.proto.log.v1.PrincipalType;
import com.dlap.proto.log.v1.ResultStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PARSE-02: the CloudTrail parser routes S3 lifecycle expirations as object deletes with a system
 * actor, and never mistakes a configuration removal or a multi-object call for a delete of the
 * bucket. PARSE-03 (D36): an unlisted lifecycle event is an update and never names a bucket (Q-650),
 * and a delete that names a version or only adds a delete marker is an update of the object (Q-651).
 */
class CloudTrailParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final MappingSpec MAPPING = AbstractParserContractTest.classpathMapping(CloudTrailFormat.FAMILY);
    private static final DefaultParserRegistry REGISTRY =
            AbstractParserContractTest.registryOf(MAPPING, CloudTrailParser.forSpec(MAPPING));
    private static final BindingConfig BINDING = MAPPING.defaultBinding("ct-test");
    private static final CanonicalEventMapper MAPPER = new CanonicalEventMapper(Clock.fixed(NOW, ZoneOffset.UTC));

    private static ObjectNode record(String source, String name) {
        ObjectNode r = JSON.createObjectNode();
        r.put("eventVersion", "1.11");
        r.put("eventTime", "2026-11-02T03:14:07Z");
        r.put("eventSource", source);
        r.put("eventName", name);
        r.put("awsRegion", "us-east-1");
        r.put("eventID", "evt-" + name);
        r.put("recipientAccountId", "123456789012");
        r.putObject("userIdentity").put("type", "AWSService").put("invokedBy", "lifecycle.s3.amazonaws.com");
        return r;
    }

    private static ObjectNode withObject(ObjectNode r, String bucket, String key) {
        ObjectNode p = r.putObject("requestParameters").put("bucketName", bucket);
        if (key != null) {
            p.put("key", key);
        }
        return r;
    }

    private static ParseOutcome parse(JsonNode body) {
        RawRecord r = RawRecord.builder().bindingId(BINDING.bindingId()).sourceKind(CloudTrailFormat.SOURCE_KIND)
                .envelope(CloudTrailFormat.S3_FILE).ref(RawRef.of("TEST", "test:record", 0)).mode(IngestMode.STREAM)
                .fetchedAt(NOW).json(body).build();
        return REGISTRY.parse(r, BINDING);
    }

    private static DlapLogEvent only(ParseOutcome o) {
        List<DlapLogEvent> events = MAPPER.events(o);
        assertThat(events).as("events of %s", o.outcomeCode()).hasSize(1);
        return events.get(0);
    }

    @Test
    void anS3LifecycleExpirationIsADeleteOfTheObjectBySystem() {
        DlapLogEvent e = only(parse(withObject(record("s3lifecycle.amazonaws.com", "DeleteObject"), "bucket-one", "k/a.csv")));

        assertThat(e.getChangeType()).isEqualTo(ChangeType.DELETE);
        assertThat(e.getEventCategory()).isEqualTo(EventCategory.EVENT_CATEGORY_DATA_WRITE);
        assertThat(e.getTargetResource().getCanonicalUri()).isEqualTo("s3://bucket-one/k/a.csv");
        assertThat(e.getTargetResource().getKind()).isEqualTo(CloudTrailFormat.S3_OBJECT_KIND);
        assertThat(e.getPrincipal().getType()).isEqualTo(PrincipalType.SYSTEM);
        assertThat(e.getPrincipal().getId()).isEqualTo("lifecycle.s3.amazonaws.com");
        assertThat(e.getResultStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(e.getOrigin().name()).isEqualTo("ORIGIN_CLOUD_LOG");
        assertThat(e.getParseWarningsList()).isEmpty();
    }

    @Test
    void anExpirationNamedLifecycleExpirationIsAlsoADelete() {
        DlapLogEvent e = only(parse(withObject(record("s3lifecycle.amazonaws.com", "LifecycleExpiration:Delete"),
                "bucket-one", "k/a.csv")));
        assertThat(e.getChangeType()).isEqualTo(ChangeType.DELETE);
        assertThat(e.getTargetResource().getCanonicalUri()).isEqualTo("s3://bucket-one/k/a.csv");
    }

    @Test
    void aLifecycleRecordWithoutRequestParametersTakesTheObjectFromItsResources() {
        ObjectNode r = record("s3lifecycle.amazonaws.com", "DeleteObject");
        r.putArray("resources")
                .add(JSON.createObjectNode().put("type", "AWS::S3::Bucket").put("ARN", "arn:aws:s3:::bucket-two"))
                .add(JSON.createObjectNode().put("type", "AWS::S3::Object").put("ARN", "arn:aws:s3:::bucket-two/x/y.parquet"));

        DlapLogEvent e = only(parse(r));

        assertThat(e.getTargetResource().getCanonicalUri()).isEqualTo("s3://bucket-two/x/y.parquet");
        assertThat(e.getTargetResource().getKind()).isEqualTo(CloudTrailFormat.S3_OBJECT_KIND);
    }

    @Test
    void aLifecycleDeleteWithoutAnyTargetIsQuarantinedNotGuessed() {
        ParseOutcome o = parse(record("s3lifecycle.amazonaws.com", "DeleteObject"));
        assertThat(o.outcomeCode()).isEqualTo("QUARANTINED:UNRESOLVABLE_ASSET_ID");
    }

    @Test
    void aLifecycleDeleteThatNamesOnlyTheBucketNeverDeletesTheBucket() {
        ParseOutcome bucketOnly = parse(withObject(record("s3lifecycle.amazonaws.com", "DeleteObject"), "bucket-one", null));
        ObjectNode bucketResource = record("s3lifecycle.amazonaws.com", "DeleteObject");
        bucketResource.putArray("resources").add(JSON.createObjectNode().put("type", "AWS::S3::Bucket")
                .put("ARN", "arn:aws:s3:::bucket-one"));

        assertThat(bucketOnly.outcomeCode()).isEqualTo("QUARANTINED:UNRESOLVABLE_ASSET_ID");
        assertThat(parse(bucketResource).outcomeCode()).isEqualTo("QUARANTINED:UNRESOLVABLE_ASSET_ID");
    }

    @Test
    void aServiceThatNamesNoIdentityTypeIsASystemActor() {
        ObjectNode r = withObject(record("s3lifecycle.amazonaws.com", "DeleteObject"), "bucket-one", "k");
        ((ObjectNode) r.get("userIdentity")).remove("type");

        assertThat(only(parse(r)).getPrincipal().getType()).isEqualTo(PrincipalType.SYSTEM);
    }

    @Test
    void anAssumedRoleIsTheRoleArnAndAPersonsDeleteIsNotASystemDelete() {
        ObjectNode r = withObject(record("s3.amazonaws.com", "DeleteObject"), "bucket-one", "k");
        ObjectNode identity = r.putObject("userIdentity");
        identity.put("type", "AssumedRole").put("arn", "arn:aws:sts::123456789012:assumed-role/Etl/session");
        identity.putObject("sessionContext").putObject("sessionIssuer")
                .put("arn", "arn:aws:iam::123456789012:role/Etl");

        DlapLogEvent e = only(parse(r));

        assertThat(e.getChangeType()).isEqualTo(ChangeType.DELETE);
        assertThat(e.getPrincipal().getId()).isEqualTo("arn:aws:iam::123456789012:role/Etl");
        assertThat(e.getPrincipal().getType()).isEqualTo(PrincipalType.ROLE);
        assertThat(e.getSourceService()).isEqualTo("s3.amazonaws.com");
    }

    @Test
    void removingABucketsConfigurationOrManyObjectsNeverDeletesTheBucket() {
        DlapLogEvent lifecycle = only(parse(withObject(record("s3.amazonaws.com", "DeleteBucketLifecycle"), "bucket-one", null)));
        DlapLogEvent objects = only(parse(withObject(record("s3.amazonaws.com", "DeleteObjects"), "bucket-one", null)));
        DlapLogEvent bucket = only(parse(withObject(record("s3.amazonaws.com", "DeleteBucket"), "bucket-one", null)));

        assertThat(lifecycle.getChangeType()).isEqualTo(ChangeType.UPDATE);
        assertThat(objects.getChangeType()).isEqualTo(ChangeType.UPDATE);
        assertThat(bucket.getChangeType()).isEqualTo(ChangeType.DELETE);
        assertThat(bucket.getTargetResource().getCanonicalUri()).isEqualTo("s3://bucket-one");
        assertThat(bucket.getTargetResource().getKind()).isEqualTo(CloudTrailFormat.S3_BUCKET_KIND);
    }

    @Test
    void aTransitionIsAnArchiveAndAnAbortIsNoDelete() {
        DlapLogEvent transition = only(parse(withObject(record("s3lifecycle.amazonaws.com", "LifecycleTransition"),
                "bucket-one", "k")));
        DlapLogEvent abort = only(parse(withObject(record("s3lifecycle.amazonaws.com", "AbortMultipartUpload"),
                "bucket-one", "k")));

        assertThat(transition.getChangeType()).isEqualTo(ChangeType.ARCHIVE);
        assertThat(abort.getChangeType()).isEqualTo(ChangeType.UPDATE);
    }

    @Test
    void anUnlistedLifecycleNameIsAnUpdateByTheCatchAllRowNeverADelete() {
        // Q-650: before PARSE-03 the verb heuristics read DeleteMarkerCreated as a DELETE.
        for (String name : List.of("DeleteMarkerCreated", "ExpireNoncurrentVersion", "RemoveExpiredObject",
                "PurgeObject", "CreateDeleteMarker", "Zeta")) {
            DlapLogEvent e = only(parse(withObject(record("s3lifecycle.amazonaws.com", name), "bucket-one", "k")));
            assertThat(e.getChangeType()).as(name).isEqualTo(ChangeType.UPDATE);
            assertThat(e.getEventCategory()).as(name).isEqualTo(EventCategory.EVENT_CATEGORY_MANAGEMENT);
            assertThat(e.getParseWarningsList()).as("%s matched an explicit row", name)
                    .doesNotContain(ParseWarning.PARSE_WARNING_HEURISTIC_CLASSIFICATION);
            assertThat(e.getTargetResource().getCanonicalUri()).as(name).isEqualTo("s3://bucket-one/k");
        }
    }

    @Test
    void aLifecycleNameNoRowReachesIsStillAnUpdateAndSaysItWasGuessed() {
        for (String name : List.of("deleteExpiredObject", "1DeleteObject", "_Delete")) {
            DlapLogEvent e = only(parse(withObject(record("s3lifecycle.amazonaws.com", name), "bucket-one", "k")));
            assertThat(e.getChangeType()).as(name).isEqualTo(ChangeType.UPDATE);
            assertThat(e.getEventCategory()).as(name).isEqualTo(EventCategory.EVENT_CATEGORY_MANAGEMENT);
            assertThat(e.getParseWarningsList()).as(name).contains(ParseWarning.PARSE_WARNING_HEURISTIC_CLASSIFICATION);
        }
    }

    @Test
    void anUnlistedLifecycleEventThatNamesOnlyTheBucketHasNoBucketUri() {
        ParseOutcome viaParameters = parse(withObject(record("s3lifecycle.amazonaws.com", "DeleteMarkerCreated"),
                "bucket-one", null));
        ObjectNode viaResources = record("s3lifecycle.amazonaws.com", "deleteBucketContents");
        viaResources.putArray("resources").add(JSON.createObjectNode().put("type", "AWS::S3::Bucket")
                .put("ARN", "arn:aws:s3:::bucket-one"));

        for (ParseOutcome o : List.of(viaParameters, parse(viaResources))) {
            DlapLogEvent e = only(o);
            assertThat(e.getChangeType()).isEqualTo(ChangeType.UPDATE);
            assertThat(e.getTargetResource().getCanonicalUri()).as("never s3://bucket-one").isEmpty();
            assertThat(e.getTargetResource().getId()).as("no bucket id a consumer could normalize").isEmpty();
            assertThat(e.getTargetResource().getProject()).isEqualTo("123456789012");
        }
        DlapLogEvent s3Bucket = only(parse(withObject(record("s3.amazonaws.com", "PutBucketTagging"), "bucket-one", null)));
        assertThat(s3Bucket.getTargetResource().getCanonicalUri()).as("an S3 bucket call keeps its bucket URI")
                .isEqualTo("s3://bucket-one");
    }

    @Test
    void theCatchAllHasARowForEveryCapitalLetter() {
        for (char c = 'A'; c <= 'Z'; c++) {
            String name = c + "nlisted";
            DlapLogEvent e = only(parse(withObject(record("s3lifecycle.amazonaws.com", name), "bucket-one", "k")));
            assertThat(e.getChangeType()).as(name).isEqualTo(ChangeType.UPDATE);
            assertThat(e.getParseWarningsList()).as(name)
                    .doesNotContain(ParseWarning.PARSE_WARNING_HEURISTIC_CLASSIFICATION);
        }
    }

    @Test
    void aDeleteThatNamesAVersionIsAnUpdateCarryingTheVersion() {
        // Q-651: a user's version delete and a lifecycle noncurrent expiration.
        ObjectNode user = withObject(record("s3.amazonaws.com", "DeleteObject"), "bucket-one", "k/a.csv");
        user.putObject("userIdentity").put("type", "IAMUser").put("arn", "arn:aws:iam::123456789012:user/u");
        ((ObjectNode) user.get("requestParameters")).put("versionId", "v-111");
        ObjectNode lifecycle = withObject(record("s3lifecycle.amazonaws.com", "DeleteObject"), "bucket-one", "k/b.csv");
        ((ObjectNode) lifecycle.get("requestParameters")).put("versionId", "v-222");

        DlapLogEvent u = only(parse(user));
        DlapLogEvent l = only(parse(lifecycle));

        assertThat(u.getChangeType()).isEqualTo(ChangeType.UPDATE);
        assertThat(u.getEventCategory()).isEqualTo(EventCategory.EVENT_CATEGORY_MANAGEMENT);
        assertThat(u.getTargetResource().getCanonicalUri()).isEqualTo("s3://bucket-one/k/a.csv");
        assertThat(u.getTargetResource().getTechnicalMetadataMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
                VersionedDeletes.ACTION_KEY, VersionedDeletes.DELETE_VERSION, VersionedDeletes.VERSION_ID_KEY, "v-111"));
        assertThat(l.getChangeType()).isEqualTo(ChangeType.UPDATE);
        assertThat(l.getPrincipal().getType()).isEqualTo(PrincipalType.SYSTEM);
        assertThat(l.getTargetResource().getTechnicalMetadataMap())
                .containsEntry(VersionedDeletes.VERSION_ID_KEY, "v-222");
    }

    @Test
    void aDeleteThatOnlyAddedADeleteMarkerIsAnUpdate() {
        ObjectNode text = withObject(record("s3.amazonaws.com", "DeleteObject"), "bucket-one", "k");
        text.putObject("responseElements").put("x-amz-delete-marker", "true").put("x-amz-version-id", "dm-1");
        ObjectNode bool = withObject(record("s3.amazonaws.com", "DeleteObject"), "bucket-one", "k");
        bool.putObject("responseElements").put("x-amz-delete-marker", true);

        DlapLogEvent t = only(parse(text));
        DlapLogEvent b = only(parse(bool));

        assertThat(t.getChangeType()).isEqualTo(ChangeType.UPDATE);
        assertThat(t.getTargetResource().getTechnicalMetadataMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
                VersionedDeletes.ACTION_KEY, VersionedDeletes.CREATE_DELETE_MARKER, VersionedDeletes.VERSION_ID_KEY, "dm-1"));
        assertThat(b.getChangeType()).isEqualTo(ChangeType.UPDATE);
        assertThat(b.getTargetResource().getTechnicalMetadataMap())
                .containsEntry(VersionedDeletes.ACTION_KEY, VersionedDeletes.CREATE_DELETE_MARKER)
                .doesNotContainKey(VersionedDeletes.VERSION_ID_KEY);
    }

    @Test
    void onlyADeleteOfTheCurrentObjectWithoutAVersionStaysADelete() {
        ObjectNode plain = withObject(record("s3.amazonaws.com", "DeleteObject"), "bucket-one", "k");
        ObjectNode noMarker = withObject(record("s3.amazonaws.com", "DeleteObject"), "bucket-one", "k");
        noMarker.putObject("responseElements").put("x-amz-delete-marker", "false");
        ObjectNode blankVersion = withObject(record("s3.amazonaws.com", "DeleteObject"), "bucket-one", "k");
        ((ObjectNode) blankVersion.get("requestParameters")).put("versionId", " ");
        ObjectNode markerOnBucket = withObject(record("s3.amazonaws.com", "DeleteBucket"), "bucket-one", null);
        markerOnBucket.putObject("responseElements").put("x-amz-delete-marker", "true");

        for (ObjectNode r : List.of(plain, noMarker, blankVersion)) {
            DlapLogEvent e = only(parse(r));
            assertThat(e.getChangeType()).isEqualTo(ChangeType.DELETE);
            assertThat(e.getTargetResource().getTechnicalMetadataMap()).isEmpty();
        }
        assertThat(only(parse(markerOnBucket)).getChangeType()).as("only object deletes are version-qualified")
                .isEqualTo(ChangeType.DELETE);
    }

    @Test
    void anEmptyErrorCodeIsNoErrorAndAnyOtherIsAFailure() {
        ObjectNode ok = withObject(record("s3lifecycle.amazonaws.com", "DeleteObject"), "bucket-one", "k");
        ok.put("errorCode", "");
        ObjectNode denied = withObject(record("s3lifecycle.amazonaws.com", "DeleteObject"), "bucket-one", "k");
        denied.put("errorCode", "AccessDenied");

        assertThat(only(parse(ok)).getResultStatus()).isEqualTo(ResultStatus.SUCCESS);
        assertThat(only(parse(denied)).getResultStatus()).isEqualTo(ResultStatus.FAILURE);
    }

    @Test
    void anotherMajorVersionIsQuarantinedAndANewerMinorParsesWithAWarning() {
        ObjectNode major = withObject(record("s3lifecycle.amazonaws.com", "DeleteObject"), "bucket-one", "k");
        major.put("eventVersion", "2.0");
        ObjectNode minor = withObject(record("s3lifecycle.amazonaws.com", "DeleteObject"), "bucket-one", "k");
        minor.put("eventVersion", "1.12");

        assertThat(parse(major).outcomeCode()).isEqualTo("QUARANTINED:UNKNOWN_MAJOR_VERSION");
        assertThat(only(parse(minor)).getParseWarningsList()).contains(ParseWarning.PARSE_WARNING_NEWER_MINOR);
    }

    @Test
    void aRecordWithoutRequestParametersTakesItsFirstResourceArn() {
        ObjectNode r = record("sts.amazonaws.com", "AssumeRole");
        r.putArray("resources").add(JSON.createObjectNode().put("type", "AWS::IAM::Role")
                .put("ARN", "arn:aws:iam::123456789012:role/Etl"));

        DlapLogEvent e = only(parse(r));

        assertThat(e.getTargetResource().getId()).isEqualTo("arn:aws:iam::123456789012:role/Etl");
        assertThat(e.getTargetResource().getKind()).isEqualTo("AWS::IAM::Role");
        assertThat(e.getChangeType()).isEqualTo(ChangeType.ACCESS);
    }

    @Test
    void theResourcePickerSkipsEntriesWithoutAnArn() {
        assertThat(CloudTrailParser.resource(null)).isNull();
        assertThat(CloudTrailParser.resource(JSON.createObjectNode())).isNull();
        assertThat(CloudTrailParser.resource(JSON.createArrayNode().add(JSON.createObjectNode().put("type", "x"))))
                .isNull();
        JsonNode first = JSON.createObjectNode().put("type", "AWS::S3::Bucket").put("ARN", "arn:aws:s3:::b");
        assertThat(CloudTrailParser.resource(JSON.createArrayNode().add(JSON.createObjectNode().put("ARN", " "))
                .add(first))).isEqualTo(first);
    }
}
