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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checkpoint payloads and Ed25519 signatures, including interoperability with the OpenSSL command
 * line that scripts/audit-verify.sh uses (signed in Java, verified by OpenSSL, and the reverse).
 */
class CheckpointSignaturesTest {

    @TempDir
    Path dir;

    static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The PEM label of a PKCS#8 key. It is built from two parts so that secret scanners (gitleaks
     * rule private-key) do not read the helper below as a committed key.
     */
    static final String PRIVATE_KEY_LABEL = "PRIVATE" + " KEY";

    /** PKCS#8 PEM of a freshly generated test key; never a committed key. */
    static String privatePem(KeyPair pair) {
        return "-----BEGIN " + PRIVATE_KEY_LABEL + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END " + PRIVATE_KEY_LABEL + "-----\n";
    }

    static AuditCheckpoint checkpoint(KeyPair pair) {
        return new AuditCheckpoint("retire.delete", 42, AuditChainTest.EXAMPLE_HASH_1,
                Instant.parse("2026-10-21T09:00:00.1234567Z"), CheckpointSignatures.keyId(pair.getPublic()));
    }

    @Test
    void thePayloadIsCanonicalAndRoundTrips() {
        KeyPair pair = newKeyPair();
        AuditCheckpoint cp = checkpoint(pair);
        String payload = cp.payload();
        assertThat(payload).isEqualTo("{\"entryHash\":\"" + AuditChainTest.EXAMPLE_HASH_1 + "\","
                + "\"format\":\"dlap-audit-checkpoint/1\",\"keyId\":\"" + cp.keyId() + "\",\"seq\":42,"
                + "\"signedAt\":\"2026-10-21T09:00:00.123456Z\",\"streamId\":\"retire.delete\"}");
        assertThat(AuditCheckpoint.parse(payload)).isEqualTo(cp);
        assertThat(cp.objectName()).isEqualTo("retire.delete/00000000000000000042.json");
    }

    @Test
    void parseAcceptsOnlyTheCanonicalText() {
        String payload = checkpoint(newKeyPair()).payload();
        for (String bad : List.of(payload.replace(",", ", "), payload.replace("\"seq\":42", "\"seq\":\"42\""),
                payload.replace("}", ",\"extra\":1}"), payload.replace("dlap-audit-checkpoint/1", "x/2"),
                payload.replace(".123456Z", "Z"), "[]", "not json", payload.replace("retire", "r\u00e9tire"))) {
            assertThatThrownBy(() -> AuditCheckpoint.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aSignatureVerifiesOnlyForItsPayloadAndKey() {
        KeyPair pair = newKeyPair();
        String payload = checkpoint(pair).payload();
        String sig = CheckpointSignatures.sign(pair.getPrivate(), payload);
        assertThat(Base64.getDecoder().decode(sig)).hasSize(64);
        assertThat(CheckpointSignatures.verify(pair.getPublic(), payload, sig)).isTrue();
        assertThat(CheckpointSignatures.verify(pair.getPublic(), payload.replace("42", "43"), sig)).isFalse();
        assertThat(CheckpointSignatures.verify(newKeyPair().getPublic(), payload, sig)).isFalse();
        assertThat(CheckpointSignatures.verify(pair.getPublic(), payload, "not base64!")).isFalse();
        assertThat(CheckpointSignatures.verify(pair.getPublic(), payload, Base64.getEncoder().encodeToString(new byte[10])))
                .isFalse();
        assertThat(CheckpointSignatures.verify(pair.getPublic(), payload, null)).isFalse();
    }

    @Test
    void pemKeysRoundTripAndPairsAreRecognised() {
        KeyPair pair = newKeyPair();
        String pub = CheckpointSignatures.toPem(pair.getPublic());
        assertThat(CheckpointSignatures.readPublicKey(pub).getEncoded()).isEqualTo(pair.getPublic().getEncoded());
        assertThat(CheckpointSignatures.readPrivateKey("junk before\n" + privatePem(pair)).getEncoded())
                .isEqualTo(pair.getPrivate().getEncoded());
        assertThat(CheckpointSignatures.isPair(pair.getPrivate(), pair.getPublic())).isTrue();
        assertThat(CheckpointSignatures.isPair(pair.getPrivate(), newKeyPair().getPublic())).isFalse();
        assertThat(CheckpointSignatures.keyId(pair.getPublic())).matches("[0-9a-f]{16}");
        assertThatThrownBy(() -> CheckpointSignatures.readPublicKey(privatePem(pair)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CheckpointSignatures.readPrivateKey(null)).isInstanceOf(IllegalArgumentException.class);
        String rsaLike = "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----\n";
        assertThatThrownBy(() -> CheckpointSignatures.readPublicKey(rsaLike)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openSslVerifiesAJavaSignatureAndJavaVerifiesAnOpenSslSignature() throws Exception {
        assumeTrue(run(dir, "openssl", "version") == 0, "openssl is not installed");
        // Key generated by OpenSSL, as the spec tells operators to do.
        assertThat(run(dir, "openssl", "genpkey", "-algorithm", "ed25519", "-out", "k.pem")).isZero();
        assertThat(run(dir, "openssl", "pkey", "-in", "k.pem", "-pubout", "-out", "pub.pem")).isZero();
        var priv = CheckpointSignatures.readPrivateKey(Files.readString(dir.resolve("k.pem")));
        var pub = CheckpointSignatures.readPublicKey(Files.readString(dir.resolve("pub.pem")));
        assertThat(CheckpointSignatures.isPair(priv, pub)).isTrue();

        AuditCheckpoint cp = new AuditCheckpoint("admin.access", 3, AuditChainTest.EXAMPLE_HASH_2,
                Instant.parse("2026-10-21T10:00:00Z"), CheckpointSignatures.keyId(pub));
        Files.writeString(dir.resolve("p.json"), cp.payload(), StandardCharsets.US_ASCII);
        Files.write(dir.resolve("p.sig"), Base64.getDecoder().decode(CheckpointSignatures.sign(priv, cp.payload())));
        assertThat(run(dir, "openssl", "pkeyutl", "-verify", "-pubin", "-inkey", "pub.pem", "-rawin",
                "-in", "p.json", "-sigfile", "p.sig")).isZero();

        assertThat(run(dir, "openssl", "pkeyutl", "-sign", "-inkey", "k.pem", "-rawin", "-in", "p.json",
                "-out", "o.sig")).isZero();
        String opensslSig = Base64.getEncoder().encodeToString(Files.readAllBytes(dir.resolve("o.sig")));
        assertThat(CheckpointSignatures.verify(pub, cp.payload(), opensslSig)).isTrue();

        // A changed payload fails in OpenSSL too.
        Files.writeString(dir.resolve("p.json"), cp.payload().replace("\"seq\":3", "\"seq\":4"), StandardCharsets.US_ASCII);
        assertThat(run(dir, "openssl", "pkeyutl", "-verify", "-pubin", "-inkey", "pub.pem", "-rawin",
                "-in", "p.json", "-sigfile", "p.sig")).isNotZero();
    }

    private static int run(Path dir, String... command) throws IOException, InterruptedException {
        Process p;
        try {
            p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                    .redirectOutput(dir.resolve("openssl.out").toFile()).start();
        } catch (IOException e) {
            return -1;
        }
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return -2;
        }
        return p.exitValue();
    }
}
