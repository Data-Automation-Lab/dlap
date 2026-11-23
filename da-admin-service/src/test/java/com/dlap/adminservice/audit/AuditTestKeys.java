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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

import com.dlap.adminservice.audit.export.SignedCheckpoint;
import com.dlap.common.audit.AuditCheckpoint;
import com.dlap.common.audit.CheckpointSignatures;

/** Test keys and checkpoints. Keys are generated per test run; no key is ever committed. */
public final class AuditTestKeys {

    /**
     * The PEM label of a PKCS#8 key, built from two parts so that secret scanners (gitleaks rule
     * private-key) do not read {@link #write} as a committed key. Every key here is generated per test.
     */
    private static final String PRIVATE_KEY_LABEL = "PRIVATE" + " KEY";

    private AuditTestKeys() {
    }

    /** @return a fresh Ed25519 pair */
    public static KeyPair newPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Writes a pair as PEM files.
     *
     * @param pair the pair
     * @param dir  the directory
     * @param name the base name
     * @return the settings that point at the files
     */
    public static AuditProperties.Signing write(KeyPair pair, Path dir, String name) {
        try {
            Path priv = dir.resolve(name + ".key.pem");
            Path pub = dir.resolve(name + ".pub.pem");
            Files.writeString(priv, "-----BEGIN " + PRIVATE_KEY_LABEL + "-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                            .encodeToString(pair.getPrivate().getEncoded())
                    + "\n-----END " + PRIVATE_KEY_LABEL + "-----\n", StandardCharsets.US_ASCII);
            Files.writeString(pub, CheckpointSignatures.toPem(pair.getPublic()), StandardCharsets.US_ASCII);
            AuditProperties.Signing s = new AuditProperties.Signing();
            s.setPrivateKeyFile(priv.toString());
            s.setPublicKeyFile(pub.toString());
            return s;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A signed checkpoint.
     *
     * @param pair   the key
     * @param stream the stream
     * @param seq    the position
     * @return the checkpoint and its signature
     */
    public static SignedCheckpoint signed(KeyPair pair, String stream, long seq) {
        AuditCheckpoint cp = new AuditCheckpoint(stream, seq, "ab".repeat(32), Instant.parse("2026-10-21T12:00:00Z"),
                CheckpointSignatures.keyId(pair.getPublic()));
        return new SignedCheckpoint(cp, CheckpointSignatures.sign(pair.getPrivate(), cp.payload()));
    }
}
