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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.dlap.adminservice.service.IEncryptionService;
import org.junit.jupiter.api.Test;

/** Unit tests for the {@link CredentialProvider} default modes (plain JUnit, no Mockito). */
class CredentialProviderTest {

    /** Hand-rolled reversible fake: decrypt strips an "enc:" prefix the fake encrypt adds. */
    private final IEncryptionService fakeEnc = new IEncryptionService() {
        @Override public String encrypt(String data) { return data == null ? null : "enc:" + data; }
        @Override public String decrypt(String data) {
            return data != null && data.startsWith("enc:") ? data.substring(4) : data;
        }
    };

    @Test
    void encryptedStore_decryptsStoredBlob() {
        var provider = new EncryptedStoreCredentialProvider(fakeEnc);
        var req = new CredentialProvider.Request("c1", "AWS", Map.of(), "enc:{\"key\":\"AKIA\"}");

        CredentialProvider.Resolved r = provider.resolve(req);

        assertThat(provider.mode()).isEqualTo(CredentialProvider.Mode.ENCRYPTED_STORE);
        assertThat(r.hasSecret()).isTrue();
        assertThat(r.isFederated()).isFalse();
        assertThat(r.secretMaterial()).isEqualTo("{\"key\":\"AKIA\"}");
    }

    @Test
    void encryptedStore_failsClosedWhenNoStoredBlob() {
        var provider = new EncryptedStoreCredentialProvider(fakeEnc);
        var req = new CredentialProvider.Request("c1", "AWS", Map.of(), null);

        assertThatThrownBy(() -> provider.resolve(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No stored credentials");
    }

    @Test
    void federated_returnsMarkerWithNoSecret() {
        var provider = new FederatedIdentityCredentialProvider();
        var req = new CredentialProvider.Request("c1", "GCP", Map.of("audience", "//iam..."), null);

        CredentialProvider.Resolved r = provider.resolve(req);

        assertThat(r.isFederated()).isTrue();
        assertThat(r.hasSecret()).isFalse();
        assertThat(r.secretMaterial()).isNull();
    }
}
