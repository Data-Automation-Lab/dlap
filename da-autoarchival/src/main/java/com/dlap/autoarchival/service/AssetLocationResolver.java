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

package com.dlap.autoarchival.service;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.dlap.autoarchival.client.CatalogAssetFeignClient;
import com.dlap.autoarchival.dto.CatalogAssetDTO;
import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.AssetIdParseResult;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.assetid.AssetProvider;
import com.dlap.common.assetid.CanonicalAssetId;

import lombok.extern.slf4j.Slf4j;

/**
 * Resolves an asset id to the real {@link AssetLocation} an archival call must operate on.
 *
 * <h3>Resolution order</h3>
 * <ol>
 *   <li><b>Policy event snapshot.</b> A {@code PolicyActionEvent} carries the asset
 *       snapshot the policy matched on ({@code matchSnapshot.assetUri} /
 *       {@code .cloudProvider}, or the same keys flat on the event). Using it costs no
 *       round-trip and reflects exactly what the policy saw.</li>
 *   <li><b>da-catalog lookup.</b> {@code GET /api/v1/catalog/assets/{assetId}} over the
 *       M2M Feign client ({@code ROLE_SERVICE_ACCOUNT}). {@code assetUri} is preferred,
 *       {@code nativeAssetId} is the secondary locator source.</li>
 *   <li><b>Unresolvable.</b> Returns {@link Optional#empty()}. Callers <b>fail closed</b>
 *       (task FAILED with the reason) and never invent a location. The previous
 *       {@code "s3://unknown/" + assetId} fallback meant a policy-driven ARCHIVE could
 *       never archive anything real while still reporting progress.</li>
 * </ol>
 *
 * <h3>Identifier normalization (plan rule R8)</h3>
 * Whichever source supplies the location, it passes through {@link AssetIdNormalizer}
 * ({@code docs/specs/asset-id.md}, ADR-0047). Every alias of one resource (an
 * {@code s3://} URI, an ARN, an https URL, {@code projects/_/buckets/b/objects/o}, ...)
 * resolves to the same {@link AssetLocation}, whose provider comes from the identifier itself.
 * The resolver fails closed (returns empty) when
 * <ul>
 *   <li>the identifier is not recognised, or only part of it is (a missing hint, a malformed
 *       name): destructive callers never guess;</li>
 *   <li>the stated provider and the provider the identifier belongs to disagree: the catalog
 *       provider is a heuristic, a URI is not, and contradictory evidence is never acted on;</li>
 *   <li>an {@code http(s)} URL carries a query string or fragment: the normalizer drops them,
 *       but they can select a version, a generation or a snapshot.</li>
 * </ul>
 *
 * <p>Transitional adapter: the Azure storage service still parses
 * {@code azure://account/container/path}, not the canonical {@code az://}, so Azure blob
 * locations are returned in that form. Drop the adapter when the Azure service adopts the
 * normalizer.
 */
@Service
@Slf4j
public class AssetLocationResolver {

    private final CatalogAssetFeignClient catalogClient;

    /**
     * @param catalogClient the da-catalog Feign client; optional so unit tests and
     *                      Feign-less contexts can construct the resolver without it
     *                      (resolution then relies on the event snapshot alone)
     */
    @Autowired
    public AssetLocationResolver(@Autowired(required = false) CatalogAssetFeignClient catalogClient) {
        this.catalogClient = catalogClient;
        if (catalogClient == null) {
            log.warn("No CatalogAssetFeignClient available; asset locations can only be resolved "
                    + "from policy-event snapshots. Archival of assets without a snapshot URI "
                    + "will fail closed.");
        }
    }

    /**
     * Resolves the location for one asset of a policy action, preferring the event's own
     * match snapshot before calling da-catalog.
     *
     * @param assetId     the catalog asset id from the policy action event
     * @param policyEvent the raw policy action event (may be {@code null})
     * @return the resolved location, or empty when neither source yields a usable one
     */
    public Optional<AssetLocation> resolveForPolicyAction(String assetId, Map<String, Object> policyEvent) {
        Optional<AssetLocation> fromEvent = fromEventSnapshot(assetId, policyEvent);
        if (fromEvent.isPresent()) {
            log.debug("Resolved asset {} location from policy-event snapshot: {}",
                    assetId, fromEvent.get().uri());
            return fromEvent;
        }
        return resolve(assetId);
    }

