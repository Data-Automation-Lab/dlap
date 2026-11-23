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

import com.dlap.logingest.dto.IngestRequestDTO;
import com.dlap.logingest.repository.SourceWatermarkRepository;
import com.dlap.logingest.service.ILogIngestService;
import com.dlap.logingest.source.gcp.SourceWatermarkEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudtrail.CloudTrailClient;
import software.amazon.awssdk.services.cloudtrail.model.Event;
import software.amazon.awssdk.services.cloudtrail.model.LookupEventsRequest;
import software.amazon.awssdk.services.cloudtrail.model.LookupEventsResponse;
import software.amazon.awssdk.services.cloudtrail.model.Resource;

/**
 * AWS CloudTrail audit-log source for da-logingest (ADR-0021 + ADR-0023).
 *
 * <p>The AWS parallel of {@link com.dlap.logingest.source.gcp.GcpLogSource}:
 * pull-based, Kafka-only. Polls CloudTrail's {@code LookupEvents} API on
 * a configurable schedule, normalizes each management event to an
 * {@link IngestRequestDTO}, and hands it to {@link ILogIngestService#ingest}
 * (which publishes the canonical {@code DlapLogEvent} to
 * {@code dlap.log.event.normalized}, a DLAP-owned Kafka topic and not a
 * cloud-native pub-sub). {@code LookupEvents} returns management and
 * Insights events only, never S3 data events, so this source cannot see
 * object reads.
 *
 * <h2>Resume semantics</h2>
 *
 * <p>{@code LookupEvents} lists the newest event first, so a poll cannot
 * simply walk the pages and ingest as it goes: a failure on an older event
 * after newer ones had been ingested would leave the cursor past it and the
 * event would be lost. The poll therefore reads the window in short time
 * slices (default 5 minutes). Each slice is read to the end, ordered oldest
 * first, and ingested in that order. The first failed ingest stops the poll,
 * and the cursor stays at the last event that was ingested, so it never
 * passes an event that failed. After a slice completes, everything up to the
 * slice end has been handled and the cursor moves to the slice end, which
 * keeps a quiet account from re-reading an ever longer window.
 *
 * <p>Every poll starts at {@code cursor - overlap} (default 20 minutes) so
 * that an event which reaches CloudTrail's event history late is still read.
 * The duplicates this produces are bounded by the overlap and are dropped by
 * the ingest service, which does not republish an {@code event_id} it has
 * already stored. The CloudTrail {@code eventID} goes into
 * {@code requestId}, which the ingest service hashes into the
 * {@code event_id}, so the same event gets the same id on every re-read. An
 * event that arrives later than the overlap is not recovered by this source.
 * At most {@code max-slices-per-poll} slices past the stored cursor are read
 * per tick, so a long outage is caught up over several ticks instead of one
 * very long call. The overlap slices before the cursor do not count toward
 * that limit (they are extra, at most {@code ceil(overlap / slice)} of them),
 * so every poll moves past the stored cursor whatever the slice length. On
 * the first run a region starts from {@code now - initialLookbackMinutes}.
 *
 * <p>The cursor is the {@code source_watermarks} row keyed by
 * {@code (provider, accountId)} with {@code provider="AWS"}. Until the
 * LOG-02 cursor migration the {@code account_id} column of an AWS row holds
 * the polled <em>region</em>, because {@code LookupEvents} is per region;
 * that column is only a key here and is never copied into an event.
 *
 * <h2>Record fields</h2>
 *
 * <p>The SDK {@link Event} lacks the account, the result and the identity
 * type, so each event's {@code cloudTrailEvent} JSON is parsed:
 *
 * <ul>
 *   <li>{@code errorCode} present: {@code resultStatus=FAILURE}, otherwise
 *       {@code SUCCESS}.</li>
 *   <li>{@code recipientAccountId} becomes {@code targetProject} (the AWS
 *       account the event belongs to); {@code userIdentity.accountId}
 *       becomes {@code principalAccountId}. The region is never used as an
 *       account.</li>
 *   <li>{@code principalId} is the stable principal ARN: the role ARN
 *       ({@code sessionContext.sessionIssuer.arn}) for an
 *       {@code AssumedRole}, otherwise {@code userIdentity.arn}, then
 *       {@code invokedBy}. The access key id is never used because it
 *       rotates.</li>
 *   <li>{@code principalType} comes from {@code userIdentity.type}
 *       ({@code AWSService} is {@code SYSTEM}, {@code AssumedRole} and
 *       {@code Role} are {@code ROLE}, human identity types are
 *       {@code USER}), not from a name suffix.</li>
 *   <li>{@code eventVersion} is checked: a major version other than 1 is
 *       logged and counted under {@value #METRIC_UNSUPPORTED_VERSION} and the
 *       event is still ingested, because quarantine arrives with the LOG-02
 *       parser registry.</li>
 *   <li>{@code rawEvent} is the original CloudTrail record JSON (LOG-01
 *       convention).</li>
 *   <li>Event time is the event's {@code eventTime}, from the SDK or from the
 *       record. An event with neither is skipped and counted; the clock is
 *       never an event time. An event whose record is missing or is not a
 *       JSON object is skipped and counted too, because its result and
 *       account cannot be known.</li>
 * </ul>
 *
 * <p>Configuration:
 *
 * <ul>
 *   <li>{@code dlap.logingest.aws.enabled=true}</li>
 *   <li>{@code dlap.logingest.aws.regions} - comma-separated AWS regions
 *       to poll (e.g. {@code us-east-1,eu-west-1}).</li>
 *   <li>{@code dlap.logingest.aws.poll-cron} - Quartz cron; default
 *       {@code 0/60 * * * * ?} (every 60s).</li>
 *   <li>{@code dlap.logingest.aws.max-results} - events per page;
 *       default 50 (CloudTrail's maximum).</li>
 *   <li>{@code dlap.logingest.aws.initial-lookback-minutes} - first-run
 *       window; default 60.</li>
 *   <li>{@code dlap.logingest.aws.overlap-minutes} - re-read window before
 *       the cursor; default 20.</li>
 *   <li>{@code dlap.logingest.aws.slice-minutes} - length of one read
 *       slice; default 5.</li>
 *   <li>{@code dlap.logingest.aws.max-slices-per-poll} - slices past the
 *       stored cursor read per region per tick; default 12. The overlap
 *       slices before the cursor are read in addition.</li>
 *   <li>{@code dlap.logingest.aws.max-events-per-slice} - safety cap on the
 *       events held for one slice; default 20000. A slice that exceeds it is
 *       not ingested, the slice length for that region is halved in memory
 *       and the poll is retried on the next tick.</li>
 *   <li>{@code dlap.logingest.aws.endpoint} - optional endpoint override
 *       (e.g. a LocalStack URL) for dev.</li>
 *   <li>{@code dlap.logingest.aws.access-key} /
 *       {@code dlap.logingest.aws.secret-key} - optional static creds;
 *       otherwise the default chain (env keys + IMDS) is used.</li>
 * </ul>
 *
 * <p>Credentials resolve via the AWS default provider chain
 * (environment access keys, shared config, ECS/EKS container creds, EC2
 * IMDS workload identity) unless static keys are configured.
 */
