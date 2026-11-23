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

import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.common.logevent.LogEventSemantics;
import com.dlap.logingest.config.sources.BuiltinBindings;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.dto.IngestRequestDTO;
import com.dlap.logingest.kafka.LogEventPublisher;
import com.dlap.logingest.parse.core.CanonicalEventMapper;
import com.dlap.logingest.parse.core.ConditionalOnRegistryPipeline;
import com.dlap.logingest.parse.core.FormatKey;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.ParseResult;
import com.dlap.logingest.parse.core.ParserRegistry;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.ShapeFingerprint;
import com.dlap.logingest.repository.FormatObservationRepository;
import com.dlap.logingest.service.QuarantineSink;
import com.dlap.logingest.service.impl.LogIngestServiceImpl;
import com.dlap.logingest.service.impl.LoggingQuarantineSink;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.LogQuarantineRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * The registry-fed ingest path (ADR-0045 decision 8, ADR-0060). Every record, from a transport,
 * REST ingest or fixture replay, goes through the one parse path:
 * <ol>
 *   <li>the record's binding and envelope select its stream and effective settings; a record of a
 *       binding that does not run is a caller defect;</li>
 *   <li>the {@link ParserRegistry} parses it with those settings;</li>
 *   <li>{@link CanonicalEventMapper} writes the events (and the pass-through event of a
 *       quarantined record) onto the wire contract;</li>
 *   <li>events that fail the binding's filters are dropped as {@code FILTERED_BY_BINDING};</li>
 *   <li>{@link LogEventStore} stores each event once and publishes it unless its id was already
 *       stored, except in {@code REPLAY} mode;</li>
 *   <li>a quarantined record goes to every {@link QuarantineSink} (logged when none is
 *       registered);</li>
 *   <li>the batch's (binding, envelope, version, fingerprint) observations are recorded.</li>
 * </ol>
 * Active only with {@code dlap.logingest.pipeline=registry}.
 */
@Service
@ConditionalOnRegistryPipeline
public class RegistryIngestService {

    private static final Logger log = LoggerFactory.getLogger(RegistryIngestService.class);

    /** The endpoint recorded as the source of REST records. */
    public static final String REST_SOURCE_URI = "rest:/api/v1/logingest/events";

    private final ParserRegistry registry;
    private final CanonicalEventMapper mapper;
    private final EffectiveLogSources sources;
    private final LogEventStore store;
    private final LogEventPublisher publisher;
    private final ObjectProvider<QuarantineSink> sinks;
    private final QuarantineSink fallbackSink = new LoggingQuarantineSink();
    private final FormatObservationRepository observations;
    private final Counter dedupHits;
    private final Clock clock;
    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();

    /**
     * Creates the service.
     *
     * @param registry     the parser registry
     * @param mapper       the wire mapper
     * @param sources      the effective configuration
     * @param store        the replay store writer
     * @param publisher    the normalized-topic publisher
     * @param sinks        the quarantine sinks
     * @param observations the format observation table
     * @param meters       the meter registry
     * @param clock        the application clock when unique, else the UTC system clock
     */
    public RegistryIngestService(ParserRegistry registry, CanonicalEventMapper mapper, EffectiveLogSources sources,
                                 LogEventStore store, LogEventPublisher publisher, ObjectProvider<QuarantineSink> sinks,
                                 FormatObservationRepository observations, MeterRegistry meters,
                                 ObjectProvider<Clock> clock) {
        this.registry = registry;
        this.mapper = mapper;
        this.sources = sources;
        this.store = store;
        this.publisher = publisher;
        this.sinks = sinks;
        this.observations = observations;
        this.dedupHits = meters.counter(LogIngestServiceImpl.METRIC_DEDUP_HITS);
        this.clock = clock.getIfUnique(Clock::systemUTC);
    }

    /**
     * Ingests one record.
     *
     * @param record the record
     * @return its outcome
     * @throws IllegalArgumentException when the record's binding does not run or has no stream of
     *                                  its envelope
     */
    public RecordReport ingest(RawRecord record) {
        return ingestBatch(List.of(record), false).records().get(0);
    }

    /**
     * Ingests a batch, recording format observations once per batch.
     *
     * @param records           the records
     * @param suppressRepublish true to skip stored ids even in {@code REPLAY} mode
     * @return one report per record
     * @throws IllegalArgumentException when a record's binding does not run
     */
    public BatchReport ingestBatch(List<RawRecord> records, boolean suppressRepublish) {
        Map<Observation, Long> seen = new LinkedHashMap<>();
        List<RecordReport> out = new ArrayList<>(records.size());
        try {
            for (RawRecord record : records) {
                out.add(ingestOne(record, suppressRepublish, seen));
            }
        } finally {
            flush(seen);
        }
        return new BatchReport(out);
    }

