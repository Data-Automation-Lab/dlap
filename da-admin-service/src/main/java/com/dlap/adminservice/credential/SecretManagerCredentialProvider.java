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

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * {@link CredentialProvider} for {@link Mode#SECRET_MANAGER} - DLAP stores only a
 * reference ({@code connectionParameters.secretRef}); the secret itself lives in the
 * customer's manager and is fetched through a deployment-supplied {@link SecretResolver}.
 * DLAP never persists or logs the secret. Active when
 * {@code dlap.credentials.provider=secret-manager}; requires a {@link SecretResolver} bean.
 */
@Component
@ConditionalOnProperty(name = "dlap.credentials.provider", havingValue = "secret-manager")
public class SecretManagerCredentialProvider implements CredentialProvider {

    static final String SECRET_REF_PARAM = "secretRef";

    private final ObjectProvider<SecretResolver> secretResolver;

    public SecretManagerCredentialProvider(ObjectProvider<SecretResolver> secretResolver) {
        this.secretResolver = secretResolver;
    }

    @Override
    public Mode mode() {
        return Mode.SECRET_MANAGER;
    }

    @Override
    public Resolved resolve(Request request) {
        SecretResolver resolver = secretResolver.getIfAvailable();
        if (resolver == null) {
            throw new IllegalStateException(
                    "dlap.credentials.provider=secret-manager but no SecretResolver bean is configured. "
                    + "Supply one (Vault / AWS/GCP/Azure secret manager) - see docs/guides/credential-store.md");
        }
        String ref = request == null || request.connectionParameters() == null
                ? null : request.connectionParameters().get(SECRET_REF_PARAM);
        if (!StringUtils.hasText(ref)) {
            throw new IllegalStateException("Connection "
                    + (request == null ? "?" : request.connectionId())
                    + " has no '" + SECRET_REF_PARAM + "' connection parameter for secret-manager mode");
        }
        return Resolved.ofSecret(Mode.SECRET_MANAGER, resolver.resolve(ref));
    }
}
