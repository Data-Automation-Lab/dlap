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
 * A trusted position of a stream: the {@code seq} and {@code entryHash} of a checkpoint whose
 * signature was verified.
 *
 * @param seq       the position
 * @param entryHash the entry hash the checkpoint signs
 */
public record ChainAnchor(long seq, String entryHash) {

    /**
     * Validates the anchor.
     *
     * @param seq       the position, at least 1
     * @param entryHash 64 lower-case hex characters
     */
    public ChainAnchor {
        if (seq < 1) {
            throw new IllegalArgumentException("anchor seq must be at least 1");
        }
        if (!AuditChain.isHash(entryHash)) {
            throw new IllegalArgumentException("anchor entryHash must be 64 lower-case hex characters");
        }
    }

    /**
     * The anchor a checkpoint states.
     *
     * @param checkpoint the checkpoint, already signature-verified
     * @return the anchor
     */
    public static ChainAnchor of(AuditCheckpoint checkpoint) {
        return new ChainAnchor(checkpoint.seq(), checkpoint.entryHash());
    }
}