    /**
     * Ingests a normalized DTO through its built-in binding.
     *
     * @param dto               the DTO
     * @param channel           the channel it arrived on
     * @param sourceUri         where it came from
     * @param recordIndex       its index in the source, or -1
     * @param suppressRepublish true to skip a stored id even in {@code REPLAY} mode
     * @return the outcome
     * @throws IllegalArgumentException when the DTO names no known provider
     */
    public RecordReport ingestDto(IngestRequestDTO dto, BuiltinBindings.Channel channel, String sourceUri,
                                  long recordIndex, boolean suppressRepublish) {
        RawRecord record = DtoRecords.toRecord(dto, channel, sourceUri, recordIndex, clock.instant());
        return ingestBatch(List.of(record), suppressRepublish).records().get(0);
    }

    private RecordReport ingestOne(RawRecord record, boolean suppressRepublish, Map<Observation, Long> seen) {
        EffectiveStream stream = sources.runningStream(record.bindingId(), record.envelope())
                .orElseThrow(() -> new IllegalArgumentException("binding " + record.bindingId()
                        + " does not run or has no stream of envelope " + record.envelope()));
        ParseOutcome outcome = registry.parse(record, stream.parse());
        outcome.detected().ifPresent(d -> seen.merge(Observation.of(record, d, outcome.fingerprint()), 1L, Long::sum));

        boolean parsed = outcome.result() instanceof ParseResult.Parsed;
        List<RecordReport.EventReport> events = new ArrayList<>();
        int kept = 0;
        for (DlapLogEvent e : mapper.events(outcome)) {
            if (parsed && !stream.filters().accepts(e.getSourceService(), e.getOperation())) {
                events.add(new RecordReport.EventReport(e.getEventId(), Disposition.FILTERED));
                continue;
            }
            kept++;
            Map<String, Object> wire = codec.toMap(e);
            boolean keepRaw = stream.parse().persistRaw().persists(e.getEventCategory(), e.getEventId());
            Disposition d = store.write(e, wire, keepRaw, record.mode(), suppressRepublish,
                    event -> publisher.publish(LogEventSemantics.partitionKey(event), wire));
            if (d == Disposition.DUPLICATE) {
                dedupHits.increment();
            }
            events.add(new RecordReport.EventReport(e.getEventId(), d));
        }

        Optional<String> quarantineId = Optional.empty();
        if (outcome.result() instanceof ParseResult.Quarantined) {
            LogQuarantineRecord q = mapper.quarantine(outcome).orElseThrow();
            quarantineId = Optional.of(q.getQuarantineId());
            deliver(q);
        }
        String code = parsed && kept == 0 ? RecordReport.SKIPPED_FILTERED : outcome.outcomeCode();
        return new RecordReport(code, events, quarantineId);
    }

    private void deliver(LogQuarantineRecord q) {
        List<QuarantineSink> registered = sinks.orderedStream().toList();
        if (registered.isEmpty()) {
            fallbackSink.accept(q);
            return;
        }
        registered.forEach(s -> s.accept(q));
    }

    private void flush(Map<Observation, Long> seen) {
        for (Map.Entry<Observation, Long> o : seen.entrySet()) {
            Observation k = o.getKey();
            try {
                if (observations.record(k.bindingId(), k.envelope(), k.version(), k.fingerprint(), o.getValue(),
                        clock.instant())) {
                    log.info("New log format observed: binding={} envelope={} version={} fingerprint={}",
                            k.bindingId(), k.envelope(), k.version(), k.fingerprint().isEmpty() ? "-" : k.fingerprint());
                }
            } catch (DataAccessException e) {
                // Observations are telemetry; a failure must not undo the ingest that just committed.
                log.warn("Could not record the format observation of binding {} ({}): {}", k.bindingId(),
                        k.envelope(), e.getMessage());
            }
        }
    }

    /** One (binding, envelope, version, fingerprint) seen in a batch. */
    private record Observation(String bindingId, String envelope, String version, String fingerprint) {

        static Observation of(RawRecord record, FormatKey detected, Optional<ShapeFingerprint> fingerprint) {
            return new Observation(record.bindingId(), record.envelope(), detected.schemaVersion(),
                    fingerprint.map(ShapeFingerprint::hash).orElse(""));
        }
    }
}
