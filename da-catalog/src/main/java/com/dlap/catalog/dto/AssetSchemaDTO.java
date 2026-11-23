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
 * Comprehensive DTO for Asset Schema Information.
 * Provides detailed schema structure with field metadata, data types, constraints, and lineage.
 * Priority 2 endpoint implementation for enhanced catalog functionality.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AssetSchemaDTO {

    private UUID assetId;
    private String assetName;
    private String schemaVersion;
    private SchemaTypeDTO schemaType;
    private List<SchemaFieldDTO> fields;
    private List<SchemaConstraintDTO> constraints;
    private List<SchemaIndexDTO> indexes;
    private SchemaStatisticsDTO statistics;
    private SchemaLineageDTO lineage;
    private SchemaEvolutionDTO evolution;
    private Map<String, Object> metadata;
    private Instant lastUpdated;
    private Instant lastAnalyzed;
    private String source;

    /**
     * Default constructor for AssetSchemaDTO.
     */
    public AssetSchemaDTO() {}

    /**
     * Constructor with basic schema information.
     */
    public AssetSchemaDTO(UUID assetId, String assetName, String schemaVersion, SchemaTypeDTO schemaType) {
        this.assetId = assetId;
        this.assetName = assetName;
        this.schemaVersion = schemaVersion;
        this.schemaType = schemaType;
    }

    /**
     * DTO for Schema Type Information.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SchemaTypeDTO {
        private String type; // TABLE, VIEW, FILE, API, STREAM, DOCUMENT
        private String format; // CSV, JSON, PARQUET, AVRO, XML, etc.
        private String encoding; // UTF-8, ASCII, etc.
        private Map<String, Object> formatSpecificProperties;

        public SchemaTypeDTO() {}

        public SchemaTypeDTO(String type, String format) {
            this.type = type;
            this.format = format;
        }

        // Getters and setters
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
        public String getEncoding() { return encoding; }
        public void setEncoding(String encoding) { this.encoding = encoding; }
        public Map<String, Object> getFormatSpecificProperties() { return formatSpecificProperties; }
        public void setFormatSpecificProperties(Map<String, Object> formatSpecificProperties) { 
            this.formatSpecificProperties = formatSpecificProperties; 
        }
    }

    /**
     * DTO for individual schema fields with comprehensive metadata.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SchemaFieldDTO {
        private String fieldName;
        private String dataType;
        private String logicalType;
        private boolean nullable;
        private boolean primaryKey;
        private boolean foreignKey;
        private String defaultValue;
        private String description;
        private List<String> tags;
        private FieldStatisticsDTO statistics;
        private FieldConstraintsDTO constraints;
        private Integer ordinalPosition;
        private String classification; // PII, SENSITIVE, PUBLIC, etc.
        private Map<String, Object> properties;

        public SchemaFieldDTO() {}

        public SchemaFieldDTO(String fieldName, String dataType, boolean nullable) {
            this.fieldName = fieldName;
            this.dataType = dataType;
            this.nullable = nullable;
        }

        // Getters and setters
        public String getFieldName() { return fieldName; }
        public void setFieldName(String fieldName) { this.fieldName = fieldName; }
        public String getDataType() { return dataType; }
        public void setDataType(String dataType) { this.dataType = dataType; }
        public String getLogicalType() { return logicalType; }
        public void setLogicalType(String logicalType) { this.logicalType = logicalType; }
        public boolean isNullable() { return nullable; }
        public void setNullable(boolean nullable) { this.nullable = nullable; }
        public boolean isPrimaryKey() { return primaryKey; }
        public void setPrimaryKey(boolean primaryKey) { this.primaryKey = primaryKey; }
        public boolean isForeignKey() { return foreignKey; }
        public void setForeignKey(boolean foreignKey) { this.foreignKey = foreignKey; }
        public String getDefaultValue() { return defaultValue; }
        public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public List<String> getTags() { return tags; }
        public void setTags(List<String> tags) { this.tags = tags; }
        public FieldStatisticsDTO getStatistics() { return statistics; }
        public void setStatistics(FieldStatisticsDTO statistics) { this.statistics = statistics; }
        public FieldConstraintsDTO getConstraints() { return constraints; }
        public void setConstraints(FieldConstraintsDTO constraints) { this.constraints = constraints; }
        public Integer getOrdinalPosition() { return ordinalPosition; }
        public void setOrdinalPosition(Integer ordinalPosition) { this.ordinalPosition = ordinalPosition; }
        public String getClassification() { return classification; }
        public void setClassification(String classification) { this.classification = classification; }
        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) { this.properties = properties; }
    }

    /**
     * DTO for field-level statistics.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FieldStatisticsDTO {
        private Long distinctCount;
        private Long nullCount;
        private Double fillRate;
        private String minValue;
        private String maxValue;
        private Double avgLength;
        private Double maxLength;
        private List<String> topValues;
        private Instant lastAnalyzed;

        public FieldStatisticsDTO() {}

        // Getters and setters
        public Long getDistinctCount() { return distinctCount; }
        public void setDistinctCount(Long distinctCount) { this.distinctCount = distinctCount; }
        public Long getNullCount() { return nullCount; }
        public void setNullCount(Long nullCount) { this.nullCount = nullCount; }
        public Double getFillRate() { return fillRate; }
        public void setFillRate(Double fillRate) { this.fillRate = fillRate; }
        public String getMinValue() { return minValue; }
        public void setMinValue(String minValue) { this.minValue = minValue; }
        public String getMaxValue() { return maxValue; }
        public void setMaxValue(String maxValue) { this.maxValue = maxValue; }
        public Double getAvgLength() { return avgLength; }
        public void setAvgLength(Double avgLength) { this.avgLength = avgLength; }
        public Double getMaxLength() { return maxLength; }
        public void setMaxLength(Double maxLength) { this.maxLength = maxLength; }
        public List<String> getTopValues() { return topValues; }
        public void setTopValues(List<String> topValues) { this.topValues = topValues; }
        public Instant getLastAnalyzed() { return lastAnalyzed; }
        public void setLastAnalyzed(Instant lastAnalyzed) { this.lastAnalyzed = lastAnalyzed; }
    }

    /**
     * DTO for field-level constraints.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FieldConstraintsDTO {
        private String pattern; // Regex pattern for validation
        private Integer minLength;
        private Integer maxLength;
        private String minValue;
        private String maxValue;
        private List<String> enumValues;
        private String format; // EMAIL, PHONE, URL, etc.
        private Map<String, Object> customConstraints;

        public FieldConstraintsDTO() {}

        // Getters and setters
        public String getPattern() { return pattern; }
        public void setPattern(String pattern) { this.pattern = pattern; }
        public Integer getMinLength() { return minLength; }
        public void setMinLength(Integer minLength) { this.minLength = minLength; }
        public Integer getMaxLength() { return maxLength; }
        public void setMaxLength(Integer maxLength) { this.maxLength = maxLength; }
        public String getMinValue() { return minValue; }
        public void setMinValue(String minValue) { this.minValue = minValue; }
        public String getMaxValue() { return maxValue; }
        public void setMaxValue(String maxValue) { this.maxValue = maxValue; }
        public List<String> getEnumValues() { return enumValues; }
        public void setEnumValues(List<String> enumValues) { this.enumValues = enumValues; }
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
        public Map<String, Object> getCustomConstraints() { return customConstraints; }
        public void setCustomConstraints(Map<String, Object> customConstraints) { 
            this.customConstraints = customConstraints; 
        }
    }

    /**
     * DTO for schema-level constraints.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SchemaConstraintDTO {
        private String constraintName;
        private String constraintType; // PRIMARY_KEY, FOREIGN_KEY, UNIQUE, CHECK, NOT_NULL
        private List<String> columnNames;
        private String referencedTable;
        private List<String> referencedColumns;
        private String checkExpression;
        private boolean enforced;

        public SchemaConstraintDTO() {}

        public SchemaConstraintDTO(String constraintName, String constraintType) {
            this.constraintName = constraintName;
            this.constraintType = constraintType;
        }

        // Getters and setters
        public String getConstraintName() { return constraintName; }
        public void setConstraintName(String constraintName) { this.constraintName = constraintName; }
        public String getConstraintType() { return constraintType; }
        public void setConstraintType(String constraintType) { this.constraintType = constraintType; }
        public List<String> getColumnNames() { return columnNames; }
        public void setColumnNames(List<String> columnNames) { this.columnNames = columnNames; }
        public String getReferencedTable() { return referencedTable; }
        public void setReferencedTable(String referencedTable) { this.referencedTable = referencedTable; }
        public List<String> getReferencedColumns() { return referencedColumns; }
        public void setReferencedColumns(List<String> referencedColumns) { this.referencedColumns = referencedColumns; }
        public String getCheckExpression() { return checkExpression; }
        public void setCheckExpression(String checkExpression) { this.checkExpression = checkExpression; }
        public boolean isEnforced() { return enforced; }
        public void setEnforced(boolean enforced) { this.enforced = enforced; }
    }

    /**
     * DTO for schema indexes.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SchemaIndexDTO {
        private String indexName;
        private String indexType; // BTREE, HASH, BITMAP, CLUSTERED, etc.
        private List<String> columnNames;
        private boolean unique;
        private boolean primary;
        private Long cardinality;
        private Map<String, Object> properties;

        public SchemaIndexDTO() {}

        public SchemaIndexDTO(String indexName, String indexType, boolean unique) {
            this.indexName = indexName;
            this.indexType = indexType;
            this.unique = unique;
        }

        // Getters and setters
        public String getIndexName() { return indexName; }
        public void setIndexName(String indexName) { this.indexName = indexName; }
        public String getIndexType() { return indexType; }
        public void setIndexType(String indexType) { this.indexType = indexType; }
        public List<String> getColumnNames() { return columnNames; }
        public void setColumnNames(List<String> columnNames) { this.columnNames = columnNames; }
        public boolean isUnique() { return unique; }
        public void setUnique(boolean unique) { this.unique = unique; }
        public boolean isPrimary() { return primary; }
        public void setPrimary(boolean primary) { this.primary = primary; }
        public Long getCardinality() { return cardinality; }
        public void setCardinality(Long cardinality) { this.cardinality = cardinality; }
        public Map<String, Object> getProperties() { return properties; }
        public void setProperties(Map<String, Object> properties) { this.properties = properties; }
    }

    /**
     * DTO for schema-level statistics.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SchemaStatisticsDTO {
        private Long totalRows;
        private Long totalColumns;
        private Long totalSize;
        private Double compressionRatio;
        private Instant lastUpdated;
        private Instant lastAnalyzed;
        private Map<String, Long> dataTypeCounts;
        private Double schemaComplexity;
        private Integer schemaDepth;

        public SchemaStatisticsDTO() {}

        // Getters and setters
        public Long getTotalRows() { return totalRows; }
        public void setTotalRows(Long totalRows) { this.totalRows = totalRows; }
        public Long getTotalColumns() { return totalColumns; }
        public void setTotalColumns(Long totalColumns) { this.totalColumns = totalColumns; }
        public Long getTotalSize() { return totalSize; }
        public void setTotalSize(Long totalSize) { this.totalSize = totalSize; }
        public Double getCompressionRatio() { return compressionRatio; }
        public void setCompressionRatio(Double compressionRatio) { this.compressionRatio = compressionRatio; }
        public Instant getLastUpdated() { return lastUpdated; }
        public void setLastUpdated(Instant lastUpdated) { this.lastUpdated = lastUpdated; }
        public Instant getLastAnalyzed() { return lastAnalyzed; }
        public void setLastAnalyzed(Instant lastAnalyzed) { this.lastAnalyzed = lastAnalyzed; }
        public Map<String, Long> getDataTypeCounts() { return dataTypeCounts; }
        public void setDataTypeCounts(Map<String, Long> dataTypeCounts) { this.dataTypeCounts = dataTypeCounts; }
        public Double getSchemaComplexity() { return schemaComplexity; }
        public void setSchemaComplexity(Double schemaComplexity) { this.schemaComplexity = schemaComplexity; }
        public Integer getSchemaDepth() { return schemaDepth; }
        public void setSchemaDepth(Integer schemaDepth) { this.schemaDepth = schemaDepth; }
    }

    /**
     * DTO for schema lineage information.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SchemaLineageDTO {
        private List<SchemaRelationshipDTO> upstream;
        private List<SchemaRelationshipDTO> downstream;
        private List<SchemaTransformationDTO> transformations;
        private Integer lineageDepth;
        private Instant lineageLastUpdated;

        public SchemaLineageDTO() {}

        // Getters and setters
        public List<SchemaRelationshipDTO> getUpstream() { return upstream; }
        public void setUpstream(List<SchemaRelationshipDTO> upstream) { this.upstream = upstream; }
        public List<SchemaRelationshipDTO> getDownstream() { return downstream; }
        public void setDownstream(List<SchemaRelationshipDTO> downstream) { this.downstream = downstream; }
        public List<SchemaTransformationDTO> getTransformations() { return transformations; }
        public void setTransformations(List<SchemaTransformationDTO> transformations) { 
            this.transformations = transformations; 
        }
        public Integer getLineageDepth() { return lineageDepth; }
        public void setLineageDepth(Integer lineageDepth) { this.lineageDepth = lineageDepth; }
        public Instant getLineageLastUpdated() { return lineageLastUpdated; }
        public void setLineageLastUpdated(Instant lineageLastUpdated) { this.lineageLastUpdated = lineageLastUpdated; }
    }

    /**
     * DTO for schema relationships in lineage.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SchemaRelationshipDTO {
        private UUID relatedAssetId;
        private String relatedAssetName;
        private String relationshipType; // DERIVED_FROM, COPIED_TO, JOINED_WITH, etc.
        private List<FieldMappingDTO> fieldMappings;
        private String transformationType;
        private Instant relationshipCreated;

        public SchemaRelationshipDTO() {}

        // Getters and setters
        public UUID getRelatedAssetId() { return relatedAssetId; }
        public void setRelatedAssetId(UUID relatedAssetId) { this.relatedAssetId = relatedAssetId; }
        public String getRelatedAssetName() { return relatedAssetName; }
        public void setRelatedAssetName(String relatedAssetName) { this.relatedAssetName = relatedAssetName; }
        public String getRelationshipType() { return relationshipType; }
        public void setRelationshipType(String relationshipType) { this.relationshipType = relationshipType; }
        public List<FieldMappingDTO> getFieldMappings() { return fieldMappings; }
        public void setFieldMappings(List<FieldMappingDTO> fieldMappings) { this.fieldMappings = fieldMappings; }
        public String getTransformationType() { return transformationType; }
        public void setTransformationType(String transformationType) { this.transformationType = transformationType; }
        public Instant getRelationshipCreated() { return relationshipCreated; }
        public void setRelationshipCreated(Instant relationshipCreated) { this.relationshipCreated = relationshipCreated; }
    }

    /**
     * DTO for field mappings in schema relationships.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FieldMappingDTO {
        private String sourceField;
        private String targetField;
        private String mappingType; // DIRECT, TRANSFORMED, CALCULATED, etc.
        private String transformation;
        private Double confidence;

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
     * DTO for schema transformations.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SchemaTransformationDTO {
        private String transformationId;
        private String transformationType; // ETL, ELT, STREAM_PROCESSING, etc.
        private String transformationLogic;
        private List<String> inputFields;
        private List<String> outputFields;
        private Map<String, Object> parameters;
        private Instant executedAt;

        public SchemaTransformationDTO() {}

        // Getters and setters
        public String getTransformationId() { return transformationId; }
        public void setTransformationId(String transformationId) { this.transformationId = transformationId; }
        public String getTransformationType() { return transformationType; }
        public void setTransformationType(String transformationType) { this.transformationType = transformationType; }
        public String getTransformationLogic() { return transformationLogic; }
        public void setTransformationLogic(String transformationLogic) { this.transformationLogic = transformationLogic; }
        public List<String> getInputFields() { return inputFields; }
        public void setInputFields(List<String> inputFields) { this.inputFields = inputFields; }
        public List<String> getOutputFields() { return outputFields; }
        public void setOutputFields(List<String> outputFields) { this.outputFields = outputFields; }
        public Map<String, Object> getParameters() { return parameters; }
        public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }
        public Instant getExecutedAt() { return executedAt; }
        public void setExecutedAt(Instant executedAt) { this.executedAt = executedAt; }
    }

    /**
     * DTO for schema evolution tracking.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SchemaEvolutionDTO {
        private List<SchemaVersionDTO> versions;
        private List<SchemaChangeDTO> recentChanges;
        private Integer totalVersions;
        private Instant firstVersion;
        private Instant lastChange;

        public SchemaEvolutionDTO() {}

        // Getters and setters
        public List<SchemaVersionDTO> getVersions() { return versions; }
        public void setVersions(List<SchemaVersionDTO> versions) { this.versions = versions; }
        public List<SchemaChangeDTO> getRecentChanges() { return recentChanges; }
        public void setRecentChanges(List<SchemaChangeDTO> recentChanges) { this.recentChanges = recentChanges; }
        public Integer getTotalVersions() { return totalVersions; }
        public void setTotalVersions(Integer totalVersions) { this.totalVersions = totalVersions; }
        public Instant getFirstVersion() { return firstVersion; }
        public void setFirstVersion(Instant firstVersion) { this.firstVersion = firstVersion; }
        public Instant getLastChange() { return lastChange; }
        public void setLastChange(Instant lastChange) { this.lastChange = lastChange; }
    }

    /**
     * DTO for schema versions.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SchemaVersionDTO {
        private String version;
        private Instant createdAt;
        private String createdBy;
        private String changeDescription;
        private Integer fieldCount;
        private List<String> changedFields;

        public SchemaVersionDTO() {}

        public SchemaVersionDTO(String version, Instant createdAt, String createdBy) {
            this.version = version;
            this.createdAt = createdAt;
            this.createdBy = createdBy;
        }

        // Getters and setters
        public String getVersion() { return version; }
        public void setVersion(String version) { this.version = version; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public String getCreatedBy() { return createdBy; }
        public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
        public String getChangeDescription() { return changeDescription; }
        public void setChangeDescription(String changeDescription) { this.changeDescription = changeDescription; }
        public Integer getFieldCount() { return fieldCount; }
        public void setFieldCount(Integer fieldCount) { this.fieldCount = fieldCount; }
        public List<String> getChangedFields() { return changedFields; }
        public void setChangedFields(List<String> changedFields) { this.changedFields = changedFields; }
    }

    /**
     * DTO for schema changes.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SchemaChangeDTO {
        private String changeType; // FIELD_ADDED, FIELD_REMOVED, FIELD_MODIFIED, CONSTRAINT_ADDED, etc.
        private String fieldName;
        private String oldValue;
        private String newValue;
        private Instant changeTimestamp;
        private String changedBy;
        private String changeReason;

        public SchemaChangeDTO() {}

        public SchemaChangeDTO(String changeType, String fieldName, Instant changeTimestamp) {
            this.changeType = changeType;
            this.fieldName = fieldName;
            this.changeTimestamp = changeTimestamp;
        }

        // Getters and setters
        public String getChangeType() { return changeType; }
        public void setChangeType(String changeType) { this.changeType = changeType; }
        public String getFieldName() { return fieldName; }
        public void setFieldName(String fieldName) { this.fieldName = fieldName; }
        public String getOldValue() { return oldValue; }
        public void setOldValue(String oldValue) { this.oldValue = oldValue; }
        public String getNewValue() { return newValue; }
        public void setNewValue(String newValue) { this.newValue = newValue; }
        public Instant getChangeTimestamp() { return changeTimestamp; }
        public void setChangeTimestamp(Instant changeTimestamp) { this.changeTimestamp = changeTimestamp; }
        public String getChangedBy() { return changedBy; }
        public void setChangedBy(String changedBy) { this.changedBy = changedBy; }
        public String getChangeReason() { return changeReason; }
        public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
    }

    // Main class getters and setters
    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }
    public String getAssetName() { return assetName; }
    public void setAssetName(String assetName) { this.assetName = assetName; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public SchemaTypeDTO getSchemaType() { return schemaType; }
    public void setSchemaType(SchemaTypeDTO schemaType) { this.schemaType = schemaType; }
    public List<SchemaFieldDTO> getFields() { return fields; }
    public void setFields(List<SchemaFieldDTO> fields) { this.fields = fields; }
    public List<SchemaConstraintDTO> getConstraints() { return constraints; }
    public void setConstraints(List<SchemaConstraintDTO> constraints) { this.constraints = constraints; }
    public List<SchemaIndexDTO> getIndexes() { return indexes; }
    public void setIndexes(List<SchemaIndexDTO> indexes) { this.indexes = indexes; }
    public SchemaStatisticsDTO getStatistics() { return statistics; }
    public void setStatistics(SchemaStatisticsDTO statistics) { this.statistics = statistics; }
    public SchemaLineageDTO getLineage() { return lineage; }
    public void setLineage(SchemaLineageDTO lineage) { this.lineage = lineage; }
    public SchemaEvolutionDTO getEvolution() { return evolution; }
    public void setEvolution(SchemaEvolutionDTO evolution) { this.evolution = evolution; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public Instant getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(Instant lastUpdated) { this.lastUpdated = lastUpdated; }
    public Instant getLastAnalyzed() { return lastAnalyzed; }
    public void setLastAnalyzed(Instant lastAnalyzed) { this.lastAnalyzed = lastAnalyzed; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
} 