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

package com.dlap.catalog.web.rest;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.dlap.catalog.dto.AssetActivityPageDTO;
import com.dlap.catalog.dto.AssetComplianceStatusDTO;
import com.dlap.catalog.dto.AssetIdResolutionDTO;
import com.dlap.catalog.dto.AssetInputDTO;
import com.dlap.catalog.dto.AssetLabelsPostRequestDTO;
import com.dlap.catalog.dto.AssetLabelsResponseDTO;
import com.dlap.catalog.dto.AssetOutputDTO;
import com.dlap.catalog.dto.AssetPolicyMappingDTO;
import com.dlap.catalog.dto.AssetSchemaDTO;
import com.dlap.catalog.dto.AssetUsageAnalyticsDTO;
import com.dlap.catalog.dto.BusinessMetadataInputDTO;
import com.dlap.catalog.dto.BusinessMetadataResponseDTO;
import com.dlap.catalog.dto.CatalogFiltersDTO;
import com.dlap.catalog.dto.EnhancedLineageDTO;
import com.dlap.catalog.dto.LabelOutputDTO;
import com.dlap.catalog.dto.LineageResponseDTO;
import com.dlap.catalog.security.SecurityUtils;
import com.dlap.catalog.service.IAssetService;
import com.dlap.catalog.service.IAssetService.ActivityQuery;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/catalog") // Base path for catalog service
public class AssetController {

    private static final Logger log = LoggerFactory.getLogger(AssetController.class);

    /**
     * Who may read the activity report: the nine human roles of the Access Model (every tier may
     * view reports and browse the data estate, docs/specs/access-model.md) plus the machine
     * principal, so the reporting service can fetch rows with its service-account token
     * (ADR-0034). A token with no DLAP role is denied.
     */
    static final String ACTIVITY_READ_ACCESS = "hasAnyAuthority('ROLE_ADMIN', 'ROLE_CDO', 'ROLE_DIRECTOR', "
            + "'ROLE_POLICY_MANAGER', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', "
            + "'ROLE_AUDITOR', 'ROLE_VIEWER', 'ROLE_SERVICE_ACCOUNT')";

    private final IAssetService assetService;

    @Autowired
    public AssetController(IAssetService assetService) {
        this.assetService = assetService;
    }