@Component
@Profile({"aws", "prod"})
@ConditionalOnProperty(name = "dlap.logingest.aws.enabled", havingValue = "true")
@Slf4j
public class CloudTrailLogSource {

    private static final String PROVIDER = "AWS";

    /**
     * Name of the ShedLock row that keeps this poll a cluster-wide singleton (Q-550, plan rule R7).
     * One lock for the whole source: with two replicas only one reads CloudTrail per tick.
     */
    public static final String LOCK_NAME = "logingest-legacy-aws-poll";

    /**
     * Longest a poll may hold the lock: well over twice a normal run (regions times at most
     * {@code max-slices-per-poll} short slices), and the wait a crashed holder can cost the others.
     */
    public static final String LOCK_AT_MOST_FOR = "PT30M";

    /** Shortest hold, so a replica whose clock fires a moment later does not re-poll the same tick. */
    public static final String LOCK_AT_LEAST_FOR = "PT15S";

    /** Major {@code eventVersion} this mapping was written for. */
    private static final int SUPPORTED_MAJOR_VERSION = 1;

    /** Shortest slice the overflow back-off will shrink to. */
    private static final Duration MIN_SLICE = Duration.ofMinutes(1);

    /** Counter of events the source could not map, tagged with {@code reason}. */
    static final String METRIC_SKIPPED = "dlap.logingest.aws.events.skipped";

