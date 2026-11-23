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

package com.dlap.catalog.mapper;

import org.springframework.stereotype.Component;

import com.dlap.catalog.dto.TaxonomyLabelDTO;
import com.dlap.common.model.entity.Label;

@Component
public class LabelTaxonomyMapper {

    public TaxonomyLabelDTO toTaxonomyLabelDTO(Label label) {
        TaxonomyLabelDTO dto = new TaxonomyLabelDTO();
        if (label == null) {
            return dto;
        }
        dto.setLabelId(label.getLabelId());
        dto.setLabelName(label.getLabelName());
        dto.setLabelCategory(label.getLabelCategory());
        dto.setDescription(label.getDescription());
        dto.setCreatedByUserId(label.getCreatedByUserId());
        dto.setCreatedAt(label.getCreatedTs());
        dto.setUpdatedAt(label.getLastModifiedTs());
        return dto;
    }

    public Label toLabelEntity(TaxonomyLabelDTO dto) {
        Label label = new Label();
        if (dto == null) {
            return label;
        }
        // label.setLabelId(dto.getLabelId()); // ID is usually set on creation or fetched
        label.setLabelName(dto.getLabelName());
        label.setLabelCategory(dto.getLabelCategory());
        label.setDescription(dto.getDescription());
        // createdByUserId, createdAt, updatedAt are often set by system or @PrePersist/@PreUpdate
        return label;
    }
    
    public void updateLabelEntityFromDto(Label label, TaxonomyLabelDTO dto) {
        if (dto == null || label == null) {
            return;
        }
        if (dto.getLabelName() != null) {
            label.setLabelName(dto.getLabelName());
        }
        if (dto.getLabelCategory() != null) {
            label.setLabelCategory(dto.getLabelCategory());
        }
        if (dto.getDescription() != null) {
            label.setDescription(dto.getDescription());
        }
        // createdByUserId is usually not updated this way
    }
} 