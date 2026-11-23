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

package com.dlap.adminservice.audit.export;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.dlap.common.audit.AuditCheckpoint;

/**
 * A checkpoint with its signature, as exported: two objects next to each other below the prefix,
 * {@code <streamId>/<seq, 20 digits>.json} (the payload bytes) and {@code .sig} (the Base64
 * signature text). The signature object is written first, so a payload never appears without it.
 *
 * @param checkpoint the checkpoint
 * @param signature  the Ed25519 signature of {@code checkpoint.payload()}, standard Base64
 */
public record SignedCheckpoint(AuditCheckpoint checkpoint, String signature) {

    /**
     * Validates the pair.
     *
     * @param checkpoint the checkpoint
     * @param signature  the signature
     */
    public SignedCheckpoint {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(signature, "signature");
    }

    /** @return the payload bytes, printable ASCII */
    public byte[] payloadBytes() {
        return checkpoint.payload().getBytes(StandardCharsets.US_ASCII);
    }

    /** @return the signature object bytes: the Base64 text */
    public byte[] signatureBytes() {
        return signature.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * The payload object name below a prefix.
     *
     * @param prefix the prefix without a trailing slash; blank for none
     * @return the name
     */
    public String payloadName(String prefix) {
        String p = prefix == null || prefix.isBlank() ? "" : stripSlashes(prefix) + "/";
        return p + checkpoint.objectName();
    }

    /**
     * The signature object name below a prefix.
     *
     * @param prefix the prefix
     * @return the name, the payload name with {@code .sig} for {@code .json}
     */
    public String signatureName(String prefix) {
        String payload = payloadName(prefix);
        return payload.substring(0, payload.length() - ".json".length()) + ".sig";
    }

    private static String stripSlashes(String prefix) {
        String p = prefix.trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
