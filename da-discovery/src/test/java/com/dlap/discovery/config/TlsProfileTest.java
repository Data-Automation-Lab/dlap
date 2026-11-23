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

package com.dlap.discovery.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ssl.SslAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Guards the opt-in {@code tls} profile ({@code config/application-tls.yml}).
 *
 * <p>The repository must ship no key material, and the profile must refuse to start unless the
 * operator supplies {@code DLAP_TLS_KEYSTORE_PATH} and {@code DLAP_TLS_KEYSTORE_PASSWORD}. The
 * profile file is loaded on its own (as the main config file) into a minimal servlet application,
 * so the test exercises exactly the {@code server.ssl.*} keys the profile sets without needing the
 * database, Kafka or the rest of the discovery service.
 */
class TlsProfileTest {

    private static final String PATH_VAR = "DLAP_TLS_KEYSTORE_PATH";
    private static final String PASSWORD_VAR = "DLAP_TLS_KEYSTORE_PASSWORD";
    private static final List<String> KEY_MATERIAL_SUFFIXES =
            List.of(".p12", ".pfx", ".jks", ".keystore", ".truststore", ".pem", ".key");

    /** Minimal servlet web application: only the embedded server and the SSL bundles. */
    @Configuration(proxyBeanMethods = false)
    @Import({TomcatServletWebServerAutoConfiguration.class, SslAutoConfiguration.class})
    static class ProbeApplication {}

    @Test
    void repositoryShipsNoKeyMaterialInMainResources() throws Exception {
        Path resources = Path.of("src", "main", "resources");
        assertThat(resources).isDirectory();
        try (Stream<Path> files = Files.walk(resources)) {
            List<Path> keyFiles =
                    files
                            .filter(Files::isRegularFile)
                            .filter(
                                    p -> {
                                        String name = p.getFileName().toString().toLowerCase();
                                        return KEY_MATERIAL_SUFFIXES.stream().anyMatch(name::endsWith);
                                    })
                            .toList();
            assertThat(keyFiles).as("key material under src/main/resources").isEmpty();
        }
    }

    @Test
    void tlsProfileHasNoDefaultKeystoreOnTheClasspath() {
        assertThat(TlsProfileTest.class.getResource("/config/tls/keystore.p12")).isNull();
        assertThat(TlsProfileTest.class.getResource("/config/application-tls.yml")).isNotNull();
    }

    @Test
    void tlsProfileFailsFastWithoutKeystorePath() {
        assumeThat(System.getenv(PATH_VAR)).isNull();

        // An unset path stays the literal "${DLAP_TLS_KEYSTORE_PATH}" text, so the embedded server
        // aborts startup while loading the keystore and the failure names the missing variable.
        assertThatThrownBy(() -> start("--" + PASSWORD_VAR + "=" + UUID.randomUUID()))
                .isInstanceOf(ApplicationContextException.class)
                .hasStackTraceContaining(PATH_VAR);
    }

    @Test
    void tlsProfileFailsFastWithoutKeystorePassword(@TempDir Path dir) throws Exception {
        assumeThat(System.getenv(PASSWORD_VAR)).isNull();
        String password = UUID.randomUUID().toString();
        Path keystore = generateKeystore(dir, password);

        // An unset password stays the literal "${DLAP_TLS_KEYSTORE_PASSWORD}" text, which cannot open
        // the keystore, so the embedded server aborts startup while loading it. The failure names the
        // operator-supplied keystore, which proves the new variable (not a classpath default) was read.
        assertThatThrownBy(() -> start("--" + PATH_VAR + "=" + keystore))
                .isInstanceOf(ApplicationContextException.class)
                .hasStackTraceContaining("Could not load store")
                .hasStackTraceContaining(keystore.getFileName().toString());
    }

    @Test
    void tlsProfileServesHttpsWithOperatorSuppliedKeystore(@TempDir Path dir) throws Exception {
        String password = UUID.randomUUID().toString();
        Path keystore = generateKeystore(dir, password);

        try (ConfigurableApplicationContext context =
                start("--" + PATH_VAR + "=" + keystore, "--" + PASSWORD_VAR + "=" + password)) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            HttpClient client = HttpClient.newBuilder().sslContext(trustOnly(keystore, password)).build();

            HttpResponse<String> response =
                    client.send(
                            HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/")).build(),
                            HttpResponse.BodyHandlers.ofString());

            // The probe registers no servlet, so the answer is a 404. Only the TLS handshake matters.
            assertThat(response.sslSession()).isPresent();
            assertThat(response.sslSession().get().getProtocol()).isEqualTo("TLSv1.2");
        }
    }

    private static ConfigurableApplicationContext start(String... operatorArgs) {
        SpringApplication application = new SpringApplication(ProbeApplication.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.setRegisterShutdownHook(false);

        String[] fixed = {
            "--spring.config.name=application-tls",
            "--spring.cloud.bootstrap.enabled=false",
            "--spring.cloud.gcp.sql.enabled=false",
            "--spring.main.banner-mode=off",
            "--server.port=0",
        };
        String[] args = Stream.concat(Stream.of(fixed), Stream.of(operatorArgs)).toArray(String[]::new);
        return application.run(args);
    }

    /** Generates a throwaway PKCS12 keystore with the JDK keytool, as an operator would. */
    private static Path generateKeystore(Path dir, String password) throws Exception {
        Path keystore = dir.resolve("operator-supplied.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process process =
                new ProcessBuilder(
                                keytool,
                                "-genkeypair",
                                "-alias",
                                "operator",
                                "-keyalg",
                                "RSA",
                                "-keysize",
                                "2048",
                                "-validity",
                                "1",
                                "-dname",
                                "CN=localhost",
                                "-ext",
                                "SAN=dns:localhost",
                                "-storetype",
                                "PKCS12",
                                "-keystore",
                                keystore.toString(),
                                "-storepass",
                                password)
                        .redirectErrorStream(true)
                        .start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("keytool finished").isTrue();
        assertThat(process.exitValue()).as("keytool exit code, output: " + output).isZero();
        return keystore;
    }

    private static SSLContext trustOnly(Path keystore, String password) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            trust.load(in, password.toCharArray());
        }
        TrustManagerFactory factory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return context;
    }
}
