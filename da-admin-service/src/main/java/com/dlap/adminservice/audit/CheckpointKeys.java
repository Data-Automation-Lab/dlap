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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.dlap.common.audit.AuditCheckpoint;
import com.dlap.common.audit.CheckpointSignatures;

/**
 * The checkpoint signing key and the public keys a verifier trusts, read once at startup from
 * PEM files. A configured file that cannot be read, or a private key that does not match its
 * public key, stops the service: a signer must never run with a key nobody can verify.
 */
public final class CheckpointKeys {

    /** The signing key with its id. */
    public record SigningKey(PrivateKey privateKey, PublicKey publicKey, String keyId) {

        @Override
        public String toString() {
            return "SigningKey[keyId=" + keyId + "]";
        }
    }

    private final SigningKey signing;
    private final Map<String, PublicKey> trusted;

    /**
     * Creates the key set.
     *
     * @param signing the signing key, or {@code null} when this instance does not sign
     * @param trusted public keys by key id; includes the signing key's public key
     */
    public CheckpointKeys(SigningKey signing, Map<String, PublicKey> trusted) {
        this.signing = signing;
        this.trusted = Collections.unmodifiableMap(new LinkedHashMap<>(trusted));
    }

    /**
     * Reads the keys from the {@code dlap.audit.signing} settings.
     *
     * @param settings the settings
     * @return the keys
     * @throws IllegalStateException when a file cannot be read or the pair does not match
     */
    public static CheckpointKeys load(AuditProperties.Signing settings) {
        Map<String, PublicKey> trusted = new LinkedHashMap<>();
        SigningKey signing = null;
        boolean hasPrivate = !isBlank(settings.getPrivateKeyFile());
        boolean hasPublic = !isBlank(settings.getPublicKeyFile());
        if (hasPrivate != hasPublic) {
            throw new IllegalStateException("dlap.audit.signing needs both private-key-file and public-key-file");
        }
        if (hasPrivate) {
            PrivateKey priv = CheckpointSignatures.readPrivateKey(read(settings.getPrivateKeyFile()));
            PublicKey pub = CheckpointSignatures.readPublicKey(read(settings.getPublicKeyFile()));
            if (!CheckpointSignatures.isPair(priv, pub)) {
                throw new IllegalStateException("dlap.audit.signing.private-key-file does not match public-key-file");
            }
            signing = new SigningKey(priv, pub, CheckpointSignatures.keyId(pub));
            trusted.put(signing.keyId(), pub);
        }
        List<String> extra = settings.getTrustedPublicKeyFiles() == null ? List.of() : settings.getTrustedPublicKeyFiles();
        for (String file : extra) {
            if (isBlank(file)) {
                continue;
            }
            PublicKey pub = CheckpointSignatures.readPublicKey(read(file));
            trusted.put(CheckpointSignatures.keyId(pub), pub);
        }
        return new CheckpointKeys(signing, trusted);
    }

    /** @return the signing key, or empty when this instance does not sign */
    public Optional<SigningKey> signing() {
        return Optional.ofNullable(signing);
    }

    /** @return the trusted public keys by key id */
    public Map<String, PublicKey> trusted() {
        return trusted;
    }

    /**
     * Why a stored checkpoint cannot be trusted: its payload must be canonical, must state the
     * row's stream, position, hash and key, and its signature must verify with a trusted key.
     *
     * @param c the stored checkpoint
     * @return the reason, or {@code null} when the checkpoint is trustworthy
     */
    public String problemWith(StoredCheckpoint c) {
        AuditCheckpoint parsed;
        try {
            parsed = AuditCheckpoint.parse(c.payload());
        } catch (IllegalArgumentException e) {
            return "the payload is not a canonical checkpoint";
        }
        if (!parsed.streamId().equals(c.streamId()) || parsed.seq() != c.seq()
                || !parsed.entryHash().equals(c.entryHash()) || !parsed.keyId().equals(c.keyId())) {
            return "the payload does not match the row";
        }
        PublicKey pub = trusted.get(parsed.keyId());
        if (pub == null) {
            return "signed by key " + parsed.keyId() + ", which is not trusted";
        }
        return CheckpointSignatures.verify(pub, c.payload(), c.signature()) ? null : "the signature does not verify";
    }

    private static String read(String file) {
        try {
            return Files.readString(Path.of(file), StandardCharsets.US_ASCII);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("cannot read audit key file " + file, e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
