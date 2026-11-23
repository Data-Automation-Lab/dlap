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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link BasicEncryptionServiceImpl} without a Spring context
 * (wires the 32-byte key and invokes package-private {@code init()} via reflection).
 */
class BasicEncryptionServiceImplTest {

    private static final String TEST_KEY_32 = "abcdefghijklmnopqrstuvwxyz123456";

    private BasicEncryptionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BasicEncryptionServiceImpl();
        ReflectionTestUtils.setField(service, "encryptionKeyString", TEST_KEY_32);
        ReflectionTestUtils.invokeMethod(service, "init");
    }

    @Test
    void encryptDecrypt_roundTrip_v2() {
        String plain = "secret-connection-string";
        String enc = service.encrypt(plain);
        assertThat(enc).startsWith("v2:");
        assertThat(service.decrypt(enc)).isEqualTo(plain);
    }

    @Test
    void encrypt_usesUniqueIv_eachCall() {
        Set<String> envelopes = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            envelopes.add(service.encrypt("same-plaintext"));
        }
        assertThat(envelopes).hasSize(20);
    }

    @Test
    void decrypt_v1_ecb_envelope() throws Exception {
        byte[] legacyKey = new byte[16];
        System.arraycopy(TEST_KEY_32.getBytes(StandardCharsets.UTF_8), 0, legacyKey, 0, 16);
        SecretKeySpec keySpec = new SecretKeySpec(legacyKey, "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        byte[] ct = cipher.doFinal("legacy-secret".getBytes(StandardCharsets.UTF_8));
        String v1 = "v1:" + Base64.getEncoder().encodeToString(ct);

        assertThat(service.decrypt(v1)).isEqualTo("legacy-secret");
    }

    @Test
    void decrypt_rawBase64_legacy_ecb() throws Exception {
        byte[] legacyKey = new byte[16];
        System.arraycopy(TEST_KEY_32.getBytes(StandardCharsets.UTF_8), 0, legacyKey, 0, 16);
        SecretKeySpec keySpec = new SecretKeySpec(legacyKey, "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        byte[] ct = cipher.doFinal("noprefix".getBytes(StandardCharsets.UTF_8));
        String raw = Base64.getEncoder().encodeToString(ct);

        assertThat(service.decrypt(raw)).isEqualTo("noprefix");
    }

    @Test
    void decrypt_tamperedV2_throws() {
        String enc = service.encrypt("data");
        String body = enc.substring("v2:".length());
        String[] parts = body.split(":", 2);
        byte[] ct = Base64.getDecoder().decode(parts[1]);
        ct[ct.length - 1] ^= (byte) 0xFF;
        String tampered = "v2:" + parts[0] + ":" + Base64.getEncoder().encodeToString(ct);

        assertThatThrownBy(() -> service.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decryption");
    }

    @Test
    void init_rejectsWrongKeyLength() {
        BasicEncryptionServiceImpl bad = new BasicEncryptionServiceImpl();
        ReflectionTestUtils.setField(bad, "encryptionKeyString", "short");
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(bad, "init"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resolveKeyBytes_hexAndRaw_bothYield32Bytes() {
        // 64 hex chars (openssl rand -hex 32) -> 32 bytes.
        String hex64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertThat(BasicEncryptionServiceImpl.resolveKeyBytes(hex64)).hasSize(32);
        // 32 raw chars -> 32 UTF-8 bytes (backward compatibility).
        assertThat(BasicEncryptionServiceImpl.resolveKeyBytes(TEST_KEY_32)).hasSize(32);
    }

    @Test
    void hexKeyFromOpensslRandHex32_initsAndRoundTrips() {
        // Regression: the documented `openssl rand -hex 32` output is 64 hex chars. It must
        // init (hex-decoded to 32 bytes) and round-trip - previously it failed startup
        // because the 64-char string was measured as 64 raw bytes.
        String hexKey = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
        BasicEncryptionServiceImpl svc = new BasicEncryptionServiceImpl();
        ReflectionTestUtils.setField(svc, "encryptionKeyString", hexKey);
        ReflectionTestUtils.invokeMethod(svc, "init");

        String enc = svc.encrypt("secret-connection-string");
        assertThat(enc).startsWith("v2:");
        assertThat(svc.decrypt(enc)).isEqualTo("secret-connection-string");
    }
}
