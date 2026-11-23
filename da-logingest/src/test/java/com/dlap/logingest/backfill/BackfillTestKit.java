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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dlap.common.assetid.AssetIdHints;
import com.dlap.logingest.config.sources.EffectiveBinding;
import com.dlap.logingest.config.sources.EffectiveLogSources;
import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.config.sources.StreamFilters;
import com.dlap.logingest.config.sources.ValidationReport;
import com.dlap.logingest.model.ConfigOrigin;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.PipelineMode;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.service.registry.BatchReport;
import com.dlap.logingest.service.registry.Disposition;
import com.dlap.logingest.service.registry.RecordReport;
import com.dlap.logingest.service.registry.RegistryIngestService;
import com.dlap.logingest.transport.CursorPosition;
import com.dlap.logingest.transport.FetchWindow;
import com.dlap.logingest.transport.LogTransport;
import com.dlap.logingest.transport.ObjectReadException;
import com.dlap.logingest.transport.RawBatch;
import com.dlap.logingest.transport.RawBatchIterator;
import com.dlap.logingest.transport.StreamContext;
import com.dlap.logingest.transport.TransportDescriptor;
import com.dlap.logingest.transport.lock.AdvisoryLocks;
import com.dlap.logingest.transport.objects.ProcessedObjectStore;
import com.dlap.proto.log.v1.SourceProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fakes shared by the backfill tests: a scripted transport, an in-memory job table, a counting ingest. */
final class BackfillTestKit {

    static final String BINDING = "scripted-binding";
    static final String KIND = "TEST_AUDIT";
    static final String ENVELOPE = "json-record";
    static final String TRANSPORT = "SCRIPTED";
    static final String STREAM = "scripted-stream";
    static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    private BackfillTestKit() {
    }

    /**
     * A stream of the test binding.
     *
     * @param backfillAllowed whether backfill may run
     * @param push            whether the stream is a push channel
     * @param rps             the stream's own rate limit, or null
     * @return the stream
     */
    static EffectiveStream stream(boolean backfillAllowed, boolean push, Double rps) {
        BindingConfig parse = new BindingConfig(BINDING, KIND, Optional.empty(), null, Map.of(), List.of(), null,
                BindingConfig.DEFAULT_MAX_RECORD_BYTES, AssetIdHints.none());
        return new EffectiveStream(BINDING, STREAM, TRANSPORT, ENVELOPE, Map.of("bucket", "b"), parse,
                StreamFilters.NONE, Duration.ZERO, Duration.ofMinutes(1), rps, null, backfillAllowed, false, push);
    }

    /**
     * Effective sources with one test binding.
     *
     * @param enabled whether the binding is enabled
     * @param streams its streams
     * @return the sources, on the registry pipeline
     */
    static EffectiveLogSources sources(boolean enabled, EffectiveStream... streams) {
        EffectiveBinding binding = EffectiveBinding.of(BINDING, "scripted-source", KIND, Optional.of(SourceProvider.AWS),
                ConfigOrigin.FILE, enabled, true, Map.of("account-id", "111111111111"), List.of(streams));
        return new EffectiveLogSources(PipelineMode.REGISTRY, "FILE", List.of(binding), ValidationReport.CLEAN);
    }

    /** One scripted event. */
    record Event(Instant ts, String id, String object) {

        static Event at(Instant ts, String id) {
            return new Event(ts, id, "");
        }
    }

    /** What the transport was asked. */
    record FetchCall(String partition, CursorPosition cursor, FetchWindow window) {
    }

    /**
     * A transport that serves scripted events. As a time transport it pages like Cloud Logging: a page's
     * low watermark is its newest timestamp and the last page moves it to the window end. As an object
     * transport it returns one batch per object overlapping the window and a final empty batch, like the
     * Cloud Storage sink reader.
     */
    static class ScriptedTransport implements LogTransport {
        final TransportDescriptor.CursorKind kind;
        final List<String> partitions;
        final int pageSize;
        final Map<String, List<Event>> events = new LinkedHashMap<>();
        final List<FetchCall> calls = new ArrayList<>();
        /** When true the last batch of a fetch does not reach the window end (a transport that stops short). */
        boolean stopShort;
        /** Records returned by the transport are given this ref uri unless the event names an object. */
        String uri = "scripted://b";
        /**
         * Objects (object transports only) that cannot be read: the object name and how many reads still
         * fail. A failing read throws {@link ObjectReadException} and the iterator is positioned past it.
         */
        final Map<String, Integer> unreadable = new LinkedHashMap<>();
        /** Builds the record text of an event id. */
        java.util.function.UnaryOperator<String> body = BackfillTestKit::json;

