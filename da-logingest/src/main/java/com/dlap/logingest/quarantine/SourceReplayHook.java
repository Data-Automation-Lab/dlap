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

package com.dlap.logingest.quarantine;

import java.util.List;
import java.util.Map;

/**
 * Re-reads quarantined records from their source (decision D26, Q-362). The quarantine table keeps a
 * masked, bounded excerpt, so a record that is truncated or had an email or address masked cannot be
 * replayed from the table, and no raw provider data is stored to fix that. Instead a hook re-reads the
 * record by its {@code raw_ref} through the bounded, rate-limited backfill reader, checks that the bytes
 * are the ones that were quarantined (SHA-256 and size), and runs them through the current parsers.
 *
 * <p>The replay service owns the table: it is the only writer of {@code replayed_at} and
 * {@code replay_count}. A hook only reads the source and ingests; it reports one {@link Outcome} per
 * row and the service records it. The implementation lives in {@code backfill/}.
 */
public interface SourceReplayHook {

    /** What happened to one row. */
    enum Outcome {
        /** Re-read, and the current parsers produced an event or a skip. */
        RESOLVED,
        /** Re-read, and the current parsers quarantined it again. */
        STILL_QUARANTINED,
        /** The row cannot be re-read: its binding does not run, the stream is a push channel or its transport changed. */
        NOT_ELIGIBLE,
        /** The stream does not allow backfill ({@code backfill.allowed}), so no history is read. */
        NOT_ALLOWED,
        /** The source no longer holds a record with the quarantined bytes at that location. */
        NOT_FOUND,
        /** The source holds a record at that location, but its bytes differ from the quarantined ones. */
        CHANGED,
        /** Reading or ingesting failed; the row stays pending. */
        FAILED,
        /** Dry run: the row is eligible and would be re-read. */
        WOULD_RE_READ
    }

    /**
     * Re-reads rows from their sources.
     *
     * @param rows   pending rows that the table cannot replay (truncated or masked)
     * @param dryRun true to classify without reading anything
     * @return one outcome per quarantine id of {@code rows}
     */
    Map<String, Outcome> replay(List<QuarantineRow> rows, boolean dryRun);
}
