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

package com.dlap.catalog.service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.catalog.dto.EnhancedLineageDTO;
import com.dlap.catalog.dto.EnhancedLineageDTO.LineageEdgeDTO;
import com.dlap.catalog.dto.EnhancedLineageDTO.LineageGraphDTO;
import com.dlap.catalog.dto.EnhancedLineageDTO.LineageNodeDTO;
import com.dlap.catalog.dto.EnhancedLineageDTO.LineageStatisticsDTO;
import com.dlap.catalog.model.AssetLineage;
import com.dlap.catalog.repository.AssetLineageRepository;
import com.dlap.common.model.entity.Asset;
import com.dlap.common.repository.IAssetRepository;

/**
 * Owns the write and read sides of the {@code dlap_asset_lineage} graph.
 *
 * <p><b>Write:</b> {@link #upsertEdgeByUri} resolves two asset URIs to catalog
 * assets and persists a directed lineage edge, de-duplicating on
 * (upstream, downstream, relationshipType). Called from the log-enrichment
 * listener for every edge a {@code LineageExtractor} derives; it never throws
 * for an unresolvable or duplicate edge so one bad edge can't poison a batch.
 *
 * <p><b>Read:</b> {@link #buildGraph} does a cycle-safe bidirectional BFS from a
 * root asset and assembles the rich {@link EnhancedLineageDTO} the visualization
 * endpoint returns - real nodes/edges/statistics, no mock data.
 */
@Service
public class LineageService {

    private static final Logger log = LoggerFactory.getLogger(LineageService.class);

    private final AssetLineageRepository lineageRepository;
    private final IAssetRepository assetRepository;

    public LineageService(AssetLineageRepository lineageRepository, IAssetRepository assetRepository) {
        this.lineageRepository = lineageRepository;
        this.assetRepository = assetRepository;
    }

    /**
     * Persist a lineage edge between two assets identified by URI.
     *
     * <p>Skips silently (debug-logged) when either URI is not yet catalogued:
     * a later discovery/enrichment event carries the asset, at which point a
     * re-processed log event re-creates the edge. Skips when an identical edge
     * already exists so replays are no-ops.
     *
     * @param upstreamUri      source asset URI (read side)
     * @param downstreamUri    target asset URI (write side)
     * @param relationshipType relationship kind stored on the edge
     * @param details          provenance stored on the edge; may be {@code null}
     */
    @Transactional
    public void upsertEdgeByUri(String upstreamUri, String downstreamUri, String relationshipType,
                                Map<String, Object> details) {
        if (isBlank(upstreamUri) || isBlank(downstreamUri)) {
            log.debug("Lineage edge skipped: blank URI (upstream={}, downstream={})", upstreamUri, downstreamUri);
            return;
        }
        if (upstreamUri.equals(downstreamUri)) {
            log.debug("Lineage edge skipped: self-referential URI {}", upstreamUri);
            return;
        }

        Optional<Asset> upstream = assetRepository.findByAssetUri(upstreamUri);
        Optional<Asset> downstream = assetRepository.findByAssetUri(downstreamUri);
        if (upstream.isEmpty() || downstream.isEmpty()) {
            log.debug("Lineage edge skipped: asset not yet catalogued (upstreamPresent={}, downstreamPresent={}) "
                            + "for {} -> {}",
                    upstream.isPresent(), downstream.isPresent(), upstreamUri, downstreamUri);
            return;
        }

        UUID upstreamId = upstream.get().getAssetId();
        UUID downstreamId = downstream.get().getAssetId();
        String relationship = relationshipType == null ? "RELATED" : relationshipType;

        boolean exists = lineageRepository.findByUpstreamAsset_AssetId(upstreamId).stream()
                .anyMatch(edge -> edge.getDownstreamAsset() != null
                        && downstreamId.equals(edge.getDownstreamAsset().getAssetId())
                        && relationship.equals(edge.getRelationshipType()));
        if (exists) {
            log.debug("Lineage edge already present: {} -[{}]-> {}", upstreamUri, relationship, downstreamUri);
            return;
        }

        AssetLineage edge = new AssetLineage();
        edge.setUpstreamAsset(upstream.get());
        edge.setDownstreamAsset(downstream.get());
        edge.setRelationshipType(relationship);
        edge.setDetailsJson(details);
        lineageRepository.save(edge);
        log.debug("Lineage edge created: {} -[{}]-> {}", upstreamUri, relationship, downstreamUri);
    }

