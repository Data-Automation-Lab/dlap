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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * DTO for Catalog Faceted Search Filters.
 * Priority 2 endpoint: Provides filter metadata for advanced search and filtering in the catalog UI.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CatalogFiltersDTO {

    private List<FilterCategoryDTO> filterCategories;
    private Map<String, List<String>> quickFilters;
    private SearchOptionsDTO searchOptions;
    private Instant lastUpdated;
    private Integer totalAssets;
    private String catalogVersion;

    /**
     * Default constructor.
     */
    public CatalogFiltersDTO() {}

    /**
     * DTO for filter category information.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FilterCategoryDTO {
        
        private String categoryName;
        private String displayName;
        private String categoryType; // SINGLE_SELECT, MULTI_SELECT, RANGE, DATE_RANGE, TEXT_SEARCH
        private List<FilterOptionDTO> options;
        private Integer totalOptions;
        private String description;
        private Boolean isExpandedByDefault;
        private Integer displayOrder;
        private Map<String, Object> metadata;

        public FilterCategoryDTO() {}

        public FilterCategoryDTO(String categoryName, String displayName, String categoryType) {
            this.categoryName = categoryName;
            this.displayName = displayName;
            this.categoryType = categoryType;
        }

        // Getters and setters
        public String getCategoryName() { return categoryName; }
        public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getCategoryType() { return categoryType; }
        public void setCategoryType(String categoryType) { this.categoryType = categoryType; }
        public List<FilterOptionDTO> getOptions() { return options; }
        public void setOptions(List<FilterOptionDTO> options) { this.options = options; }
        public Integer getTotalOptions() { return totalOptions; }
        public void setTotalOptions(Integer totalOptions) { this.totalOptions = totalOptions; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Boolean getIsExpandedByDefault() { return isExpandedByDefault; }
        public void setIsExpandedByDefault(Boolean isExpandedByDefault) { this.isExpandedByDefault = isExpandedByDefault; }
        public Integer getDisplayOrder() { return displayOrder; }
        public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }

    /**
     * DTO for individual filter option.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FilterOptionDTO {
        
        private String value;
        private String displayValue;
        private Integer count;
        private String description;
        private Boolean isSelected;
        private String icon;
        private String color;
        private List<FilterOptionDTO> subOptions;
        private Map<String, Object> metadata;

        public FilterOptionDTO() {}

        public FilterOptionDTO(String value, String displayValue, Integer count) {
            this.value = value;
            this.displayValue = displayValue;
            this.count = count;
        }

        // Getters and setters
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public String getDisplayValue() { return displayValue; }
        public void setDisplayValue(String displayValue) { this.displayValue = displayValue; }
        public Integer getCount() { return count; }
        public void setCount(Integer count) { this.count = count; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Boolean getIsSelected() { return isSelected; }
        public void setIsSelected(Boolean isSelected) { this.isSelected = isSelected; }
        public String getIcon() { return icon; }
        public void setIcon(String icon) { this.icon = icon; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
        public List<FilterOptionDTO> getSubOptions() { return subOptions; }
        public void setSubOptions(List<FilterOptionDTO> subOptions) { this.subOptions = subOptions; }
        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }

    /**
     * DTO for search options and capabilities.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SearchOptionsDTO {
        
        private List<String> searchableFields;
        private List<String> sortableFields;
        private List<SortOptionDTO> defaultSortOptions;
        private Integer maxResults;
        private Integer defaultPageSize;
        private List<String> supportedOperators;
        private Boolean supportsFullTextSearch;
        private Boolean supportsWildcards;
        private Boolean supportsFuzzySearch;
        private Map<String, Object> advancedOptions;

        public SearchOptionsDTO() {}

        // Getters and setters
        public List<String> getSearchableFields() { return searchableFields; }
        public void setSearchableFields(List<String> searchableFields) { this.searchableFields = searchableFields; }
        public List<String> getSortableFields() { return sortableFields; }
        public void setSortableFields(List<String> sortableFields) { this.sortableFields = sortableFields; }
        public List<SortOptionDTO> getDefaultSortOptions() { return defaultSortOptions; }
        public void setDefaultSortOptions(List<SortOptionDTO> defaultSortOptions) { this.defaultSortOptions = defaultSortOptions; }
        public Integer getMaxResults() { return maxResults; }
        public void setMaxResults(Integer maxResults) { this.maxResults = maxResults; }
        public Integer getDefaultPageSize() { return defaultPageSize; }
        public void setDefaultPageSize(Integer defaultPageSize) { this.defaultPageSize = defaultPageSize; }
        public List<String> getSupportedOperators() { return supportedOperators; }
        public void setSupportedOperators(List<String> supportedOperators) { this.supportedOperators = supportedOperators; }
        public Boolean getSupportsFullTextSearch() { return supportsFullTextSearch; }
        public void setSupportsFullTextSearch(Boolean supportsFullTextSearch) { this.supportsFullTextSearch = supportsFullTextSearch; }
        public Boolean getSupportsWildcards() { return supportsWildcards; }
        public void setSupportsWildcards(Boolean supportsWildcards) { this.supportsWildcards = supportsWildcards; }
        public Boolean getSupportsFuzzySearch() { return supportsFuzzySearch; }
        public void setSupportsFuzzySearch(Boolean supportsFuzzySearch) { this.supportsFuzzySearch = supportsFuzzySearch; }
        public Map<String, Object> getAdvancedOptions() { return advancedOptions; }
        public void setAdvancedOptions(Map<String, Object> advancedOptions) { this.advancedOptions = advancedOptions; }
    }

    /**
     * DTO for sort option.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SortOptionDTO {
        
        private String field;
        private String direction; // ASC, DESC
        private String displayName;
        private Boolean isDefault;

        public SortOptionDTO() {}

        public SortOptionDTO(String field, String direction, String displayName) {
            this.field = field;
            this.direction = direction;
            this.displayName = displayName;
        }

        // Getters and setters
        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getDirection() { return direction; }
        public void setDirection(String direction) { this.direction = direction; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public Boolean getIsDefault() { return isDefault; }
        public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
    }

    // Main class getters and setters
    public List<FilterCategoryDTO> getFilterCategories() { return filterCategories; }
    public void setFilterCategories(List<FilterCategoryDTO> filterCategories) { this.filterCategories = filterCategories; }
    public Map<String, List<String>> getQuickFilters() { return quickFilters; }
    public void setQuickFilters(Map<String, List<String>> quickFilters) { this.quickFilters = quickFilters; }
    public SearchOptionsDTO getSearchOptions() { return searchOptions; }
    public void setSearchOptions(SearchOptionsDTO searchOptions) { this.searchOptions = searchOptions; }
    public Instant getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(Instant lastUpdated) { this.lastUpdated = lastUpdated; }
    public Integer getTotalAssets() { return totalAssets; }
    public void setTotalAssets(Integer totalAssets) { this.totalAssets = totalAssets; }
    public String getCatalogVersion() { return catalogVersion; }
    public void setCatalogVersion(String catalogVersion) { this.catalogVersion = catalogVersion; }
} 