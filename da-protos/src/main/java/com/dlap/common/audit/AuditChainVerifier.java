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

package com.dlap.common.audit;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Recomputes the hash chain of one stream and checks it against trusted anchors
 * (docs/specs/audit-log.md, section "Verification").
 *
 * <p>Every entry must have the next {@code seq}, the previous entry's hash as {@code prevHash},
 * a printable-ASCII record and an {@code entryHash} equal to the recomputed hash. Every anchor
 * (the position and hash of a signature-verified checkpoint) must name an entry that exists and
 * carries that hash; an anchor beyond the head means entries were removed. Verification stops at
 * the first problem and reports it. The chain alone proves internal consistency; the anchors
 * prove that no one rewrote the chain consistently, because only the checkpoint key could sign a
 * new anchor.
 */
public final class AuditChainVerifier {

    /** Entries read per page. */
    public static final int DEFAULT_PAGE_SIZE = 1_000;

    private final int pageSize;

    /** Creates a verifier that reads {@link #DEFAULT_PAGE_SIZE} entries per page. */
    public AuditChainVerifier() {
        this(DEFAULT_PAGE_SIZE);
    }

    /**
     * Creates a verifier.
     *
     * @param pageSize entries per page, at least 1
     */
    public AuditChainVerifier(int pageSize) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be at least 1");
        }
        this.pageSize = pageSize;
    }

    /**
     * Verifies a stream from its first entry to its head.
     *
     * @param streamId the stream
     * @param source   where the entries come from
     * @param anchors  positions and hashes from verified checkpoints; may be empty
     * @return the result
     */
    public ChainVerification verify(String streamId, AuditEntryPages source, Collection<ChainAnchor> anchors) {
        return verifyFrom(streamId, source, anchors, null);
    }

    /**
     * Verifies a stream from a trusted position to its head. The entry at {@code from.seq()} must
     * still exist with {@code from.entryHash()}, and the entries after it must chain from it. A signer uses this
     * to check only what was appended since its last checkpoint before it signs a new one.
     *
     * @param streamId the stream
     * @param source   where the entries come from
     * @param anchors  further positions and hashes from verified checkpoints; those at or before
     *                 {@code from} are ignored
     * @param from     the trusted starting position, or {@code null} to start at the first entry
     * @return the result; {@code entriesChecked} counts only the entries after {@code from}
     */
    public ChainVerification verifyFrom(String streamId, AuditEntryPages source, Collection<ChainAnchor> anchors,
            ChainAnchor from) {
        Objects.requireNonNull(source, "source");
        TreeMap<Long, String> pending = new TreeMap<>();
        long startSeq = from == null ? 0 : from.seq();
        for (ChainAnchor anchor : anchors) {
            if (anchor.seq() <= startSeq) {
                continue;
            }
            String earlier = pending.putIfAbsent(anchor.seq(), anchor.entryHash());
            if (earlier != null && !earlier.equals(anchor.entryHash())) {
                return ChainVerification.broken(streamId, 0, 0, null, anchor.seq(),
                        ChainVerification.Problem.ANCHOR_MISMATCH,
                        "two verified checkpoints name different hashes for seq " + anchor.seq(), 0);
            }
        }
        if (from != null) {
            List<AuditEntry> start = source.page(streamId, from.seq() - 1, 1);
            if (start.isEmpty() || start.get(0).seq() != from.seq()) {
                return ChainVerification.broken(streamId, 0, 0, null, from.seq(),
                        ChainVerification.Problem.ANCHOR_BEYOND_HEAD,
                        "the trusted starting entry " + from.seq() + " is gone", 0);
            }
            if (!from.entryHash().equals(start.get(0).entryHash())) {
                return ChainVerification.broken(streamId, 0, 0, null, from.seq(),
                        ChainVerification.Problem.ANCHOR_MISMATCH,
                        "the trusted starting entry " + from.seq() + " carries another hash", 0);
            }
        }
        int anchorsMatched = 0;
        long checked = 0;
        long expectedSeq = startSeq + 1;
        String prevHash = from == null ? AuditChain.GENESIS_HASH : from.entryHash();
        long afterSeq = startSeq;
        while (true) {
            List<AuditEntry> page = source.page(streamId, afterSeq, pageSize);
            for (AuditEntry e : page) {
                ChainVerification.Problem problem = null;
                String detail = null;
                if (!streamId.equals(e.streamId())) {
                    problem = ChainVerification.Problem.WRONG_STREAM;
                    detail = "entry belongs to stream " + e.streamId();
                } else if (e.seq() != expectedSeq) {
                    problem = ChainVerification.Problem.SEQ_GAP;
                    detail = "expected seq " + expectedSeq + ", found " + e.seq();
                } else if (!prevHash.equals(e.prevHash())) {
                    problem = ChainVerification.Problem.PREV_HASH_MISMATCH;
                    detail = "prev_hash does not equal the entry_hash of seq " + (e.seq() - 1);
                } else if (!CanonicalJson.isPrintableAscii(e.record())) {
                    problem = ChainVerification.Problem.RECORD_NOT_ASCII;
                    detail = "record is not printable ASCII";
                } else if (!AuditChain.entryHash(streamId, e.seq(), e.prevHash(), e.record()).equals(e.entryHash())) {
                    problem = ChainVerification.Problem.ENTRY_HASH_MISMATCH;
                    detail = "entry_hash does not match the recomputed hash";
                } else {
                    String anchored = pending.remove(e.seq());
                    if (anchored != null) {
                        if (!anchored.equals(e.entryHash())) {
                            problem = ChainVerification.Problem.ANCHOR_MISMATCH;
                            detail = "a signed checkpoint names a different hash for seq " + e.seq();
                        } else {
                            anchorsMatched++;
                        }
                    }
                }
                if (problem != null) {
                    return ChainVerification.broken(streamId, checked, expectedSeq - 1, prevHashOrNull(prevHash),
                            e.seq(), problem, detail, anchorsMatched);
                }
                checked++;
                expectedSeq = e.seq() + 1;
                prevHash = e.entryHash();
            }
            if (page.size() < pageSize) {
                break;
            }
            afterSeq = page.get(page.size() - 1).seq();
        }
        long head = expectedSeq - 1;
        if (!pending.isEmpty()) {
            long missing = pending.firstKey();
            return ChainVerification.broken(streamId, checked, head, prevHashOrNull(prevHash), missing,
                    ChainVerification.Problem.ANCHOR_BEYOND_HEAD,
                    "a signed checkpoint covers seq " + missing + " but the stream ends at seq " + head,
                    anchorsMatched);
        }
        return ChainVerification.valid(streamId, checked, head, prevHashOrNull(prevHash), anchorsMatched);
    }

    private static String prevHashOrNull(String hash) {
        return AuditChain.GENESIS_HASH.equals(hash) ? null : hash;
    }
}
