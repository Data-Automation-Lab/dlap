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

package com.dlap.catalog.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DTO for Enhanced Data Lineage Visualization.
 * Priority 2 endpoint: Provides detailed lineage information with visualization metadata for graph rendering.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnhancedLineageDTO {

    private UUID assetId;
    private String assetName;
    private LineageGraphDTO lineageGraph;
    private LineageStatisticsDTO statistics;
    private List<LineagePathDTO> criticalPaths;
    private List<LineageIssueDTO> issues;
    private LineageVisualizationMetadataDTO visualizationMetadata;
    private Instant lastUpdated;
    private String lineageVersion;
    private Integer maxDepth;

    /**
     * Default constructor.
     */
    public EnhancedLineageDTO() {}

    /**
     * Constructor with basic information.
     */
    public EnhancedLineageDTO(UUID assetId, String assetName) {
        this.assetId = assetId;
        this.assetName = assetName;
    }

    /**
     * DTO for lineage graph structure.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LineageGraphDTO {
        
        private List<LineageNodeDTO> nodes;
        private List<LineageEdgeDTO> edges;
        private Map<String, Object> layout; // Positioning and layout information for UI
        private Integer totalNodes;
        private Integer totalEdges;
        private Integer maxDepth;
        private List<String> layers; // Hierarchical layer information

        public LineageGraphDTO() {}

        // Getters and setters
        public List<LineageNodeDTO> getNodes() { return nodes; }
        public void setNodes(List<LineageNodeDTO> nodes) { this.nodes = nodes; }
        public List<LineageEdgeDTO> getEdges() { return edges; }
        public void setEdges(List<LineageEdgeDTO> edges) { this.edges = edges; }
        public Map<String, Object> getLayout() { return layout; }
        public void setLayout(Map<String, Object> layout) { this.layout = layout; }
        public Integer getTotalNodes() { return totalNodes; }
        public void setTotalNodes(Integer totalNodes) { this.totalNodes = totalNodes; }
        public Integer getTotalEdges() { return totalEdges; }
        public void setTotalEdges(Integer totalEdges) { this.totalEdges = totalEdges; }
        public Integer getMaxDepth() { return maxDepth; }
        public void setMaxDepth(Integer maxDepth) { this.maxDepth = maxDepth; }
        public List<String> getLayers() { return layers; }
        public void setLayers(List<String> layers) { this.layers = layers; }
    }

    /**
     * DTO for lineage node information.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LineageNodeDTO {
        
        private UUID nodeId;
        private String nodeName;
        private String nodeType; // ASSET, TRANSFORMATION, PROCESS, EXTERNAL_SOURCE
        private String assetType; // TABLE, VIEW, FILE, API, etc.
        private String description;
        private Map<String, Object> position; // x, y coordinates for visualization
        private String status; // ACTIVE, INACTIVE, DEPRECATED, UNKNOWN
        private List<String> tags;
        private Map<String, Object> metadata;
        private NodeStatsDTO statistics;
        private String icon; // Icon identifier for UI
        private String color; // Color code for visualization
        private Integer layer; // Hierarchical layer number

        public LineageNodeDTO() {}

        public LineageNodeDTO(UUID nodeId, String nodeName, String nodeType) {
            this.nodeId = nodeId;
            this.nodeName = nodeName;
            this.nodeType = nodeType;
        }

        // Getters and setters
        public UUID getNodeId() { return nodeId; }
        public void setNodeId(UUID nodeId) { this.nodeId = nodeId; }
        public String getNodeName() { return nodeName; }
        public void setNodeName(String nodeName) { this.nodeName = nodeName; }
        public String getNodeType() { return nodeType; }
        public void setNodeType(String nodeType) { this.nodeType = nodeType; }
        public String getAssetType() { return assetType; }
        public void setAssetType(String assetType) { this.assetType = assetType; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Map<String, Object> getPosition() { return position; }
        public void setPosition(Map<String, Object> position) { this.position = position; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
        public NodeStatsDTO getStatistics() { return statistics; }
        public void setStatistics(NodeStatsDTO statistics) { this.statistics = statistics; }
        public String getIcon() { return icon; }
        public void setIcon(String icon) { this.icon = icon; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
        public Integer getLayer() { return layer; }
        public void setLayer(Integer layer) { this.layer = layer; }
    }

    /**
     * DTO for node statistics.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class NodeStatsDTO {
        
        private Long recordCount;
        private Long sizeBytes;
        private Instant lastAccessed;
        private Instant lastModified;
        private Integer usageFrequency;
        private String healthStatus; // HEALTHY, WARNING, ERROR, UNKNOWN

        public NodeStatsDTO() {}

        // Getters and setters
        public Long getRecordCount() { return recordCount; }
        public void setRecordCount(Long recordCount) { this.recordCount = recordCount; }
        public Long getSizeBytes() { return sizeBytes; }
        public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
        public Instant getLastAccessed() { return lastAccessed; }
        public void setLastAccessed(Instant lastAccessed) { this.lastAccessed = lastAccessed; }
        public Instant getLastModified() { return lastModified; }
        public void setLastModified(Instant lastModified) { this.lastModified = lastModified; }
        public Integer getUsageFrequency() { return usageFrequency; }
        public void setUsageFrequency(Integer usageFrequency) { this.usageFrequency = usageFrequency; }
        public String getHealthStatus() { return healthStatus; }
        public void setHealthStatus(String healthStatus) { this.healthStatus = healthStatus; }
    }

    /**
     * DTO for lineage edge (relationship) information.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LineageEdgeDTO {
        
        private String edgeId;
        private UUID sourceNodeId;
        private UUID targetNodeId;
        private String relationshipType; // DERIVED_FROM, WRITES_TO, READS_FROM, TRANSFORMS_TO
        private String transformationType; // ETL, ELT, COPY, AGGREGATE, JOIN, FILTER, etc.
        private List<FieldMappingDTO> fieldMappings;
        private String description;
        private Map<String, Object> transformationLogic;
        private Instant createdAt;
        private Instant lastExecuted;
        private String executionStatus; // SUCCESS, FAILED, RUNNING, SCHEDULED
        private EdgeStatsDTO statistics;
        private String style; // Styling information for visualization (dashed, solid, etc.)
        private String color; // Color for the edge in visualization

        public LineageEdgeDTO() {}

        public LineageEdgeDTO(String edgeId, UUID sourceNodeId, UUID targetNodeId, String relationshipType) {
            this.edgeId = edgeId;
            this.sourceNodeId = sourceNodeId;
            this.targetNodeId = targetNodeId;
            this.relationshipType = relationshipType;
        }

        // Getters and setters
        public String getEdgeId() { return edgeId; }
        public void setEdgeId(String edgeId) { this.edgeId = edgeId; }
        public UUID getSourceNodeId() { return sourceNodeId; }
        public void setSourceNodeId(UUID sourceNodeId) { this.sourceNodeId = sourceNodeId; }
        public UUID getTargetNodeId() { return targetNodeId; }
        public void setTargetNodeId(UUID targetNodeId) { this.targetNodeId = targetNodeId; }
        public String getRelationshipType() { return relationshipType; }
        public void setRelationshipType(String relationshipType) { this.relationshipType = relationshipType; }
        public String getTransformationType() { return transformationType; }
        public void setTransformationType(String transformationType) { this.transformationType = transformationType; }
        public List<FieldMappingDTO> getFieldMappings() { return fieldMappings; }
        public void setFieldMappings(List<FieldMappingDTO> fieldMappings) { this.fieldMappings = fieldMappings; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Map<String, Object> getTransformationLogic() { return transformationLogic; }
        public void setTransformationLogic(Map<String, Object> transformationLogic) { this.transformationLogic = transformationLogic; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public Instant getLastExecuted() { return lastExecuted; }
        public void setLastExecuted(Instant lastExecuted) { this.lastExecuted = lastExecuted; }
        public String getExecutionStatus() { return executionStatus; }
        public void setExecutionStatus(String executionStatus) { this.executionStatus = executionStatus; }
        public EdgeStatsDTO getStatistics() { return statistics; }
        public void setStatistics(EdgeStatsDTO statistics) { this.statistics = statistics; }
        public String getStyle() { return style; }
        public void setStyle(String style) { this.style = style; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
    }

    /**
     * DTO for field mapping information.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FieldMappingDTO {
        
        private String sourceField;
        private String targetField;
        private String mappingType; // DIRECT, TRANSFORMED, CALCULATED, DERIVED
        private String transformation;
        private Double confidence; // 0.0 to 1.0

        public FieldMappingDTO() {}

        public FieldMappingDTO(String sourceField, String targetField, String mappingType) {
            this.sourceField = sourceField;
            this.targetField = targetField;
            this.mappingType = mappingType;
        }

        // Getters and setters
        public String getSourceField() { return sourceField; }
        public void setSourceField(String sourceField) { this.sourceField = sourceField; }
        public String getTargetField() { return targetField; }
        public void setTargetField(String targetField) { this.targetField = targetField; }
        public String getMappingType() { return mappingType; }
        public void setMappingType(String mappingType) { this.mappingType = mappingType; }
        public String getTransformation() { return transformation; }
        public void setTransformation(String transformation) { this.transformation = transformation; }
        public Double getConfidence() { return confidence; }
        public void setConfidence(Double confidence) { this.confidence = confidence; }
    }

    /**
     * DTO for edge statistics.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EdgeStatsDTO {
        
        private Long recordsProcessed;
        private Double averageProcessingTime; // in milliseconds
        private Integer executionCount;
        private Instant lastSuccessfulExecution;
        private Integer failureCount;
        private String reliability; // HIGH, MEDIUM, LOW

        public EdgeStatsDTO() {}

        // Getters and setters
        public Long getRecordsProcessed() { return recordsProcessed; }
        public void setRecordsProcessed(Long recordsProcessed) { this.recordsProcessed = recordsProcessed; }
        public Double getAverageProcessingTime() { return averageProcessingTime; }
        public void setAverageProcessingTime(Double averageProcessingTime) { this.averageProcessingTime = averageProcessingTime; }
        public Integer getExecutionCount() { return executionCount; }
        public void setExecutionCount(Integer executionCount) { this.executionCount = executionCount; }
        public Instant getLastSuccessfulExecution() { return lastSuccessfulExecution; }
        public void setLastSuccessfulExecution(Instant lastSuccessfulExecution) { this.lastSuccessfulExecution = lastSuccessfulExecution; }
        public Integer getFailureCount() { return failureCount; }
        public void setFailureCount(Integer failureCount) { this.failureCount = failureCount; }
        public String getReliability() { return reliability; }
        public void setReliability(String reliability) { this.reliability = reliability; }
    }

    /**
     * DTO for lineage statistics.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LineageStatisticsDTO {
        
        private Integer totalUpstreamAssets;
        private Integer totalDownstreamAssets;
        private Integer totalTransformations;
        private Integer directDependencies;
        private Integer indirectDependencies;
        private Double lineageCompleteness; // 0.0 to 100.0
        private String lineageHealth; // HEALTHY, PARTIALLY_TRACKED, INCOMPLETE, BROKEN
        private Map<String, Integer> assetTypeBreakdown;
        private Map<String, Integer> transformationTypeBreakdown;

        public LineageStatisticsDTO() {}

        // Getters and setters
        public Integer getTotalUpstreamAssets() { return totalUpstreamAssets; }
        public void setTotalUpstreamAssets(Integer totalUpstreamAssets) { this.totalUpstreamAssets = totalUpstreamAssets; }
        public Integer getTotalDownstreamAssets() { return totalDownstreamAssets; }
        public void setTotalDownstreamAssets(Integer totalDownstreamAssets) { this.totalDownstreamAssets = totalDownstreamAssets; }
        public Integer getTotalTransformations() { return totalTransformations; }
        public void setTotalTransformations(Integer totalTransformations) { this.totalTransformations = totalTransformations; }
        public Integer getDirectDependencies() { return directDependencies; }
        public void setDirectDependencies(Integer directDependencies) { this.directDependencies = directDependencies; }
        public Integer getIndirectDependencies() { return indirectDependencies; }
        public void setIndirectDependencies(Integer indirectDependencies) { this.indirectDependencies = indirectDependencies; }
        public Double getLineageCompleteness() { return lineageCompleteness; }
        public void setLineageCompleteness(Double lineageCompleteness) { this.lineageCompleteness = lineageCompleteness; }
        public String getLineageHealth() { return lineageHealth; }
        public void setLineageHealth(String lineageHealth) { this.lineageHealth = lineageHealth; }
        public Map<String, Integer> getAssetTypeBreakdown() { return assetTypeBreakdown; }
        public void setAssetTypeBreakdown(Map<String, Integer> assetTypeBreakdown) { this.assetTypeBreakdown = assetTypeBreakdown; }
        public Map<String, Integer> getTransformationTypeBreakdown() { return transformationTypeBreakdown; }
        public void setTransformationTypeBreakdown(Map<String, Integer> transformationTypeBreakdown) { this.transformationTypeBreakdown = transformationTypeBreakdown; }
    }

    /**
     * DTO for critical lineage paths.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LineagePathDTO {
        
        private String pathId;
        private String pathType; // CRITICAL_BUSINESS_PATH, HIGH_VOLUME_PATH, SENSITIVE_DATA_PATH
        private List<UUID> nodeSequence;
        private String description;
        private String businessImpact; // HIGH, MEDIUM, LOW
        private List<String> businessProcesses;
        private String riskLevel; // CRITICAL, HIGH, MEDIUM, LOW
        private Map<String, Object> metadata;

        public LineagePathDTO() {}

        public LineagePathDTO(String pathId, String pathType, List<UUID> nodeSequence) {
            this.pathId = pathId;
            this.pathType = pathType;
            this.nodeSequence = nodeSequence;
        }

        // Getters and setters
        public String getPathId() { return pathId; }
        public void setPathId(String pathId) { this.pathId = pathId; }
        public String getPathType() { return pathType; }
        public void setPathType(String pathType) { this.pathType = pathType; }
        public List<UUID> getNodeSequence() { return nodeSequence; }
        public void setNodeSequence(List<UUID> nodeSequence) { this.nodeSequence = nodeSequence; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getBusinessImpact() { return businessImpact; }
        public void setBusinessImpact(String businessImpact) { this.businessImpact = businessImpact; }
        public List<String> getBusinessProcesses() { return businessProcesses; }
        public void setBusinessProcesses(List<String> businessProcesses) { this.businessProcesses = businessProcesses; }
        public String getRiskLevel() { return riskLevel; }
        public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }

    /**
     * DTO for lineage issues.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LineageIssueDTO {
        
        private String issueId;
        private String issueType; // BROKEN_LINEAGE, MISSING_METADATA, STALE_CONNECTION, CIRCULAR_DEPENDENCY
        private String severity; // CRITICAL, HIGH, MEDIUM, LOW
        private String description;
        private List<UUID> affectedNodes;
        private String resolutionSuggestion;
        private Instant detectedAt;
        private String status; // OPEN, IN_PROGRESS, RESOLVED

        public LineageIssueDTO() {}

        public LineageIssueDTO(String issueId, String issueType, String severity) {
            this.issueId = issueId;
            this.issueType = issueType;
            this.severity = severity;
        }

        // Getters and setters
        public String getIssueId() { return issueId; }
        public void setIssueId(String issueId) { this.issueId = issueId; }
        public String getIssueType() { return issueType; }
        public void setIssueType(String issueType) { this.issueType = issueType; }
        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<UUID> getAffectedNodes() { return affectedNodes; }
        public void setAffectedNodes(List<UUID> affectedNodes) { this.affectedNodes = affectedNodes; }
        public String getResolutionSuggestion() { return resolutionSuggestion; }
        public void setResolutionSuggestion(String resolutionSuggestion) { this.resolutionSuggestion = resolutionSuggestion; }
        public Instant getDetectedAt() { return detectedAt; }
        public void setDetectedAt(Instant detectedAt) { this.detectedAt = detectedAt; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    /**
     * DTO for visualization metadata.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LineageVisualizationMetadataDTO {
        
        private String layoutType; // HIERARCHICAL, FORCE_DIRECTED, CIRCULAR, TREE
        private Map<String, Object> layoutConfiguration;
        private Map<String, String> colorScheme; // nodeType -> color
        private Map<String, String> iconMapping; // nodeType -> icon
        private List<String> availableFilters;
        private Map<String, Object> defaultViewport; // Initial zoom and pan settings
        private Boolean supportsInteractiveEditing;

        public LineageVisualizationMetadataDTO() {}

        // Getters and setters
        public String getLayoutType() { return layoutType; }
        public void setLayoutType(String layoutType) { this.layoutType = layoutType; }
        public Map<String, Object> getLayoutConfiguration() { return layoutConfiguration; }
        public void setLayoutConfiguration(Map<String, Object> layoutConfiguration) { this.layoutConfiguration = layoutConfiguration; }
        public Map<String, String> getColorScheme() { return colorScheme; }
        public void setColorScheme(Map<String, String> colorScheme) { this.colorScheme = colorScheme; }
        public Map<String, String> getIconMapping() { return iconMapping; }
        public void setIconMapping(Map<String, String> iconMapping) { this.iconMapping = iconMapping; }
        public List<String> getAvailableFilters() { return availableFilters; }
        public void setAvailableFilters(List<String> availableFilters) { this.availableFilters = availableFilters; }
        public Map<String, Object> getDefaultViewport() { return defaultViewport; }
        public void setDefaultViewport(Map<String, Object> defaultViewport) { this.defaultViewport = defaultViewport; }
        public Boolean getSupportsInteractiveEditing() { return supportsInteractiveEditing; }
        public void setSupportsInteractiveEditing(Boolean supportsInteractiveEditing) { this.supportsInteractiveEditing = supportsInteractiveEditing; }
    }

    // Main class getters and setters
    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }
    public String getAssetName() { return assetName; }
    public void setAssetName(String assetName) { this.assetName = assetName; }
    public LineageGraphDTO getLineageGraph() { return lineageGraph; }
    public void setLineageGraph(LineageGraphDTO lineageGraph) { this.lineageGraph = lineageGraph; }
    public LineageStatisticsDTO getStatistics() { return statistics; }
    public void setStatistics(LineageStatisticsDTO statistics) { this.statistics = statistics; }
    public List<LineagePathDTO> getCriticalPaths() { return criticalPaths; }
    public void setCriticalPaths(List<LineagePathDTO> criticalPaths) { this.criticalPaths = criticalPaths; }
    public List<LineageIssueDTO> getIssues() { return issues; }
    public void setIssues(List<LineageIssueDTO> issues) { this.issues = issues; }
    public LineageVisualizationMetadataDTO getVisualizationMetadata() { return visualizationMetadata; }
    public void setVisualizationMetadata(LineageVisualizationMetadataDTO visualizationMetadata) { this.visualizationMetadata = visualizationMetadata; }
    public Instant getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(Instant lastUpdated) { this.lastUpdated = lastUpdated; }
    public String getLineageVersion() { return lineageVersion; }
    public void setLineageVersion(String lineageVersion) { this.lineageVersion = lineageVersion; }
    public Integer getMaxDepth() { return maxDepth; }
    public void setMaxDepth(Integer maxDepth) { this.maxDepth = maxDepth; }
} 