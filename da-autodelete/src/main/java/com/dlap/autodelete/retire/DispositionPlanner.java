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

package com.dlap.autodelete.retire;

import com.dlap.autodelete.domain.RetireDispositionEntity;
import com.dlap.autodelete.nativerule.NativeRule;
import com.dlap.autodelete.nativerule.NativeRuleApplier;
import com.dlap.autodelete.nativerule.NativeRuleGenerator;
import com.dlap.autodelete.nativerule.NativeRulePlatform;
import com.dlap.autodelete.service.storage.CloudDeletionServiceFactory;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.assetid.CanonicalAssetId;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Chooses how an approved hard delete is carried out (decision D20).
 *
 * <ol>
 *   <li><b>Native rule first.</b> When a generator has an exact rule for the asset kind and an
 *       applier exists for its platform: S3 bucket, GCS bucket, Azure Blob container (of a
 *       configured storage account), BigQuery table.</li>
 *   <li><b>Direct engine delete</b> only for kinds with no exact native rule that the engine can
 *       delete: one S3 object, one GCS object, one Azure blob.</li>
 *   <li>Anything else has no executor and is refused.</li>
 * </ol>
 * Planning is pure and calls no cloud API, so the would-delete list can show the plan.
 */
@Component
public class DispositionPlanner {

    /** Kinds the engine deletes itself, because no exact native rule exists for them. */
    static final Set<AssetKind> DIRECT_KINDS = Set.of(AssetKind.S3_OBJECT, AssetKind.GCS_OBJECT, AssetKind.BLOB_OBJECT);

    /** Platform name of a direct engine delete on a provider. */
    static final String ENGINE_PREFIX = "ENGINE_";

    private final List<NativeRuleGenerator> generators;
    private final Map<NativeRulePlatform, NativeRuleApplier> appliers = new EnumMap<>(NativeRulePlatform.class);
    private final CloudDeletionServiceFactory engines;

    /**
     * Creates the planner.
     *
     * @param generators every native rule generator
     * @param appliers   every native rule applier
     * @param engines    the engine's delete services
     */
    public DispositionPlanner(List<NativeRuleGenerator> generators, List<NativeRuleApplier> appliers,
            CloudDeletionServiceFactory engines) {
        this.generators = List.copyOf(generators);
        for (NativeRuleApplier a : appliers) {
            this.appliers.put(a.platform(), a);
        }
        this.engines = engines;
    }

    /**
     * A plan.
     *
     * @param mode     {@code NATIVE_RULE} or {@code DIRECT}
     * @param platform the native platform name, or {@code ENGINE_<provider>}
     * @param rule     the generated rule (native mode), or null
     * @param applier  the applier (native mode), or null
     * @param engineLocation the location the engine deletes (direct mode), or null
     */
    public record Plan(String mode, String platform, NativeRule rule, NativeRuleApplier applier,
            String engineLocation) {

        /** @return true for a native rule */
        public boolean nativeRule() {
            return RetireDispositionEntity.MODE_NATIVE_RULE.equals(mode);
        }

        /** @return the rule id, or null */
        public String ruleId() {
            return rule == null ? null : rule.ruleId();
        }

        /** @return the rule document, or null */
        public String ruleDocument() {
            return rule == null ? null : rule.document();
        }

        /** @return when the rule takes effect, or null */
        public Instant effectiveAt() {
            return rule == null ? null : rule.effectiveAt();
        }
    }

    /**
     * The applier of a stored disposition's platform (a restore of a rule in force removes the rule
     * with it).
     *
     * @param platformName the platform column of a disposition
     * @return the applier, or empty for an engine delete or an unknown platform
     */
    public Optional<NativeRuleApplier> applierFor(String platformName) {
        if (platformName == null) {
            return Optional.empty();
        }
        for (Map.Entry<NativeRulePlatform, NativeRuleApplier> a : appliers.entrySet()) {
            if (a.getKey().name().equals(platformName)) {
                return Optional.of(a.getValue());
            }
        }
        return Optional.empty();
    }

    /**
     * Plans the hard delete of an asset.
     *
     * @param asset    the canonical asset
     * @param provider the cloud provider
     * @param ruleKey  the soft-delete record id
     * @param now      the planning time
     * @return the plan, or empty when nothing can delete the asset
     */
    public Optional<Plan> plan(CanonicalAssetId asset, String provider, UUID ruleKey, Instant now) {
        for (NativeRuleGenerator g : generators) {
            NativeRuleApplier applier = appliers.get(g.platform());
            if (applier != null && g.supports(asset)) {
                NativeRule rule = g.generate(asset, ruleKey, now);
                return Optional.of(new Plan(RetireDispositionEntity.MODE_NATIVE_RULE, g.platform().name(), rule,
                        applier, null));
            }
        }
        if (asset != null && DIRECT_KINDS.contains(asset.kind()) && engineSupports(provider)) {
            return Optional.of(new Plan(RetireDispositionEntity.MODE_DIRECT,
                    ENGINE_PREFIX + provider.trim().toUpperCase(java.util.Locale.ROOT), null, null,
                    engineLocation(asset)));
        }
        return Optional.empty();
    }

    private boolean engineSupports(String provider) {
        if (provider == null || provider.isBlank()) {
            return false;
        }
        try {
            engines.forProvider(provider);
            return true;
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The location form the engine's delete service accepts: the canonical URI, except Azure blobs,
     * which the Azure engine reads as {@code azure://account/container/blob}.
     *
     * @param asset the canonical asset
     * @return the location
     */
    static String engineLocation(CanonicalAssetId asset) {
        String uri = asset.uri();
        if (asset.kind() == AssetKind.BLOB_OBJECT && uri.startsWith("az://")) {
            return "azure://" + uri.substring("az://".length());
        }
        return uri;
    }
}