    /** Counter of events whose {@code eventVersion} major is not supported. */
    static final String METRIC_UNSUPPORTED_VERSION = "dlap.logingest.aws.events.unsupported_version";

    /** Counter of slices dropped because they held more than the per-slice cap. */
    static final String METRIC_SLICE_OVERFLOW = "dlap.logingest.aws.slice.overflow";

    static final String REASON_NO_EVENT_NAME = "no_event_name";
    static final String REASON_UNPARSEABLE_RECORD = "unparseable_record";
    static final String REASON_NO_EVENT_TIME = "no_event_time";
    static final String REASON_MAP_ERROR = "map_error";

    private static final Pattern EVENT_VERSION = Pattern.compile("^(\\d+)\\.(\\d+)$");

    private static final ObjectMapper RECORD_MAPPER = new ObjectMapper();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE =
            new TypeReference<>() { };

    private final ILogIngestService logIngestService;
    private final SourceWatermarkRepository watermarkRepo;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Function<String, CloudTrailClient> clientFactory;

    /** Slice length per region after an overflow back-off; absent means the configured length. */
    private final Map<String, Duration> sliceByRegion = new ConcurrentHashMap<>();

    /** {@code eventVersion} values already warned about, so the log is not flooded. */
    private final Set<String> warnedVersions = ConcurrentHashMap.newKeySet();

    @Value("${dlap.logingest.aws.regions:}")
    private String regionsCsv;

    @Value("${dlap.logingest.aws.max-results:50}")
    private int maxResults = 50;

    @Value("${dlap.logingest.aws.initial-lookback-minutes:60}")
    private long initialLookbackMinutes = 60;

    @Value("${dlap.logingest.aws.overlap-minutes:20}")
    private long overlapMinutes = 20;

    @Value("${dlap.logingest.aws.slice-minutes:5}")
    private long sliceMinutes = 5;

    @Value("${dlap.logingest.aws.max-slices-per-poll:12}")
    private int maxSlicesPerPoll = 12;

    @Value("${dlap.logingest.aws.max-events-per-slice:20000}")
    private int maxEventsPerSlice = 20_000;

    @Value("${dlap.logingest.aws.endpoint:}")
    private String endpointOverride;

    @Value("${dlap.logingest.aws.access-key:}")
    private String accessKey;

    @Value("${dlap.logingest.aws.secret-key:}")
    private String secretKey;

    /**
     * Spring constructor: reads the real CloudTrail API on the system clock.
     *
     * @param logIngestService ingest pipeline entry point.
     * @param watermarkRepo persistence for the per-region cursor.
     * @param meterRegistry registry for the skip, version and overflow counters.
     */
    @Autowired
    public CloudTrailLogSource(ILogIngestService logIngestService,
                               SourceWatermarkRepository watermarkRepo,
                               MeterRegistry meterRegistry) {
        this.logIngestService = logIngestService;
        this.watermarkRepo = watermarkRepo;
        this.meterRegistry = meterRegistry;
        this.clock = Clock.systemUTC();
        this.clientFactory = this::buildClient;
    }

    /**
     * Test constructor: injects the clock and the CloudTrail client factory.
     */
    CloudTrailLogSource(ILogIngestService logIngestService,
                        SourceWatermarkRepository watermarkRepo,
                        MeterRegistry meterRegistry,
                        Clock clock,
                        Function<String, CloudTrailClient> clientFactory) {
        this.logIngestService = logIngestService;
        this.watermarkRepo = watermarkRepo;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.clientFactory = clientFactory;
    }

