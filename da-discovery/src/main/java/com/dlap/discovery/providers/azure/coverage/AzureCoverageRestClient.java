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

package com.dlap.discovery.providers.azure.coverage;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.dlap.discovery.coverage.CoverageHttp;
import com.dlap.discovery.coverage.CoverageHttp.CoverageTransportException;
import com.dlap.discovery.coverage.MissingPermissionException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@link AzureCoverageApi} over the Azure Resource Manager REST API with a bearer token for
 * {@code https://management.azure.com/.default} from the discovery service's
 * {@link TokenCredential}. HTTP 403 becomes a {@link MissingPermissionException} naming the
 * action; any other failure a {@link CoverageTransportException} with the status only.
 */
public class AzureCoverageRestClient implements AzureCoverageApi {

    /** Storage Accounts - List. */
    static final String STORAGE_API_VERSION = "2023-05-01";
    /** Diagnostic Settings - List. */
    static final String DIAGNOSTICS_API_VERSION = "2021-05-01-preview";
    /** At most this many pages of storage accounts are read. */
    static final int MAX_PAGES = 1_000;

    private final CoverageHttp http;
    private final Supplier<TokenCredential> credential;
    private final String management;
    private final String scope;

    /**
     * Creates the client.
     *
     * @param http       the transport
     * @param credential the Azure credential, obtained on first use
     * @param management the Resource Manager base URL
     */
    public AzureCoverageRestClient(CoverageHttp http, Supplier<TokenCredential> credential, String management) {
        this.http = http;
        this.credential = credential;
        this.management = management.endsWith("/") ? management.substring(0, management.length() - 1) : management;
        this.scope = "https://management.azure.com/.default";
    }

    @Override
    public List<JsonNode> listStorageAccounts(String subscriptionId) {
        List<JsonNode> out = new ArrayList<>();
        String url = management + "/subscriptions/" + subscriptionId
                + "/providers/Microsoft.Storage/storageAccounts?api-version=" + STORAGE_API_VERSION;
        for (int page = 0; page < MAX_PAGES && url != null; page++) {
            JsonNode body = get(url, "Microsoft.Storage/storageAccounts/read", "/subscriptions/" + subscriptionId);
            body.path("value").forEach(out::add);
            String next = body.path("nextLink").asText("");
            url = next.isBlank() ? null : sameHost(next);
        }
        if (url != null) {
            throw new CoverageTransportException("storage account list returned more than " + MAX_PAGES + " pages");
        }
        return out;
    }

    @Override
    public JsonNode listBlobDiagnosticSettings(String storageAccountId) {
        String resource = storageAccountId + "/blobServices/default";
        return get(management + resource + "/providers/Microsoft.Insights/diagnosticSettings?api-version="
                + DIAGNOSTICS_API_VERSION, "Microsoft.Insights/diagnosticSettings/read", resource);
    }

    /** A {@code nextLink} must point at the configured endpoint; the token is never sent elsewhere. */
    private String sameHost(String next) {
        if (!next.startsWith(management + "/")) {
            throw new CoverageTransportException("a nextLink points outside the Resource Manager endpoint");
        }
        return next;
    }

    private JsonNode get(String url, String action, String resource) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(CoverageHttp.TIMEOUT)
                .header("Authorization", "Bearer " + token())
                .GET()
                .build();
        CoverageHttp.Response response = http.send(request);
        if (response.ok()) {
            return response.body();
        }
        if (response.status() == 403) {
            throw new MissingPermissionException(action, resource);
        }
        throw new CoverageTransportException(action + " call for " + resource + " answered HTTP " + response.status());
    }

    private String token() {
        AccessToken token = credential.get().getTokenSync(new TokenRequestContext().addScopes(scope));
        if (token == null || token.getToken() == null) {
            throw new CoverageTransportException("no Azure Resource Manager token");
        }
        return token.getToken();
    }
}