    /**
     * Resolves the location for one asset from da-catalog.
     *
     * @param assetId the catalog asset id (a UUID string)
     * @return the resolved location, or empty when the catalog has no usable metadata
     */
    public Optional<AssetLocation> resolve(String assetId) {
        UUID uuid = tryParseUuid(assetId);
        if (uuid == null) {
            log.warn("Asset id '{}' is not a catalog UUID; cannot resolve its storage location",
                    assetId);
            return Optional.empty();
        }
        if (catalogClient == null) {
            return Optional.empty();
        }

        CatalogAssetDTO dto;
        try {
            dto = catalogClient.getAssetById(uuid);
        } catch (RuntimeException ex) {
            // Feign wraps any non-2xx (including 404) as a RuntimeException.
            log.warn("Catalog lookup failed for asset {}: {} - {}",
                    assetId, ex.getClass().getSimpleName(), ex.getMessage());
            return Optional.empty();
        }
        if (dto == null) {
            log.warn("Catalog returned no asset for {}", assetId);
            return Optional.empty();
        }

        String uri = firstNonBlank(dto.getAssetUri(), dto.getNativeAssetId());
        if (uri == null) {
            log.warn("Catalog asset {} has no usable location (assetUri='{}', nativeAssetId='{}', "
                            + "cloudProvider='{}'); refusing to invent one",
                    assetId, dto.getAssetUri(), dto.getNativeAssetId(), dto.getCloudProvider());
            return Optional.empty();
        }
        AssetKind kind = AssetKind.fromServiceType(dto.getAssetType()).orElse(null);
        return locate(normaliseProvider(dto.getCloudProvider()), uri, kind, dto.getRegion(), assetId);
    }

    // ------------------------------------------------------------------
    // Step 1: the policy event's own snapshot
    // ------------------------------------------------------------------

    private Optional<AssetLocation> fromEventSnapshot(String assetId, Map<String, Object> policyEvent) {
        if (policyEvent == null) {
            return Optional.empty();
        }
        Map<String, Object> snapshot = asMap(policyEvent.get("matchSnapshot"));

        String uri = firstNonBlank(
                snapshot == null ? null : asString(snapshot.get("assetUri")),
                asString(policyEvent.get("assetUri")));
        if (uri == null) {
            return Optional.empty();
        }

        String provider = normaliseProvider(firstNonBlank(
                snapshot == null ? null : asString(snapshot.get("cloudProvider")),
                asString(policyEvent.get("cloudProvider"))));
        return locate(provider, uri, null, null, assetId);
    }

    // ------------------------------------------------------------------
    // Normalization shared by both sources
    // ------------------------------------------------------------------

    /**
     * Normalizes a location and builds the {@link AssetLocation} the storage service receives.
     *
     * @param statedProvider the provider the source states, or {@code null} when it states none
     * @param uri            the location in any alias the asset-id spec accepts
     * @param kindHint       the asset type the source states, or {@code null}
     * @param regionHint     the region the source states, or {@code null}
     * @param assetId        the asset, for log lines only
     * @return the location, or empty when the identifier is not safe to act on
     */
    private Optional<AssetLocation> locate(String statedProvider, String uri, AssetKind kindHint,
                                           String regionHint, String assetId) {
        if (LocationSafety.carriesUrlSelector(uri)) {
            log.warn("Asset {} location '{}' is a URL with a query string or fragment, which can select a "
                    + "version, generation or snapshot; refusing to archive it", assetId, LocationSafety.redact(uri));
            return Optional.empty();
        }
        AssetProvider stated = AssetProvider.fromString(statedProvider).orElse(null);
        if (stated == null && statedProvider != null) {
            // A provider the normalizer does not know ("ONPREM"). Keep it so that the storage
            // service selection refuses it; the identifier is not rewritten.
            return Optional.of(new AssetLocation(statedProvider, uri));
        }
        AssetIdHints hints = AssetIdHints.builder()
                .provider(stated)
                .kind(kindHint)
                .region(regionHint)
                .build();
        AssetIdParseResult parsed = AssetIdNormalizer.parse(uri, hints);
        if (!(parsed instanceof AssetIdParseResult.Normalized normalized)) {
            AssetIdParseResult.Rejected rejected = (AssetIdParseResult.Rejected) parsed;
            log.warn("Asset {} location '{}' is not a recognised identifier ({}: {}); refusing to invent one",
                    assetId, LocationSafety.redact(uri), rejected.reason(), rejected.detail());
            return Optional.empty();
        }
        CanonicalAssetId id = normalized.canonical();
        if (stated != null && stated != id.provider()) {
            log.warn("Asset {} is stated to be on {} but its location '{}' belongs to {}; contradictory "
                    + "evidence is never acted on", assetId, stated, LocationSafety.redact(uri), id.provider());
            return Optional.empty();
        }
        return Optional.of(new AssetLocation(id.cloudProvider(), dispatchForm(id)));
    }

    /**
     * The form the provider storage service receives: the canonical URI, except that Azure blob
     * kinds use {@code azure://} (the only blob scheme the Azure service parses).
     */
    private static String dispatchForm(CanonicalAssetId id) {
        if (id.provider() == AssetProvider.AZURE
                && (id.kind() == AssetKind.BLOB_OBJECT || id.kind() == AssetKind.BLOB_CONTAINER
                || id.kind() == AssetKind.BLOB_STORAGE)) {
            return "azure://" + String.join("/", id.parts());
        }
        return id.uri();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String normaliseProvider(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if ("UNKNOWN".equals(upper) || "UNSPECIFIED".equals(upper)) {
            return null;
        }
        return upper;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static UUID tryParseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