    /**
     * Polled once per cron tick, a cluster-wide singleton under ShedLock (Q-550). Iterates over
     * configured regions; each iteration is independent - a failure on one region doesn't halt
     * the others.
     */
    @Scheduled(cron = "${dlap.logingest.aws.poll-cron:0/60 * * * * ?}")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = LOCK_AT_MOST_FOR, lockAtLeastFor = LOCK_AT_LEAST_FOR)
    public void poll() {
        List<String> regions = parseRegions();
        if (regions.isEmpty()) {
            log.debug("CloudTrailLogSource enabled but no regions configured; skipping poll");
            return;
        }

        for (String region : regions) {
            try {
                int polled = pollRegion(region);
                if (polled > 0) {
                    log.info("AWS CloudTrail poll: region={} handed-to-ingest={} (overlap duplicates "
                            + "are dropped by the ingest service)", region, polled);
                }
            } catch (Exception e) {
                log.warn("AWS CloudTrail poll failed for region {}: {}", region, e.getMessage());
            }
        }
    }

    /**
     * Pulls CloudTrail events for one region from {@code cursor - overlap}
     * (or from the initial lookback on the first run), a slice at a time,
     * oldest event first, and hands each to the ingest service. Persists the
     * cursor at the end; the cursor never passes an event whose ingest
     * failed.
     *
     * @param region AWS region to poll.
     * @return number of events handed to the ingest service in this call.
     */
    int pollRegion(String region) {
        Instant now = clock.instant();
        Optional<Instant> stored = readWatermark(region);
        Instant from = stored
                .map(w -> w.minus(Duration.ofMinutes(overlapMinutes)))
                .orElseGet(() -> now.minus(Duration.ofMinutes(initialLookbackMinutes)));

        PollState state = new PollState(stored.orElse(null));
        Set<String> seenEventIds = new HashSet<>();

        try (CloudTrailClient client = clientFactory.apply(region)) {
            Instant sliceStart = from;
            // Only slices that reach past the stored cursor use up the per-poll budget. The
            // overlap slices before it are a re-read of ground already covered, and counting
            // them would let a short slice (slice-minutes=1, or the overflow back-off) spend the
            // whole budget inside the overlap, so the cursor could never move forward again.
            int newSlices = 0;
            while (sliceStart.isBefore(now) && newSlices < maxSlicesPerPoll) {
                Duration slice = sliceLength(region);
                Instant sliceEnd = sliceStart.plus(slice).isAfter(now) ? now : sliceStart.plus(slice);
                List<Event> newestFirst = fetchSlice(client, sliceStart, sliceEnd);
                if (stored.isEmpty() || sliceEnd.isAfter(stored.get())) {
                    newSlices++;
                }
                if (newestFirst == null) {
                    shrinkSlice(region, slice);
                    break;
                }
                if (!ingestSlice(region, newestFirst, sliceEnd, seenEventIds, state)) {
                    break;
                }
                sliceStart = sliceEnd;
            }
        } catch (Exception e) {
            // Slices that completed before the error keep their progress.
            log.warn("AWS CloudTrail client error for region {}: {}", region, e.getMessage());
        }

        if (state.cursor != null && (stored.isEmpty() || state.cursor.isAfter(stored.get()))) {
            advanceWatermark(region, state.cursor);
        }
        return state.ingested;
    }

    /**
     * Reads every page of one time slice. {@code LookupEvents} returns the
     * newest event first, so the list is newest first too.
     *
     * @return the slice's events, or {@code null} when the slice holds more
     *         than the per-slice cap and must be read in shorter pieces.
     */
    private List<Event> fetchSlice(CloudTrailClient client, Instant start, Instant end) {
        List<Event> collected = new ArrayList<>();
        String nextToken = null;
        do {
            LookupEventsRequest.Builder req = LookupEventsRequest.builder()
                    .startTime(start)
                    .endTime(end)
                    .maxResults(maxResults);
            if (nextToken != null) {
                req.nextToken(nextToken);
            }
            LookupEventsResponse response = client.lookupEvents(req.build());
            if (response.events() != null) {
                collected.addAll(response.events());
            }
            if (collected.size() > maxEventsPerSlice) {
                return null;
            }
            nextToken = response.nextToken();
        } while (nextToken != null && !nextToken.isBlank());
        return collected;
    }

    /**
     * Maps and ingests one slice oldest first. The first failed ingest stops
     * the slice and leaves the cursor at the last event that succeeded.
     *
     * @return {@code true} when the whole slice was handled.
     */
    private boolean ingestSlice(String region, List<Event> newestFirst, Instant sliceEnd,
                                Set<String> seenEventIds, PollState state) {
        List<IngestRequestDTO> batch = new ArrayList<>(newestFirst.size());
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            Event event = newestFirst.get(i);
            String eventId = event.eventId();
            // Adjacent slices share their boundary instant, so one event can arrive twice.
            if (eventId != null && !seenEventIds.add(eventId)) {
                continue;
            }
            IngestRequestDTO dto = mapSafely(event, region);
            if (dto != null) {
                batch.add(dto);
            }
        }
        // The API order is not relied on: a stable sort keeps same-instant events in read order.
        batch.sort(Comparator.comparing(IngestRequestDTO::getEventTime));

        for (IngestRequestDTO dto : batch) {
            try {
                logIngestService.ingest(dto);
            } catch (Exception e) {
                log.warn("Ingest failed for CloudTrail eventID={}: {}; stopping this poll so the cursor "
                        + "stays before it", dto.getRequestId(), e.getMessage());
                return false;
            }
            state.ingested++;
            state.advanceTo(dto.getEventTime());
        }
        state.advanceTo(sliceEnd);
        return true;
    }

    private IngestRequestDTO mapSafely(Event event, String region) {
        try {
            return mapToIngestRequest(event, region);
        } catch (RuntimeException e) {
            // A record shape this mapping cannot handle must not block every later event.
            log.warn("Could not map CloudTrail eventID={}: {}", event.eventId(), e.getClass().getSimpleName());
            countSkipped(REASON_MAP_ERROR);
            return null;
        }
    }

    /**
     * Map a single CloudTrail {@link Event} to an {@link IngestRequestDTO}.
     * The SDK type carries the operation ({@code eventName}), the service
     * ({@code eventSource}) and a list of affected {@code resources}; the
     * result, the accounts, the stable principal and the version come from
     * the {@code cloudTrailEvent} record, which is also passed through as
     * {@code rawEvent} for audit fidelity.
     *
     * @param event the CloudTrail event from {@code LookupEvents}.
     * @param region the polled region, used when the record has no {@code awsRegion}.
     * @return the ingest request, or {@code null} when the event is skipped.
     */
    IngestRequestDTO mapToIngestRequest(Event event, String region) {
        if (event.eventName() == null || event.eventName().isBlank()) {
            countSkipped(REASON_NO_EVENT_NAME);
            return null;
        }
        JsonNode record = parseRecord(event);
        if (record == null) {
            log.warn("CloudTrail eventID={} has no parseable cloudTrailEvent record; skipped", event.eventId());
            countSkipped(REASON_UNPARSEABLE_RECORD);
            return null;
        }
        Instant eventTime = resolveEventTime(event, record);
        if (eventTime == null) {
            log.warn("CloudTrail eventID={} has no eventTime; skipped", event.eventId());
            countSkipped(REASON_NO_EVENT_TIME);
            return null;
        }
        checkEventVersion(text(record, "eventVersion"));

        JsonNode identity = record.path("userIdentity");
        String identityType = text(identity, "type");
        String principalId = stablePrincipalId(identity, identityType, event.username());
        String errorCode = text(record, "errorCode");
        Resource primary = (event.resources() != null && !event.resources().isEmpty())
                ? event.resources().get(0) : null;
        String readOnly = event.readOnly() != null ? event.readOnly() : text(record, "readOnly");

        return IngestRequestDTO.builder()
                .sourceProvider(PROVIDER)
                .sourceService(firstNonBlank(event.eventSource(), text(record, "eventSource")))
                .operation(event.eventName())
                .changeType(deriveChangeType(event.eventName(), readOnly))
                .resultStatus(errorCode != null ? "FAILURE" : "SUCCESS")
                .eventTime(eventTime)
                .principalId(principalId)
                .principalType(derivePrincipalType(identityType, text(identity, "invokedBy")))
                .principalDisplayName(displayName(event.username(), identity, principalId))
                .principalAccountId(text(identity, "accountId"))
                .targetResourceKind(primary != null ? primary.resourceType() : null)
                .targetResourceId(primary != null ? primary.resourceName() : null)
                .targetResourceName(primary != null ? primary.resourceName() : null)
                .targetProject(text(record, "recipientAccountId"))
                .targetRegion(firstNonBlank(text(record, "awsRegion"), region))
                .callerIp(text(record, "sourceIPAddress"))
                .userAgent(text(record, "userAgent"))
                .requestId(firstNonBlank(event.eventId(), text(record, "eventID")))
                .rawEvent(RECORD_MAPPER.convertValue(record, MAP_TYPE))
                .build();
    }

    /**
     * Parses the {@code cloudTrailEvent} string into a JSON object.
     *
     * @return the record, or {@code null} when it is missing, not JSON or
     *         not a JSON object.
     */
    private static JsonNode parseRecord(Event event) {
        String json = event.cloudTrailEvent();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = RECORD_MAPPER.readTree(json);
            return node != null && node.isObject() ? node : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Event time is the SDK event's {@code eventTime}, then the record's
     * {@code eventTime}. Returns {@code null} when neither is usable; the
     * clock is never an event time.
     */
    private static Instant resolveEventTime(Event event, JsonNode record) {
        if (event.eventTime() != null) {
            return event.eventTime();
        }
        String fromRecord = text(record, "eventTime");
        if (fromRecord == null) {
            return null;
        }
        try {
            return Instant.parse(fromRecord);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Logs and counts an event whose {@code eventVersion} major is not the
     * one this mapping was written for. The event is still ingested.
     */
    private void checkEventVersion(String eventVersion) {
        Matcher matcher = eventVersion == null ? null : EVENT_VERSION.matcher(eventVersion);
        if (matcher != null && matcher.matches()
                && Integer.parseInt(matcher.group(1)) == SUPPORTED_MAJOR_VERSION) {
            return;
        }
        String tag = matcher != null && matcher.matches() ? eventVersion : "unrecognized";
        meterRegistry.counter(METRIC_UNSUPPORTED_VERSION, "version", tag).increment();
        if (warnedVersions.add(tag)) {
            log.warn("CloudTrail eventVersion '{}' is not major {}; events are still ingested with the "
                    + "version-1 mapping", tag, SUPPORTED_MAJOR_VERSION);
        }
    }

    /**
     * The stable principal id: the role ARN for an {@code AssumedRole}
     * (the session part of the caller ARN changes on every assumption),
     * otherwise the caller ARN, then the invoking service, then the
     * principal id, then the user name. The access key id is not an
     * option because it rotates.
     */
    static String stablePrincipalId(JsonNode identity, String identityType, String eventUsername) {
        if ("AssumedRole".equalsIgnoreCase(identityType)) {
            String issuerArn = text(identity.path("sessionContext").path("sessionIssuer"), "arn");
            if (issuerArn != null) {
                return issuerArn;
            }
        }
        return firstNonBlank(
                text(identity, "arn"),
                text(identity, "invokedBy"),
                text(identity.path("onBehalfOf"), "userId"),
                text(identity, "principalId"),
                eventUsername);
    }

    /**
     * Maps {@code userIdentity.type} to the DLAP principal type. Only
     * {@code AWSService} is {@code SYSTEM}: an IAM user or role that calls
     * through a service ({@code invokedBy} set) is still that user or
     * role. A record without a type but with {@code invokedBy} is a
     * service. Unknown types are left unset rather than guessed.
     */
    static String derivePrincipalType(String identityType, String invokedBy) {
        if (identityType == null) {
            return invokedBy != null ? "SYSTEM" : null;
        }
        return switch (identityType.toLowerCase(Locale.ROOT)) {
            case "awsservice" -> "SYSTEM";
            case "assumedrole", "role" -> "ROLE";
            case "iamuser", "root", "federateduser", "identitycenteruser", "builderiduser",
                 "samluser", "webidentityuser", "directory", "awsaccount" -> "USER";
            default -> null;
        };
    }

    private static String displayName(String eventUsername, JsonNode identity, String principalId) {
        String name = firstNonBlank(eventUsername, text(identity, "userName"));
        if (name != null) {
            return name;
        }
        // Session name: the tail of an assumed-role caller ARN.
        String arn = text(identity, "arn");
        if (arn != null && arn.contains("/")) {
            return arn.substring(arn.lastIndexOf('/') + 1);
        }
        return principalId;
    }

    private void countSkipped(String reason) {
        meterRegistry.counter(METRIC_SKIPPED, "reason", reason).increment();
    }

    private Duration sliceLength(String region) {
        return sliceByRegion.getOrDefault(region, Duration.ofMinutes(Math.max(1, sliceMinutes)));
    }

    /** Halves the region's slice after an overflow, down to {@link #MIN_SLICE}. */
    private void shrinkSlice(String region, Duration current) {
        Duration halved = current.dividedBy(2);
        Duration next = halved.compareTo(MIN_SLICE) < 0 ? MIN_SLICE : halved;
        sliceByRegion.put(region, next);
        meterRegistry.counter(METRIC_SLICE_OVERFLOW, "region", region).increment();
        log.error("AWS CloudTrail region {} has more than {} events in a {}-second slice; nothing from "
                + "the slice was ingested, the slice length is now {} second(s) and the next tick retries",
                region, maxEventsPerSlice, current.toSeconds(), next.toSeconds());
    }

    private CloudTrailClient buildClient(String region) {
        var builder = CloudTrailClient.builder()
                .region(Region.of(region))
                .credentialsProvider(credentialsProvider());
        if (endpointOverride != null && !endpointOverride.isBlank()) {
            log.info("AWS CloudTrail client using endpoint override {} (LocalStack/dev)", endpointOverride);
            builder.endpointOverride(URI.create(endpointOverride));
        }
        return builder.build();
    }

    private AwsCredentialsProvider credentialsProvider() {
        if (accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank()) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        }
        // Env access keys (+ shared config) + IMDS workload identity.
        return DefaultCredentialsProvider.create();
    }

    private List<String> parseRegions() {
        if (regionsCsv == null || regionsCsv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(regionsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private Optional<Instant> readWatermark(String region) {
        return watermarkRepo.findByProviderAndAccountId(PROVIDER, region)
                .map(SourceWatermarkEntity::getLastEventTime);
    }

    private void advanceWatermark(String region, Instant newWatermark) {
        SourceWatermarkEntity wm = watermarkRepo.findByProviderAndAccountId(PROVIDER, region)
                .orElseGet(() -> SourceWatermarkEntity.builder()
                        .provider(PROVIDER)
                        .accountId(region)
                        .build());
        wm.setLastEventTime(newWatermark);
        wm.setUpdatedAt(clock.instant());
        watermarkRepo.save(wm);
    }

    /**
     * CloudTrail event names are {@code <Verb><Resource>} (e.g.
     * {@code CreateBucket}, {@code DeleteDBInstance}, {@code GetObject}).
     * Map the leading verb to the {@code DlapLogEvent.changeType} enum.
     * The {@code readOnly} flag short-circuits to ACCESS for read events.
     */
    static String deriveChangeType(String eventName, String readOnly) {
        if (eventName == null) {
            return "UPDATE";
        }
        if ("true".equalsIgnoreCase(readOnly)) {
            return "ACCESS";
        }
        String lower = eventName.toLowerCase();
        // Permission/IAM changes are checked first: a name like
        // "PutBucketPolicy" is a permission change, not a create.
        if (lower.contains("policy") || lower.contains("permission") || lower.contains("acl")
                || lower.contains("grant")) {
            return "PERMISSION";
        }
        if (lower.startsWith("create") || lower.startsWith("put") || lower.startsWith("run")) {
            return "CREATE";
        }
        if (lower.startsWith("delete") || lower.startsWith("terminate") || lower.startsWith("remove")) {
            return "DELETE";
        }
        if (lower.startsWith("get") || lower.startsWith("list") || lower.startsWith("describe")) {
            return "ACCESS";
        }
        return "UPDATE";
    }

    /** The text of {@code node.field}, or {@code null} when it is absent, null or blank. */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String s = value.asText();
        return s.isBlank() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /** Mutable per-poll bookkeeping: events handed to ingest and the cursor to persist. */
    private static final class PollState {
        private int ingested;
        private Instant cursor;

        PollState(Instant stored) {
            this.cursor = stored;
        }

        /** Moves the cursor forward; it never moves back. */
        void advanceTo(Instant instant) {
            if (cursor == null || instant.isAfter(cursor)) {
                cursor = instant;
            }
        }
    }
}
