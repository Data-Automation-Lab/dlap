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
package com.dlap.autodelete.service;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dlap.autodelete.client.ResilientCatalogAssetClient;
import com.dlap.autodelete.dto.CatalogAssetDTO;
import com.dlap.autodelete.service.storage.LocationSafety;
import com.dlap.common.assetid.AssetIdHints;
import com.dlap.common.assetid.AssetIdNormalizer;
import com.dlap.common.assetid.AssetIdParseResult;
import com.dlap.common.assetid.AssetKind;
import com.dlap.common.assetid.AssetProvider;
import com.dlap.common.assetid.CanonicalAssetId;

/**
 * Resolves a caller-supplied asset identifier to an {@link AssetLocator}
 * describing {@code (provider, locatorString, displayName)} suitable for
 * dispatching a real cloud-deletion SDK call.
 *
 * <h3>Strategy / fallback chain</h3>
 * <ol>
 *   <li><strong>Catalog lookup</strong>: if {@code rawAssetId} parses as a
 *       {@link UUID}, call da-catalog via Feign
 *       ({@link ResilientCatalogAssetClient#getAssetById(UUID)}). If the
 *       catalog returns a populated DTO with a usable {@code cloudProvider}
 *       and {@code assetUri} (or {@code nativeAssetId} as a secondary
 *       source), return that locator. Any catalog failure (404, network,
 *       circuit open) falls through to step&nbsp;2.</li>
 *   <li><strong>URI parsing</strong>: treat {@code rawAssetId} as a cloud
 *       identifier in any alias the canonical asset-id spec accepts
 *       ({@code docs/specs/asset-id.md}, ADR-0047) and infer the provider from
 *       it. This keeps older callers working during the migration to
 *       catalog-backed resolution.</li>
 *   <li><strong>Failure</strong>: if both strategies fail, throw
 *       {@link AssetLocatorResolutionException} so the caller can mark the
 *       task asset {@code FAILED} and write a {@code FAILED} audit row.
 *       The caller MUST NOT silently drop to any default provider, because
 *       that risks deleting from the wrong cloud.</li>
 * </ol>
 *
 * <h3>Identifier normalization (plan rule R8)</h3>
 * Every identifier passes through {@link AssetIdNormalizer}. A recognised
 * identifier is handed to the deletion service in canonical form
 * ({@code s3://}, {@code gs://}, {@code bq://}, ARN), so every alias of one
 * resource dispatches identically. The resolver fails closed (throws) when
 * <ul>
 *   <li>the stated provider and the provider the identifier belongs to
 *       disagree (the catalog provider is a heuristic, a URI is not);</li>
 *   <li>an {@code http(s)} URL carries a query string or fragment: the
 *       normalizer drops them, but they can select a version, a generation or
 *       a snapshot, and the retire path would then act on the whole object.</li>
 * </ul>
 * An identifier the normalizer cannot canonicalise is not rewritten: it is
 * passed through unchanged so that the provider deletion service, which
 * validates its own short forms ({@code rds://}, {@code azuresql://}, ...),
 * remains the final judge and refuses what it cannot parse.
 *
 * <p>Transitional adapter: the Azure deletion service still parses
 * {@code azure://account/container/path}, not the canonical {@code az://}, so
 * Azure blob objects are dispatched in that form. Drop the adapter when the
 * Azure service adopts the normalizer.
 */
@Service
public class AssetLocatorResolver {

    private static final Logger log = LoggerFactory.getLogger(AssetLocatorResolver.class);

    private static final String PROVIDER_GCP = "GCP";
    private static final String PROVIDER_AWS = "AWS";
    private static final String PROVIDER_AZURE = "AZURE";

    /** Clouds that have a deletion service; a raw identifier of any other provider is refused. */
    private static final Set<AssetProvider> RETIRE_CLOUDS =
            EnumSet.of(AssetProvider.GCP, AssetProvider.AWS, AssetProvider.AZURE);

    private final ResilientCatalogAssetClient catalogClient;
    private final String defaultProviderHint;

    @Autowired
    public AssetLocatorResolver(
            ResilientCatalogAssetClient catalogClient,
            @Value("${dlap.autodelete.default-provider:GCP}") String defaultProviderHint) {
        this.catalogClient = catalogClient;
        this.defaultProviderHint = defaultProviderHint;
    }

