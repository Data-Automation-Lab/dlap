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

package com.dlap.logingest.backfill;

import com.dlap.logingest.config.sources.EffectiveBinding;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.parse.core.ConditionalOnRegistryPipeline;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.quarantine.QuarantineRow;
import com.dlap.logingest.quarantine.SourceReplayHook;
import com.dlap.logingest.service.registry.RegistryIngestService;
import com.dlap.logingest.transport.CursorPosition;
import com.dlap.logingest.transport.FetchWindow;
import com.dlap.logingest.transport.LogTransport;
import com.dlap.logingest.transport.StreamContext;
import com.dlap.logingest.transport.TransportDescriptor;
import com.dlap.logingest.transport.TransportRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.DoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Replays a quarantined record that the table cannot replay, by re-reading it from its source
 * (decision D26, Q-362). The quarantine table keeps a masked excerpt of at most a few KB, so a record
 * that was truncated or had an email or address masked is gone from the table; nothing raw is stored
 * to bring it back. The source still holds it, and {@code raw_ref} says where.
 *
 * <h3>How a record is found</h3>
 * A transport cannot fetch one record by reference (the SPI reads windows), so the re-read asks the
 * stream's own transport for the event-time window around the record: {@code replay-time-pad} on both
 * sides of its event time, or {@code replay-fallback-lookback} before {@code quarantined_at} when the
 * event time could not be read (a record that was not JSON). Rows of one stream whose windows touch are
 * merged, up to {@code replay-max-window}, and each window is read once through {@link BoundedReader}:
 * sliced, paced by {@link BatchPacer} and never wider than the configured bounds, the same reader a
 * backfill job uses. Nothing in the window is ingested except the records being replayed.
 *
 * <h3>What counts as the same record</h3>
 * The record's SHA-256 must equal the quarantined {@code raw_sha256}, it must sit in the same container
 * ({@code raw_ref.uri}), and for an object transport (where the index is stable) at the same index.
 * Bytes that differ at the same place are {@link Outcome#CHANGED}: the provider rewrote the record, and
 * replaying it would store an event under the old record's identity.
 *
 * <h3>Ingest</h3>
 * The matched bytes run through the current registry in {@code REPLAY} mode under the stored
 * {@code raw_ref}, so the quarantine identity is unchanged (a second failure updates the same row) and a
 * stored pass-through event id is published again with the corrected event. Only streams with
 * {@code backfill.allowed} are re-read, because reading history can cost bytes or API quota.
 */
@Component
@ConditionalOnRegistryPipeline
public class SourceReplayer implements SourceReplayHook {

    private static final Logger log = LoggerFactory.getLogger(SourceReplayer.class);

    private final EffectiveLogSources sources;
    private final TransportRegistry transports;
    private final RegistryIngestService ingest;
    private final BackfillProperties props;
    private final Clock clock;
    private final DoubleFunction<BatchPacer> pacers;

    /**
     * Creates the replayer for the running service.
     *
     * @param sources    the effective configuration
     * @param transports the transports
     * @param ingest     the registry ingest path
     * @param props      the settings
     * @param clock      the application clock when unique, else the UTC system clock
     */
    @Autowired
    public SourceReplayer(EffectiveLogSources sources, TransportRegistry transports, RegistryIngestService ingest,
                          BackfillProperties props, ObjectProvider<Clock> clock) {
        this(sources, transports, ingest, props, clock.getIfUnique(Clock::systemUTC), BatchPacer::system);
    }

    /**
     * Creates the replayer with a fixed clock and pacer, for tests.
     *
     * @param sources    the effective configuration
     * @param transports the transports
     * @param ingest     the registry ingest path
     * @param props      the settings
     * @param clock      the clock
     * @param pacers     builds a pacer from a rate
     */
    public SourceReplayer(EffectiveLogSources sources, TransportRegistry transports, RegistryIngestService ingest,
                          BackfillProperties props, Clock clock, DoubleFunction<BatchPacer> pacers) {
        this.sources = sources;
        this.transports = transports;
        this.ingest = ingest;
        this.props = props;
        this.clock = clock;
        this.pacers = pacers;
    }

    /** A row with the stream it is re-read through. */
    private record Target(QuarantineRow row, EffectiveBinding binding, EffectiveStream stream, LogTransport transport) {
    }

    /** Rows of one stream whose search windows were merged. */
    private static final class Cluster {
        private Instant start;
        private Instant end;
        private final List<Target> targets = new ArrayList<>();

        Cluster(Instant start, Instant end) {
            this.start = start;
            this.end = end;
        }
    }

    @Override
    public Map<String, Outcome> replay(List<QuarantineRow> rows, boolean dryRun) {
        Map<String, Outcome> out = new LinkedHashMap<>();
        Map<String, List<Target>> byStream = new LinkedHashMap<>();
        for (QuarantineRow row : rows) {
            Optional<Target> target = target(row);
            if (target.isEmpty()) {
                out.put(row.quarantineId(), Outcome.NOT_ELIGIBLE);
            } else if (!target.get().stream().backfillAllowed()) {
                out.put(row.quarantineId(), Outcome.NOT_ALLOWED);
            } else if (dryRun) {
                out.put(row.quarantineId(), Outcome.WOULD_RE_READ);
            } else {
                byStream.computeIfAbsent(row.bindingId() + "/" + target.get().stream().streamId(),
                        k -> new ArrayList<>()).add(target.get());
            }
        }
        for (List<Target> group : byStream.values()) {
            for (Cluster cluster : clusters(group)) {
                reRead(cluster, out);
            }
        }
        return out;
    }

    private Optional<Target> target(QuarantineRow row) {
        if (!props.replayFromSource()) {
            return Optional.empty();
        }
        Map<String, Object> ref = row.rawRef();
        if (ref == null || ref.get("transport") == null || ref.get("uri") == null) {
            return Optional.empty();
        }
        String transportId = String.valueOf(ref.get("transport"));
        Optional<EffectiveBinding> binding = sources.binding(row.bindingId())
                .filter(b -> b.runs(sources.pipeline()));
        if (binding.isEmpty()) {
            return Optional.empty();
        }
        Optional<EffectiveStream> stream = binding.get().streams().stream()
                .filter(s -> !s.push() && row.envelope().equals(s.envelope()) && transportId.equals(s.transport()))
                .findFirst();
        Optional<LogTransport> transport = stream.flatMap(s -> transports.find(s.transport()));
        if (stream.isEmpty() || transport.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Target(row, binding.get(), stream.get(), transport.get()));
    }

    /** Builds the merged search windows of rows that share a stream. */
    private List<Cluster> clusters(List<Target> group) {
        Instant now = clock.instant();
        record Anchored(Target target, Instant start, Instant end) {
        }
        List<Anchored> anchored = new ArrayList<>();
        for (Target t : group) {
            Instant at = t.row().eventTime();
            Instant start = at != null ? at.minus(props.replayTimePad())
                    : t.row().quarantinedAt().minus(props.replayFallbackLookback());
            Instant end = (at != null ? at : t.row().quarantinedAt()).plus(props.replayTimePad())
                    .plus(Duration.ofMillis(1));
            anchored.add(new Anchored(t, start, end.isAfter(now) ? now : end));
        }
        anchored.sort(Comparator.comparing(Anchored::start));
        List<Cluster> out = new ArrayList<>();
        Cluster current = null;
        for (Anchored a : anchored) {
            if (!a.start().isBefore(a.end())) {
                // A record dated after now cannot be in a window that ends at now.
                Cluster lone = new Cluster(a.start(), a.start());
                lone.targets.add(a.target());
                out.add(lone);
                continue;
            }
            Instant merged = current == null ? null : (a.end().isAfter(current.end) ? a.end() : current.end);
            if (current != null && !a.start().isAfter(current.end)
                    && Duration.between(current.start, merged).compareTo(props.replayMaxWindow()) <= 0) {
                current.end = merged;
                current.targets.add(a.target());
            } else {
                current = new Cluster(a.start(), a.end());
                current.targets.add(a.target());
                out.add(current);
            }
        }
        return out;
    }

    private void reRead(Cluster cluster, Map<String, Outcome> out) {
        Target first = cluster.targets.get(0);
        TransportDescriptor descriptor = first.transport().descriptor();
        boolean stableIndex = descriptor.cursorKind() == TransportDescriptor.CursorKind.OBJECT;
        Map<String, List<Target>> bySha = new HashMap<>();
        Map<String, Target> byLocation = new HashMap<>();
        Set<String> pending = new HashSet<>();
        for (Target t : cluster.targets) {
            bySha.computeIfAbsent(t.row().rawSha256(), k -> new ArrayList<>()).add(t);
            byLocation.put(location(t.row()), t);
            pending.add(t.row().quarantineId());
        }
        Set<String> changed = new HashSet<>();
        if (!cluster.start.isBefore(cluster.end)) {
            finish(cluster, pending, changed, out);
            return;
        }
        StreamContext ctx = new StreamContext(first.stream(), first.binding().sourceKind(), IngestMode.BACKFILL, clock,
                ScopedObjects.none());
        BoundedReader reader = new BoundedReader(pacers.apply(BatchPacer.effectiveRate(props.maxBatchesPerSecond(),
                first.stream().requestsPerSecond())));
        Duration size = BoundedReader.sliceSize(descriptor, props);
        try {
            for (String partition : first.transport().partitions(ctx)) {
                for (Instant low = cluster.start; low.isBefore(cluster.end) && !pending.isEmpty();) {
                    Instant high = BoundedReader.sliceEnd(low, cluster.end, size);
                    reader.readSlice(first.transport(), ctx, partition, new CursorPosition(low, null, Map.of()),
                            new FetchWindow(low, high), batch -> {
                                for (RawRecord rec : batch.records()) {
                                    inspect(rec, stableIndex, bySha, byLocation, pending, changed, out);
                                }
                                return pending.isEmpty() ? BoundedReader.Verdict.STOP : BoundedReader.Verdict.CONTINUE;
                            });
                    low = high;
                }
                if (pending.isEmpty()) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            // The message may quote provider data or SQL; only the class goes to the log.
            log.warn("Source re-read of binding {} failed: {}", first.row().bindingId(), e.getClass().getSimpleName());
            pending.forEach(id -> out.put(id, Outcome.FAILED));
            pending.clear();
        }
        finish(cluster, pending, changed, out);
    }

    private void inspect(RawRecord rec, boolean stableIndex, Map<String, List<Target>> bySha,
                         Map<String, Target> byLocation, Set<String> pending, Set<String> changed,
                         Map<String, Outcome> out) {
        List<Target> candidates = bySha.get(rec.sha256());
        if (candidates != null) {
            for (Target t : candidates) {
                if (pending.contains(t.row().quarantineId()) && sameContainer(t.row(), rec, stableIndex)) {
                    pending.remove(t.row().quarantineId());
                    out.put(t.row().quarantineId(), ingestCopy(t.row(), rec));
                    return;
                }
            }
        }
        if (stableIndex) {
            Target at = byLocation.get(rec.ref().uri() + "#" + rec.ref().recordIndex());
            if (at != null && pending.contains(at.row().quarantineId())) {
                changed.add(at.row().quarantineId());
            }
        }
    }

    private Outcome ingestCopy(QuarantineRow row, RawRecord found) {
        Map<String, Object> ref = row.rawRef();
        long index = ref.get("recordIndex") instanceof Number n ? n.longValue() : -1L;
        try {
            RawRecord copy = RawRecord.builder()
                    .bindingId(row.bindingId())
                    .sourceKind(row.sourceKind())
                    .envelope(row.envelope())
                    .ref(new RawRef(String.valueOf(ref.get("transport")), String.valueOf(ref.get("uri")), index,
                            ref.get("cursor") == null ? "" : String.valueOf(ref.get("cursor"))))
                    .mode(IngestMode.REPLAY)
                    .fetchedAt(clock.instant())
                    .observedTime(found.observedTime().orElse(null))
                    .bytes(found.bytes())
                    .build();
            boolean quarantined = ingest.ingestBatch(List.of(copy), false).records().get(0).quarantined();
            return quarantined ? Outcome.STILL_QUARANTINED : Outcome.RESOLVED;
        } catch (RuntimeException e) {
            log.warn("Replay of quarantine record {} (binding {}) failed: {}", row.quarantineId(), row.bindingId(),
                    e.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }

    /** Rows still pending after the scan were not found, or were found changed. */
    private static void finish(Cluster cluster, Set<String> pending, Set<String> changed, Map<String, Outcome> out) {
        for (Target t : cluster.targets) {
            String id = t.row().quarantineId();
            if (pending.contains(id)) {
                out.put(id, changed.contains(id) ? Outcome.CHANGED : Outcome.NOT_FOUND);
            }
        }
    }

    private static boolean sameContainer(QuarantineRow row, RawRecord rec, boolean stableIndex) {
        Map<String, Object> ref = row.rawRef();
        if (!String.valueOf(ref.get("uri")).equals(rec.ref().uri())) {
            return false;
        }
        long index = ref.get("recordIndex") instanceof Number n ? n.longValue() : -1L;
        return !stableIndex || index == rec.ref().recordIndex();
    }

    private static String location(QuarantineRow row) {
        Map<String, Object> ref = row.rawRef();
        long index = ref.get("recordIndex") instanceof Number n ? n.longValue() : -1L;
        return ref.get("uri") + "#" + index;
    }
}
