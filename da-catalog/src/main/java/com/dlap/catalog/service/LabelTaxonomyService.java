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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.dlap.catalog.common.ConflictException;
import com.dlap.catalog.common.ResourceNotFoundException;
import com.dlap.catalog.dto.TaxonomyLabelDTO;
import com.dlap.catalog.mapper.LabelTaxonomyMapper;
import com.dlap.common.model.entity.Label;
import com.dlap.common.repository.IAssetLabelAssignmentRepository;
import com.dlap.common.repository.ILabelRepository;

import jakarta.persistence.criteria.Predicate;

@Service
@Transactional
public class LabelTaxonomyService implements ILabelTaxonomyService {

    private static final Logger log = LoggerFactory.getLogger(LabelTaxonomyService.class);

    private final ILabelRepository labelRepository;
    private final LabelTaxonomyMapper labelTaxonomyMapper;
    private final IAssetLabelAssignmentRepository assetLabelAssignmentRepository;

    @Autowired
    public LabelTaxonomyService(ILabelRepository labelRepository, 
                                LabelTaxonomyMapper labelTaxonomyMapper,
                                IAssetLabelAssignmentRepository assetLabelAssignmentRepository) {
        this.labelRepository = labelRepository;
        this.labelTaxonomyMapper = labelTaxonomyMapper;
        this.assetLabelAssignmentRepository = assetLabelAssignmentRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<TaxonomyLabelDTO> getAllLabels(Pageable pageable, String category, String nameContains) {
        Specification<Label> spec = (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(category)) {
                predicates.add(criteriaBuilder.equal(root.get("labelCategory"), category));
            }
            if (StringUtils.hasText(nameContains)) {
                predicates.add(criteriaBuilder.like(criteriaBuilder.lower(root.get("labelName")),
                        "%" + nameContains.toLowerCase() + "%"));
            }
            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
        return labelRepository.findAll(spec, pageable).map(labelTaxonomyMapper::toTaxonomyLabelDTO);
    }

    @Override
    @Transactional(readOnly = true)
    public TaxonomyLabelDTO getLabelByIdOrName(String idOrName) {
        Label label;
        try {
            UUID labelId = UUID.fromString(idOrName);
            label = labelRepository.findById(labelId)
                    .orElseThrow(() -> new ResourceNotFoundException("Label", "id", idOrName));
        } catch (IllegalArgumentException ex) {
            // Not a UUID, try by name
            label = labelRepository.findByLabelName(idOrName)
                    .orElseThrow(() -> new ResourceNotFoundException("Label", "name", idOrName));
        }
        return labelTaxonomyMapper.toTaxonomyLabelDTO(label);
    }

    @Override
    public TaxonomyLabelDTO createLabel(TaxonomyLabelDTO labelDTO, UUID creatorUserId) {
        if (!StringUtils.hasText(labelDTO.getLabelName())){
            throw new IllegalArgumentException("Label name cannot be empty.");
        }
        labelRepository.findByLabelName(labelDTO.getLabelName()).ifPresent(l -> {
            throw new ConflictException("Label with name '" + labelDTO.getLabelName() + "' already exists.");
        });

        Label label = labelTaxonomyMapper.toLabelEntity(labelDTO);
        label.setCreatedByUserId(creatorUserId);
        // ID, createdAt, updatedAt are handled by entity constructor and @PrePersist/@PreUpdate
        
        Label savedLabel = labelRepository.save(label);
        return labelTaxonomyMapper.toTaxonomyLabelDTO(savedLabel);
    }

    @Override
    public TaxonomyLabelDTO updateLabel(String idOrName, TaxonomyLabelDTO labelDTO, UUID updaterUserId) {
        final Label existingLabel = findLabelByIdOrName(idOrName);

        // Check for name conflict if name is being changed
        if (StringUtils.hasText(labelDTO.getLabelName()) && !existingLabel.getLabelName().equals(labelDTO.getLabelName())) {
            Optional<Label> conflictingLabel = labelRepository.findByLabelName(labelDTO.getLabelName());
            if (conflictingLabel.isPresent() && !conflictingLabel.get().getLabelId().equals(existingLabel.getLabelId())) {
                throw new ConflictException("Another label with name '" + labelDTO.getLabelName() + "' already exists.");
            }
        }

        labelTaxonomyMapper.updateLabelEntityFromDto(existingLabel, labelDTO);
        // existingLabel.setUpdatedByUserId(updaterUserId); // If tracking updater
        Label updatedLabel = labelRepository.save(existingLabel); // @PreUpdate will set updatedAt
        return labelTaxonomyMapper.toTaxonomyLabelDTO(updatedLabel);
    }

    @Override
    public void deleteLabel(String idOrName) {
        Label labelToDelete = findLabelByIdOrName(idOrName);

        // Check if the label is used in any assignments
        long usageCount = assetLabelAssignmentRepository.countByLabel(labelToDelete);
        if (usageCount > 0) {
            throw new ConflictException("Label '" + labelToDelete.getLabelName() + "' cannot be deleted as it is currently assigned to " + usageCount + " asset(s).");
        }

        try {
            labelRepository.delete(labelToDelete);
            log.info("Deleted label: {}", labelToDelete.getLabelName());
        } catch (DataIntegrityViolationException e) {
            // This is a fallback, primary check is done by usageCount
            throw new ConflictException("Label '" + labelToDelete.getLabelName() + "' cannot be deleted due to existing references (e.g., assignments).");
        }
    }

    private Label findLabelByIdOrName(String idOrName) {
        try {
            UUID labelId = UUID.fromString(idOrName);
            return labelRepository.findById(labelId)
                    .orElseThrow(() -> new ResourceNotFoundException("Label", "id", idOrName));
        } catch (IllegalArgumentException ex) {
            // Not a UUID, try by name
            return labelRepository.findByLabelName(idOrName)
                    .orElseThrow(() -> new ResourceNotFoundException("Label", "name", idOrName));
        }
    }
} 