    /**
     * Resolves the given raw asset identifier to an {@link AssetLocator}.
     *
     * @param rawAssetId either a catalog UUID string or a raw cloud identifier.
     * @return a populated {@link AssetLocator}, never {@code null}.
     * @throws AssetLocatorResolutionException if neither strategy produces
     *         a usable locator, or the evidence is contradictory or unsafe.
     */
    public AssetLocator resolve(String rawAssetId) {
        if (rawAssetId == null || rawAssetId.isBlank()) {
            throw new AssetLocatorResolutionException(
                    "Cannot resolve asset locator: rawAssetId is null or blank");
        }

        // Strategy 1: catalog lookup (preferred)
        UUID parsedUuid = tryParseUuid(rawAssetId);
        if (parsedUuid != null && catalogClient != null) {
            Optional<AssetLocator> fromCatalog = resolveFromCatalog(parsedUuid);
            if (fromCatalog.isPresent()) {
                return fromCatalog.get();
            }
            log.debug("Catalog lookup for asset {} returned no usable locator; falling back to URI parsing",
                    rawAssetId);
        }

        // Strategy 2: URI parsing (safety fallback)
        Optional<AssetLocator> fromUri = resolveFromUri(rawAssetId);
        if (fromUri.isPresent()) {
            return fromUri.get();
        }

        // Strategy 3: failure
        throw new AssetLocatorResolutionException(
                "Cannot resolve asset locator for id='" + LocationSafety.redact(rawAssetId)
                        + "': catalog lookup found nothing and the id is not a recognised "
                        + "GCP, AWS or Azure identifier (gs://, bq://, s3://, an Azure blob URL, an ARN or "
                        + "another alias in docs/specs/asset-id.md). "
                        + "Refusing to dispatch a deletion to the default provider.");
    }

    // ------------------------------------------------------------------
    // Strategy 1: catalog lookup
    // ------------------------------------------------------------------

