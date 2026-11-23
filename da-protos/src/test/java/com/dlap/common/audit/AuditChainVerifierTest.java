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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dlap.common.audit.ChainVerification.Problem;

/** {@link AuditChainVerifier} over in-memory chains: one case per problem it must find. */
class AuditChainVerifierTest {

    private static final String STREAM = "retire.delete";

    /** A valid chain of {@code n} entries. */
    static List<AuditEntry> chain(String stream, int n) {
        List<AuditEntry> out = new ArrayList<>();
        String prev = AuditChain.GENESIS_HASH;
        for (int i = 1; i <= n; i++) {
            AuditEvent e = AuditEvent.builder("retire.delete.executed")
                    .occurredAt(Instant.parse("2026-10-21T00:00:00Z").plusSeconds(i))
                    .actor(AuditActor.system("test"))
                    .outcome(AuditOutcome.SUCCEEDED)
                    .detail("n", i)
                    .build();
            String record = AuditChain.record(e, Instant.parse("2026-10-21T01:00:00Z").plusSeconds(i));
            String hash = AuditChain.entryHash(stream, i, prev, record);
            out.add(new AuditEntry(stream, i, prev, hash, record));
            prev = hash;
        }
        return out;
    }

    static AuditEntryPages pages(List<AuditEntry> entries) {
        return (stream, after, limit) -> entries.stream().filter(e -> e.seq() > after).limit(limit).toList();
    }

