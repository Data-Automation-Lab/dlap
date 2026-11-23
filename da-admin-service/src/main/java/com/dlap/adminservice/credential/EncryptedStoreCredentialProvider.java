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

import com.dlap.adminservice.service.IEncryptionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Default {@link CredentialProvider}: the sensitive credentials are held as an
 * AES-256/GCM-encrypted blob in DLAP's database and decrypted at use-time via
 * {@link IEncryptionService}. Self-contained; the right choice for dev / single-tenant.
 * Active unless {@code dlap.credentials.provider} selects another mode.
 */
@Component
@ConditionalOnProperty(name = "dlap.credentials.provider", havingValue = "encrypted-store", matchIfMissing = true)
public class EncryptedStoreCredentialProvider implements CredentialProvider {

    private final IEncryptionService encryptionService;

    public EncryptedStoreCredentialProvider(IEncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    @Override
    public Mode mode() {
        return Mode.ENCRYPTED_STORE;
    }

    @Override
    public Resolved resolve(Request request) {
        if (request == null || !StringUtils.hasText(request.encryptedCredentials())) {
            throw new IllegalStateException(
                    "No stored credentials for connection " + (request == null ? "?" : request.connectionId()));
        }
        return Resolved.ofSecret(Mode.ENCRYPTED_STORE, encryptionService.decrypt(request.encryptedCredentials()));
    }
}