        ScriptedTransport(TransportDescriptor.CursorKind kind, int pageSize, String... partitions) {
            this.kind = kind;
            this.pageSize = pageSize;
            this.partitions = partitions.length == 0 ? List.of("") : List.of(partitions);
        }

        ScriptedTransport add(String partition, Event... evs) {
            events.computeIfAbsent(partition, k -> new ArrayList<>()).addAll(List.of(evs));
            return this;
        }

        @Override
        public TransportDescriptor descriptor() {
            return new TransportDescriptor(TRANSPORT, Set.of(), Set.of("bucket"), kind, Duration.ofHours(1),
                    Duration.ZERO);
        }

        @Override
        public List<String> partitions(StreamContext context) {
            return partitions;
        }

        @Override
        public RawBatchIterator fetch(StreamContext ctx, String partition, CursorPosition cursor, FetchWindow window) {
            calls.add(new FetchCall(partition, cursor, window));
            List<Event> inWindow = events.getOrDefault(partition, List.of()).stream()
                    .filter(e -> !e.ts().isBefore(window.from()) && e.ts().isBefore(window.to()))
                    .sorted(Comparator.comparing(Event::ts).thenComparing(Event::id)).toList();
            return kind == TransportDescriptor.CursorKind.OBJECT ? objects(ctx, partition, inWindow, window)
                    : pages(ctx, partition, inWindow, window);
        }

        private RawBatchIterator pages(StreamContext ctx, String partition, List<Event> inWindow, FetchWindow window) {
            List<RawBatch> batches = new ArrayList<>();
            if (inWindow.isEmpty()) {
                batches.add(new RawBatch(partition, List.of(), Optional.empty(),
                        new CursorPosition(stopShort ? window.from() : window.to(), null, Map.of())));
            }
            long index = 0;
            for (int i = 0; i < inWindow.size(); i += pageSize) {
                List<Event> page = inWindow.subList(i, Math.min(inWindow.size(), i + pageSize));
                boolean last = i + pageSize >= inWindow.size();
                List<RawRecord> records = new ArrayList<>();
                for (Event e : page) {
                    records.add(ctx.record(RawRef.of(TRANSPORT, uri, index++)).text(body.apply(e.id())).build());
                }
                Instant newest = page.get(page.size() - 1).ts();
                Instant low = last && !stopShort ? window.to() : newest;
                batches.add(new RawBatch(partition, records, Optional.empty(), new CursorPosition(low, newest, Map.of())));
            }
            return RawBatchIterator.of(batches);
        }

        private RawBatchIterator objects(StreamContext ctx, String partition, List<Event> inWindow, FetchWindow window) {
            Map<String, List<Event>> byObject = new TreeMap<>();
            for (Event e : inWindow) {
                byObject.computeIfAbsent(e.object(), k -> new ArrayList<>()).add(e);
            }
            List<Object> batches = new ArrayList<>();
            for (Map.Entry<String, List<Event>> o : byObject.entrySet()) {
                if (ctx.alreadyProcessed(o.getKey(), "g1")) {
                    continue;
                }
                int failing = unreadable.getOrDefault(o.getKey(), 0);
                if (failing > 0) {
                    unreadable.put(o.getKey(), failing == Integer.MAX_VALUE ? failing : failing - 1);
                    batches.add(new ObjectReadException(new RawBatch.ObjectRef(o.getKey(), "g1"),
                            "scripted://b/" + o.getKey(), new IllegalStateException("Access Denied")));
                    continue;
                }
                List<RawRecord> records = new ArrayList<>();
                long index = 0;
                for (Event e : o.getValue()) {
                    records.add(ctx.record(new RawRef(TRANSPORT, uri + "/" + o.getKey(), index++, "g1"))
                            .text(body.apply(e.id())).build());
                }
                Instant end = o.getValue().get(o.getValue().size() - 1).ts();
                batches.add(new RawBatch(partition, records, Optional.of(new RawBatch.ObjectRef(o.getKey(), "g1")),
                        new CursorPosition(end, end, Map.of())));
            }
            batches.add(new RawBatch(partition, List.of(), Optional.empty(),
                    new CursorPosition(stopShort ? window.from() : window.to(), null, Map.of())));
            java.util.Iterator<Object> it = batches.iterator();
            return new RawBatchIterator() {
                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public RawBatch next() {
                    Object item = it.next();
                    if (item instanceof ObjectReadException e) {
                        throw e;
                    }
                    return (RawBatch) item;
                }
            };
        }
    }

