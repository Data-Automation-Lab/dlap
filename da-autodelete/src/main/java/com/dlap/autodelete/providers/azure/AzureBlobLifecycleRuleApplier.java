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

package com.dlap.autodelete.providers.azure;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.management.AzureEnvironment;
import com.azure.core.management.exception.ManagementException;
import com.azure.core.management.profile.AzureProfile;
import com.azure.core.management.serializer.SerializerFactory;
import com.azure.core.util.serializer.SerializerAdapter;
import com.azure.core.util.serializer.SerializerEncoding;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.resourcemanager.storage.StorageManager;
import com.azure.resourcemanager.storage.fluent.BlobServicesClient;
import com.azure.resourcemanager.storage.fluent.ManagementPoliciesClient;
import com.azure.resourcemanager.storage.fluent.StorageManagementClient;
import com.azure.resourcemanager.storage.fluent.models.BlobServicePropertiesInner;
import com.azure.resourcemanager.storage.fluent.models.ManagementPolicyInner;
import com.azure.resourcemanager.storage.models.ManagementPolicyName;
import com.azure.resourcemanager.storage.models.ManagementPolicyRule;
import com.azure.resourcemanager.storage.models.ManagementPolicySchema;
import com.dlap.autodelete.nativerule.BucketVersioning;
import com.dlap.autodelete.nativerule.NativeRule;
import com.dlap.autodelete.nativerule.NativeRuleApplier;
import com.dlap.autodelete.nativerule.NativeRuleException;
import com.dlap.autodelete.nativerule.NativeRulePlatform;
import com.dlap.autodelete.nativerule.RuleDocuments;
import com.dlap.autodelete.nativerule.TestEndpoints;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Applies an Azure Blob lifecycle management rule: reads the account's {@code default} management
 * policy (none is an empty policy), keeps every rule except one with the same name, adds DLAP's
 * rule and writes the policy back. A management policy write replaces the whole policy, so a write
 * without the read would delete the account owner's own rules. Least privilege:
 * {@code Microsoft.Storage/storageAccounts/managementPolicies/read}, {@code /write} and {@code /delete}, and
 * {@code Microsoft.Storage/storageAccounts/blobServices/read} for the versioning check.
 *
 * <p>The rule is turned into the SDK model by the management serializer from the very JSON the
 * would-delete list shows, so the document and the applied rule cannot differ.
 *
 * <p>Blob versioning (RET-08, Q-694, decision D38): {@link #versioning} reads the account's blob service
 * properties ({@code isVersioningEnabled}, permission {@code Microsoft.Storage/storageAccounts/blobServices/read}).
 * A delete rule on an account that keeps versions only turns current versions into previous versions, which the
 * provider's log cannot prove as a delete, so the executor refuses it {@code VERSIONED_BUCKET_UNSUPPORTED}. This
 * is tested against mocks only; a live capture of an Azure account with versioning is still needed.
 */
@Component
public class AzureBlobLifecycleRuleApplier implements NativeRuleApplier {

    private static final Logger LOG = LoggerFactory.getLogger(AzureBlobLifecycleRuleApplier.class);

    /** The key of the Resource Manager endpoint in an {@link AzureEnvironment}'s endpoint map. */
    static final String RESOURCE_MANAGER_KEY = "resourceManagerEndpointUrl";

    /** A placeholder token for the test endpoint only: an emulator does not check it, and Azure would refuse it. */
    private static final TokenCredential TEST_ENDPOINT_TOKEN = request -> Mono.just(
            new AccessToken("dlap-test-endpoint", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)));

    private static final SerializerAdapter SERIALIZER = SerializerFactory.createDefaultManagementSerializerAdapter();

    private final Function<String, ManagementPoliciesClient> clients;
    private final Function<String, BlobServicesClient> blobServices;

    /** The production applier: {@code DefaultAzureCredential} (managed identity on a VM). */
    public AzureBlobLifecycleRuleApplier() {
        this(TestEndpoints.none());
    }

    /**
     * The applier Spring builds: {@code DefaultAzureCredential} against Azure, or, when
     * {@code dlap.autodelete.test-endpoint.azure} is set (RET-08, Q-713), that Resource Manager stub with a fixed
     * placeholder token, logged as a WARN.
     *
     * @param endpoints the test-only endpoints, empty in production
     */
    @Autowired
    public AzureBlobLifecycleRuleApplier(TestEndpoints endpoints) {
        this(managers(endpoints), true);
    }

    private AzureBlobLifecycleRuleApplier(Function<String, StorageManagementClient> managers, boolean sdk) {
        this(sub -> managers.apply(sub).getManagementPolicies(), sub -> managers.apply(sub).getBlobServices());
    }

    /**
     * An applier with its own management policy clients and no versioning read (tests): {@link #versioning}
     * answers {@link BucketVersioning#UNKNOWN}.
     *
     * @param clients from a subscription id to its management policy client
     */
    public AzureBlobLifecycleRuleApplier(Function<String, ManagementPoliciesClient> clients) {
        this(clients, sub -> {
            throw new UnsupportedOperationException("this applier does not read blob versioning");
        });
    }

    /**
     * An applier with its own clients (tests).
     *
     * @param clients      from a subscription id to its management policy client
     * @param blobServices from a subscription id to its blob services client (the versioning read)
     */
    public AzureBlobLifecycleRuleApplier(Function<String, ManagementPoliciesClient> clients,
            Function<String, BlobServicesClient> blobServices) {
        this.clients = clients;
        this.blobServices = blobServices;
    }

    @Override
    public NativeRulePlatform platform() {
        return NativeRulePlatform.AZURE_BLOB_LIFECYCLE;
    }

    @Override
    public void apply(NativeRule rule) {
        JsonNode doc = RuleDocuments.read(rule.document());
        String[] account = AzureBlobLifecycleRuleGenerator.parseAccountId(doc.path("storageAccountId").asText());
        List<ManagementPolicyRule> ours = new ArrayList<>();
        for (JsonNode r : doc.path("rules")) {
            ours.add(toSdk(r));
        }
        try {
            ManagementPoliciesClient client = clients.apply(account[0]);
            List<ManagementPolicyRule> merged = new ArrayList<>();
            for (ManagementPolicyRule existing : existingRules(client, account[1], account[2])) {
                if (ours.stream().noneMatch(o -> o.name().equals(existing.name()))) {
                    merged.add(existing);
                }
            }
            merged.addAll(ours);
            client.createOrUpdate(account[1], account[2], ManagementPolicyName.DEFAULT,
                    new ManagementPolicyInner().withPolicy(new ManagementPolicySchema().withRules(merged)));
        } catch (ManagementException e) {
            String code = e.getValue() == null ? "unknown" : e.getValue().getCode();
            throw new NativeRuleException("Azure refused the lifecycle rule on " + account[2] + ": " + code, e);
        } catch (RuntimeException e) {
            if (e instanceof NativeRuleException n) {
                throw n;
            }
            throw new NativeRuleException("Azure could not be reached for account " + account[2] + ": "
                    + e.getClass().getSimpleName(), e);
        }
    }

    @Override
    public boolean remove(NativeRule rule) {
        JsonNode doc = RuleDocuments.read(rule.document());
        String[] account = AzureBlobLifecycleRuleGenerator.parseAccountId(doc.path("storageAccountId").asText());
        List<String> names = new ArrayList<>();
        for (JsonNode r : doc.path("rules")) {
            names.add(r.path("name").asText());
        }
        try {
            ManagementPoliciesClient client = clients.apply(account[0]);
            List<ManagementPolicyRule> existing = existingRules(client, account[1], account[2]);
            List<ManagementPolicyRule> kept = existing.stream().filter(r -> !names.contains(r.name())).toList();
            long found = existing.stream().map(ManagementPolicyRule::name).filter(names::contains).distinct().count();
            if (kept.size() == existing.size()) {
                return false;
            }
            if (kept.isEmpty()) {
                client.delete(account[1], account[2], ManagementPolicyName.DEFAULT);
            } else {
                client.createOrUpdate(account[1], account[2], ManagementPolicyName.DEFAULT,
                        new ManagementPolicyInner().withPolicy(new ManagementPolicySchema().withRules(kept)));
            }
            return found == names.stream().distinct().count();
        } catch (ManagementException e) {
            String code = e.getValue() == null ? "unknown" : e.getValue().getCode();
            throw new NativeRuleException("Azure refused removing the lifecycle rule on " + account[2] + ": " + code, e);
        } catch (RuntimeException e) {
            if (e instanceof NativeRuleException n) {
                throw n;
            }
            throw new NativeRuleException("Azure could not be reached for account " + account[2] + ": "
                    + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * Reads the account's blob versioning from its blob service properties (RET-08, Q-694).
     * {@code isVersioningEnabled} true is {@link BucketVersioning#ENABLED}; false or absent (versioning was never
     * turned on, the platform default) is {@link BucketVersioning#UNVERSIONED}. No properties, a refusal or no
     * answer is {@link BucketVersioning#UNKNOWN}. Azure has no suspended state: an account whose versioning was
     * turned off reads UNVERSIONED, and the previous versions kept from before are not considered (as for GCS).
     */
    @Override
    public BucketVersioning versioning(NativeRule rule) {
        String accountId = RuleDocuments.read(rule.document()).path("storageAccountId").asText();
        try {
            String[] account = AzureBlobLifecycleRuleGenerator.parseAccountId(accountId);
            BlobServicePropertiesInner properties = blobServices.apply(account[0])
                    .getServiceProperties(account[1], account[2]);
            if (properties == null) {
                LOG.warn("Azure gave no blob service properties for account {}; versioning unknown", account[2]);
                return BucketVersioning.UNKNOWN;
            }
            return Boolean.TRUE.equals(properties.isVersioningEnabled()) ? BucketVersioning.ENABLED
                    : BucketVersioning.UNVERSIONED;
        } catch (RuntimeException e) {
            LOG.warn("Could not read the blob versioning of storage account {}: {}", accountId,
                    e.getClass().getSimpleName());
            return BucketVersioning.UNKNOWN;
        }
    }

    private static List<ManagementPolicyRule> existingRules(ManagementPoliciesClient client, String group,
            String account) {
        try {
            ManagementPolicyInner current = client.get(group, account, ManagementPolicyName.DEFAULT);
            if (current == null || current.policy() == null || current.policy().rules() == null) {
                return List.of();
            }
            return current.policy().rules();
        } catch (ManagementException e) {
            if (e.getResponse() != null && e.getResponse().getStatusCode() == 404) {
                return List.of();
            }
            throw e;
        }
    }

    /**
     * Turns one document rule (ARM member names) into the SDK model.
     *
     * @param rule the rule member
     * @return the SDK rule
     */
    static ManagementPolicyRule toSdk(JsonNode rule) {
        try {
            ManagementPolicyRule sdk = SERIALIZER.deserialize(RuleDocuments.write(rule), ManagementPolicyRule.class,
                    SerializerEncoding.JSON);
            sdk.validate();
            return sdk;
        } catch (IOException e) {
            throw new IllegalArgumentException("not an Azure management policy rule", e);
        }
    }

    /**
     * The SDK model as ARM JSON (tests compare it with the document).
     *
     * @param rule the SDK rule
     * @return the JSON text
     */
    static String toJson(ManagementPolicyRule rule) {
        try {
            return SERIALIZER.serialize(rule, SerializerEncoding.JSON);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write an Azure management policy rule", e);
        }
    }

    /**
     * The storage management clients for the settings: Azure with {@code DefaultAzureCredential}, or the test
     * endpoint with a placeholder token.
     *
     * @param endpoints the test-only endpoints
     * @return from a subscription id to its storage management client
     */
    static Function<String, StorageManagementClient> managers(TestEndpoints endpoints) {
        if (endpoints.azureResourceManager().isEmpty()) {
            return sub -> StorageManager.authenticate(new DefaultAzureCredentialBuilder().build(),
                    new AzureProfile(null, sub, AzureEnvironment.AZURE)).serviceClient();
        }
        String endpoint = endpoints.azureResourceManager().get();
        LOG.warn(TestEndpoints.warning(NativeRulePlatform.AZURE_BLOB_LIFECYCLE, endpoint));
        AzureEnvironment environment = testEnvironment(endpoint);
        return sub -> StorageManager.authenticate(TEST_ENDPOINT_TOKEN, new AzureProfile(null, sub, environment))
                .serviceClient();
    }

    /**
     * The public Azure environment with the Resource Manager endpoint replaced by the test endpoint (RET-08,
     * Q-713).
     *
     * @param endpoint the Resource Manager stub
     * @return the environment
     */
    static AzureEnvironment testEnvironment(String endpoint) {
        Map<String, String> endpoints = new HashMap<>(AzureEnvironment.AZURE.getEndpoints());
        endpoints.put(RESOURCE_MANAGER_KEY, endpoint);
        return new AzureEnvironment(endpoints);
    }
}
