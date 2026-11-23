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

import java.time.Instant;

/**
 * One row of {@code dlap_admin.audit_checkpoints}.
 *
 * @param streamId        the stream
 * @param seq             the covered position
 * @param entryHash       the covered entry's hash
 * @param keyId           the signing key id
 * @param signedAt        the signing time
 * @param payload         the signed payload text
 * @param signature       the Base64 signature
 * @param exportSink      the sink that holds the write-once copy, or {@code null}
 * @param exportLocation  where the copy is, or {@code null} while not exported
 * @param exportedAt      when it was exported, or {@code null}
 * @param exportAttempts  failed and successful export attempts so far
 * @param lastExportError the last export error, or {@code null}
 */
public record StoredCheckpoint(String streamId, long seq, String entryHash, String keyId, Instant signedAt,
        String payload, String signature, String exportSink, String exportLocation, Instant exportedAt,
        int exportAttempts, String lastExportError) {

    /** @return whether the write-once copy exists */
    public boolean isExported() {
        return exportedAt != null;
    }
}