    static String json(String id) {
        return "{\"id\":\"" + id + "\"}";
    }

    private static final Pattern ID = Pattern.compile("\"id\":\"([^\"]+)\"");

    static String idOf(RawRecord record) {
        Matcher m = ID.matcher(record.lenientText());
        if (!m.find()) {
            throw new IllegalArgumentException("record without id");
        }
        return m.group(1);
    }

    /** Thrown by {@link CountingIngest} to model a killed process; an Error, so no handler catches it. */
    static final class Killed extends Error {
        private static final long serialVersionUID = 1L;

        Killed() {
            super("process killed");
        }
    }

    /**
     * A registry ingest double that behaves like the store: the first time an event id is seen it is
     * stored and published (unless the record is in a mode that suppresses it), later it is a duplicate.
     * Ids starting with {@code bad} are quarantined.
     */
    static final class CountingIngest {
        final RegistryIngestService service = mock(RegistryIngestService.class);
        final Set<String> stored = new LinkedHashSet<>();
        final List<String> published = new ArrayList<>();
        final List<RawRecord> seen = new ArrayList<>();
        final List<Integer> batchSizes = new ArrayList<>();
        int calls;
        /** Throw {@link Killed} after storing the batch of this call number (1-based); 0 for never. */
        int killAfterStoringCall;
        /** Run before each batch is stored. */
        Runnable beforeBatch = () -> { };
        RuntimeException failWith;

        CountingIngest() {
            when(service.ingestBatch(anyList(), anyBoolean())).thenAnswer(inv -> ingest(inv.getArgument(0)));
        }

        private BatchReport ingest(List<RawRecord> records) {
            calls++;
            beforeBatch.run();
            if (failWith != null) {
                throw failWith;
            }
            batchSizes.add(records.size());
            List<RecordReport> reports = new ArrayList<>();
            for (RawRecord r : records) {
                seen.add(r);
                String id = idOf(r);
                boolean first = stored.add(id);
                boolean publish = r.mode().shouldPublish(!first);
                if (publish) {
                    published.add(id);
                }
                Disposition d = first ? Disposition.PUBLISHED
                        : (publish ? Disposition.REPUBLISHED : Disposition.DUPLICATE);
                if (id.startsWith("bad")) {
                    reports.add(new RecordReport("QUARANTINED:INVALID_JSON", List.of(new RecordReport.EventReport(id, d)),
                            Optional.of("q-" + id)));
                } else {
                    reports.add(new RecordReport("PARSED", List.of(new RecordReport.EventReport(id, d)),
                            Optional.empty()));
                }
            }
            if (calls == killAfterStoringCall) {
                throw new Killed();
            }
            return new BatchReport(reports);
        }

        Set<String> publishedSet() {
            return new HashSet<>(published);
        }
    }

    /** An in-memory job table with the guarded transitions of the SQL implementation. */
    static final class InMemoryJobs implements BackfillJobs {
        final Map<UUID, BackfillJob> rows = new LinkedHashMap<>();
        int progressWrites;

        @Override
        public BackfillJob insert(UUID id, String bindingId, String streamId, Instant from, Instant to,
                                  String requestedBy, Instant at) {
            if (activeFor(bindingId).isPresent()) {
                throw new ActiveJobExistsException(bindingId);
            }
            BackfillJob job = new BackfillJob(id, bindingId, streamId, from, to, BackfillStatus.PENDING,
                    BackfillProgress.EMPTY, requestedBy, at, null, null, "");
            rows.put(id, job);
            return job;
        }

