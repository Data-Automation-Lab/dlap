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

package com.dlap.logingest.source.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dlap.logingest.dto.IngestRequestDTO;
import com.dlap.logingest.repository.SourceWatermarkRepository;
import com.dlap.logingest.service.ILogIngestService;
import com.dlap.logingest.source.gcp.SourceWatermarkEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.Event;
import software.amazon.awssdk.services.cloudtrail.model.LookupEventsRequest;
import software.amazon.awssdk.services.cloudtrail.model.LookupEventsResponse;
import software.amazon.awssdk.services.cloudtrail.model.Resource;

/**
 * Unit tests for {@link CloudTrailLogSource}: the field mapping from the
 * {@code cloudTrailEvent} record (result, accounts, stable principal, version,
 * raw event), the oldest-first slice walk, the cursor that never passes a
 * failed event, and the 20-minute overlap with dedup on {@code eventID}.
 *
 * <p>No network, no credentials and no emulator are involved. LocalStack
 * community does not implement CloudTrail (Pro feature), so the
 * {@code LookupEvents} call is served by an in-memory {@link CloudTrailClient}
 * that filters by the request's time range and pages newest first, as the real
 * API does. Every record below is synthetic and written for this test.
 */
class CloudTrailLogSourceTest {

    private static final String REGION = "us-east-1";
    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String IAM_USER = """
            {"type":"IAMUser","principalId":"AIDAEXAMPLEUSER0001","arn":"arn:aws:iam::111122223333:user/alice",
             "accountId":"111122223333","accessKeyId":"AKIAEXAMPLEKEY0001","userName":"alice"}""";

    private static final String ASSUMED_ROLE = """
            {"type":"AssumedRole","principalId":"AROAEXAMPLEROLE001:etl-session",
             "arn":"arn:aws:sts::111122223333:assumed-role/DataEngineer/etl-session",
             "accountId":"111122223333","accessKeyId":"ASIAEXAMPLEKEY0002",
             "sessionContext":{"sessionIssuer":{"type":"Role","principalId":"AROAEXAMPLEROLE001",
               "arn":"arn:aws:iam::111122223333:role/DataEngineer","accountId":"111122223333",
               "userName":"DataEngineer"}}}""";

    private static final String AWS_SERVICE = """
            {"type":"AWSService","invokedBy":"config.amazonaws.com"}""";

    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AtomicReference<SourceWatermarkEntity> watermark = new AtomicReference<>();
    private final SourceWatermarkRepository watermarkRepo = mock(SourceWatermarkRepository.class);
    private final RecordingIngest ingest = new RecordingIngest();
    private final List<FakeCloudTrail> clients = new ArrayList<>();

    private FakeCloudTrail cloudTrail = new FakeCloudTrail(List.of());
    private CloudTrailLogSource source;

