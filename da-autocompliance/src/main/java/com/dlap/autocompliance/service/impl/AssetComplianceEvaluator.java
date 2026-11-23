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

package com.dlap.autocompliance.service.impl;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.dlap.autocompliance.client.dto.CatalogAssetDTO;
import com.dlap.autocompliance.model.entity.ComplianceControlEntity;
import com.dlap.autocompliance.model.entity.ReportFindingData;

import lombok.extern.slf4j.Slf4j;

/**
 * Catalog-backed compliance evaluator (Q-017). Given a seeded control and a
 * real catalog asset, it inspects the asset's actual attributes - labels,
 * technical/business metadata, region, asset type - and returns a concrete
 * {@link ReportFindingData} with status COMPLIANT / NON_COMPLIANT, or
 * {@code null} when the control does not apply to the asset (so nothing is
 * fabricated for assets outside a control's scope).
 *
 * <p>Every decision is derived from the asset's own attributes; there is no
 * random or hardcoded pass/fail. Evaluators are keyed by control id so new
 * controls plug in without touching the scan aggregation:
 *
 * <ul>
 *   <li><b>IAM-DRIFT-001</b> - flags assets whose metadata/labels indicate
 *       public exposure (e.g. {@code publicAccess=true}, an {@code allUsers}
 *       principal, or a {@code PUBLIC} label). Least-privilege assets pass.</li>
 *   <li><b>PII-ENCRYPTION-001</b> - flags assets carrying a sensitivity label
 *       (PII / CONFIDENTIAL / SENSITIVE / PHI) that lack any encryption marker
 *       in their technical metadata. Encrypted sensitive assets pass; assets
 *       with no sensitivity label are out of scope.</li>
 *   <li><b>GDPR-RESIDENCY-001</b> - flags assets located outside the control's
 *       allowed regions (from {@code evaluationParametersDefinition.allowedRegions},
 *       default EU). In-region assets pass; assets with no region are out of
 *       scope.</li>
 * </ul>
 */
@Component
@Slf4j
public class AssetComplianceEvaluator {

    private static final String IAM_DRIFT = "IAM-DRIFT-001";
    private static final String PII_ENCRYPTION = "PII-ENCRYPTION-001";
    private static final String GDPR_RESIDENCY = "GDPR-RESIDENCY-001";

    /** Labels that mark an asset as sensitive (PII-ENCRYPTION-001 scope). */
    private static final Set<String> SENSITIVITY_LABELS =
            Set.of("PII", "CONFIDENTIAL", "SENSITIVE", "PHI", "RESTRICTED");

    /** Metadata keys / values that indicate encryption is present. */
    private static final Set<String> ENCRYPTION_KEYS =
            Set.of("encryption", "encrypted", "kmskey", "kmskeyid", "kmskeyname",
                    "encryptionkey", "serversideencryption", "ssealgorithm", "cmek");

    /** Metadata keys that indicate public exposure. */
    private static final Set<String> PUBLIC_KEYS =
            Set.of("publicaccess", "ispublic", "public", "publiclyaccessible",
                    "anonymousaccess", "allusers", "allauthenticatedusers");

    /** Default GDPR-allowed regions when the control declares none. */
    private static final Set<String> DEFAULT_ALLOWED_REGIONS =
            Set.of("EU", "EUROPE", "EU-WEST-1", "EU-WEST-2", "EU-WEST-3",
                    "EU-CENTRAL-1", "EU-NORTH-1", "EU-SOUTH-1",
                    "EUROPE-WEST1", "EUROPE-WEST2", "EUROPE-WEST3", "EUROPE-WEST4",
                    "EUROPE-NORTH1", "EUROPE-CENTRAL2");

    /**
     * Evaluate one control against one asset.
     *
     * @return a concrete finding, or {@code null} if the control does not
     *         apply to this asset (out of scope / not enough data).
     */
    public ReportFindingData evaluate(ComplianceControlEntity control, CatalogAssetDTO asset) {
        if (control == null || asset == null) {
            return null;
        }
        if (!appliesToAssetType(control, asset)) {
            return null;
        }
        String controlId = control.getControlId();
        if (controlId == null) {
            return null;
        }
        return switch (controlId.toUpperCase(Locale.ROOT)) {
            case IAM_DRIFT -> evaluateIamDrift(control, asset);
            case PII_ENCRYPTION -> evaluatePiiEncryption(control, asset);
            case GDPR_RESIDENCY -> evaluateGdprResidency(control, asset);
            default -> {
                // No evaluator implemented for this control id yet; do not
                // fabricate a result. The findings-lookup path (Q-017) still
                // handles pre-existing findings for such controls.
                log.debug("No catalog-backed evaluator for control {}; skipping asset {}",
                        controlId, asset.getAssetId());
                yield null;
            }
        };
    }

