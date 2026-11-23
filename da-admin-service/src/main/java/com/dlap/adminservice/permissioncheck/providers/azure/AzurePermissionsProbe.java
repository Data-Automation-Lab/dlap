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

package com.dlap.adminservice.permissioncheck.providers.azure;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.dlap.adminservice.audit.export.HttpCalls;
import com.dlap.adminservice.permissioncheck.PermissionCheckReport;
import com.dlap.adminservice.permissioncheck.PermissionProbe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Probes an Azure Actor with the effective-permissions API
 * ({@code GET <scope>/providers/Microsoft.Authorization/permissions}, api-version 2022-04-01), which
 * lists the caller's actions, not-actions, data actions and not-data actions at a scope. A probed
 * permission is held when an action pattern matches it and no not-action of the same entry does;
 * any listed pattern that is not an acceptable permission (for example {@code Microsoft.Storage/*}
 * or {@code *}) is reported as granted beyond the trace. Nothing is read or changed.
 */
public final class AzurePermissionsProbe implements PermissionProbe {

    /** The permissions API version. */
    public static final String API_VERSION = "2022-04-01";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACCOUNT_ID = "/subscriptions/[0-9a-fA-F-]{36}/resourceGroups/[-\\w._()]{1,90}"
            + "/providers/Microsoft\\.Storage/storageAccounts/[a-z0-9]{3,24}";
    private static final Pattern ACCOUNT = Pattern.compile(ACCOUNT_ID);
    private static final Pattern CONTAINER = Pattern.compile(ACCOUNT_ID
            + "/blobServices/default/containers/[a-z0-9](?:[a-z0-9]|-[a-z0-9]){2,62}");

    private static final Pattern DATA_ACTION = Pattern.compile("(?i)/(blobServices/containers/blobs|queueServices/queues"
            + "/messages|fileServices/fileshares/files|tableServices/tables/entities)/");

    private final AzurePermissionCheckProperties config;
    private final HttpCalls http;
    private final Clock clock;
    private volatile String cachedToken;
    private volatile Instant cachedUntil = Instant.EPOCH;

    /**
     * Creates the probe.
     *
     * @param config the Azure block
     * @param http   the HTTP client
     * @param clock  the token cache clock
     */
    public AzurePermissionsProbe(AzurePermissionCheckProperties config, HttpCalls http, Clock clock) {
        this.config = config;
        this.http = http;
        this.clock = clock;
    }

    @Override
    public String cloud() {
        return "azure";
    }

    @Override
    public String method() {
        return "Microsoft.Authorization/permissions";
    }

    @Override
    public PermissionCheckReport.IdentityBasis identityBasis() {
        return PermissionCheckReport.IdentityBasis.CALLER;
    }

    @Override
    public String identityNote() {
        return "Microsoft.Authorization/permissions answers for the calling identity: the managed identity"
                + " da-admin-service runs as, or the token in access-token-file. It is the Actor only if that is the"
                + " Actor's identity; nothing is impersonated (Q-727).";
    }

    @Override
    public List<Target> targets() {
        List<Target> out = new ArrayList<>();
        String caller = config.getAccessTokenFile() == null || config.getAccessTokenFile().isBlank()
                ? "the managed identity" : "the identity of the access token file";
        for (Map.Entry<String, AzurePermissionCheckProperties.Executor> e : new TreeMap<>(config.getExecutors()).entrySet()) {
            AzurePermissionCheckProperties.Executor x = e.getValue();
            for (String id : new TreeSet<>(x.getStorageAccountIds())) {
                require(ACCOUNT, id, e.getKey() + ".storage-account-ids");
                out.add(new Target(e.getKey(), "storage_account", id, caller, true, true));
            }
            for (String id : new TreeSet<>(x.getContainerIds())) {
                require(CONTAINER, id, e.getKey() + ".container-ids");
                out.add(new Target(e.getKey(), "container", id, caller, x.isObjectActions(), true));
            }
        }
        return out;
    }

    @Override
    public Answer probe(Target target, Set<String> candidates, Set<String> acceptable) {
        List<JsonNode> entries = permissions(target.resource());
        Set<String> allowed = new TreeSet<>();
        for (String c : candidates) {
            if (held(entries, c)) {
                allowed.add(c);
            }
        }
        Set<String> beyond = new TreeSet<>();
        for (JsonNode entry : entries) {
            for (String field : List.of("actions", "dataActions")) {
                for (JsonNode p : entry.path(field)) {
                    String pattern = p.asText();
                    if (!equalsAnyIgnoreCase(acceptable, pattern) && !equalsAnyIgnoreCase(candidates, pattern)) {
                        beyond.add(pattern);
                    }
                }
            }
        }
        return new Answer(allowed, beyond);
    }

    /**
     * Whether an effective-permissions entry list grants an action.
     *
     * @param entries the {@code value} entries of the permissions API
     * @param action  the action or data action
     * @return true when one entry's actions (or data actions) match it and its not-actions do not
     */
    static boolean held(List<JsonNode> entries, String action) {
        boolean data = isDataAction(action);
        for (JsonNode e : entries) {
            if (data ? anyMatch(e.path("dataActions"), action) && !anyMatch(e.path("notDataActions"), action)
                    : anyMatch(e.path("actions"), action) && !anyMatch(e.path("notActions"), action)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a permission is a data action: an action pattern never grants one, and a data-action
     * pattern never grants a control-plane action. The Azure Storage data planes name their data
     * actions under the blob, queue-message, file and table-entity paths.
     *
     * @param action the permission
     * @return true for a data action
     */
    static boolean isDataAction(String action) {
        return DATA_ACTION.matcher(action).find();
    }

    /**
     * Azure's action pattern match: case-insensitive, {@code *} matches any run of characters.
     *
     * @param pattern the pattern, for example {@code Microsoft.Storage/*}
     * @param action  the action
     * @return true when the pattern covers the action
     */
    static boolean matches(String pattern, String action) {
        StringBuilder regex = new StringBuilder();
        for (String part : pattern.split("\\*", -1)) {
            if (!regex.isEmpty()) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(part));
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(action).matches();
    }

    private static boolean anyMatch(JsonNode patterns, String action) {
        for (JsonNode p : patterns) {
            if (matches(p.asText(), action)) {
                return true;
            }
        }
        return false;
    }

    private static boolean equalsAnyIgnoreCase(Set<String> names, String name) {
        for (String n : names) {
            if (n.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private List<JsonNode> permissions(String scope) {
        String base = trim(config.getManagementEndpoint());
        URI uri = URI.create(base + scope + "/providers/Microsoft.Authorization/permissions?api-version=" + API_VERSION);
        List<JsonNode> out = new ArrayList<>();
        for (int page = 0; uri != null && page < 20; page++) {
            HttpResponse<byte[]> r = http.send(http.request(uri).header("Authorization", "Bearer " + token()).GET()
                    .build(), "Azure permissions list");
            if (r.statusCode() != 200) {
                throw HttpCalls.unexpected("Azure permissions list on " + scope, r);
            }
            JsonNode body = json(r.body(), "Azure permissions list");
            body.path("value").forEach(out::add);
            String next = body.path("nextLink").asText("");
            // Follow a next page only on the same endpoint, so the token never leaves it.
            uri = next.startsWith(base + "/") ? URI.create(next) : null;
        }
        return out;
    }

    private String token() {
        if (config.getAccessTokenFile() != null && !config.getAccessTokenFile().isBlank()) {
            return HttpCalls.tokenFromFile(config.getAccessTokenFile());
        }
        String t = cachedToken;
        if (t != null && clock.instant().isBefore(cachedUntil)) {
            return t;
        }
        StringBuilder q = new StringBuilder("/metadata/identity/oauth2/token?api-version=2018-02-01&resource=")
                .append(enc(trim(config.getManagementEndpoint()) + "/"));
        if (config.getManagedIdentityClientId() != null && !config.getManagedIdentityClientId().isBlank()) {
            q.append("&client_id=").append(enc(config.getManagedIdentityClientId()));
        }
        URI uri = URI.create(trim(config.getImdsEndpoint()) + q);
        HttpResponse<byte[]> r = http.send(http.request(uri).header("Metadata", "true").GET().build(),
                "Azure IMDS token");
        if (r.statusCode() != 200) {
            throw HttpCalls.unexpected("Azure IMDS token", r);
        }
        JsonNode n = json(r.body(), "Azure IMDS token");
        t = n.path("access_token").asText("");
        if (t.isEmpty()) {
            throw new IllegalStateException("Azure IMDS returned no access token");
        }
        cachedToken = t;
        long expiresOn = n.path("expires_on").asLong(0);
        cachedUntil = expiresOn > 0 ? Instant.ofEpochSecond(expiresOn).minusSeconds(300) : clock.instant();
        return t;
    }

    private static void require(Pattern pattern, String id, String key) {
        if (!pattern.matcher(id).matches()) {
            throw new IllegalArgumentException("dlap.permission-check.azure.executors." + key
                    + " holds a value that is not an ARM id of that type");
        }
    }

    private static JsonNode json(byte[] body, String what) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException(what + " answered with a body that is not JSON", e);
        }
    }

    private static String trim(String endpoint) {
        return endpoint.replaceAll("/+$", "");
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
