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

/**
 * The result of {@link AuditChainVerifier#verify}.
 *
 * @param streamId       the stream
 * @param status         {@link Status#VALID} or {@link Status#BROKEN}
 * @param entriesChecked entries that passed before the first problem (all of them when valid)
 * @param lastGoodSeq    seq of the last entry that passed; 0 when none did
 * @param lastGoodHash   its entry hash; {@code null} when none passed
 * @param firstBadSeq    seq where the first problem was found; {@code null} when valid
 * @param problem        the first problem; {@code null} when valid
 * @param detail         a sentence about the problem; {@code null} when valid
 * @param anchorsMatched anchors that matched before verification ended
 */
public record ChainVerification(String streamId, Status status, long entriesChecked, long lastGoodSeq,
        String lastGoodHash, Long firstBadSeq, Problem problem, String detail, int anchorsMatched) {

    /** Overall result. */
    public enum Status {
        /** Every entry and every anchor checked out. */
        VALID,
        /** A problem was found; see {@link ChainVerification#problem()}. */
        BROKEN
    }

    /** What was wrong at {@link ChainVerification#firstBadSeq()}. */
    public enum Problem {
        /** The entry belongs to another stream. */
        WRONG_STREAM,
        /** A position is missing or repeated. */
        SEQ_GAP,
        /** {@code prev_hash} is not the previous entry's hash. */
        PREV_HASH_MISMATCH,
        /** The record holds a character outside printable ASCII. */
        RECORD_NOT_ASCII,
        /** {@code entry_hash} is not the hash of the stored fields: the row was changed. */
        ENTRY_HASH_MISMATCH,
        /** A signed checkpoint names another hash for this position: the chain was rewritten. */
        ANCHOR_MISMATCH,
        /** A signed checkpoint names a position past the head: entries were removed. */
        ANCHOR_BEYOND_HEAD
    }

    /**
     * A valid result.
     *
     * @param streamId       the stream
     * @param checked        entries checked
     * @param headSeq        seq of the head, 0 for an empty stream
     * @param headHash       hash of the head, {@code null} for an empty stream
     * @param anchorsMatched anchors matched
     * @return the result
     */
    static ChainVerification valid(String streamId, long checked, long headSeq, String headHash, int anchorsMatched) {
        return new ChainVerification(streamId, Status.VALID, checked, headSeq, headHash, null, null, null,
                anchorsMatched);
    }

    /**
     * A broken result.
     *
     * @param streamId       the stream
     * @param checked        entries that passed
     * @param lastGoodSeq    the last good seq
     * @param lastGoodHash   the last good hash
     * @param firstBadSeq    where the problem is
     * @param problem        the problem
     * @param detail         a sentence
     * @param anchorsMatched anchors matched
     * @return the result
     */
    static ChainVerification broken(String streamId, long checked, long lastGoodSeq, String lastGoodHash,
            long firstBadSeq, Problem problem, String detail, int anchorsMatched) {
        return new ChainVerification(streamId, Status.BROKEN, checked, lastGoodSeq, lastGoodHash, firstBadSeq,
                problem, detail, anchorsMatched);
    }

    /**
     * Whether the stream verified.
     *
     * @return true when {@link #status()} is {@link Status#VALID}
     */
    public boolean isValid() {
        return status == Status.VALID;
    }
}
