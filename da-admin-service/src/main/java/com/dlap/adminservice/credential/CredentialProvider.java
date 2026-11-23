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

package com.dlap.adminservice.credential;

import java.util.Map;

/**
 * SPI for resolving a cloud connection's sensitive credentials at use-time (ADR-0036).
 * DLAP defines the contract, "what we need to authenticate to a provider", and a
 * deployment picks how those credentials are held via {@code dlap.credentials.provider}:
 *
 * <ul>
 *   <li>{@link Mode#ENCRYPTED_STORE} (default) - an AES-256/GCM-encrypted blob in DLAP's
 *       own database. Self-contained; suitable for dev/single-tenant.</li>
 *   <li>{@link Mode#SECRET_MANAGER} - DLAP stores only a <i>reference</i>
 *       ({@code connectionParameters.secretRef}); the secret lives in the customer's manager
 *       (Vault, AWS/GCP/Azure secret managers), resolved through a user-supplied
 *       {@link SecretResolver}. DLAP never persists the secret.</li>
 *   <li>{@link Mode#FEDERATED_IDENTITY} (recommended for prod) - no stored secret at all;
 *       DLAP uses the runtime's ambient identity (AWS STS AssumeRole, GCP Workload Identity,
 *       Azure federated credentials) resolved by the provider SDK.</li>
 * </ul>
 *
 * <p>Cloud-neutral: this interface and the encrypted-store default live in the service;
 * provider-specific resolvers (Vault client, STS) are supplied by the deployment as beans,
 * never bundled in {@code core}/{@code common} (CLAUDE.md §5/§9). See
 * {@code docs/guides/credential-store.md}.
 */
public interface CredentialProvider {

    /** The mode this provider implements. */
    Mode mode();

    /**
     * Resolve credentials for one connection. Implementations must never log the secret
     * material and should fail closed (throw) rather than return partial credentials.
     */
    Resolved resolve(Request request);

    enum Mode {
        ENCRYPTED_STORE,
        SECRET_MANAGER,
        FEDERATED_IDENTITY
    }

    /**
     * What DLAP knows about a connection when it needs credentials. Non-secret
     * {@code connectionParameters} (region, endpoint, {@code secretRef}, role ARN, …) plus
     * the encrypted blob (only used by {@link Mode#ENCRYPTED_STORE}).
     */
    record Request(String connectionId,
                   String providerType,
                   Map<String, String> connectionParameters,
                   String encryptedCredentials) {
    }

    /**
     * The resolved outcome: either concrete {@code secretMaterial} (encrypted-store /
     * secret-manager) or a federated marker (the caller uses the ambient identity).
     */
    record Resolved(Mode mode, String secretMaterial, String note) {

        public boolean isFederated() {
            return mode == Mode.FEDERATED_IDENTITY;
        }

        public boolean hasSecret() {
            return secretMaterial != null && !secretMaterial.isBlank();
        }

        public static Resolved ofSecret(Mode mode, String secretMaterial) {
            return new Resolved(mode, secretMaterial, null);
        }

        public static Resolved federated(String note) {
            return new Resolved(Mode.FEDERATED_IDENTITY, null, note);
        }
    }
}
