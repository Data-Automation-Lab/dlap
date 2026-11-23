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

package com.dlap.autoarchival.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.dlap.autoarchival.approval.ApprovalRefusedException.Reason;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * The pilot TLS overlay (SEC-06) gives the policy engine a certificate of the pilot CA and gives every
 * service a JVM trust store that holds that CA ({@code JDK_JAVA_OPTIONS=-Djavax.net.ssl.trustStore=...}).
 * {@link PolicyEngineApprovalClient} must therefore take its trust from the JVM default {@code SSLContext}
 * and from nothing else (WIRE-01, Q-604). Against a loopback HTTPS stand-in for the policy engine whose
 * certificate is self-signed for {@code localhost}:
 * <ul>
 *   <li>a JVM whose default context trusts that certificate reads the approval;</li>
 *   <li>a JVM whose default context does not, refuses with {@code VERIFY_UNAVAILABLE} and names the
 *       handshake failure (the executor then fails closed and acts on nothing).</li>
 * </ul>
 * The certificate is made with the JDK's own {@code keytool}.
 */
class PolicyEngineApprovalClientTlsTest {

    private static final String ID = "0d4f9c40-1a2b-3c4d-8e5f-000000000002";
    private static final String ASSET = "11111111-2222-3333-4444-555555555555";
    private static final char[] PASSWORD = "changeit".toCharArray();

    @TempDir
    Path dir;

    private HttpsServer server;
    private SSLContext original;
    private SSLContext trusting;
    private int port;

    @BeforeEach
    void start() throws Exception {
        original = SSLContext.getDefault();
        Path keystore = dir.resolve("policyengine.p12");
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "policyengine", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=localhost", "-ext", "san=dns:localhost,ip:127.0.0.1", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass", new String(PASSWORD))
                .redirectErrorStream(true).start();
        String output;
        try (InputStream in = p.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).as("keytool: " + output).isTrue();
        assertThat(p.exitValue()).as("keytool: " + output).isZero();

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, PASSWORD);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, PASSWORD);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);
        trusting = SSLContext.getInstance("TLS");
        trusting.init(null, tmf.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/", ex -> {
            byte[] out = ("{\"id\":\"" + ID + "\",\"status\":\"APPROVED\",\"executable\":true,"
                    + "\"actionClass\":\"ARCHIVE\",\"assetId\":\"" + ASSET + "\",\"requiredApprovals\":1,"
                    + "\"decisions\":[{\"approverSub\":\"alice-sub\",\"decision\":\"APPROVE\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        SSLContext.setDefault(original);
        server.stop(0);
    }

    private PolicyEngineApprovalClient client(String baseUrl) {
        // The HttpClient is built in the constructor, from whatever the JVM default context is at that moment.
        return new PolicyEngineApprovalClient(baseUrl, () -> "tok-123", Duration.ofSeconds(5));
    }

    @Test
    void aJvmThatTrustsThePolicyEnginesCertificateReadsTheApprovalOverHttps() {
        SSLContext.setDefault(trusting);

        ArchiveApproval a = client("https://localhost:" + port).fetchExecutable(ID, ASSET);

        assertThat(a.approvalId()).isEqualTo(ID);
        assertThat(a.status()).isEqualTo("APPROVED");
        assertThat(a.approvers()).containsExactly("alice-sub");
    }

    @Test
    void aJvmThatDoesNotTrustItRefusesAndNamesTheHandshakeFailure() {
        // The default context of this JVM holds the public roots only, as a service without the pilot trust store does.
        PolicyEngineApprovalClient untrusting = client("https://localhost:" + port);

        assertThatThrownBy(() -> untrusting.fetchExecutable(ID, ASSET))
                .isInstanceOf(ApprovalRefusedException.class)
                .satisfies(e -> {
                    ApprovalRefusedException refused = (ApprovalRefusedException) e;
                    assertThat(refused.reason()).isEqualTo(Reason.VERIFY_UNAVAILABLE);
                    assertThat(refused.isTransient()).isTrue();
                    assertThat(refused.getMessage()).contains("SSLHandshakeException");
                });
    }
}