    @BeforeEach
    void setUp() {
        when(watermarkRepo.findByProviderAndAccountId(anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(watermark.get()));
        when(watermarkRepo.save(any(SourceWatermarkEntity.class))).thenAnswer(inv -> {
            SourceWatermarkEntity saved = inv.getArgument(0);
            watermark.set(saved);
            return saved;
        });
        source = newSource(ingest);
    }

    private CloudTrailLogSource newSource(ILogIngestService service) {
        return new CloudTrailLogSource(service, watermarkRepo, metrics, clock, region -> cloudTrail);
    }

    // ---- field mapping from the cloudTrailEvent record ----

    @Test
    void mapsIamUserManagementEventFromTheRecord() {
        Event event = event("e-123", at(0), "CreateBucket", IAM_USER)
                .toBuilder()
                .resources(Resource.builder()
                        .resourceType("AWS::S3::Bucket")
                        .resourceName("dlap-demo")
                        .build())
                .build();

        IngestRequestDTO dto = source.mapToIngestRequest(event, REGION);

        assertThat(dto).isNotNull();
        assertThat(dto.getSourceProvider()).isEqualTo("AWS");
        assertThat(dto.getSourceService()).isEqualTo("s3.amazonaws.com");
        assertThat(dto.getOperation()).isEqualTo("CreateBucket");
        assertThat(dto.getChangeType()).isEqualTo("CREATE");
        assertThat(dto.getResultStatus()).isEqualTo("SUCCESS");
        assertThat(dto.getEventTime()).isEqualTo(NOW);
        assertThat(dto.getPrincipalId()).isEqualTo("arn:aws:iam::111122223333:user/alice");
        assertThat(dto.getPrincipalType()).isEqualTo("USER");
        assertThat(dto.getPrincipalDisplayName()).isEqualTo("alice");
        assertThat(dto.getPrincipalAccountId()).isEqualTo("111122223333");
        assertThat(dto.getTargetProject()).isEqualTo("111122223333");
        assertThat(dto.getTargetResourceKind()).isEqualTo("AWS::S3::Bucket");
        assertThat(dto.getTargetResourceId()).isEqualTo("dlap-demo");
        assertThat(dto.getTargetRegion()).isEqualTo("us-east-1");
        assertThat(dto.getCallerIp()).isEqualTo("198.51.100.7");
        assertThat(dto.getUserAgent()).isEqualTo("synthetic-agent");
    }

    @Test
    void nativeEventKeyIsTheEventIdSoRereadsKeepTheSameEventId() {
        IngestRequestDTO first = source.mapToIngestRequest(event("evt-key-1", at(3), "CreateBucket", IAM_USER), REGION);
        IngestRequestDTO again = source.mapToIngestRequest(event("evt-key-1", at(3), "CreateBucket", IAM_USER), REGION);

        assertThat(first.getRequestId()).isEqualTo("evt-key-1");
        assertThat(again.getRequestId()).isEqualTo(first.getRequestId());
    }

    @Test
    void assumedRolePrincipalIsTheRoleArnNotTheSessionOrTheAccessKey() {
        Event event = event("e-role", at(0), "PutBucketPolicy", ASSUMED_ROLE)
                .toBuilder().username("etl-session").accessKeyId("ASIAEXAMPLEKEY0002").build();

        IngestRequestDTO dto = source.mapToIngestRequest(event, REGION);

        assertThat(dto.getPrincipalId()).isEqualTo("arn:aws:iam::111122223333:role/DataEngineer");
        assertThat(dto.getPrincipalType()).isEqualTo("ROLE");
        assertThat(dto.getPrincipalDisplayName()).isEqualTo("etl-session");
        assertThat(dto.getPrincipalAccountId()).isEqualTo("111122223333");
        assertThat(dto.getPrincipalId()).doesNotContain("ASIA").doesNotContain("etl-session");
    }

    @Test
    void assumedRoleWithoutSessionIssuerFallsBackToTheCallerArn() {
        String identity = """
                {"type":"AssumedRole","arn":"arn:aws:sts::111122223333:assumed-role/Ops/sess","accountId":"111122223333"}""";

        Event noUsername = event("e-1", at(0), "ListBuckets", identity).toBuilder().username(null).build();

        IngestRequestDTO dto = source.mapToIngestRequest(noUsername, REGION);

        assertThat(dto.getPrincipalId()).isEqualTo("arn:aws:sts::111122223333:assumed-role/Ops/sess");
        assertThat(dto.getPrincipalDisplayName()).isEqualTo("sess");
    }

    @Test
    void awsServicePrincipalIsSystemAndHasNoAccount() {
        Event event = event("e-svc", at(0), "PutObject", AWS_SERVICE)
                .toBuilder().username("config.amazonaws.com").build();

        IngestRequestDTO dto = source.mapToIngestRequest(event, REGION);

        assertThat(dto.getPrincipalType()).isEqualTo("SYSTEM");
        assertThat(dto.getPrincipalId()).isEqualTo("config.amazonaws.com");
        assertThat(dto.getPrincipalAccountId()).isNull();
    }

    @Test
    void principalTypeFollowsTheIdentityTypeNotTheName() {
        assertThat(CloudTrailLogSource.derivePrincipalType("IAMUser", null)).isEqualTo("USER");
        assertThat(CloudTrailLogSource.derivePrincipalType("Root", null)).isEqualTo("USER");
        assertThat(CloudTrailLogSource.derivePrincipalType("FederatedUser", null)).isEqualTo("USER");
        assertThat(CloudTrailLogSource.derivePrincipalType("IdentityCenterUser", null)).isEqualTo("USER");
        assertThat(CloudTrailLogSource.derivePrincipalType("AssumedRole", null)).isEqualTo("ROLE");
        assertThat(CloudTrailLogSource.derivePrincipalType("AWSService", "ec2.amazonaws.com")).isEqualTo("SYSTEM");
        // A user or role calling through a service (invokedBy set) is still that user or role.
        assertThat(CloudTrailLogSource.derivePrincipalType("IAMUser", "cloudformation.amazonaws.com")).isEqualTo("USER");
        assertThat(CloudTrailLogSource.derivePrincipalType("AssumedRole", "cloudformation.amazonaws.com")).isEqualTo("ROLE");
        // No type: a service if it names the invoker, otherwise unknown. A name suffix is never consulted.
        assertThat(CloudTrailLogSource.derivePrincipalType(null, "config.amazonaws.com")).isEqualTo("SYSTEM");
        assertThat(CloudTrailLogSource.derivePrincipalType(null, null)).isNull();
        assertThat(CloudTrailLogSource.derivePrincipalType("FutureIdentityType", null)).isNull();
    }

    @Test
    void errorCodeMakesTheResultAFailure() {
        String denied = record("e-denied", at(0), "DeleteBucket", IAM_USER,
                "\"errorCode\":\"AccessDenied\",\"errorMessage\":\"Access Denied\"");
        Event event = Event.builder().eventId("e-denied").eventName("DeleteBucket")
                .eventSource("s3.amazonaws.com").eventTime(NOW).username("alice").readOnly("false")
                .cloudTrailEvent(denied).build();

        IngestRequestDTO dto = source.mapToIngestRequest(event, REGION);

        assertThat(dto.getResultStatus()).isEqualTo("FAILURE");
        // The operation is still reported as it was attempted; only the result differs.
        assertThat(dto.getChangeType()).isEqualTo("DELETE");
    }

    @Test
    void recordWithoutErrorCodeIsASuccess() {
        IngestRequestDTO dto = source.mapToIngestRequest(event("e-ok", at(0), "ListBuckets", IAM_USER), REGION);

        assertThat(dto.getResultStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void accountsComeFromTheRecordAndTheRegionIsNeverUsedAsAnAccount() {
        // A record with no account fields at all: the accounts stay empty rather than becoming the region.
        String bare = """
                {"eventVersion":"1.11","eventID":"e-bare","eventTime":"2026-10-18T12:00:00Z",
                 "eventSource":"s3.amazonaws.com","eventName":"ListBuckets","userIdentity":{"type":"AWSService",
                 "invokedBy":"config.amazonaws.com"}}""";
        Event event = Event.builder().eventId("e-bare").eventName("ListBuckets")
                .eventSource("s3.amazonaws.com").eventTime(NOW).cloudTrailEvent(bare).build();

        IngestRequestDTO dto = source.mapToIngestRequest(event, "eu-west-1");

        assertThat(dto.getPrincipalAccountId()).isNull();
        assertThat(dto.getTargetProject()).isNull();
        // The polled region is the fallback for the region only.
        assertThat(dto.getTargetRegion()).isEqualTo("eu-west-1");
    }

    @Test
    void recipientAccountIsTheTargetAccountEvenWhenTheCallerIsFromAnotherAccount() {
        String crossAccount = """
                {"type":"AssumedRole","arn":"arn:aws:sts::999988887777:assumed-role/Partner/s1",
                 "accountId":"999988887777","sessionContext":{"sessionIssuer":{"type":"Role",
                 "arn":"arn:aws:iam::999988887777:role/Partner","accountId":"999988887777"}}}""";

        IngestRequestDTO dto = source.mapToIngestRequest(event("e-x", at(0), "ListBuckets", crossAccount), REGION);

        assertThat(dto.getPrincipalAccountId()).isEqualTo("999988887777");
        assertThat(dto.getTargetProject()).isEqualTo("111122223333");
    }

    @Test
    void rawEventIsTheOriginalRecordJson() throws Exception {
        String json = record("e-raw", at(0), "CreateBucket", IAM_USER, "\"requestParameters\":{\"bucketName\":\"dlap-demo\"}");
        Event event = Event.builder().eventId("e-raw").eventName("CreateBucket")
                .eventSource("s3.amazonaws.com").eventTime(NOW).cloudTrailEvent(json).build();

        IngestRequestDTO dto = source.mapToIngestRequest(event, REGION);

        Map<String, Object> expected = MAPPER.readValue(json, new TypeReference<>() { });
        assertThat(dto.getRawEvent()).isEqualTo(expected);
        assertThat(dto.getRawEvent()).containsEntry("eventVersion", "1.11").containsEntry("eventID", "e-raw");
        assertThat(dto.getRawEvent()).doesNotContainKey("cloudTrailEvent");
        assertThat(dto.getRawEvent().get("userIdentity")).isInstanceOf(Map.class);
    }

    @Test
    void unsupportedMajorVersionIsCountedAndTheEventIsStillIngested() {
        String v2 = record("e-v2", at(0), "ListBuckets", IAM_USER, "").replace("\"1.11\"", "\"2.0\"");
        Event event = Event.builder().eventId("e-v2").eventName("ListBuckets")
                .eventSource("s3.amazonaws.com").eventTime(NOW).cloudTrailEvent(v2).build();

        IngestRequestDTO dto = source.mapToIngestRequest(event, REGION);

        assertThat(dto).isNotNull();
        assertThat(metrics.counter(CloudTrailLogSource.METRIC_UNSUPPORTED_VERSION, "version", "2.0").count())
                .isEqualTo(1.0);
    }

    @Test
    void knownMajorVersionsAreNotCounted() {
        for (String version : List.of("1.0", "1.08", "1.11", "1.99")) {
            String json = record("e-" + version, at(0), "ListBuckets", IAM_USER, "").replace("\"1.11\"", "\"" + version + "\"");
            source.mapToIngestRequest(Event.builder().eventId("e-" + version).eventName("ListBuckets")
                    .eventSource("s3.amazonaws.com").eventTime(NOW).cloudTrailEvent(json).build(), REGION);
        }

        assertThat(metrics.find(CloudTrailLogSource.METRIC_UNSUPPORTED_VERSION).counters()).isEmpty();
    }

    @Test
    void missingOrGarbledVersionIsCountedAsUnrecognized() {
        String noVersion = """
                {"eventID":"e-nv","eventTime":"2026-10-18T12:00:00Z","eventName":"ListBuckets","userIdentity":{"type":"IAMUser"}}""";
        IngestRequestDTO dto = source.mapToIngestRequest(Event.builder().eventId("e-nv").eventName("ListBuckets")
                .eventTime(NOW).cloudTrailEvent(noVersion).build(), REGION);

        assertThat(dto).isNotNull();
        assertThat(metrics.counter(CloudTrailLogSource.METRIC_UNSUPPORTED_VERSION, "version", "unrecognized").count())
                .isEqualTo(1.0);
    }

    @Test
    void missingOrInvalidRecordIsSkippedAndCountedNotMappedAsSuccess() {
        for (String bad : new String[] {null, "", "   ", "not json", "[1,2,3]", "\"text\"", "{\"truncated\":"}) {
            Event event = Event.builder().eventId("e-bad").eventName("PutObject")
                    .eventSource("s3.amazonaws.com").eventTime(NOW).cloudTrailEvent(bad).build();
            assertThat(source.mapToIngestRequest(event, REGION)).as("record %s", bad).isNull();
        }

        assertThat(metrics.counter(CloudTrailLogSource.METRIC_SKIPPED, "reason",
                CloudTrailLogSource.REASON_UNPARSEABLE_RECORD).count()).isEqualTo(7.0);
    }

    @Test
    void eventTimeFallsBackToTheRecordAndNeverToTheClock() {
        String json = record("e-t", at(7), "ListBuckets", IAM_USER, "");
        Event noSdkTime = Event.builder().eventId("e-t").eventName("ListBuckets")
                .eventSource("s3.amazonaws.com").cloudTrailEvent(json).build();

        IngestRequestDTO fromRecord = source.mapToIngestRequest(noSdkTime, REGION);
        assertThat(fromRecord.getEventTime()).isEqualTo(at(7));

        String noTime = """
                {"eventVersion":"1.11","eventID":"e-nt","eventName":"ListBuckets","userIdentity":{"type":"IAMUser"}}""";
        Event neither = Event.builder().eventId("e-nt").eventName("ListBuckets").cloudTrailEvent(noTime).build();
        assertThat(source.mapToIngestRequest(neither, REGION)).isNull();

        String badTime = noTime.replace("\"eventID\"", "\"eventTime\":\"yesterday\",\"eventID\"");
        Event unparseable = Event.builder().eventId("e-bt").eventName("ListBuckets").cloudTrailEvent(badTime).build();
        assertThat(source.mapToIngestRequest(unparseable, REGION)).isNull();

        assertThat(metrics.counter(CloudTrailLogSource.METRIC_SKIPPED, "reason",
                CloudTrailLogSource.REASON_NO_EVENT_TIME).count()).isEqualTo(2.0);
    }

    @Test
    void eventWithoutNameIsSkipped() {
        Event event = Event.builder().eventSource("s3.amazonaws.com").build();

        assertThat(source.mapToIngestRequest(event, REGION)).isNull();
        assertThat(metrics.counter(CloudTrailLogSource.METRIC_SKIPPED, "reason",
                CloudTrailLogSource.REASON_NO_EVENT_NAME).count()).isEqualTo(1.0);
    }

    @Test
    void changeTypeDerivation() {
        assertThat(CloudTrailLogSource.deriveChangeType("CreateBucket", "false")).isEqualTo("CREATE");
        assertThat(CloudTrailLogSource.deriveChangeType("DeleteDBInstance", "false")).isEqualTo("DELETE");
        assertThat(CloudTrailLogSource.deriveChangeType("TerminateInstances", "false")).isEqualTo("DELETE");
        assertThat(CloudTrailLogSource.deriveChangeType("PutBucketPolicy", "false")).isEqualTo("PERMISSION");
        assertThat(CloudTrailLogSource.deriveChangeType("ListBuckets", "false")).isEqualTo("ACCESS");
        assertThat(CloudTrailLogSource.deriveChangeType("DescribeInstances", "false")).isEqualTo("ACCESS");
        assertThat(CloudTrailLogSource.deriveChangeType("ModifyDBInstance", "false")).isEqualTo("UPDATE");
        // readOnly flag short-circuits to ACCESS regardless of verb.
        assertThat(CloudTrailLogSource.deriveChangeType("GetObject", "true")).isEqualTo("ACCESS");
        assertThat(CloudTrailLogSource.deriveChangeType(null, "false")).isEqualTo("UPDATE");
    }

    // ---- oldest first ----

    @Test
    void ingestsOldestFirstAcrossNewestFirstPages() {
        // Five events, two per page: the API serves 5,4 then 3,2 then 1 (newest first).
        cloudTrail = new FakeCloudTrail(List.of(
                event("e-1", at(9), "CreateBucket", IAM_USER),
                event("e-2", at(8), "CreateBucket", IAM_USER),
                event("e-3", at(7), "CreateBucket", IAM_USER),
                event("e-4", at(6), "CreateBucket", IAM_USER),
                event("e-5", at(5), "CreateBucket", IAM_USER)));
        ReflectionTestUtils.setField(source, "maxResults", 2);

        int handed = source.pollRegion(REGION);

        assertThat(handed).isEqualTo(5);
        assertThat(ingest.requestIds()).containsExactly("e-1", "e-2", "e-3", "e-4", "e-5");
        assertThat(cloudTrail.requests.get(0).nextToken()).isNull();
        assertThat(cloudTrail.requests.stream().filter(r -> r.nextToken() != null)).isNotEmpty();
    }

    @Test
    void sameInstantEventsAreAllIngestedAndTheOrderIsOldestFirstOverall() {
        cloudTrail = new FakeCloudTrail(List.of(
                event("late", at(1), "CreateBucket", IAM_USER),
                event("same-a", at(4), "CreateBucket", IAM_USER),
                event("same-b", at(4), "CreateBucket", IAM_USER),
                event("early", at(9), "CreateBucket", IAM_USER)));

        source.pollRegion(REGION);

        assertThat(ingest.requestIds()).hasSize(4);
        assertThat(ingest.requestIds().get(0)).isEqualTo("early");
        assertThat(ingest.requestIds().get(3)).isEqualTo("late");
        assertThat(ingest.received).extracting(IngestRequestDTO::getEventTime)
                .isSortedAccordingTo(Comparator.naturalOrder());
    }

    @Test
    void walksALongWindowInConsecutiveSlicesOldestFirst() {
        cloudTrail = new FakeCloudTrail(List.of(
                event("e-old", at(58), "CreateBucket", IAM_USER),
                event("e-mid", at(31), "CreateBucket", IAM_USER),
                event("e-new", at(2), "CreateBucket", IAM_USER)));

        source.pollRegion(REGION);

        // First run: 60-minute lookback in 5-minute slices.
        assertThat(cloudTrail.requests).hasSize(12);
        assertThat(cloudTrail.requests.get(0).startTime()).isEqualTo(at(60));
        assertThat(cloudTrail.requests.get(11).endTime()).isEqualTo(NOW);
        for (int i = 1; i < cloudTrail.requests.size(); i++) {
            assertThat(cloudTrail.requests.get(i).startTime()).isEqualTo(cloudTrail.requests.get(i - 1).endTime());
        }
        assertThat(ingest.requestIds()).containsExactly("e-old", "e-mid", "e-new");
        assertThat(watermark.get().getLastEventTime()).isEqualTo(NOW);
    }

    @Test
    void anEventOnASliceBoundaryIsIngestedOnce() {
        // Both slices include their end points, so the 5-minute mark is returned twice.
        ReflectionTestUtils.setField(source, "initialLookbackMinutes", 10L);
        cloudTrail = new FakeCloudTrail(List.of(
                event("boundary", at(5), "CreateBucket", IAM_USER),
                event("after", at(3), "CreateBucket", IAM_USER)));

        source.pollRegion(REGION);

        assertThat(cloudTrail.requests).hasSize(2);
        assertThat(ingest.requestIds()).containsExactly("boundary", "after");
    }

    @Test
    void maxSlicesPerPollBoundsACatchUpAndTheCursorStopsAtTheLastSlice() {
        ReflectionTestUtils.setField(source, "maxSlicesPerPoll", 3);

        source.pollRegion(REGION);

        assertThat(cloudTrail.requests).hasSize(3);
        assertThat(watermark.get().getLastEventTime()).isEqualTo(at(45));
    }

    // ---- the cursor never passes a failed event ----

    @Test
    void cursorStaysBeforeTheFailedEventAndTheNextPollRetriesIt() {
        cloudTrail = new FakeCloudTrail(List.of(
                event("e-1", at(9), "CreateBucket", IAM_USER),
                event("e-2", at(8), "CreateBucket", IAM_USER),
                event("e-3", at(7), "CreateBucket", IAM_USER),
                event("e-4", at(6), "CreateBucket", IAM_USER),
                event("e-5", at(5), "CreateBucket", IAM_USER)));
        ingest.failWhen = dto -> "e-3".equals(dto.getRequestId());

        int handed = source.pollRegion(REGION);

        // Only the two events before the failure were ingested; nothing after it was tried.
        assertThat(handed).isEqualTo(2);
        assertThat(ingest.requestIds()).containsExactly("e-1", "e-2");
        assertThat(watermark.get().getLastEventTime()).isEqualTo(at(8));
        assertThat(watermark.get().getLastEventTime()).isBefore(at(7));

        // The source recovers: the next poll re-reads from cursor - overlap and gets the rest.
        ingest.failWhen = dto -> false;
        source.pollRegion(REGION);

        assertThat(ingest.requestIds()).contains("e-3", "e-4", "e-5");
        assertThat(watermark.get().getLastEventTime()).isEqualTo(NOW);
    }

    @Test
    void aFailureOnTheFirstEventOfTheWindowLeavesTheCursorBeforeIt() {
        cloudTrail = new FakeCloudTrail(List.of(event("e-1", at(9), "CreateBucket", IAM_USER)));
        ingest.failWhen = dto -> true;

        int handed = source.pollRegion(REGION);

        assertThat(handed).isZero();
        assertThat(ingest.received).isEmpty();
        // The empty slices before the event completed, so the cursor sits just before it, never at or past it.
        assertThat(watermark.get().getLastEventTime()).isEqualTo(at(10));
        assertThat(watermark.get().getLastEventTime()).isBefore(at(9));
    }

    @Test
    void aFailureAfterAStoredCursorStopsAtTheLastSuccess() {
        storeWatermark(at(30));
        cloudTrail = new FakeCloudTrail(List.of(
                event("e-1", at(15), "CreateBucket", IAM_USER),
                event("e-2", at(12), "CreateBucket", IAM_USER)));
        ingest.failWhen = dto -> "e-2".equals(dto.getRequestId());

        source.pollRegion(REGION);

        // e-1 succeeded and the failure was at e-2 (12 minutes ago): the cursor moves forward from at(30) to e-1 only.
        assertThat(watermark.get().getLastEventTime()).isEqualTo(at(15));
    }

    @Test
    void aFailureInALaterSliceKeepsTheEarlierSlicesProgress() {
        cloudTrail = new FakeCloudTrail(List.of(
                event("slice-1", at(58), "CreateBucket", IAM_USER),
                event("slice-3", at(47), "CreateBucket", IAM_USER)));
        ingest.failWhen = dto -> "slice-3".equals(dto.getRequestId());

        source.pollRegion(REGION);

        assertThat(ingest.requestIds()).containsExactly("slice-1");
        // Slices 1 and 2 (to at(50)) completed; the failure sits in slice 3, so the cursor is at(50).
        assertThat(watermark.get().getLastEventTime()).isEqualTo(at(50));
    }

    @Test
    void aFetchErrorKeepsTheProgressOfTheSlicesBeforeIt() {
        cloudTrail = new FakeCloudTrail(List.of(event("first", at(58), "CreateBucket", IAM_USER)));
        cloudTrail.failAtRequest = 3;

        int handed = source.pollRegion(REGION);

        assertThat(handed).isEqualTo(1);
        assertThat(watermark.get().getLastEventTime()).isEqualTo(at(50));
    }

    @Test
    void anUnmappableEventIsSkippedAndTheCursorMovesPastIt() {
        Event garbled = Event.builder().eventId("e-garbled").eventName("PutObject")
                .eventSource("s3.amazonaws.com").eventTime(at(9)).cloudTrailEvent("{broken").build();
        cloudTrail = new FakeCloudTrail(List.of(garbled, event("e-ok", at(8), "CreateBucket", IAM_USER)));

        source.pollRegion(REGION);

        assertThat(ingest.requestIds()).containsExactly("e-ok");
        assertThat(watermark.get().getLastEventTime()).isEqualTo(NOW);
    }

    // ---- overlap and dedup on eventID ----

    @Test
    void everyPollStartsTwentyMinutesBeforeTheCursor() {
        storeWatermark(at(10));

        source.pollRegion(REGION);

        assertThat(cloudTrail.requests.get(0).startTime()).isEqualTo(at(30));
        assertThat(cloudTrail.requests.get(cloudTrail.requests.size() - 1).endTime()).isEqualTo(NOW);
    }

    @Test
    void firstRunStartsAtTheInitialLookbackWithoutAnOverlap() {
        source.pollRegion(REGION);

        assertThat(cloudTrail.requests.get(0).startTime()).isEqualTo(at(60));
    }

    @Test
    void overlapIsConfigurable() {
        ReflectionTestUtils.setField(source, "overlapMinutes", 5L);
        storeWatermark(at(10));

        source.pollRegion(REGION);

        assertThat(cloudTrail.requests.get(0).startTime()).isEqualTo(at(15));
    }

    @Test
    void aOneMinuteSliceStillReachesPastTheStoredCursorAndIngestsNewEvents() {
        // 12 one-minute slices would end at the 11:51 mark if the 20 overlap slices used the budget.
        ReflectionTestUtils.setField(source, "sliceMinutes", 1L);
        storeWatermark(at(1));
        cloudTrail = new FakeCloudTrail(List.of(event("e-new", NOW.minusSeconds(30), "CreateBucket", IAM_USER)));

        int handed = source.pollRegion(REGION);

        assertThat(handed).isEqualTo(1);
        assertThat(ingest.requestIds()).containsExactly("e-new");
        // 20 overlap slices (from 21 minutes back to the stored cursor) plus the one new slice.
        assertThat(cloudTrail.requests).hasSize(21);
        assertThat(cloudTrail.requests.get(0).startTime()).isEqualTo(at(21));
        assertThat(cloudTrail.requests.get(20).endTime()).isEqualTo(NOW);
        assertThat(watermark.get().getLastEventTime()).isAfter(at(1)).isEqualTo(NOW);
    }

    @Test
    void aBackedOffSliceStillReachesPastTheStoredCursorAndIngestsNewEvents() {
        // After two overflows the region's slice is 75 seconds; 12 of them (15 minutes) are shorter
        // than the 20-minute overlap, so the cursor must not depend on the per-poll budget alone.
        sliceByRegion().put(REGION, Duration.ofSeconds(75));
        storeWatermark(at(1));
        cloudTrail = new FakeCloudTrail(List.of(event("e-new", NOW.minusSeconds(30), "CreateBucket", IAM_USER)));

        int handed = source.pollRegion(REGION);

        assertThat(handed).isEqualTo(1);
        assertThat(ingest.requestIds()).containsExactly("e-new");
        assertThat(cloudTrail.requests.get(cloudTrail.requests.size() - 1).endTime()).isEqualTo(NOW);
        assertThat(watermark.get().getLastEventTime()).isAfter(at(1)).isEqualTo(NOW);
    }

    @Test
    void overlapSlicesDoNotUseUpThePerPollBudgetButSlicesPastTheCursorStillDo() {
        ReflectionTestUtils.setField(source, "maxSlicesPerPoll", 3);
        storeWatermark(at(40));

        source.pollRegion(REGION);

        // Four free overlap slices (at(60) to at(40)), then three slices past the cursor.
        assertThat(cloudTrail.requests).hasSize(7);
        assertThat(cloudTrail.requests.get(0).startTime()).isEqualTo(at(60));
        assertThat(cloudTrail.requests.get(6).endTime()).isEqualTo(at(25));
        assertThat(watermark.get().getLastEventTime()).isEqualTo(at(25));
    }

    @Test
    void rereadEventsInTheOverlapReachTheIngestServiceOnlyOnceByEventId() {
        DedupingIngest dedup = new DedupingIngest();
        source = newSource(dedup);
        cloudTrail = new FakeCloudTrail(List.of(
                event("e-1", at(9), "CreateBucket", IAM_USER),
                event("e-2", at(8), "CreateBucket", IAM_USER)));

        source.pollRegion(REGION);
        // A second poll, a minute later: the same events sit inside the 20-minute overlap again.
        cloudTrail.add(event("e-3", at(0), "CreateBucket", IAM_USER));
        source.pollRegion(REGION);

        // Both polls handed e-1 and e-2 to the ingest service, and the id was identical both times.
        assertThat(dedup.offered).containsExactly("e-1", "e-2", "e-1", "e-2", "e-3");
        assertThat(dedup.stored).containsExactlyInAnyOrder("e-1", "e-2", "e-3");
        assertThat(dedup.published).containsExactly("e-1", "e-2", "e-3");
    }

    @Test
    void eventIdsAreNeverRepeatedWithinOnePoll() {
        Event duplicate = event("e-dup", at(4), "CreateBucket", IAM_USER);
        // The fake serves the same event twice in one slice, as an overlapping page could.
        cloudTrail = new FakeCloudTrail(List.of(duplicate, duplicate, event("e-other", at(3), "CreateBucket", IAM_USER)));

        source.pollRegion(REGION);

        assertThat(ingest.requestIds()).containsExactly("e-dup", "e-other");
    }

    // ---- cursor housekeeping ----

    @Test
    void aCleanPollWithNoEventsStillMovesTheCursorToNowSoAQuietAccountDoesNotRescanItsHistory() {
        source.pollRegion(REGION);

        assertThat(watermark.get().getLastEventTime()).isEqualTo(NOW);
        assertThat(watermark.get().getProvider()).isEqualTo("AWS");
        assertThat(watermark.get().getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void theWatermarkRowIsKeyedByProviderAndRegionOnly() {
        source.pollRegion("eu-west-1");

        assertThat(watermark.get().getAccountId()).isEqualTo("eu-west-1");
    }

    @Test
    void aCursorInTheFutureMakesNoRequestAndIsNotMovedBack() {
        storeWatermark(NOW.plus(Duration.ofHours(2)));

        source.pollRegion(REGION);

        assertThat(cloudTrail.requests).isEmpty();
        assertThat(watermark.get().getLastEventTime()).isEqualTo(NOW.plus(Duration.ofHours(2)));
    }

    // ---- slice overflow safety valve ----

    @Test
    void aSliceOverTheCapIsNotIngestedAndTheNextTickReadsShorterSlices() {
        ReflectionTestUtils.setField(source, "maxEventsPerSlice", 3);
        ReflectionTestUtils.setField(source, "initialLookbackMinutes", 5L);
        cloudTrail = new FakeCloudTrail(List.of(
                event("e-1", at(4), "CreateBucket", IAM_USER),
                event("e-2", at(3), "CreateBucket", IAM_USER),
                event("e-3", at(2), "CreateBucket", IAM_USER),
                event("e-4", at(1), "CreateBucket", IAM_USER)));

        int handed = source.pollRegion(REGION);

        assertThat(handed).isZero();
        assertThat(ingest.received).isEmpty();
        assertThat(watermark.get()).isNull();
        assertThat(metrics.counter(CloudTrailLogSource.METRIC_SLICE_OVERFLOW, "region", REGION).count())
                .isEqualTo(1.0);

        // The next tick uses a 150-second slice, so the first request is shorter than five minutes.
        cloudTrail.requests.clear();
        source.pollRegion(REGION);
        LookupEventsRequest first = cloudTrail.requests.get(0);
        assertThat(Duration.between(first.startTime(), first.endTime())).isEqualTo(Duration.ofSeconds(150));
        assertThat(ingest.requestIds()).containsExactly("e-1", "e-2", "e-3", "e-4");
    }

    // ---- regions are independent ----

    @Test
    void oneFailingRegionDoesNotStopTheOthers() {
        FakeCloudTrail broken = new FakeCloudTrail(List.of());
        broken.failAtRequest = 1;
        FakeCloudTrail healthy = new FakeCloudTrail(List.of(event("e-ok", at(2), "CreateBucket", IAM_USER)));
        source = new CloudTrailLogSource(ingest, watermarkRepo, metrics, clock,
                region -> "ap-south-1".equals(region) ? broken : healthy);
        ReflectionTestUtils.setField(source, "regionsCsv", "ap-south-1, us-east-1");

        source.poll();

        assertThat(ingest.requestIds()).containsExactly("e-ok");
    }

    // ---- helpers ----

    /** {@code NOW} minus the given number of minutes. */
    private static Instant at(int minutesBeforeNow) {
        return NOW.minus(Duration.ofMinutes(minutesBeforeNow));
    }

    /** The source's per-region slice length map, which the overflow back-off writes to. */
    @SuppressWarnings("unchecked")
    private Map<String, Duration> sliceByRegion() {
        return (Map<String, Duration>) ReflectionTestUtils.getField(source, "sliceByRegion");
    }

    private void storeWatermark(Instant lastEventTime) {
        watermark.set(SourceWatermarkEntity.builder()
                .provider("AWS").accountId(REGION).lastEventTime(lastEventTime).updatedAt(lastEventTime).build());
    }

    /** An SDK event whose record is a synthetic management event by the given identity. */
    private static Event event(String eventId, Instant time, String name, String identityJson) {
        return Event.builder()
                .eventId(eventId)
                .eventName(name)
                .eventSource("s3.amazonaws.com")
                .eventTime(time)
                .username("alice")
                .readOnly("false")
                .cloudTrailEvent(record(eventId, time, name, identityJson, ""))
                .build();
    }

    /** A synthetic version 1.11 CloudTrail record; {@code extra} is spliced in as more top-level members. */
    private static String record(String eventId, Instant time, String name, String identityJson, String extra) {
        return """
                {"eventVersion":"1.11","eventID":"%s","eventTime":"%s","eventSource":"s3.amazonaws.com",
                 "eventName":"%s","awsRegion":"us-east-1","recipientAccountId":"111122223333",
                 "sourceIPAddress":"198.51.100.7","userAgent":"synthetic-agent","readOnly":false,
                 "userIdentity":%s%s}""".formatted(eventId, time, name, identityJson,
                extra.isEmpty() ? "" : "," + extra);
    }

    /** Records every event handed to ingest, in order, and can be told to fail on chosen ones. */
    private static final class RecordingIngest implements ILogIngestService {
        private final List<IngestRequestDTO> received = new ArrayList<>();
        private Predicate<IngestRequestDTO> failWhen = dto -> false;

        @Override
        public String ingest(IngestRequestDTO request) {
            if (failWhen.test(request)) {
                throw new IllegalStateException("kafka unavailable");
            }
            received.add(request);
            return request.getRequestId();
        }

        List<String> requestIds() {
            return received.stream().map(IngestRequestDTO::getRequestId).toList();
        }
    }

    /**
     * Models the ingest service's event-id dedup: an id already stored is
     * not published again. The id is the requestId, as the real service
     * hashes it.
     */
    private static final class DedupingIngest implements ILogIngestService {
        private final List<String> offered = new ArrayList<>();
        private final Set<String> stored = new HashSet<>();
        private final List<String> published = new ArrayList<>();

        @Override
        public String ingest(IngestRequestDTO request) {
            offered.add(request.getRequestId());
            if (stored.add(request.getRequestId())) {
                published.add(request.getRequestId());
            }
            return request.getRequestId();
        }
    }

    /**
     * In-memory {@code LookupEvents}: filters by the request's inclusive time
     * range and pages newest first with an offset token, like the real API.
     */
    private static final class FakeCloudTrail implements CloudTrailClient {
        private final List<Event> events;
        private final List<LookupEventsRequest> requests = new ArrayList<>();
        private int failAtRequest = -1;

        FakeCloudTrail(List<Event> events) {
            this.events = new ArrayList<>(events);
        }

        void add(Event event) {
            events.add(event);
        }

        @Override
        public String serviceName() {
            return "cloudtrail";
        }

        @Override
        public void close() {
            // Nothing to release.
        }

        @Override
        public LookupEventsResponse lookupEvents(LookupEventsRequest request) {
            requests.add(request);
            if (requests.size() == failAtRequest) {
                throw SdkClientException.create("simulated LookupEvents failure");
            }
            List<Event> inRange = events.stream()
                    .filter(e -> !e.eventTime().isBefore(request.startTime()) && !e.eventTime().isAfter(request.endTime()))
                    .sorted(Comparator.comparing(Event::eventTime).reversed())
                    .toList();
            int offset = request.nextToken() == null ? 0 : Integer.parseInt(request.nextToken());
            int end = Math.min(offset + request.maxResults(), inRange.size());
            return LookupEventsResponse.builder()
                    .events(inRange.subList(offset, end))
                    .nextToken(end < inRange.size() ? String.valueOf(end) : null)
                    .build();
        }
    }
}