    /**
     * Build the enhanced lineage graph rooted at {@code rootAssetId}.
     *
     * <p>Bidirectional BFS: upstream via {@code findByDownstreamAsset_AssetId},
     * downstream via {@code findByUpstreamAsset_AssetId}, bounded by
     * {@code maxDepth} and guarded against cycles with a visited set. When the
     * root has no lineage the graph is the root node alone (never mock data).
     *
     * @param rootAssetId the asset to centre the graph on
     * @param maxDepth    hop limit in each direction (values &lt; 1 are treated as 1)
     * @return a fully populated {@link EnhancedLineageDTO}
     */
    @Transactional(readOnly = true)
    public EnhancedLineageDTO buildGraph(UUID rootAssetId, int maxDepth) {
        int depth = Math.max(1, maxDepth);

        Asset root = assetRepository.findById(rootAssetId).orElse(null);
        EnhancedLineageDTO dto = new EnhancedLineageDTO(rootAssetId, root == null ? null : root.getAssetName());

        Map<UUID, LineageNodeDTO> nodes = new LinkedHashMap<>();
        Map<UUID, Asset> assets = new HashMap<>();
        Map<String, LineageEdgeDTO> edges = new LinkedHashMap<>();
        Map<UUID, Integer> layerByAsset = new HashMap<>();

        if (root != null) {
            assets.put(rootAssetId, root);
            layerByAsset.put(rootAssetId, 0);
        }

        Set<UUID> upstreamAssetIds = new HashSet<>();
        Set<UUID> downstreamAssetIds = new HashSet<>();
        int directUpstream = 0;
        int directDownstream = 0;

        // Upstream BFS (negative layers).
        Set<UUID> visitedUp = new HashSet<>();
        visitedUp.add(rootAssetId);
        Deque<UUID> queueUp = new ArrayDeque<>();
        queueUp.add(rootAssetId);
        for (int level = 1; level <= depth && !queueUp.isEmpty(); level++) {
            int frontier = queueUp.size();
            for (int i = 0; i < frontier; i++) {
                UUID current = queueUp.poll();
                for (AssetLineage edge : lineageRepository.findByDownstreamAsset_AssetId(current)) {
                    Asset upstream = edge.getUpstreamAsset();
                    if (upstream == null) {
                        continue;
                    }
                    UUID upstreamId = upstream.getAssetId();
                    assets.putIfAbsent(upstreamId, upstream);
                    upstreamAssetIds.add(upstreamId);
                    if (current.equals(rootAssetId)) {
                        directUpstream++;
                    }
                    registerEdge(edges, edge);
                    if (visitedUp.add(upstreamId)) {
                        layerByAsset.merge(upstreamId, -level, LineageService::closerToZero);
                        queueUp.add(upstreamId);
                    }
                }
            }
        }

        // Downstream BFS (positive layers).
        Set<UUID> visitedDown = new HashSet<>();
        visitedDown.add(rootAssetId);
        Deque<UUID> queueDown = new ArrayDeque<>();
        queueDown.add(rootAssetId);
        for (int level = 1; level <= depth && !queueDown.isEmpty(); level++) {
            int frontier = queueDown.size();
            for (int i = 0; i < frontier; i++) {
                UUID current = queueDown.poll();
                for (AssetLineage edge : lineageRepository.findByUpstreamAsset_AssetId(current)) {
                    Asset downstream = edge.getDownstreamAsset();
                    if (downstream == null) {
                        continue;
                    }
                    UUID downstreamId = downstream.getAssetId();
                    assets.putIfAbsent(downstreamId, downstream);
                    downstreamAssetIds.add(downstreamId);
                    if (current.equals(rootAssetId)) {
                        directDownstream++;
                    }
                    registerEdge(edges, edge);
                    if (visitedDown.add(downstreamId)) {
                        layerByAsset.merge(downstreamId, level, LineageService::closerToZero);
                        queueDown.add(downstreamId);
                    }
                }
            }
        }

        // Materialize nodes for every distinct asset reached (plus the root).
        for (Map.Entry<UUID, Asset> entry : assets.entrySet()) {
            nodes.put(entry.getKey(), toNode(entry.getValue(), layerByAsset.getOrDefault(entry.getKey(), 0)));
        }

        LineageGraphDTO graph = new LineageGraphDTO();
        graph.setNodes(new ArrayList<>(nodes.values()));
        graph.setEdges(new ArrayList<>(edges.values()));
        graph.setTotalNodes(nodes.size());
        graph.setTotalEdges(edges.size());
        graph.setMaxDepth(depth);
        dto.setLineageGraph(graph);

        dto.setStatistics(buildStatistics(upstreamAssetIds, downstreamAssetIds,
                directUpstream, directDownstream, edges.size(), assets.values()));
        dto.setLastUpdated(Instant.now());
        dto.setMaxDepth(depth);
        return dto;
    }

