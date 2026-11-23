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

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.dlap.autoarchival.config.TestEndpointProperties;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The Azure Blob data-plane clients of the archive executor (EXEC-01, Q-724).
 *
 * <p>The executor signs in with {@code DefaultAzureCredential} (managed identity on a VM, workload identity on AKS,
 * environment or developer sign-in), so the archive Actor role and the permission self-check cover what it does.
 * The blob endpoint is, in this order:
 * <ol>
 *   <li>{@code dlap.autoarchival.test-endpoint.azure}: TEST ONLY, an https blob endpoint of an emulator or stub,
 *       with a fixed placeholder token; refused under the prod profile;</li>
 *   <li>{@code dlap.autoarchival.azure.account-endpoint}: the account's blob endpoint, for example
 *       {@code https://myaccount.blob.core.windows.net};</li>
 *   <li>{@code dlap.autoarchival.azure.connection-string}: DEPRECATED fallback that Azure RBAC does not govern and
 *       that may hold an account key; logged as a WARN and refused under the prod profile;</li>
 *   <li>otherwise {@code https://<account>.blob.core.windows.net} for the account the asset's location names.</li>
 * </ol>
 */
@Component
public class AzureBlobClients {

    /** Public Azure blob endpoint suffix of a storage account. */
    static final String BLOB_SUFFIX = ".blob.core.windows.net";

    private static final Logger LOG = LoggerFactory.getLogger(AzureBlobClients.class);

    private static final Pattern ACCOUNT_NAME = Pattern.compile("[a-z0-9]{3,24}");

    /** A placeholder token for the test endpoint only: an emulator does not check it, and Azure would refuse it. */
    private static final TokenCredential TEST_ENDPOINT_TOKEN = request -> Mono.just(
            new AccessToken("dlap-test-endpoint", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)));

    private final String accountEndpoint;
    private final String connectionString;
    private final String testEndpoint;
    private final Map<String, BlobServiceClient> byEndpoint = new ConcurrentHashMap<>();
    private volatile TokenCredential credential;
    private volatile BlobServiceClient connectionStringClient;

    /**
     * Creates the clients from the settings.
     *
     * @param accountEndpoint  {@code dlap.autoarchival.azure.account-endpoint}; empty to derive it from the location
     * @param connectionString {@code dlap.autoarchival.azure.connection-string}: DEPRECATED fallback; empty for none
     * @param testEndpoint     {@code dlap.autoarchival.test-endpoint.azure}: TEST ONLY blob endpoint; empty for Azure
     * @throws IllegalArgumentException when an endpoint is not an absolute https URI with a host; the message names
     *                                  the property, never the value
     */
    public AzureBlobClients(
            @Value("${dlap.autoarchival.azure.account-endpoint:}") String accountEndpoint,
            @Value("${dlap.autoarchival.azure.connection-string:}") String connectionString,
            @Value(TestEndpointProperties.AZURE) String testEndpoint) {
        this.accountEndpoint = https("dlap.autoarchival.azure.account-endpoint", accountEndpoint);
        this.connectionString = connectionString == null ? "" : connectionString.strip();
        this.testEndpoint = https("dlap.autoarchival.test-endpoint.azure", testEndpoint);
        if (!this.testEndpoint.isEmpty()) {
            LOG.warn("The Azure archive client uses the TEST-ONLY blob endpoint ({}); tiering goes to that emulator, "
                    + "not to Azure. Never set this in production.", "dlap.autoarchival.test-endpoint.azure");
        } else if (!this.connectionString.isEmpty() && this.accountEndpoint.isEmpty()) {
            LOG.warn("dlap.autoarchival.azure.connection-string is DEPRECATED: Azure RBAC does not govern it and it "
                    + "may hold an account key. Set dlap.autoarchival.azure.account-endpoint and let the executor sign "
                    + "in with DefaultAzureCredential; the prod profile refuses the connection string.");
        }
    }

    /**
     * Whether an endpoint or a connection string is configured, so a listing can be attempted without guessing the
     * account.
     *
     * @return true when the test endpoint, the account endpoint or the connection string is set
     */
    public boolean configured() {
        return !testEndpoint.isEmpty() || !accountEndpoint.isEmpty() || !connectionString.isEmpty();
    }

    /**
     * Whether the deprecated connection string is the one in use.
     *
     * @return true when no test or account endpoint is set and a connection string is
     */
    public boolean usesConnectionString() {
        return testEndpoint.isEmpty() && accountEndpoint.isEmpty() && !connectionString.isEmpty();
    }

    /**
     * The blob service client for a storage account.
     *
     * @param account the account name from the asset's location; used only when no endpoint is configured
     * @return the client, shared
     * @throws IllegalArgumentException when no endpoint is configured and the account is not a valid account name
     */
    public BlobServiceClient serviceClient(String account) {
        if (!testEndpoint.isEmpty()) {
            return byEndpoint.computeIfAbsent(testEndpoint, e -> build(e, TEST_ENDPOINT_TOKEN));
        }
        if (!accountEndpoint.isEmpty()) {
            return byEndpoint.computeIfAbsent(accountEndpoint, e -> build(e, credential()));
        }
        if (!connectionString.isEmpty()) {
            BlobServiceClient c = connectionStringClient;
            if (c == null) {
                synchronized (this) {
                    c = connectionStringClient;
                    if (c == null) {
                        c = new BlobServiceClientBuilder().connectionString(connectionString).buildClient();
                        connectionStringClient = c;
                    }
                }
            }
            return c;
        }
        String name = account == null ? "" : account.toLowerCase(Locale.ROOT);
        if (!ACCOUNT_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("not an Azure storage account name: " + account);
        }
        return byEndpoint.computeIfAbsent("https://" + name + BLOB_SUFFIX, e -> build(e, credential()));
    }

    /**
     * The credential every client of the executor signs with.
     *
     * @return a {@code DefaultAzureCredential}, built once
     */
    TokenCredential credential() {
        TokenCredential c = credential;
        if (c == null) {
            synchronized (this) {
                c = credential;
                if (c == null) {
                    c = new DefaultAzureCredentialBuilder().build();
                    credential = c;
                }
            }
        }
        return c;
    }

    private static BlobServiceClient build(String endpoint, TokenCredential token) {
        return new BlobServiceClientBuilder().endpoint(endpoint).credential(token).buildClient();
    }

    private static String https(String property, String value) {
        String v = value == null ? "" : value.strip();
        if (v.isEmpty()) {
            return "";
        }
        URI uri;
        try {
            uri = new URI(v);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException(property + " is not a URI", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException(property + " must be an absolute https URI with a host");
        }
        return v;
    }
}
