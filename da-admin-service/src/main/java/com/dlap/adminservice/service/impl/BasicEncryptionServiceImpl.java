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

package com.dlap.adminservice.service.impl;

import com.dlap.adminservice.service.IEncryptionService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * AES-256/GCM credential encryption with backwards-compatible v1 (ECB) decrypt path.
 *
 * <h2>Envelope format</h2>
 * <ul>
 *   <li>{@code v2:<base64_iv>:<base64_ciphertext_with_tag>} - current format (GCM, always used for new writes)</li>
 *   <li>{@code v1:<base64_ciphertext>} - legacy ECB, decrypt-only, lazily upgraded on next write</li>
 *   <li>Unprefixed Base64 - pre-versioning legacy ECB, decrypt-only</li>
 * </ul>
 *
 * <h2>Key requirements</h2>
 * <p>The {@code DLAP_ENCRYPTION_KEY} env var must resolve to exactly 32 bytes (256 bits).
 * Two forms are accepted:
 * <ul>
 *   <li><b>64 hex characters</b> - the documented {@code openssl rand -hex 32} output;
 *       hex-decoded to 32 bytes (256-bit entropy). Preferred.</li>
 *   <li><b>32 raw characters</b> - used as-is (UTF-8 bytes); backward-compatible with
 *       earlier deployments (~128-bit entropy for ASCII - prefer the hex form).</li>
 * </ul>
 * The service fails fast on startup if the key is missing or does not resolve to 32 bytes.
 *
 * <h2>Backwards compatibility</h2>
 * <p>Existing rows encrypted with the old AES-128/ECB implementation are still
 * readable: the decrypt path detects the version prefix and routes to the legacy
 * ECB decrypt. The first 16 bytes of the 32-byte key are used for legacy ECB
 * decryption (matching the old zero-padded AES-128 behaviour). A log message
 * notes when a legacy row is decrypted so operators can track backfill progress.
 *
 * <h2>Migration</h2>
 * <p>Lazy backfill: on the next write operation that touches a row (e.g.
 * {@code updateCloudConnection}), the re-encrypted value is automatically
 * stored in v2 format. No explicit migration job is required for the OSS launch.
 */
@Service
@Slf4j
public class BasicEncryptionServiceImpl implements IEncryptionService {

    private static final String ALGORITHM = "AES";
    private static final String GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ECB_TRANSFORMATION = "AES/ECB/PKCS5Padding";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int REQUIRED_KEY_BYTES = 32; // AES-256
    private static final int LEGACY_KEY_BYTES = 16;   // AES-128 (old impl)

    private static final String V2_PREFIX = "v2:";
    private static final String V1_PREFIX = "v1:";

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.encryption.key:}")
    private String encryptionKeyString;

    private SecretKeySpec gcmKeySpec;
    private SecretKeySpec legacyEcbKeySpec;

    @PostConstruct
    void init() {
        if (encryptionKeyString == null || encryptionKeyString.isBlank()) {
            throw new IllegalStateException(
                    "DLAP_ENCRYPTION_KEY is not set. "
                    + "Generate one with: openssl rand -hex 32");
        }
        byte[] keyBytes = resolveKeyBytes(encryptionKeyString.trim());
        if (keyBytes.length != REQUIRED_KEY_BYTES) {
            throw new IllegalStateException(String.format(
                    "DLAP_ENCRYPTION_KEY must resolve to exactly %d bytes (got %d). "
                    + "Use `openssl rand -hex 32` (64 hex chars) or a 32-character key.",
                    REQUIRED_KEY_BYTES, keyBytes.length));
        }
        this.gcmKeySpec = new SecretKeySpec(keyBytes, ALGORITHM);

        // Legacy key: first 16 bytes of the 32-byte key, matching old AES-128 behaviour
        byte[] legacyKey = new byte[LEGACY_KEY_BYTES];
        System.arraycopy(keyBytes, 0, legacyKey, 0, LEGACY_KEY_BYTES);
        this.legacyEcbKeySpec = new SecretKeySpec(legacyKey, ALGORITHM);

        log.info("BasicEncryptionServiceImpl initialized with AES-256/GCM. "
                + "Legacy v1 (AES-128/ECB) decrypt path active for backwards compatibility.");
    }

    /**
     * Resolves the configured key string to raw bytes. A 64-hex-character value (the
     * documented {@code openssl rand -hex 32} output) is hex-decoded to 32 bytes; any other
     * value is used as raw UTF-8 bytes (backward compatibility). Callers validate the length.
     */
    static byte[] resolveKeyBytes(String key) {
        if (key.length() == 64 && key.matches("[0-9a-fA-F]{64}")) {
            return HexFormat.of().parseHex(key);
        }
        return key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String encrypt(String data) {
        if (data == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, gcmKeySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertextWithTag = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));

            return V2_PREFIX
                    + Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(ciphertextWithTag);
        } catch (Exception e) {
            throw new IllegalStateException("AES/GCM encryption failed", e);
        }
    }

    @Override
    public String decrypt(String encryptedData) {
        if (encryptedData == null) return null;
        try {
            if (encryptedData.startsWith(V2_PREFIX)) {
                return decryptGcm(encryptedData.substring(V2_PREFIX.length()));
            } else if (encryptedData.startsWith(V1_PREFIX)) {
                log.info("Decrypted legacy v1 (ECB) credential - will be upgraded to GCM on next write");
                return decryptEcb(encryptedData.substring(V1_PREFIX.length()));
            } else {
                // Pre-versioning legacy: raw Base64 without prefix
                log.info("Decrypted pre-versioning (ECB) credential - will be upgraded to GCM on next write");
                return decryptEcb(encryptedData);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Credential decryption failed", e);
        }
    }

    private String decryptGcm(String body) throws Exception {
        String[] parts = body.split(":");
        if (parts.length != 2) {
            throw new IllegalStateException(
                    "Malformed v2 envelope (expected iv:ciphertext, got " + parts.length + " parts)");
        }
        byte[] iv = Base64.getDecoder().decode(parts[0]);
        byte[] ciphertextWithTag = Base64.getDecoder().decode(parts[1]);

        Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, gcmKeySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plaintext = cipher.doFinal(ciphertextWithTag);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private String decryptEcb(String base64Ciphertext) throws Exception {
        Cipher cipher = Cipher.getInstance(ECB_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, legacyEcbKeySpec);
        byte[] plaintext = cipher.doFinal(Base64.getDecoder().decode(base64Ciphertext));
        return new String(plaintext, StandardCharsets.UTF_8);
    }
}
