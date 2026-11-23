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

import com.dlap.logingest.transport.CursorPosition;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * What a backfill job has done, stored as the {@code progress} JSON of its row after every committed
 * batch. It is the job's own cursor: one position per transport partition, plus counters. A job that
 * died is resumed from here, so the position moves only after a batch reached its outcomes.
 *
 * <p>The JSON holds instants as ISO-8601 strings and transport state as JSON scalars.
 *
 * @param partitions    the position of every partition read so far
 * @param batches       batches committed
 * @param records       records ingested
 * @param quarantined   records the registry quarantined
 * @param duplicates    events whose id was already stored, so they were not published again
 * @param attempts      consecutive failed attempts since the last committed batch
 * @param lastError     the class of the last failure, never its message
 * @param nextAttemptAt the job is not run again before this instant after a failure
 * @param heartbeat     when the progress was last written
 */
public record BackfillProgress(List<Partition> partitions, long batches, long records, long quarantined,
                               long duplicates, int attempts, String lastError, Instant nextAttemptAt,
                               Instant heartbeat) {

    /** A job that has not read anything. */
    public static final BackfillProgress EMPTY = new BackfillProgress(List.of(), 0, 0, 0, 0, 0, "", null, null);

    /**
     * The position of one partition.
     *
     * @param key   the partition key, {@code ""} for a stream with one cursor
     * @param low   the oldest event time not yet delivered; everything before it is done
     * @param high  the newest event time seen
     * @param state opaque transport state
     * @param done  true when the partition was read to the end of the job's window
     */
    @Schema(name = "BackfillPartition")
    public record Partition(String key, Instant low, Instant high, Map<String, Object> state, boolean done) {

        /**
         * Freezes the position.
         *
         * @param key   see the record description
         * @param low   see the record description
         * @param high  see the record description
         * @param state see the record description
         * @param done  see the record description
         */
        public Partition {
            key = key == null ? "" : key;
            state = state == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(state));
        }

        /**
         * The position as a transport cursor.
         *
         * @return the cursor
         */
        public CursorPosition cursor() {
            return new CursorPosition(low, high, state);
        }
    }

    /**
     * Freezes the lists.
     *
     * @param partitions    see the record description
     * @param batches       see the record description
     * @param records       see the record description
     * @param quarantined   see the record description
     * @param duplicates    see the record description
     * @param attempts      see the record description
     * @param lastError     see the record description
     * @param nextAttemptAt see the record description
     * @param heartbeat     see the record description
     */
    public BackfillProgress {
        partitions = partitions == null ? List.of() : List.copyOf(partitions);
        lastError = lastError == null ? "" : lastError;
    }

    /**
     * The stored position of a partition.
     *
     * @param key the partition key
     * @return the position, or empty before the partition was read
     */
    public Optional<Partition> partition(String key) {
        return partitions.stream().filter(p -> p.key().equals(key)).findFirst();
    }

    /**
     * The same progress with one partition replaced or added.
     *
     * @param next the partition's new position
     * @return a new progress
     */
    public BackfillProgress withPartition(Partition next) {
        List<Partition> list = new ArrayList<>();
        boolean replaced = false;
        for (Partition p : partitions) {
            if (p.key().equals(next.key())) {
                list.add(next);
                replaced = true;
            } else {
                list.add(p);
            }
        }
        if (!replaced) {
            list.add(next);
        }
        return new BackfillProgress(list, batches, records, quarantined, duplicates, attempts, lastError,
                nextAttemptAt, heartbeat);
    }

    /**
     * The same progress after one committed batch: counters added, the failure streak reset.
     *
     * @param batchRecords     records in the batch
     * @param batchQuarantined records quarantined
     * @param batchDuplicates  events already stored
     * @param at               when it committed
     * @return a new progress
     */
    public BackfillProgress afterBatch(long batchRecords, long batchQuarantined, long batchDuplicates, Instant at) {
        return new BackfillProgress(partitions, batches + 1, records + batchRecords, quarantined + batchQuarantined,
                duplicates + batchDuplicates, 0, "", null, at);
    }

    /**
     * The same progress after an object that could not be read was quarantined and skipped
     * (Q-702): one record counted as quarantined, no batch, the failure streak reset because the
     * job moved past the object.
     *
     * @param at when it was skipped
     * @return a new progress
     */
    public BackfillProgress afterSkippedObject(Instant at) {
        return new BackfillProgress(partitions, batches, records, quarantined + 1, duplicates, 0, "", null, at);
    }

    /**
     * The same progress after a failed attempt.
     *
     * @param error the failure's class name
     * @param retry when the job may run again
     * @param at    the heartbeat
     * @return a new progress
     */
    public BackfillProgress afterFailure(String error, Instant retry, Instant at) {
        return new BackfillProgress(partitions, batches, records, quarantined, duplicates, attempts + 1, error, retry,
                at);
    }

    /**
     * The same progress with a new heartbeat.
     *
     * @param at the time
     * @return a new progress
     */
    public BackfillProgress withHeartbeat(Instant at) {
        return new BackfillProgress(partitions, batches, records, quarantined, duplicates, attempts, lastError,
                nextAttemptAt, at);
    }

    /**
     * Whether the job must wait before running again.
     *
     * @param now the time
     * @return true while {@code nextAttemptAt} lies ahead
     */
    public boolean waiting(Instant now) {
        return nextAttemptAt != null && now.isBefore(nextAttemptAt);
    }

    /**
     * The JSON form stored in the {@code progress} column.
     *
     * @return a map of JSON scalars, lists and maps
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> ps = new ArrayList<>();
        for (Partition p : partitions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", p.key());
            row.put("low", p.low() == null ? null : p.low().toString());
            row.put("high", p.high() == null ? null : p.high().toString());
            row.put("state", new TreeMap<>(p.state()));
            row.put("done", p.done());
            ps.add(row);
        }
        m.put("partitions", ps);
        m.put("batches", batches);
        m.put("records", records);
        m.put("quarantined", quarantined);
        m.put("duplicates", duplicates);
        m.put("attempts", attempts);
        m.put("lastError", lastError);
        m.put("nextAttemptAt", nextAttemptAt == null ? null : nextAttemptAt.toString());
        m.put("heartbeat", heartbeat == null ? null : heartbeat.toString());
        return m;
    }

    /**
     * Reads the stored JSON. A missing or malformed field reads as its empty value, so a row written by
     * an older build still loads.
     *
     * @param m the stored map, may be {@code null}
     * @return the progress
     */
    public static BackfillProgress fromMap(Map<String, Object> m) {
        if (m == null || m.isEmpty()) {
            return EMPTY;
        }
        List<Partition> ps = new ArrayList<>();
        if (m.get("partitions") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> row) {
                    Map<String, Object> state = new TreeMap<>();
                    if (row.get("state") instanceof Map<?, ?> s) {
                        s.forEach((k, v) -> state.put(String.valueOf(k), v));
                    }
                    ps.add(new Partition(row.get("key") == null ? "" : String.valueOf(row.get("key")),
                            instant(row.get("low")), instant(row.get("high")), state,
                            Boolean.TRUE.equals(row.get("done"))));
                }
            }
        }
        return new BackfillProgress(ps, number(m.get("batches")), number(m.get("records")),
                number(m.get("quarantined")), number(m.get("duplicates")), (int) number(m.get("attempts")),
                m.get("lastError") == null ? "" : String.valueOf(m.get("lastError")), instant(m.get("nextAttemptAt")),
                instant(m.get("heartbeat")));
    }

    private static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static Instant instant(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(o));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