        @Override
        public Optional<BackfillJob> find(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<BackfillJob> activeFor(String bindingId) {
            return rows.values().stream().filter(j -> j.bindingId().equals(bindingId) && j.status().active())
                    .findFirst();
        }

        @Override
        public List<BackfillJob> list(String bindingId, BackfillStatus status, int limit, long offset) {
            return rows.values().stream()
                    .filter(j -> bindingId == null || j.bindingId().equals(bindingId))
                    .filter(j -> status == null || j.status() == status)
                    .sorted(Comparator.comparing(BackfillJob::requestedAt).reversed())
                    .skip(offset).limit(limit).toList();
        }

        @Override
        public long count(String bindingId, BackfillStatus status) {
            return list(bindingId, status, Integer.MAX_VALUE, 0).size();
        }

        @Override
        public List<BackfillJob> runnable(int limit) {
            return rows.values().stream().filter(j -> j.status().active())
                    .sorted(Comparator.comparing(BackfillJob::requestedAt)).limit(limit).toList();
        }

        @Override
        public boolean markRunning(UUID id, Instant at) {
            BackfillJob j = rows.get(id);
            if (j == null || !j.status().active()) {
                return false;
            }
            rows.put(id, new BackfillJob(id, j.bindingId(), j.streamId(), j.from(), j.to(), BackfillStatus.RUNNING,
                    j.progress(), j.requestedBy(), j.requestedAt(), j.startedAt() == null ? at : j.startedAt(), null,
                    j.errorDetail()));
            return true;
        }

        @Override
        public boolean saveProgress(UUID id, BackfillProgress progress) {
            BackfillJob j = rows.get(id);
            if (j == null || j.status() != BackfillStatus.RUNNING) {
                return false;
            }
            progressWrites++;
            // Round-trip through the JSON form, as the database does.
            rows.put(id, new BackfillJob(id, j.bindingId(), j.streamId(), j.from(), j.to(), j.status(),
                    BackfillProgress.fromMap(progress.toMap()), j.requestedBy(), j.requestedAt(), j.startedAt(),
                    j.finishedAt(), j.errorDetail()));
            return true;
        }

        @Override
        public boolean finish(UUID id, BackfillStatus terminal, Instant at, String error, BackfillProgress progress) {
            BackfillJob j = rows.get(id);
            if (j == null || j.status() != BackfillStatus.RUNNING) {
                return false;
            }
            rows.put(id, new BackfillJob(id, j.bindingId(), j.streamId(), j.from(), j.to(), terminal,
                    BackfillProgress.fromMap(progress.toMap()), j.requestedBy(), j.requestedAt(), j.startedAt(), at,
                    error));
            return true;
        }

        @Override
        public boolean cancel(UUID id, Instant at, String reason) {
            BackfillJob j = rows.get(id);
            if (j == null || !j.status().active()) {
                return false;
            }
            rows.put(id, new BackfillJob(id, j.bindingId(), j.streamId(), j.from(), j.to(), BackfillStatus.CANCELLED,
                    j.progress(), j.requestedBy(), j.requestedAt(), j.startedAt(), at, reason));
            return true;
        }
    }

    /** A lock that is free or held elsewhere. */
    static final class FakeLocks implements AdvisoryLocks {
        boolean available = true;
        int taken;
        int released;
        String lastScope;
        String lastKey;

        @Override
        public Optional<Lease> tryLockStream(String bindingId, String streamId) {
            lastScope = bindingId;
            lastKey = streamId;
            if (!available) {
                return Optional.empty();
            }
            taken++;
            return Optional.of(() -> released++);
        }
    }

    /** In-memory object checkpoints that remember the stream id they were written under. */
    static final class RecordingObjects implements ProcessedObjectStore {
        final Set<String> done = new LinkedHashSet<>();
        final Set<String> failed = new LinkedHashSet<>();
        final Set<String> streamIdsUsed = new LinkedHashSet<>();
        final Map<String, Integer> attempts = new LinkedHashMap<>();

        @Override
        public boolean isDone(String bindingId, String streamId, String objectKey, String etag) {
            streamIdsUsed.add(streamId);
            return done.contains(streamId + "|" + objectKey + "#" + etag);
        }

        @Override
        public void markDone(String bindingId, String streamId, String objectKey, String etag, long records,
                             long quarantined) {
            streamIdsUsed.add(streamId);
            done.add(streamId + "|" + objectKey + "#" + etag);
            attempts.merge(streamId + "|" + objectKey + "#" + etag, 1, Integer::sum);
        }

        @Override
        public void markFailed(String bindingId, String streamId, String objectKey, String etag) {
            streamIdsUsed.add(streamId);
            failed.add(streamId + "|" + objectKey + "#" + etag);
            attempts.merge(streamId + "|" + objectKey + "#" + etag, 1, Integer::sum);
        }

        @Override
        public int attempts(String bindingId, String streamId, String objectKey, String etag) {
            return attempts.getOrDefault(streamId + "|" + objectKey + "#" + etag, 0);
        }
    }

    /** A pacer that sleeps on a fake clock and records what it waited. */
    static final class FakePacing {
        long nanos;
        final List<Duration> sleeps = new ArrayList<>();
        final List<Double> rates = new ArrayList<>();

        BatchPacer pacer(double rate) {
            rates.add(rate);
            return new BatchPacer(rate, () -> nanos, d -> {
                sleeps.add(d);
                nanos += d.toNanos();
            });
        }

        Duration totalSleep() {
            return sleeps.stream().reduce(Duration.ZERO, Duration::plus);
        }
    }
}
