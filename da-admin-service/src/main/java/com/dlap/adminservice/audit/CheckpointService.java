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

package com.dlap.adminservice.audit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dlap.adminservice.audit.export.CheckpointSink;
import com.dlap.adminservice.audit.export.SignedCheckpoint;
import com.dlap.common.audit.AuditCheckpoint;
import com.dlap.common.audit.AuditChainVerifier;
import com.dlap.common.audit.AuditEntry;
import com.dlap.common.audit.AuditException;
import com.dlap.common.audit.AuditLogReader;
import com.dlap.common.audit.AuditStream;
import com.dlap.common.audit.ChainAnchor;
import com.dlap.common.audit.ChainVerification;
import com.dlap.common.audit.CheckpointSignatures;

/**
 * Signs and exports checkpoints (docs/specs/audit-log.md, section "Checkpoints").
 *
 * <p>One run, for every registered stream: verify what was appended since the stream's last
 * checkpoint (whose signature is checked first), and when that holds, sign the new head and store
 * the checkpoint. A stream that does not verify gets no checkpoint and an ERROR log line: signing
 * would vouch for a broken chain. Then every checkpoint without a write-once copy is exported,
 * after the sink's preflight. A run holds a PostgreSQL advisory lock in {@code dlap_common}, so
 * replicas never run it twice at the same time; the (stream, seq) key makes a repeated run harmless.
 */
public class CheckpointService {

    private static final Logger LOG = LoggerFactory.getLogger(CheckpointService.class);
    private static final String LOCK_SQL =
            "SELECT pg_catalog.pg_try_advisory_xact_lock(pg_catalog.hashtextextended('dlap_admin.audit_checkpoints:run', 0))";
    private static final int EXPORT_BATCH = 200;

    /** Outcome per stream. */
    public enum StreamStatus {
        /** The stream has no entries. */
        NO_ENTRIES,
        /** The newest checkpoint already covers the head. */
        UP_TO_DATE,
        /** A new checkpoint was signed. */
        SIGNED,
        /** The stream did not verify; nothing was signed. */
        BROKEN
    }

    /**
     * Outcome for one stream.
     *
     * @param streamId the stream
     * @param status   what happened
     * @param seq      the covered position for SIGNED and UP_TO_DATE, the bad position for BROKEN
     * @param detail   why it is BROKEN; {@code null} otherwise
     */
    public record StreamResult(String streamId, StreamStatus status, Long seq, String detail) {
    }

    /**
     * Outcome of one run.
     *
     * @param startedAt       when the run started
     * @param skipped         why the run did nothing (another instance held the lock), or {@code null}
     * @param streams         one result per stream
     * @param exported        checkpoints exported in this run
     * @param exportFailures  exports that failed in this run
     * @param exportPending   checkpoints still without a write-once copy afterwards
     * @param exportError     the preflight or last export error, or {@code null}
     */
    public record CheckpointRun(Instant startedAt, String skipped, List<StreamResult> streams, int exported,
            int exportFailures, int exportPending, String exportError) {
    }

    private final AuditLogReader reader;
    private final DataSource commonDb;
    private final CheckpointStore store;
    private final CheckpointKeys keys;
    private final CheckpointSink sink;
    private final AuditChainVerifier verifier;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param reader   the audit log reader
     * @param commonDb {@code dlap_common}, for the run lock
     * @param store    the checkpoint table
     * @param keys     the signing and trusted keys
     * @param sink     the export sink, or {@code null} when none is configured
     * @param verifier the chain verifier
     * @param clock    the clock for {@code signedAt} and export times
     */
    public CheckpointService(AuditLogReader reader, DataSource commonDb, CheckpointStore store, CheckpointKeys keys,
            CheckpointSink sink, AuditChainVerifier verifier, Clock clock) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.commonDb = Objects.requireNonNull(commonDb, "commonDb");
        this.store = Objects.requireNonNull(store, "store");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.sink = sink;
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @return whether this instance can sign and export */
    public boolean isConfigured() {
        return keys.signing().isPresent() && sink != null;
    }

