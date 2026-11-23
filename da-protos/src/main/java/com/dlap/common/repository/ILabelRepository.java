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

package com.dlap.common.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.dlap.common.model.entity.Label;

/**
 * Spring Data JPA repository for the {@link Label} entity.
 * 
 * Provides standard CRUD operations and custom query methods for label taxonomy management.
 * 
 * @author DLAP Team
 * @version 1.0
 * @since 1.0
 */
@Repository
public interface ILabelRepository extends JpaRepository<Label, UUID>, JpaSpecificationExecutor<Label> {

    /**
     * Find label by name
     * 
     * @param labelName the label name
     * @return optional label
     */
    Optional<Label> findByLabelName(String labelName);

    /**
     * Find labels by label names
     * 
     * @param labelNames the list of label names
     * @return list of labels matching the names
     */
    List<Label> findByLabelNameIn(List<String> labelNames);

    /**
     * Find label IDs by label names
     * 
     * @param labelNames the list of label names
     * @return list of label IDs matching the names
     */
    @Query("SELECT l.labelId FROM Label l WHERE l.labelName IN :labelNames")
    List<UUID> findLabelIdsByLabelNames(@Param("labelNames") List<String> labelNames);

    /**
     * Find labels by category
     * 
     * @param labelCategory the label category
     * @return list of labels in the specified category
     */
    List<Label> findByLabelCategory(String labelCategory);

    /**
     * Find labels by category
     * 
     * @param labelCategory the label category
     * @param pageable pagination information
     * @return page of labels in the specified category
     */
    Page<Label> findByLabelCategory(String labelCategory, Pageable pageable);

    /**
     * Find labels created by a specific user
     * 
     * @param createdByUserId the user ID who created the labels
     * @param pageable pagination information
     * @return page of labels created by the user
     */
    Page<Label> findByCreatedByUserId(UUID createdByUserId, Pageable pageable);

    /**
     * Find all system-defined labels (no creator)
     * 
     * @param pageable pagination information
     * @return page of system-defined labels
     */
    Page<Label> findByCreatedByUserIdIsNull(Pageable pageable);

    /**
     * Find all user-defined labels (has creator)
     * 
     * @param pageable pagination information
     * @return page of user-defined labels
     */
    Page<Label> findByCreatedByUserIdIsNotNull(Pageable pageable);

    /**
     * Search labels by name containing the given text (case-insensitive)
     * 
     * @param namePattern the pattern to search for
     * @param pageable pagination information
     * @return page of labels matching the name pattern
     */
    Page<Label> findByLabelNameContainingIgnoreCase(String namePattern, Pageable pageable);

    /**
     * Search labels by description containing the given text (case-insensitive)
     * 
     * @param descriptionPattern the pattern to search for
     * @param pageable pagination information
     * @return page of labels matching the description pattern
     */
    Page<Label> findByDescriptionContainingIgnoreCase(String descriptionPattern, Pageable pageable);

    /**
     * Find all distinct label categories
     * 
     * @return list of distinct label categories
     */
    @Query("SELECT DISTINCT l.labelCategory FROM Label l WHERE l.labelCategory IS NOT NULL ORDER BY l.labelCategory")
    List<String> findDistinctCategories();

    /**
     * Find labels by category (case-insensitive)
     * 
     * @param categoryName the category name
     * @return list of labels in the category
     */
    @Query("SELECT l FROM Label l WHERE LOWER(l.labelCategory) = LOWER(:categoryName)")
    List<Label> findByCategoryIgnoreCase(@Param("categoryName") String categoryName);

    /**
     * Check if label exists by name
     * 
     * @param labelName the label name
     * @return true if label exists, false otherwise
     */
    boolean existsByLabelName(String labelName);

    /**
     * Count labels by category
     * 
     * @param labelCategory the label category
     * @return number of labels in the category
     */
    long countByLabelCategory(String labelCategory);

    /**
     * Count system-defined labels
     * 
     * @return number of system-defined labels
     */
    long countByCreatedByUserIdIsNull();

    /**
     * Count user-defined labels
     * 
     * @return number of user-defined labels
     */
    long countByCreatedByUserIdIsNotNull();

    /**
     * Find sensitivity labels
     * 
     * @return list of sensitivity labels
     */
    @Query("SELECT l FROM Label l WHERE LOWER(l.labelCategory) = 'sensitivity'")
    List<Label> findSensitivityLabels();

    /**
     * Find compliance labels
     * 
     * @return list of compliance labels
     */
    @Query("SELECT l FROM Label l WHERE LOWER(l.labelCategory) = 'compliance'")
    List<Label> findComplianceLabels();

    /**
     * Find data type labels
     * 
     * @return list of data type labels
     */
    @Query("SELECT l FROM Label l WHERE LOWER(l.labelCategory) = 'data type'")
    List<Label> findDataTypeLabels();
} 