    private Optional<AssetLocator> resolveFromCatalog(UUID assetId) {
        Optional<CatalogAssetDTO> maybe;
        try {
            maybe = catalogClient.getAssetById(assetId);
        } catch (RuntimeException ex) {
            log.warn("Unexpected error during catalog asset lookup for {}: {} - {}",
                    assetId, ex.getClass().getSimpleName(), ex.getMessage());
            return Optional.empty();
        }
        if (maybe.isEmpty()) {
            return Optional.empty();
        }
        CatalogAssetDTO dto = maybe.get();

        String provider = normaliseProvider(dto.getCloudProvider());
        // Catalog may populate assetUri directly; if not, fall back to nativeAssetId
        // (which for discovery-written assets already contains the gs://... / bq://... form).
        String locator = firstNonBlank(dto.getAssetUri(), dto.getNativeAssetId());

        if (provider == null || locator == null || locator.isBlank()) {
            log.warn("Catalog returned asset {} but cloudProvider='{}' and locator='{}' were not usable; "
                            + "falling back to URI parsing",
                    assetId, dto.getCloudProvider(), LocationSafety.redact(locator));
            return Optional.empty();
        }

        AssetKind kind = AssetKind.fromServiceType(dto.getAssetType()).orElse(null);
        return Optional.of(locate(provider, locator, dto.getAssetName(), kind, dto.getRegion()));
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

    private static String normaliseProvider(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        // Accept the handful of aliases discovery might emit.
        return switch (upper) {
            case "GCP", "GOOGLE", "GOOGLE_CLOUD" -> PROVIDER_GCP;
            case "AWS", "AMAZON", "AMAZON_WEB_SERVICES" -> PROVIDER_AWS;
            case "AZURE", "MICROSOFT", "MICROSOFT_AZURE" -> PROVIDER_AZURE;
            default -> upper;
        };
    }

    // ------------------------------------------------------------------
    // Strategy 2: URI parsing (safety fallback, moved from DeletionTaskServiceImpl)
    // ------------------------------------------------------------------

    /**
     * Parses the raw id as a cloud identifier in any alias the canonical asset-id spec
     * accepts. Returns {@link Optional#empty()} if the id is not recognised or belongs to a
     * provider without a deletion service. Callers upstream treat an empty result as "URI
     * fallback did not match".
     *
     * @param rawAssetId the raw identifier; may be {@code null}
     * @return the locator, or empty when the id is not a recognised GCP, AWS or Azure identifier
     * @throws AssetLocatorResolutionException when the id is recognised but unsafe to act on
     */
    Optional<AssetLocator> resolveFromUri(String rawAssetId) {
        if (rawAssetId == null) {
            return Optional.empty();
        }
        refuseUrlSelector(rawAssetId);
        AssetIdParseResult parsed = AssetIdNormalizer.parse(rawAssetId, AssetIdHints.none());
        if (!(parsed instanceof AssetIdParseResult.Normalized normalized)) {
            return Optional.empty();
        }
        CanonicalAssetId id = normalized.canonical();
        if (!RETIRE_CLOUDS.contains(id.provider())) {
            return Optional.empty();
        }
        String locator = dispatchForm(id, rawAssetId);
        return Optional.of(new AssetLocator(id.cloudProvider(), locator, locator));
    }

    // ------------------------------------------------------------------
    // Normalization shared by both strategies
    // ------------------------------------------------------------------

    /**
     * Builds the locator for a catalog asset: normalizes the identifier with the catalog
     * provider, asset type and region as hints, cross-checks the provider and picks the form
     * the deletion service receives.
     */
    private AssetLocator locate(String statedProvider, String candidate, String assetName,
                                AssetKind kindHint, String regionHint) {
        refuseUrlSelector(candidate);
        AssetProvider stated = AssetProvider.fromString(statedProvider).orElse(null);
        if (stated == null) {
            // A provider the normalizer does not know ("UNKNOWN", "ONPREM"): nothing to cross-check.
            // Hand the identifier on unchanged; the factory has no service for it and refuses.
            return new AssetLocator(statedProvider, candidate, displayName(assetName, candidate));
        }
        AssetIdHints hints = AssetIdHints.builder()
                .provider(stated)
                .kind(kindHint)
                .region(regionHint)
                .build();
        AssetIdParseResult parsed = AssetIdNormalizer.parse(candidate, hints);
        if (!(parsed instanceof AssetIdParseResult.Normalized normalized)) {
            // Not canonicalisable (for example the region-qualified rds:// short form, which
            // carries no account). The provider deletion service is the final judge.
            log.debug("Locator for provider {} did not normalize ({}); passing it to the provider service",
                    statedProvider, ((AssetIdParseResult.Rejected) parsed).reason());
            return new AssetLocator(statedProvider, candidate, displayName(assetName, candidate));
        }
        CanonicalAssetId id = normalized.canonical();
        if (id.provider() != stated) {
            throw new AssetLocatorResolutionException(
                    "Refusing to delete: the catalog says the asset is on " + stated
                            + " but its location '" + LocationSafety.redact(candidate) + "' belongs to " + id.provider()
                            + ". Contradictory evidence is never acted on.");
        }
        String locator = dispatchForm(id, candidate);
        return new AssetLocator(id.cloudProvider(), locator, displayName(assetName, locator));
    }

    /**
     * The form the provider deletion service receives: the canonical URI, except that Azure
     * blob objects use {@code azure://} (the only blob scheme the Azure service parses) and other
     * Azure kinds keep the caller short form ({@code azuresql://}, {@code azurecosmos://}).
     */
    private static String dispatchForm(CanonicalAssetId id, String original) {
        if (id.provider() == AssetProvider.AZURE) {
            if (id.kind() == AssetKind.BLOB_OBJECT) {
                return "azure://" + String.join("/", id.parts());
            }
            return original.strip();
        }
        return id.uri();
    }

    private static String displayName(String assetName, String locator) {
        return assetName != null && !assetName.isBlank() ? assetName : LocationSafety.redact(locator);
    }

    /**
     * Fails closed on an {@code http(s)} URL with a query string or fragment. The normalizer drops
     * both (so a SAS token never reaches an ID), but a query can select an object version, a GCS
     * generation or an Azure snapshot, and dropping it would widen a targeted delete to the whole
     * object.
     */
    private static void refuseUrlSelector(String raw) {
        if (LocationSafety.carriesUrlSelector(raw)) {
            throw new AssetLocatorResolutionException(
                    "Refusing to resolve '" + LocationSafety.redact(raw) + "': a URL query string or fragment can select "
                            + "a version, generation or snapshot, which the retire path cannot honour. "
                            + "Use the plain object location.");
        }
    }

    /**
     * Convenience for callers (tests, audit builders) that need to infer
     * the deletion {@code action} string from a resolved locator.
     *
     * @param provider normalised provider
     * @param locator  canonical URI-like locator
     * @return action constant for the deletion audit row.
     */
    public static String inferAction(String provider, String locator) {
        String lower = locator == null ? "" : locator.toLowerCase(Locale.ROOT);
        AssetKind kind = AssetIdNormalizer.normalize(locator).map(CanonicalAssetId::kind).orElse(null);
        if (PROVIDER_GCP.equals(provider)) {
            if (lower.startsWith("bq://") || kind == AssetKind.BIGQUERY_TABLE) {
                return "DELETE_BQ_TABLE";
            }
            return "DELETE_GCS_OBJECT";
        }
        if (PROVIDER_AWS.equals(provider)) {
            if (lower.startsWith("rds://") || kind == AssetKind.RDS_INSTANCE) {
                return "DELETE_RDS_INSTANCE";
            }
            if (lower.startsWith("dynamodb://") || kind == AssetKind.DYNAMODB_TABLE) {
                return "DELETE_DYNAMODB_TABLE";
            }
            return "DELETE_S3_OBJECT";
        }
        if (PROVIDER_AZURE.equals(provider)) {
            return "DELETE_BLOB";
        }
        return "DELETE_UNKNOWN";
    }

    /**
     * Attempts to parse the given raw id as a UUID. Used by the resolver to
     * decide whether a catalog lookup is even possible. Returns {@code null}
     * for non-UUID strings (e.g. URI forms) so callers can cleanly skip the
     * Feign hop.
     */
    public static UUID tryParseUuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Exposes the configured default-provider hint so diagnostics can
     * report what <em>would</em> have been used if the caller had chosen to
     * bypass resolution (which they no longer do).
     */
    public String getDefaultProviderHint() {
        return defaultProviderHint;
    }
}