    // ---- Per-control evaluators ------------------------------------------

    private ReportFindingData evaluateIamDrift(ComplianceControlEntity control, CatalogAssetDTO asset) {
        boolean publicExposure = hasPublicMarker(asset);
        if (publicExposure) {
            return finding(control, asset, "NON_COMPLIANT",
                    "Asset is publicly accessible (over-broad IAM grant detected in "
                            + "metadata/labels). Public exposure violates the least-privilege baseline.",
                    "Remove public / allUsers principals and restore least-privilege IAM bindings.",
                    Map.of("publicExposure", true, "baseline", "least-privilege"));
        }
        return finding(control, asset, "COMPLIANT",
                "No public exposure detected in the asset's IAM metadata/labels; "
                        + "access posture matches the least-privilege baseline.",
                null,
                Map.of("publicExposure", false, "baseline", "least-privilege"));
    }

    private ReportFindingData evaluatePiiEncryption(ComplianceControlEntity control, CatalogAssetDTO asset) {
        Set<String> normalizedLabels = normalizedLabels(asset);
        boolean sensitive = normalizedLabels.stream().anyMatch(SENSITIVITY_LABELS::contains);
        if (!sensitive) {
            // Not a sensitive asset -> control does not apply.
            return null;
        }
        boolean encrypted = hasEncryptionMarker(asset);
        if (!encrypted) {
            return finding(control, asset, "NON_COMPLIANT",
                    "Asset carries a sensitivity label (" + sensitivityLabelsOf(normalizedLabels)
                            + ") but no encryption marker is present in its technical metadata.",
                    "Enable encryption at rest (e.g. a KMS/CMEK key) for this sensitive asset "
                            + "and record the key reference in its technical metadata.",
                    Map.of("sensitive", true, "encrypted", false,
                            "sensitivityLabels", sensitivityLabelsOf(normalizedLabels)));
        }
        return finding(control, asset, "COMPLIANT",
                "Sensitive asset (" + sensitivityLabelsOf(normalizedLabels)
                        + ") has an encryption marker in its technical metadata.",
                null,
                Map.of("sensitive", true, "encrypted", true));
    }

    private ReportFindingData evaluateGdprResidency(ComplianceControlEntity control, CatalogAssetDTO asset) {
        String region = asset.getRegion();
        if (region == null || region.isBlank()) {
            // No region on the asset -> cannot assert residency; out of scope.
            return null;
        }
        Set<String> allowed = allowedRegions(control);
        String normalizedRegion = region.trim().toUpperCase(Locale.ROOT);
        boolean inRegion = allowed.contains(normalizedRegion);
        if (!inRegion) {
            return finding(control, asset, "NON_COMPLIANT",
                    "Asset resides in region '" + region + "', which is outside the "
                            + "GDPR-allowed regions " + allowed + ".",
                    "Relocate the data to an allowed region or record a documented transfer "
                            + "mechanism (e.g. SCCs) for this cross-border processing.",
                    Map.of("region", region, "allowedRegions", allowed, "inRegion", false));
        }
        return finding(control, asset, "COMPLIANT",
                "Asset resides in an allowed region ('" + region + "').",
                null,
                Map.of("region", region, "inRegion", true));
    }

    // ---- Attribute inspection helpers ------------------------------------

