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

package com.dlap.mcppep;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Test double of Keycloak for the SEC-07 tests: an in-process key-set endpoint on the loopback
 * interface and a signer, so the tests run the real {@code JwtDecoder} of the PEP (signature,
 * validity period, issuer, audience) against real signed tokens, with no Docker and no Keycloak.
 *
 * <p>The key set is started on first use and stays up for the life of the test JVM (daemon thread).
 */
final class JwtTestSupport {

    /** The issuer the tests configure as expected. */
    static final String ISSUER = "https://auth.sec07.test/realms/dlap";

    /** A second accepted issuer, to show the comma separated list. */
    static final String PUBLIC_ISSUER = "https://public.sec07.test/realms/dlap";

    /** The audience the tests configure as expected. */
    static final String AUDIENCE = "dlap-mcp-pep";

    private static final String KEY_ID = "sec07-key";

    private static RSAKey signingKey;
    private static HttpServer keySetServer;

    private JwtTestSupport() {
    }

    /** The address of the in-process key set, started on the first call. */
    static synchronized String jwkSetUri() {
        if (keySetServer == null) {
            try {
                signingKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
                byte[] body = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                keySetServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                keySetServer.createContext("/certs", exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
                keySetServer.setExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "sec07-jwks");
                    thread.setDaemon(true);
                    thread.start();
                });
                keySetServer.start();
            } catch (JOSEException e) {
                throw new IllegalStateException("cannot generate the test signing key", e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + keySetServer.getAddress().getPort() + "/certs";
    }

    /**
     * Registers the settings of the PEP's authentication: the key set, the expected issuers (two,
     * comma separated) and the expected audience.
     *
     * @param registry the registry of the test's {@code @DynamicPropertySource} method
     */
    static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JwtTestSupport::jwkSetUri);
        registry.add("dlap.security.jwt.expected-issuer", () -> ISSUER + "," + PUBLIC_ISSUER);
        registry.add("dlap.security.jwt.expected-audience", () -> AUDIENCE);
    }

    /** A token that the PEP accepts: right issuer, audience and signature, valid now, role SERVICE_ACCOUNT. */
    static String validToken() {
        return token().build();
    }

    /** Starts a token that the PEP accepts; each method changes one property. */
    static TokenBuilder token() {
        jwkSetUri();
        return new TokenBuilder();
    }

    /** Fluent builder of a signed token. */
    static final class TokenBuilder {

        private String issuer = ISSUER;
        private List<String> audience = List.of(AUDIENCE);
        private Instant expiresAt = Instant.now().plusSeconds(300);
        private Instant notBefore = Instant.now().minusSeconds(60);
        private List<String> realmRoles = List.of("SERVICE_ACCOUNT");
        private List<String> topLevelRoles;
        private RSAKey key = signingKey;
        private String subject = "sec07-caller";
        private final Map<String, Object> extraClaims = new java.util.LinkedHashMap<>();

        TokenBuilder issuer(String value) {
            this.issuer = value;
            return this;
        }

        TokenBuilder audience(String... values) {
            this.audience = values.length == 0 ? null : List.of(values);
            return this;
        }

        TokenBuilder expiresAt(Instant value) {
            this.expiresAt = value;
            return this;
        }

        TokenBuilder notBefore(Instant value) {
            this.notBefore = value;
            return this;
        }

        /** The {@code sub} claim, the identity PEP-02 binds a user or agent request to. */
        TokenBuilder subject(String value) {
            this.subject = value;
            return this;
        }

        /** Any further claim, for example the persona {@code tier}. */
        TokenBuilder claim(String name, Object value) {
            this.extraClaims.put(name, value);
            return this;
        }

        /** Roles in {@code realm_access.roles}, where Keycloak puts them. */
        TokenBuilder realmRoles(String... values) {
            this.realmRoles = List.of(values);
            return this;
        }

        /** Roles in a top-level {@code roles} claim, and none in {@code realm_access}. */
        TokenBuilder topLevelRoles(String... values) {
            this.realmRoles = null;
            this.topLevelRoles = List.of(values);
            return this;
        }

        /** Signs with a key the PEP does not know, under the same key id. */
        TokenBuilder signedByAnotherKey() {
            try {
                this.key = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
            return this;
        }

        String build() {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .jwtID(UUID.randomUUID().toString())
                    .issuer(issuer)
                    .subject(subject)
                    .issueTime(Date.from(Instant.now().minusSeconds(60)))
                    .notBeforeTime(Date.from(notBefore))
                    .expirationTime(Date.from(expiresAt));
            extraClaims.forEach(claims::claim);
            if (audience != null) {
                claims.audience(audience);
            }
            if (realmRoles != null) {
                claims.claim("realm_access", Map.of("roles", realmRoles));
            }
            if (topLevelRoles != null) {
                claims.claim("roles", topLevelRoles);
            }
            try {
                SignedJWT jwt = new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
                jwt.sign(new RSASSASigner(key));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException("cannot sign the test token", e);
            }
        }
    }
}