    /** A source that ignores seq and serves the list as stored, for gap and order cases. */
    static AuditEntryPages raw(List<AuditEntry> entries) {
        return (stream, after, limit) -> {
            int from = 0;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).seq() == after) {
                    from = i + 1;
                }
            }
            return entries.subList(Math.min(from, entries.size()), Math.min(entries.size(), from + limit));
        };
    }

    private final AuditChainVerifier verifier = new AuditChainVerifier(7);

    @Test
    void aValidChainVerifiesAcrossPageBoundaries() {
        List<AuditEntry> c = chain(STREAM, 30);
        ChainVerification v = verifier.verify(STREAM, pages(c), List.of(new ChainAnchor(7, c.get(6).entryHash()),
                new ChainAnchor(30, c.get(29).entryHash())));
        assertThat(v.isValid()).isTrue();
        assertThat(v.entriesChecked()).isEqualTo(30);
        assertThat(v.lastGoodSeq()).isEqualTo(30);
        assertThat(v.lastGoodHash()).isEqualTo(c.get(29).entryHash());
        assertThat(v.anchorsMatched()).isEqualTo(2);
        assertThat(v.problem()).isNull();
    }

    @Test
    void anEmptyStreamIsValidWithoutAnchors() {
        ChainVerification v = verifier.verify(STREAM, pages(List.of()), List.of());
        assertThat(v.isValid()).isTrue();
        assertThat(v.lastGoodSeq()).isZero();
        assertThat(v.lastGoodHash()).isNull();
    }

    @Test
    void aChangedRecordIsFound() {
        List<AuditEntry> c = new ArrayList<>(chain(STREAM, 10));
        AuditEntry e = c.get(4);
        c.set(4, new AuditEntry(STREAM, 5, e.prevHash(), e.entryHash(), e.record().replace("\"n\":5", "\"n\":6")));
        ChainVerification v = verifier.verify(STREAM, pages(c), List.of());
        assertThat(v.isValid()).isFalse();
        assertThat(v.problem()).isEqualTo(Problem.ENTRY_HASH_MISMATCH);
        assertThat(v.firstBadSeq()).isEqualTo(5L);
        assertThat(v.entriesChecked()).isEqualTo(4);
        assertThat(v.lastGoodSeq()).isEqualTo(4);
    }

    @Test
    void aRewrittenEntryWithARecomputedHashBreaksTheNextLink() {
        List<AuditEntry> c = new ArrayList<>(chain(STREAM, 10));
        AuditEntry e = c.get(4);
        String forged = e.record().replace("\"n\":5", "\"n\":6");
        c.set(4, new AuditEntry(STREAM, 5, e.prevHash(), AuditChain.entryHash(STREAM, 5, e.prevHash(), forged), forged));
        ChainVerification v = verifier.verify(STREAM, pages(c), List.of());
        assertThat(v.problem()).isEqualTo(Problem.PREV_HASH_MISMATCH);
        assertThat(v.firstBadSeq()).isEqualTo(6L);
    }

    @Test
    void aConsistentlyRewrittenChainFailsOnlyAgainstASignedAnchor() {
        List<AuditEntry> original = chain(STREAM, 10);
        List<AuditEntry> forged = new ArrayList<>(original.subList(0, 4));
        String prev = original.get(3).entryHash();
        for (AuditEntry e : original.subList(4, 10)) {
            String record = e.record().replace("\"outcome\":\"SUCCEEDED\"", "\"outcome\":\"FAILED\"");
            String hash = AuditChain.entryHash(STREAM, e.seq(), prev, record);
            forged.add(new AuditEntry(STREAM, e.seq(), prev, hash, record));
            prev = hash;
        }
        assertThat(verifier.verify(STREAM, pages(forged), List.of()).isValid())
                .as("the chain alone cannot see a consistent rewrite").isTrue();
        ChainVerification v = verifier.verify(STREAM, pages(forged),
                List.of(new ChainAnchor(8, original.get(7).entryHash())));
        assertThat(v.problem()).isEqualTo(Problem.ANCHOR_MISMATCH);
        assertThat(v.firstBadSeq()).isEqualTo(8L);
    }

    @Test
    void removedTailEntriesAreFoundByAnAnchorBeyondTheHead() {
        List<AuditEntry> c = chain(STREAM, 10);
        ChainVerification v = verifier.verify(STREAM, pages(c.subList(0, 6)),
                List.of(new ChainAnchor(9, c.get(8).entryHash())));
        assertThat(v.problem()).isEqualTo(Problem.ANCHOR_BEYOND_HEAD);
        assertThat(v.firstBadSeq()).isEqualTo(9L);
        assertThat(v.lastGoodSeq()).isEqualTo(6);
    }

    @Test
    void aMissingEntryIsAGap() {
        List<AuditEntry> c = new ArrayList<>(chain(STREAM, 10));
        c.remove(3);
        ChainVerification v = verifier.verify(STREAM, raw(c), List.of());
        assertThat(v.problem()).isEqualTo(Problem.SEQ_GAP);
        assertThat(v.firstBadSeq()).isEqualTo(5L);
    }

    @Test
    void aReorderedEntryBreaksTheLinkAtTheFirstMovedSeq() {
        List<AuditEntry> c = new ArrayList<>(chain(STREAM, 5));
        AuditEntry second = c.get(1);
        AuditEntry third = c.get(2);
        // Rows 2 and 3 swap their seq values: the stored rows are untouched apart from seq.
        c.set(1, new AuditEntry(STREAM, 2, third.prevHash(), third.entryHash(), third.record()));
        c.set(2, new AuditEntry(STREAM, 3, second.prevHash(), second.entryHash(), second.record()));
        ChainVerification v = verifier.verify(STREAM, pages(c), List.of());
        assertThat(v.isValid()).isFalse();
        assertThat(v.problem()).isEqualTo(Problem.PREV_HASH_MISMATCH);
        assertThat(v.firstBadSeq()).isEqualTo(2L);
        assertThat(v.lastGoodSeq()).isEqualTo(1);

        // Rows served out of seq order, without renumbering, are a gap at the first wrong position.
        List<AuditEntry> outOfOrder = new ArrayList<>(chain(STREAM, 5));
        outOfOrder.add(1, outOfOrder.remove(2));
        ChainVerification served = verifier.verify(STREAM, raw(outOfOrder), List.of());
        assertThat(served.problem()).isEqualTo(Problem.SEQ_GAP);
        assertThat(served.firstBadSeq()).isEqualTo(3L);
    }

    @Test
    void aWrongFirstLinkIsFound() {
        List<AuditEntry> c = new ArrayList<>(chain(STREAM, 3));
        AuditEntry first = c.get(0);
        String prev = "f".repeat(64);
        c.set(0, new AuditEntry(STREAM, 1, prev, AuditChain.entryHash(STREAM, 1, prev, first.record()), first.record()));
        assertThat(verifier.verify(STREAM, pages(c), List.of()).problem()).isEqualTo(Problem.PREV_HASH_MISMATCH);
    }

    @Test
    void aNonAsciiRecordAndAForeignEntryAreFound() {
        List<AuditEntry> c = new ArrayList<>(chain(STREAM, 3));
        AuditEntry e = c.get(1);
        String record = e.record().replace("test", "t\u00e9st");
        c.set(1, new AuditEntry(STREAM, 2, e.prevHash(), AuditChain.entryHash(STREAM, 2, e.prevHash(), record), record));
        assertThat(verifier.verify(STREAM, pages(c), List.of()).problem()).isEqualTo(Problem.RECORD_NOT_ASCII);

        List<AuditEntry> other = chain("retire.archive", 2);
        assertThat(verifier.verify(STREAM, pages(other), List.of()).problem()).isEqualTo(Problem.WRONG_STREAM);
    }

    @Test
    void twoAnchorsThatDisagreeAreAProblemBeforeAnyRead() {
        ChainVerification v = verifier.verify(STREAM, pages(chain(STREAM, 2)),
                List.of(new ChainAnchor(1, "a".repeat(64)), new ChainAnchor(1, "b".repeat(64))));
        assertThat(v.problem()).isEqualTo(Problem.ANCHOR_MISMATCH);
        assertThat(v.entriesChecked()).isZero();
    }

    @Test
    void verifyFromChecksOnlyTheEntriesAfterATrustedPosition() {
        List<AuditEntry> c = new ArrayList<>(chain(STREAM, 12));
        ChainAnchor at5 = new ChainAnchor(5, c.get(4).entryHash());
        ChainVerification v = verifier.verifyFrom(STREAM, pages(c), List.of(new ChainAnchor(2, "a".repeat(64))), at5);
        assertThat(v.isValid()).as("an anchor at or before the start is ignored").isTrue();
        assertThat(v.entriesChecked()).isEqualTo(7);
        assertThat(v.lastGoodSeq()).isEqualTo(12);

        // The starting entry must still carry the trusted hash.
        ChainVerification wrongStart = verifier.verifyFrom(STREAM, pages(c), List.of(),
                new ChainAnchor(5, "c".repeat(64)));
        assertThat(wrongStart.problem()).isEqualTo(Problem.ANCHOR_MISMATCH);
        assertThat(wrongStart.firstBadSeq()).isEqualTo(5L);

        // An entry after the start that was rewritten together with its hash breaks the next link.
        AuditEntry e6 = c.get(5);
        String forged = e6.record().replace("\"n\":6", "\"n\":60");
        c.set(5, new AuditEntry(STREAM, 6, e6.prevHash(), AuditChain.entryHash(STREAM, 6, e6.prevHash(), forged), forged));
        ChainVerification relinked = verifier.verifyFrom(STREAM, pages(c), List.of(), at5);
        assertThat(relinked.problem()).isEqualTo(Problem.PREV_HASH_MISMATCH);
        assertThat(relinked.firstBadSeq()).isEqualTo(7L);
        c = new ArrayList<>(chain(STREAM, 12));

        // Nothing after the start is valid; an anchor past the head is not.
        assertThat(verifier.verifyFrom(STREAM, pages(c), List.of(), new ChainAnchor(12, c.get(11).entryHash()))
                .entriesChecked()).isZero();
        assertThat(verifier.verifyFrom(STREAM, pages(c.subList(0, 8)), List.of(), at5).isValid()).isTrue();
        assertThat(verifier.verifyFrom(STREAM, pages(c.subList(0, 4)), List.of(), at5).problem())
                .as("the starting entry is gone").isEqualTo(Problem.ANCHOR_BEYOND_HEAD);
    }

    @Test
    void badArgumentsAreRefused() {
        assertThatThrownBy(() -> new AuditChainVerifier(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChainAnchor(0, "a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChainAnchor(1, "A".repeat(64))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new AuditChainVerifier().verify(STREAM, pages(chain(STREAM, 1)), List.of()).isValid()).isTrue();
    }
}