    /**
     * Runs once.
     *
     * @return what happened
     * @throws IllegalStateException when no signing key or no sink is configured
     * @throws AuditException        when {@code dlap_common} cannot be reached
     */
    public CheckpointRun run() {
        CheckpointKeys.SigningKey key = keys.signing()
                .orElseThrow(() -> new IllegalStateException("no audit checkpoint signing key is configured"));
        if (sink == null) {
            throw new IllegalStateException("no audit checkpoint export sink is configured");
        }
        Instant started = clock.instant();
        try (Connection c = commonDb.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (!tryLock(c)) {
                    return new CheckpointRun(started, "another instance is running the checkpoint job", List.of(), 0, 0,
                            store.unexported(EXPORT_BATCH).size(), null);
                }
                List<StreamResult> results = new ArrayList<>();
                for (AuditStream s : reader.streams()) {
                    results.add(checkpoint(s.streamId(), key));
                }
                return export(started, results);
            } finally {
                c.rollback();
            }
        } catch (SQLException e) {
            throw new AuditException("checkpoint run failed: " + e.getMessage(), e);
        }
    }

    private static boolean tryLock(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(LOCK_SQL); ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private StreamResult checkpoint(String streamId, CheckpointKeys.SigningKey key) {
        Optional<AuditEntry> head = reader.head(streamId);
        if (head.isEmpty()) {
            return new StreamResult(streamId, StreamStatus.NO_ENTRIES, null, null);
        }
        Optional<StoredCheckpoint> latest = store.latest(streamId);
        ChainVerification v;
        if (latest.isPresent()) {
            StoredCheckpoint last = latest.get();
            String problem = keys.problemWith(last);
            if (problem != null) {
                return broken(streamId, last.seq(), "the newest stored checkpoint is not trustworthy: " + problem);
            }
            if (last.seq() == head.get().seq() && last.entryHash().equals(head.get().entryHash())) {
                return new StreamResult(streamId, StreamStatus.UP_TO_DATE, last.seq(), null);
            }
            v = verifier.verifyFrom(streamId, reader, List.of(), new ChainAnchor(last.seq(), last.entryHash()));
        } else {
            v = verifier.verify(streamId, reader, List.of());
        }
        if (!v.isValid()) {
            return broken(streamId, v.firstBadSeq(), v.problem() + ": " + v.detail());
        }
        if (latest.isPresent() && v.lastGoodSeq() <= latest.get().seq()) {
            return new StreamResult(streamId, StreamStatus.UP_TO_DATE, latest.get().seq(), null);
        }
        AuditCheckpoint cp = new AuditCheckpoint(streamId, v.lastGoodSeq(), v.lastGoodHash(), clock.instant(), key.keyId());
        String payload = cp.payload();
        String signature = CheckpointSignatures.sign(key.privateKey(), payload);
        store.insert(new StoredCheckpoint(streamId, cp.seq(), cp.entryHash(), cp.keyId(), cp.signedAt(), payload,
                signature, null, null, null, 0, null));
        LOG.info("Signed audit checkpoint stream={} seq={} keyId={}", streamId, cp.seq(), cp.keyId());
        return new StreamResult(streamId, StreamStatus.SIGNED, cp.seq(), null);
    }

    private static StreamResult broken(String streamId, Long seq, String detail) {
        LOG.error("Audit stream {} does not verify at seq {}: {}. No checkpoint was signed; investigate before the next run.",
                streamId, seq, detail);
        return new StreamResult(streamId, StreamStatus.BROKEN, seq, detail);
    }

    private CheckpointRun export(Instant started, List<StreamResult> results) {
        List<StoredCheckpoint> pending = store.unexported(EXPORT_BATCH);
        if (pending.isEmpty()) {
            return new CheckpointRun(started, null, results, 0, 0, 0, null);
        }
        try {
            sink.preflight();
        } catch (RuntimeException e) {
            LOG.error("Audit checkpoint export target is not ready ({}): {}", sink.name(), e.getMessage());
            for (StoredCheckpoint c : pending) {
                store.markExportFailed(c.streamId(), c.seq(), sink.name(), "preflight: " + e.getMessage());
            }
            return new CheckpointRun(started, null, results, 0, pending.size(), pending.size(), e.getMessage());
        }
        int exported = 0;
        int failed = 0;
        String lastError = null;
        for (StoredCheckpoint c : pending) {
            try {
                String location = sink.export(new SignedCheckpoint(AuditCheckpoint.parse(c.payload()), c.signature()));
                store.markExported(c.streamId(), c.seq(), sink.name(), location, clock.instant());
                exported++;
            } catch (RuntimeException e) {
                failed++;
                lastError = e.getMessage();
                store.markExportFailed(c.streamId(), c.seq(), sink.name(), e.getMessage());
                LOG.warn("Audit checkpoint export failed stream={} seq={} sink={}: {}", c.streamId(), c.seq(),
                        sink.name(), e.getMessage());
            }
        }
        return new CheckpointRun(started, null, results, exported, failed, store.unexported(EXPORT_BATCH).size(), lastError);
    }
}