    /** Register an {@link AssetLineage} as a graph edge, de-duplicated by lineage id. */
    private static void registerEdge(Map<String, LineageEdgeDTO> edges, AssetLineage lineage) {
        Asset up = lineage.getUpstreamAsset();
        Asset down = lineage.getDownstreamAsset();
        if (up == null || down == null) {
            return;
        }
        String edgeId = lineage.getLineageId().toString();
        if (edges.containsKey(edgeId)) {
            return;
        }
        LineageEdgeDTO dto = new LineageEdgeDTO(edgeId, up.getAssetId(), down.getAssetId(),
                lineage.getRelationshipType());
        if (lineage.getDetailsJson() != null && !lineage.getDetailsJson().isEmpty()) {
            dto.setTransformationLogic(lineage.getDetailsJson());
        }
        dto.setCreatedAt(lineage.getCreatedAt());
        dto.setStyle("solid");
        edges.put(edgeId, dto);
    }

    /** Map an {@link Asset} to a visualization node, surfacing harvested owner/team when present. */
    private static LineageNodeDTO toNode(Asset asset, int layer) {
        LineageNodeDTO node = new LineageNodeDTO(asset.getAssetId(), asset.getAssetName(), "ASSET");
        node.setAssetType(asset.getAssetType());
        node.setStatus(asset.getStatus() != null ? asset.getStatus() : "UNKNOWN");
        node.setLayer(layer);

        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "cloudProvider", asset.getCloudProvider());
        putIfPresent(metadata, "region", asset.getRegion());
        putIfPresent(metadata, "serviceType", asset.getServiceType());
        putIfPresent(metadata, "assetUri", asset.getAssetUri());
        putIfPresent(metadata, "ownerInfo", asset.getOwnerInfo());

        // Surface harvested owner/team from the enrichment listener if present.
        Map<String, Object> business = asset.getBusinessMetadataJson();
        if (business != null) {
            Object harvested = business.get("harvested");
            if (harvested instanceof Map<?, ?> h) {
                putIfPresent(metadata, "harvestedOwner", stringOrNull(h.get("harvestedOwner")));
                putIfPresent(metadata, "harvestedTeam", stringOrNull(h.get("harvestedTeam")));
            }
        }
        if (!metadata.isEmpty()) {
            node.setMetadata(metadata);
        }
        return node;
    }

    private static LineageStatisticsDTO buildStatistics(Set<UUID> upstreamAssetIds, Set<UUID> downstreamAssetIds,
                                                        int directUpstream, int directDownstream,
                                                        int totalEdges, Iterable<Asset> assets) {
        LineageStatisticsDTO stats = new LineageStatisticsDTO();
        stats.setTotalUpstreamAssets(upstreamAssetIds.size());
        stats.setTotalDownstreamAssets(downstreamAssetIds.size());
        stats.setTotalTransformations(totalEdges);
        int direct = directUpstream + directDownstream;
        stats.setDirectDependencies(direct);
        int total = upstreamAssetIds.size() + downstreamAssetIds.size();
        stats.setIndirectDependencies(Math.max(0, total - direct));

        Map<String, Integer> assetTypeBreakdown = new HashMap<>();
        for (Asset asset : assets) {
            String type = asset.getAssetType() != null ? asset.getAssetType()
                    : (asset.getServiceType() != null ? asset.getServiceType() : "UNKNOWN");
            assetTypeBreakdown.merge(type, 1, Integer::sum);
        }
        stats.setAssetTypeBreakdown(assetTypeBreakdown);
        stats.setLineageHealth(total == 0 ? "INCOMPLETE" : "HEALTHY");
        return stats;
    }

    /** For layer merges: keep the hop count nearest to the root (smallest magnitude). */
    private static int closerToZero(int existing, int candidate) {
        return Math.abs(candidate) < Math.abs(existing) ? candidate : existing;
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
