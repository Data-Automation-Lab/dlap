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

package com.dlap.autoarchival.providers.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.core.http.HttpPipeline;
import com.azure.core.http.policy.BearerTokenAuthenticationPolicy;
import com.azure.identity.DefaultAzureCredential;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.common.policy.StorageSharedKeyCredentialPolicy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * EXEC-01 (Q-724): da-autoarchival reaches Azure through {@code DefaultAzureCredential} and an account endpoint.
 * The connection string is only a deprecated fallback (the prod profile refuses it, see
 * {@code EmulatorEndpointGuardTest}). Building a client calls nothing on Azure.
 */
class AzureBlobClientsTest {

    private static final String CONNECTION_STRING = "DefaultEndpointsProtocol=https;AccountName=legacyacct;"
            + "AccountKey=Zm9vYmFy;EndpointSuffix=core.windows.net";

    private static List<Class<?>> policies(BlobServiceClient client) {
        HttpPipeline pipeline = client.getHttpPipeline();
        List<Class<?>> classes = new ArrayList<>();
        for (int i = 0; i < pipeline.getPolicyCount(); i++) {
            classes.add(pipeline.getPolicy(i).getClass());
        }
        return classes;
    }

    /** Q-724: with an account endpoint the client signs in with DefaultAzureCredential and a bearer token. */
    @Test
    void theAccountEndpointIsReachedWithDefaultAzureCredential() {
        AzureBlobClients clients = new AzureBlobClients("https://myaccount.blob.core.windows.net", "", "");

        BlobServiceClient client = clients.serviceClient("ignored");

        assertThat(clients.credential()).isInstanceOf(DefaultAzureCredential.class);
        assertThat(client.getAccountUrl()).isEqualTo("https://myaccount.blob.core.windows.net");
        assertThat(policies(client)).contains(BearerTokenAuthenticationPolicy.class)
                .doesNotContain(StorageSharedKeyCredentialPolicy.class);
        assertThat(clients.serviceClient("other")).as("one client per endpoint").isSameAs(client);
        assertThat(clients.configured()).isTrue();
        assertThat(clients.usesConnectionString()).isFalse();
    }

    /** Q-724: with nothing configured the account named by the location gives the endpoint. */
    @Test
    void withNoEndpointTheAccountOfTheLocationGivesTheBlobEndpoint() {
        AzureBlobClients clients = new AzureBlobClients("", "", "");

        BlobServiceClient client = clients.serviceClient("MyAccount");

        assertThat(client.getAccountUrl()).isEqualTo("https://myaccount.blob.core.windows.net");
        assertThat(policies(client)).contains(BearerTokenAuthenticationPolicy.class);
        assertThat(clients.configured()).as("a listing needs an explicit endpoint").isFalse();
    }

    /** Q-724: an account name that cannot be an Azure storage account never builds an endpoint. */
    @Test
    void anAccountThatIsNoStorageAccountNameIsRefused() {
        AzureBlobClients clients = new AzureBlobClients("", "", "");

        for (String bad : new String[] {null, "", "ab", "has.dot", "evil.example.com/x", "UPPER_CASE", "a".repeat(25)}) {
            assertThatThrownBy(() -> clients.serviceClient(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** Q-724: the endpoint settings must be https URIs; the message names the property, never the value. */
    @Test
    void anEndpointThatIsNotHttpsIsRefusedWithoutEchoingIt() {
        assertThatThrownBy(() -> new AzureBlobClients("http://plain-host.example", "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dlap.autoarchival.azure.account-endpoint").hasMessageContaining("https")
                .hasMessageNotContaining("plain-host");
        assertThatThrownBy(() -> new AzureBlobClients("", "", "http://stub-host:10000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dlap.autoarchival.test-endpoint.azure").hasMessageNotContaining("stub-host");
        assertThatThrownBy(() -> new AzureBlobClients("not a uri", "", "")).isInstanceOf(IllegalArgumentException.class);
    }

    /** Q-724: the connection string still works as a deprecated fallback, with a shared-key policy and no bearer. */
    @Test
    void theConnectionStringIsOnlyAFallbackBehindTheAccountEndpoint() {
        AzureBlobClients legacy = new AzureBlobClients("", CONNECTION_STRING, "");

        BlobServiceClient viaString = legacy.serviceClient("legacyacct");

        assertThat(legacy.usesConnectionString()).isTrue();
        assertThat(viaString.getAccountUrl()).isEqualTo("https://legacyacct.blob.core.windows.net");
        assertThat(policies(viaString)).contains(StorageSharedKeyCredentialPolicy.class)
                .doesNotContain(BearerTokenAuthenticationPolicy.class);

        AzureBlobClients both = new AzureBlobClients("https://modern.blob.core.windows.net", CONNECTION_STRING, "");
        assertThat(both.usesConnectionString()).isFalse();
        assertThat(both.serviceClient("legacyacct").getAccountUrl()).isEqualTo("https://modern.blob.core.windows.net");
    }

    /** Q-740: the test endpoint wins and signs with the placeholder token, never with DefaultAzureCredential. */
    @Test
    void theTestEndpointSignsWithAPlaceholderTokenOverHttps() {
        AzureBlobClients clients = new AzureBlobClients("https://modern.blob.core.windows.net", "",
                "https://stub.example:10000/devstoreaccount1");

        BlobServiceClient client = clients.serviceClient("ignored");

        assertThat(client.getAccountUrl()).startsWith("https://stub.example:10000");
        assertThat(policies(client)).contains(BearerTokenAuthenticationPolicy.class);
    }

    /** Q-724: Spring wires the clients from the three properties. */
    @Test
    void springBindsTheSettings() {
        new ApplicationContextRunner().withUserConfiguration(AzureBlobClients.class)
                .withPropertyValues("dlap.autoarchival.azure.account-endpoint=https://spring.blob.core.windows.net")
                .run(ctx -> assertThat(ctx.getBean(AzureBlobClients.class).serviceClient("x").getAccountUrl())
                        .isEqualTo("https://spring.blob.core.windows.net"));
        new ApplicationContextRunner().withUserConfiguration(AzureBlobClients.class)
                .withPropertyValues("dlap.autoarchival.azure.account-endpoint=http://spring.example")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