    @GetMapping("/assets")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_SERVICE_ACCOUNT')") // + M2M service account
    public ResponseEntity<Page<AssetOutputDTO>> getAllAssets(
            @PageableDefault(size = 20, sort = "assetName") Pageable pageable,
            @RequestParam(required = false) String cloudProvider,
            @RequestParam(required = false) String assetType,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) List<String> labels,
            @RequestParam(required = false) String nameContains,
            @RequestParam(required = false) UUID connectionId) {
        log.info("REST request to get all Assets with filters: provider={}, type={}, region={}, labels={}, name={}, connectionId={}",
                cloudProvider, assetType, region, labels, nameContains, connectionId);
        Page<AssetOutputDTO> assetPage = assetService.getAllAssets(pageable, cloudProvider, assetType, region, labels, nameContains, connectionId);
        return ResponseEntity.ok(assetPage);
    }

    @GetMapping("/assets/{assetId}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_SERVICE_ACCOUNT')") // + M2M service account
    public ResponseEntity<AssetOutputDTO> getAssetById(@PathVariable UUID assetId) {
        log.info("REST request to get Asset by id: {}", assetId);
        AssetOutputDTO assetDTO = assetService.getAssetById(assetId);
        return ResponseEntity.ok(assetDTO);
    }

    /**
     * Paged, filtered report of what the catalog knows about each asset's age, size, use and
     * retention as of one instant. M2M-reachable.
     *
     * <p>Every filter is optional. Query parameters are parsed here, not by Spring's converters, so
     * a malformed value is a 400 with a message and never a 500. Repeatable filters take either
     * repeated parameters or a comma-separated list. A last read that is unknown matches neither
     * {@code lastReadBefore} nor {@code lastReadAfter}; use {@code lastReadKnown=false} for those.
     *
     * @param pageable           page, size (at most 200) and sort; default {@code assetUri} ascending
     * @param after              keyset cursor: the {@code assetUri} of the last row already seen. When
     *                           present, the page is the next {@code size} assets after it in
     *                           {@code assetUri} order and {@code page} must be 0 and the sort
     *                           {@code assetUri} ascending (Q-445)
     * @param provider           cloud providers, any of
     * @param kind               asset kinds ({@code serviceType}), any of
     * @param status             lifecycle statuses, any of
     * @param lastReadBefore     ISO-8601 instant; only assets last read strictly before it
     * @param lastReadAfter      ISO-8601 instant; only assets last read strictly after it
     * @param lastReadKnown      {@code true}: a last read is recorded; {@code false}: none is
     * @param retentionStatus    stored retention statuses, any of
     * @param legalHold          {@code true}: only held assets; {@code false}: only the others
     * @param readCoverage       read coverage, any of {@code FULL}, {@code PARTIAL}, {@code NONE},
     *                           {@code UNKNOWN}
     * @param asOf               ISO-8601 instant the day counts are computed against; now when absent
     * @return one page of activity rows, with {@code nextAfter}, the cursor of the next keyset page
     *         ({@code null} on the last page)
     */
    @GetMapping("/assets/activity")
    @PreAuthorize(ACTIVITY_READ_ACCESS)
    @Operation(
        summary = "Asset activity report",
        description = "Page through assets with their creation time, size, last read, last write and retention "
                + "state, computed as of one instant. Unknown values are null and never read as stale or fresh. "
                + "Filters: provider, kind, status, last-read window, retention status, legal hold and read coverage. "
                + "Paging is by page and size, or by keyset: send after=<nextAfter of the previous page> and size=N "
                + "to walk the assets in assetUri order without a page shifting when assets are written; the walk "
                + "ends when nextAfter is null.",
        tags = {"Assets", "Reports"},
        responses = {
            @ApiResponse(responseCode = "200", description = "One page of activity rows"),
            @ApiResponse(responseCode = "400", description = "A filter, the sort, a time or the cursor is malformed"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions")
        }
    )
    public ResponseEntity<AssetActivityPageDTO> getAssetActivity(
            @PageableDefault(size = 50, sort = "assetUri") Pageable pageable,
            @Parameter(description = "Keyset cursor: the assetUri of the last row already seen (the previous page's "
                    + "nextAfter). Needs page 0 and the default assetUri ascending sort. Absent or blank: ordinary "
                    + "page and size paging.")
            @RequestParam(required = false) String after,
            @Parameter(description = "Cloud providers, for example AWS or GCP; repeat or comma-separate")
            @RequestParam(required = false) List<String> provider,
            @Parameter(description = "Asset kinds (serviceType), for example GCS_BUCKET; repeat or comma-separate")
            @RequestParam(required = false) List<String> kind,
            @Parameter(description = "Lifecycle statuses: ACTIVE, ARCHIVED, PENDING_DELETION, DELETED")
            @RequestParam(required = false) List<String> status,
            @Parameter(description = "Only assets last read strictly before this ISO-8601 instant, for example 2026-07-19T00:00:00Z")
            @RequestParam(required = false) String lastReadBefore,
            @Parameter(description = "Only assets last read strictly after this ISO-8601 instant")
            @RequestParam(required = false) String lastReadAfter,
            @Parameter(description = "true: only assets with a recorded last read; false: only assets without one")
            @RequestParam(required = false) String lastReadKnown,
            @Parameter(description = "Stored retention status: UNKNOWN, NO_POLICY, RETAINED, DUE, ON_HOLD")
            @RequestParam(required = false) List<String> retentionStatus,
            @Parameter(description = "true: only assets on legal hold; false: only assets not held")
            @RequestParam(required = false) String legalHold,
            @Parameter(description = "Read coverage: FULL, PARTIAL, NONE, or UNKNOWN for none recorded")
            @RequestParam(required = false) List<String> readCoverage,
            @Parameter(description = "ISO-8601 instant the day counts are computed against; now when absent")
            @RequestParam(required = false) String asOf) {
        ActivityQuery query = new ActivityQuery(
                provider, kind, status,
                parseInstant("lastReadBefore", lastReadBefore),
                parseInstant("lastReadAfter", lastReadAfter),
                parseBoolean("lastReadKnown", lastReadKnown),
                retentionStatus,
                parseBoolean("legalHold", legalHold),
                readCoverage,
                parseInstant("asOf", asOf));
        if (after != null && !after.isBlank()) {
            if (pageable.getPageNumber() != 0 || !AssetActivityPageDTO.isAssetUriAscending(pageable)) {
                throw new IllegalArgumentException("after pages by assetUri ascending: send page 0 (or none) and "
                        + "no sort other than assetUri,asc");
            }
            log.info("REST request for the asset activity report: keyset, size={}", pageable.getPageSize());
            return ResponseEntity.ok(assetService.getAssetActivityKeyset(query, after, pageable.getPageSize()));
        }
        log.info("REST request for the asset activity report: page={}, size={}", pageable.getPageNumber(),
                pageable.getPageSize());
        return ResponseEntity.ok(AssetActivityPageDTO.ofOffsetPage(assetService.getAssetActivity(query, pageable),
                pageable));
    }

    /** Parses an ISO-8601 instant ({@code Z} or an offset); blank means absent. */
    private static Instant parseInstant(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException notZulu) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(name + " must be an ISO-8601 instant such as 2026-07-19T00:00:00Z");
            }
        }
    }

    /** Parses {@code true} or {@code false}, ignoring case; blank means absent. */
    private static Boolean parseBoolean(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        if ("true".equalsIgnoreCase(text)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(text)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException(name + " must be true or false");
    }

    /**
     * Resolve an asset URI to its catalog id (the join key). M2M-reachable. The URI may be in any
     * form {@code AssetIdNormalizer} knows (ADR-0047): the canonical URI, an ARN, a resource name.
     * The response echoes the URI as the caller sent it.
     */
    @GetMapping("/assets/resolve")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_SERVICE_ACCOUNT')") // + M2M service account
    public ResponseEntity<AssetIdResolutionDTO> resolveAssetIdByUri(@RequestParam("assetUri") String assetUri) {
        return assetService.resolveAssetIdByUri(assetUri)
                .map(id -> ResponseEntity.ok(new AssetIdResolutionDTO(id, assetUri)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/assets")
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or @systemSecurityService.isSystemUser(authentication)") // Example: Admin or System
    public ResponseEntity<AssetOutputDTO> createAsset(@Valid @RequestBody AssetInputDTO assetInputDTO) {
        log.info("REST request to create Asset: {}", assetInputDTO.getAssetName());
        UUID creatorUserId = SecurityUtils.getAuthenticatedUserId();

        AssetOutputDTO createdAsset = assetService.createAsset(assetInputDTO, creatorUserId);

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(createdAsset.getAssetId())
                .toUri();

        return ResponseEntity.created(location).body(createdAsset);
    }
    
    @PutMapping("/assets/{assetId}/metadata/business")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')") // Example roles, adjust as needed
    public ResponseEntity<AssetOutputDTO> updateBusinessMetadata(
            @PathVariable UUID assetId,
            @RequestBody Map<String, Object> businessMetadata) {
        log.info("REST request to update business metadata for Asset ID: {}", assetId);
        UUID updaterUserId = SecurityUtils.getAuthenticatedUserId();

        AssetOutputDTO updatedAsset = assetService.updateBusinessMetadata(assetId, businessMetadata, updaterUserId);
        return ResponseEntity.ok(updatedAsset);
    }

    @PutMapping("/assets/{assetId}/business-context")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')") 
    @Operation(
        summary = "Update enhanced business context and metadata",
        description = "Update comprehensive business metadata including steward relationships, compliance classification, and business context",
        tags = {"Assets", "Business Metadata"}
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Business context updated successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid business metadata input"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions"),
        @ApiResponse(responseCode = "404", description = "Asset not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<BusinessMetadataResponseDTO> updateBusinessContext(
            @Parameter(description = "Asset ID", required = true) @PathVariable UUID assetId,
            @Parameter(description = "Enhanced business metadata with steward relationships and compliance classification", required = true)
            @Valid @RequestBody BusinessMetadataInputDTO businessMetadataInput) {
        log.info("REST request to update enhanced business context for Asset ID: {}", assetId);
        UUID updaterUserId = SecurityUtils.getAuthenticatedUserId();

        BusinessMetadataResponseDTO response = assetService.updateBusinessContext(assetId, businessMetadataInput, updaterUserId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/assets/{assetId}/business-metadata")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')") 
    @Operation(
        summary = "Get comprehensive business metadata",
        description = "Retrieve complete business metadata including business context, steward relationships, compliance classification, audit information, and validation status",
        tags = {"Assets", "Business Metadata"}
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Business metadata retrieved successfully"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions"),
        @ApiResponse(responseCode = "404", description = "Asset not found"),
        @ApiResponse(responseCode = "500", description = "Internal server error")
    })
    public ResponseEntity<BusinessMetadataResponseDTO> getBusinessMetadata(
            @Parameter(description = "Asset ID", required = true) @PathVariable UUID assetId) {
        log.info("REST request to get business metadata for Asset ID: {}", assetId);
        
        BusinessMetadataResponseDTO response = assetService.getBusinessMetadata(assetId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/assets/{assetId}/lineage")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')") // Adjust roles as needed
    public ResponseEntity<LineageResponseDTO> getAssetLineage(@PathVariable UUID assetId) {
        log.info("REST request to get lineage for Asset ID: {}", assetId);
        LineageResponseDTO lineageResponse = assetService.getAssetLineage(assetId);
        return ResponseEntity.ok(lineageResponse);
    }

    @GetMapping("/assets/{assetId}/labels")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')") // Adjust roles
    public ResponseEntity<AssetLabelsResponseDTO> getAssetLabels(@PathVariable UUID assetId) {
        log.info("REST request to get labels for Asset ID: {}", assetId);
        List<LabelOutputDTO> labels = assetService.getAssetLabels(assetId);
        return ResponseEntity.ok(new AssetLabelsResponseDTO(labels));
    }

    @PostMapping("/assets/{assetId}/labels")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_SERVICE_ACCOUNT')") // + M2M service account (da-autolabel)
    public ResponseEntity<AssetLabelsResponseDTO> assignLabelsToAsset(
            @PathVariable UUID assetId,
            @Valid @RequestBody AssetLabelsPostRequestDTO labelsToAssignRequest) {
        log.info("REST request to assign labels to Asset ID: {}", assetId);
        UUID assignerUserId = SecurityUtils.getAuthenticatedUserId();

        List<LabelOutputDTO> updatedLabels = assetService.assignLabelsToAsset(assetId, labelsToAssignRequest, assignerUserId);
        return ResponseEntity.ok(new AssetLabelsResponseDTO(updatedLabels));
    }

    @DeleteMapping("/assets/{assetId}/labels/{labelName}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_SERVICE_ACCOUNT')") // + M2M service account (da-autolabel)
    public ResponseEntity<Void> removeLabelFromAsset(
            @PathVariable UUID assetId,
            @PathVariable String labelName) {
        log.info("REST request to remove label '{}' from Asset ID: {}", labelName, assetId);
        UUID removerUserId = SecurityUtils.getAuthenticatedUserId();

        assetService.removeLabelFromAsset(assetId, labelName, removerUserId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/assets/{assetId}/usage")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(
        summary = "Get asset usage analytics",
        description = "Retrieve comprehensive usage analytics for a specific asset including access patterns, user behavior, performance metrics, and optimization recommendations",
        tags = {"Asset Management"},
        responses = {
            @ApiResponse(responseCode = "200", description = "Usage analytics retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Asset not found"),
            @ApiResponse(responseCode = "403", description = "Access denied")
        }
    )
    public ResponseEntity<AssetUsageAnalyticsDTO> getAssetUsageAnalytics(
            @Parameter(description = "Asset ID", required = true) @PathVariable UUID assetId) {
        
        AssetUsageAnalyticsDTO analytics = assetService.getAssetUsageAnalytics(assetId);
        return ResponseEntity.ok(analytics);
    }

    @GetMapping("/assets/{assetId}/schema")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(
        summary = "Get comprehensive asset schema information",
        description = "Retrieve detailed schema structure including field metadata, data types, constraints, indexes, statistics, lineage, and evolution history. This Priority 2 endpoint provides enhanced catalog functionality for complete asset understanding.",
        tags = {"Asset Management", "Schema"},
        responses = {
            @ApiResponse(
                responseCode = "200", 
                description = "Asset schema information retrieved successfully",
                content = @Content(
                    mediaType = "application/json",
                    schema = @Schema(implementation = AssetSchemaDTO.class),
                    examples = @ExampleObject(
                        name = "Customer Table Schema",
                        description = "Example schema for a customer table with comprehensive metadata",
                        value = """
                        {
                          "assetId": "123e4567-e89b-12d3-a456-426614174000",
                          "assetName": "customer_master_table",
                          "schemaVersion": "2.1.0",
                          "schemaType": {
                            "type": "TABLE",
                            "format": "PARQUET",
                            "encoding": "UTF-8"
                          },
                          "fields": [
                            {
                              "fieldName": "customer_id",
                              "dataType": "BIGINT",
                              "logicalType": "IDENTIFIER",
                              "nullable": false,
                              "primaryKey": true,
                              "description": "Unique identifier for customer",
                              "classification": "PII",
                              "statistics": {
                                "distinctCount": 125000,
                                "fillRate": 100.0
                              }
                            }
                          ],
                          "statistics": {
                            "totalRows": 125000,
                            "totalColumns": 5,
                            "totalSize": 52428800,
                            "compressionRatio": 0.75
                          }
                        }
                        """
                    )
                )
            ),
            @ApiResponse(responseCode = "404", description = "Asset not found"),
            @ApiResponse(responseCode = "403", description = "Access denied - insufficient permissions"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
        }
    )
    public ResponseEntity<AssetSchemaDTO> getAssetSchema(
            @Parameter(
                description = "Unique identifier of the asset to retrieve schema information for",
                required = true,
                example = "123e4567-e89b-12d3-a456-426614174000"
            ) 
            @PathVariable UUID assetId) {
        
        AssetSchemaDTO schema = assetService.getAssetSchema(assetId);
        return ResponseEntity.ok(schema);
    }

    @GetMapping("/assets/{assetId}/lineage/enhanced")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(
        summary = "Get enhanced data lineage visualization",
        description = "Retrieve comprehensive lineage information with visualization metadata, graph nodes, edges, statistics, and critical path analysis. This Priority 2 endpoint provides detailed lineage for interactive graph rendering.",
        tags = {"Asset Management", "Lineage"},
        responses = {
            @ApiResponse(responseCode = "200", description = "Enhanced lineage information retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Asset not found"),
            @ApiResponse(responseCode = "403", description = "Access denied"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
        }
    )
    public ResponseEntity<EnhancedLineageDTO> getEnhancedLineage(
            @Parameter(description = "Asset ID", required = true) @PathVariable UUID assetId,
            @Parameter(description = "Maximum lineage depth to traverse", example = "5") 
            @RequestParam(required = false, defaultValue = "5") Integer maxDepth) {
        
        EnhancedLineageDTO lineage = assetService.getEnhancedLineage(assetId, maxDepth);
        return ResponseEntity.ok(lineage);
    }

    @GetMapping("/assets/{assetId}/policies")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(
        summary = "Get asset policy mapping",
        description = "Retrieve information about which policies apply to an asset, their compliance status, risk assessment, and recommendations. This Priority 2 endpoint provides policy-asset relationship details.",
        tags = {"Asset Management", "Policy"},
        responses = {
            @ApiResponse(responseCode = "200", description = "Asset policy mapping retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Asset not found"),
            @ApiResponse(responseCode = "403", description = "Access denied"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
        }
    )
    public ResponseEntity<AssetPolicyMappingDTO> getAssetPolicyMapping(
            @Parameter(description = "Asset ID", required = true) @PathVariable UUID assetId) {
        
        AssetPolicyMappingDTO mapping = assetService.getAssetPolicyMapping(assetId);
        return ResponseEntity.ok(mapping);
    }

    @GetMapping("/filters")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    @Operation(
        summary = "Get catalog faceted search filters",
        description = "Retrieve filter metadata for advanced search and filtering in the catalog UI. This Priority 2 endpoint provides filter categories, options, quick filters, and search capabilities.",
        tags = {"Catalog", "Search"},
        responses = {
            @ApiResponse(responseCode = "200", description = "Catalog filters retrieved successfully"),
            @ApiResponse(responseCode = "403", description = "Access denied"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
        }
    )
    public ResponseEntity<CatalogFiltersDTO> getCatalogFilters() {
        
        CatalogFiltersDTO filters = assetService.getCatalogFilters();
        return ResponseEntity.ok(filters);
    }

    @GetMapping("/assets/{assetId}/compliance")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR', 'ROLE_AUDITOR')")
    @Operation(
        summary = "Get asset compliance status",
        description = "Retrieve detailed compliance status including framework compliance, violations, controls, risk assessment, and recommendations. This Priority 2 endpoint provides comprehensive compliance monitoring.",
        tags = {"Asset Management", "Compliance"},
        responses = {
            @ApiResponse(responseCode = "200", description = "Asset compliance status retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Asset not found"),
            @ApiResponse(responseCode = "403", description = "Access denied"),
            @ApiResponse(responseCode = "500", description = "Internal server error")
        }
    )
    public ResponseEntity<AssetComplianceStatusDTO> getAssetComplianceStatus(
            @Parameter(description = "Asset ID", required = true) @PathVariable UUID assetId) {
        
        AssetComplianceStatusDTO compliance = assetService.getAssetComplianceStatus(assetId);
        return ResponseEntity.ok(compliance);
    }

    @GetMapping("/assets/count")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_POLICY_MANAGER', 'ROLE_AUDITOR', 'ROLE_SERVICE_ACCOUNT')")
    public ResponseEntity<Long> countTotalAssets() {
        return ResponseEntity.ok(assetService.countTotalAssets());
    }

    @GetMapping("/assets/count/classified")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_POLICY_MANAGER', 'ROLE_AUDITOR', 'ROLE_SERVICE_ACCOUNT')")
    public ResponseEntity<Long> countClassifiedAssets() {
        return ResponseEntity.ok(assetService.countClassifiedAssets());
    }

    @GetMapping("/assets/count/with-metadata")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_POLICY_MANAGER', 'ROLE_AUDITOR', 'ROLE_SERVICE_ACCOUNT')")
    public ResponseEntity<Long> countAssetsWithMetadata() {
        return ResponseEntity.ok(assetService.countAssetsWithMetadata());
    }

    @GetMapping("/assets/count/with-business-context")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_POLICY_MANAGER', 'ROLE_AUDITOR', 'ROLE_SERVICE_ACCOUNT')")
    public ResponseEntity<Long> countAssetsWithBusinessContext() {
        return ResponseEntity.ok(assetService.countAssetsWithBusinessContext());
    }

    @GetMapping("/assets/count/with-lineage")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_POLICY_MANAGER', 'ROLE_AUDITOR', 'ROLE_SERVICE_ACCOUNT')")
    public ResponseEntity<Long> countAssetsWithLineage() {
        return ResponseEntity.ok(assetService.countAssetsWithLineage());
    }

    @GetMapping("/assets/lineage/connections/count")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_POLICY_MANAGER', 'ROLE_AUDITOR', 'ROLE_SERVICE_ACCOUNT')")
    public ResponseEntity<Long> countLineageConnections() {
        return ResponseEntity.ok(assetService.countLineageConnections());
    }

    @GetMapping("/assets/count/status/{status}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_POLICY_MANAGER', 'ROLE_AUDITOR', 'ROLE_SERVICE_ACCOUNT')")
    public ResponseEntity<Long> countAssetsByStatus(@PathVariable String status) {
        return ResponseEntity.ok(assetService.countAssetsByStatus(status));
    }

    @GetMapping("/assets/governance-issues")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_POLICY_MANAGER', 'ROLE_AUDITOR', 'ROLE_SERVICE_ACCOUNT')")
    public ResponseEntity<Map<String, Object>> getGovernanceIssues(
            @RequestParam(required = false) String severity) {
        return ResponseEntity.ok(assetService.getGovernanceIssuesSnapshot(severity));
    }

    @GetMapping("/assets/classification-breakdown")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST', 'ROLE_POLICY_MANAGER', 'ROLE_AUDITOR', 'ROLE_SERVICE_ACCOUNT')")
    public ResponseEntity<Map<String, Long>> getClassificationBreakdown() {
        return ResponseEntity.ok(assetService.getClassificationBreakdown());
    }

    // TODO: Implement other Asset related endpoints (Taxonomy APIs)
} 