    private boolean appliesToAssetType(ComplianceControlEntity control, CatalogAssetDTO asset) {
        List<String> targetTypes = control.getTargetAssetTypes();
        if (targetTypes == null || targetTypes.isEmpty()) {
            // Control declares no asset-type scope -> applies to all types.
            return true;
        }
        String assetType = asset.getAssetType();
        if (assetType == null) {
            return false;
        }
        String normalized = assetType.trim().toUpperCase(Locale.ROOT);
        return targetTypes.stream()
                .filter(t -> t != null)
                .map(t -> t.trim().toUpperCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }

    private boolean hasPublicMarker(CatalogAssetDTO asset) {
        // Labels such as PUBLIC / allUsers.
        for (String label : normalizedLabels(asset)) {
            if (PUBLIC_KEYS.contains(label) || "PUBLIC".equals(label)) {
                return true;
            }
        }
        // Metadata keys signalling public access, with truthy values.
        return metadataMatches(asset, PUBLIC_KEYS, true);
    }

    private boolean hasEncryptionMarker(CatalogAssetDTO asset) {
        // A present, non-empty encryption key OR a truthy encrypted flag.
        return metadataKeyPresent(asset, ENCRYPTION_KEYS);
    }

    /**
     * True when any metadata map (technical, business, details) contains a key
     * in {@code keys}. When {@code requireTruthy} is set, the value must also be
     * truthy (true / "true" / "yes" / "enabled" / a non-empty principal list).
     */
    @SuppressWarnings("unchecked")
    private boolean metadataMatches(CatalogAssetDTO asset, Set<String> keys, boolean requireTruthy) {
        for (Map<String, Object> map : metadataMaps(asset)) {
            for (Map.Entry<String, Object> e : map.entrySet()) {
                String key = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT).replace("_", "");
                if (!keys.contains(key)) {
                    continue;
                }
                if (!requireTruthy) {
                    return true;
                }
                if (isTruthy(e.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when any metadata map has a non-null / non-empty value for one of {@code keys}. */
    private boolean metadataKeyPresent(CatalogAssetDTO asset, Set<String> keys) {
        for (Map<String, Object> map : metadataMaps(asset)) {
            for (Map.Entry<String, Object> e : map.entrySet()) {
                String key = e.getKey() == null ? "" : e.getKey().toLowerCase(Locale.ROOT).replace("_", "");
                if (!keys.contains(key)) {
                    continue;
                }
                Object v = e.getValue();
                if (v == null) {
                    continue;
                }
                String s = v.toString().trim();
                if (s.isEmpty() || "false".equalsIgnoreCase(s) || "none".equalsIgnoreCase(s)
                        || "null".equalsIgnoreCase(s) || "disabled".equalsIgnoreCase(s)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private boolean isTruthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        String s = v.toString().trim();
        return "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s)
                || "enabled".equalsIgnoreCase(s) || "1".equals(s)
                || s.contains("allUsers") || s.contains("allAuthenticatedUsers");
    }

    private List<Map<String, Object>> metadataMaps(CatalogAssetDTO asset) {
        return java.util.stream.Stream.of(
                        asset.getTechnicalMetadata(),
                        asset.getBusinessMetadataJson(),
                        asset.getDetailsJson())
                .filter(m -> m != null)
                .collect(Collectors.toList());
    }

    private Set<String> normalizedLabels(CatalogAssetDTO asset) {
        List<String> labels = asset.getLabels();
        if (labels == null) {
            return Set.of();
        }
        return labels.stream()
                .filter(l -> l != null)
                .map(l -> l.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String sensitivityLabelsOf(Set<String> normalizedLabels) {
        return normalizedLabels.stream()
                .filter(SENSITIVITY_LABELS::contains)
                .collect(Collectors.joining(", "));
    }

    private Set<String> allowedRegions(ComplianceControlEntity control) {
        Map<String, String> params = control.getEvaluationParametersDefinition();
        if (params != null) {
            String allowed = params.get("allowedRegions");
            if (allowed != null && !allowed.isBlank()) {
                return Arrays.stream(allowed.split("[,;\\s]+"))
                        .filter(s -> !s.isBlank())
                        .map(s -> s.trim().toUpperCase(Locale.ROOT))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            }
        }
        return DEFAULT_ALLOWED_REGIONS;
    }

    private ReportFindingData finding(ComplianceControlEntity control, CatalogAssetDTO asset,
                                      String status, String description, String recommendation,
                                      Map<String, Object> details) {
        return ReportFindingData.builder()
                .checkId(control.getControlId())
                .description(description)
                .assetId(asset.getAssetId())
                .assetType(asset.getAssetType())
                .assetName(asset.getAssetName())
                .status(status)
                .severity(control.getSeverity())
                .recommendation(recommendation)
                .details(details)
                .evidenceLink(control.getDetailsLink())
                .build();
    }
}
