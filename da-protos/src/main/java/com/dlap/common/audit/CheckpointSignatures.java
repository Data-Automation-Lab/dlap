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

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ed25519 signatures over checkpoint payloads (RFC 8032; docs/specs/audit-log.md, section
 * "Checkpoints"). JDK only: the {@code Ed25519} algorithm of the SunEC provider, no extra
 * dependency.
 *
 * <p>Keys are PEM: a PKCS#8 {@code PRIVATE KEY} and an X.509 {@code PUBLIC KEY}, the forms that
 * {@code openssl genpkey -algorithm ed25519} and {@code openssl pkey -pubout} write. A signature
 * travels as standard Base64 of its 64 bytes. The key id is the first 16 hex characters of the
 * SHA-256 of the public key's DER encoding; it names a key, it does not authenticate one.
 */
public final class CheckpointSignatures {

    /** The JCA algorithm name. */
    public static final String ALGORITHM = "Ed25519";

    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN ([A-Z ]+)-----\\s*([A-Za-z0-9+/=\\s]+?)\\s*-----END \\1-----");

    private CheckpointSignatures() {
    }

    /**
     * Reads a PKCS#8 Ed25519 private key.
     *
     * @param pem the PEM text with a {@code PRIVATE KEY} block
     * @return the key
     * @throws IllegalArgumentException when the text holds no Ed25519 private key
     */
    public static PrivateKey readPrivateKey(String pem) {
        byte[] der = pemBody(pem, "PRIVATE KEY");
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not an Ed25519 PKCS#8 private key", e);
        }
    }

    /**
     * Reads an X.509 Ed25519 public key.
     *
     * @param pem the PEM text with a {@code PUBLIC KEY} block
     * @return the key
     * @throws IllegalArgumentException when the text holds no Ed25519 public key
     */
    public static PublicKey readPublicKey(String pem) {
        byte[] der = pemBody(pem, "PUBLIC KEY");
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not an Ed25519 X.509 public key", e);
        }
    }

    /**
     * Writes a public key as PEM.
     *
     * @param key the key
     * @return the {@code PUBLIC KEY} PEM text, LF line ends, 64 characters per line
     */
    public static String toPem(PublicKey key) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(key.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }

    /**
     * The key id of a public key.
     *
     * @param key the key
     * @return 16 lower-case hex characters
     */
    public static String keyId(PublicKey key) {
        return HexFormat.of().formatHex(AuditChain.sha256(key.getEncoded())).substring(0, 16);
    }

    /**
     * Signs a payload.
     *
     * @param key     the private key
     * @param payload the payload text (printable ASCII)
     * @return the signature, standard Base64
     */
    public static String sign(PrivateKey key, String payload) {
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initSign(key);
            s.update(payload.getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    /**
     * Verifies a signature.
     *
     * @param key       the public key
     * @param payload   the payload text
     * @param signature the signature, standard Base64
     * @return true when the signature is valid for the payload and key
     */
    public static boolean verify(PublicKey key, String payload, String signature) {
        byte[] sig;
        try {
            sig = Base64.getDecoder().decode(signature == null ? "" : signature.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (sig.length != 64) {
            return false;
        }
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(key);
            s.update(payload.getBytes(StandardCharsets.US_ASCII));
            return s.verify(sig);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * Whether a private and a public key belong together: a probe signed with the first verifies
     * with the second.
     *
     * @param privateKey the private key
     * @param publicKey  the public key
     * @return true for a pair
     */
    public static boolean isPair(PrivateKey privateKey, PublicKey publicKey) {
        String probe = "dlap-audit-key-probe";
        return verify(publicKey, probe, sign(privateKey, probe));
    }

    private static byte[] pemBody(String pem, String label) {
        if (pem == null) {
            throw new IllegalArgumentException("no PEM text");
        }
        Matcher m = PEM.matcher(pem);
        while (m.find()) {
            if (label.equals(m.group(1))) {
                return Base64.getMimeDecoder().decode(m.group(2));
            }
        }
        throw new IllegalArgumentException("no " + label + " PEM block");
    }
}
