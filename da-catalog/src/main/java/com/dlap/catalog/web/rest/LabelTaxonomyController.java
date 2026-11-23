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

import com.dlap.catalog.dto.TaxonomyLabelDTO;
import com.dlap.catalog.dto.TaxonomyLabelsResponseDTO;
import com.dlap.catalog.service.ILabelTaxonomyService;
import com.dlap.catalog.security.SecurityUtils;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/catalog/taxonomy")
public class LabelTaxonomyController {

    private static final Logger log = LoggerFactory.getLogger(LabelTaxonomyController.class);

    private final ILabelTaxonomyService labelTaxonomyService;

    @Autowired
    public LabelTaxonomyController(ILabelTaxonomyService labelTaxonomyService) {
        this.labelTaxonomyService = labelTaxonomyService;
    }

    @GetMapping("/labels")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')") // Adjust as needed
    public ResponseEntity<TaxonomyLabelsResponseDTO> getAllLabels(
            @PageableDefault(size = 100, sort = "labelName") Pageable pageable,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String nameContains) {
        log.info("REST request to get all taxonomy labels with filters: category={}, nameContains={}", category, nameContains);
        Page<TaxonomyLabelDTO> labelPage = labelTaxonomyService.getAllLabels(pageable, category, nameContains);
        return ResponseEntity.ok(new TaxonomyLabelsResponseDTO(labelPage.getContent())); 
    }

    @PostMapping("/labels")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER')")
    public ResponseEntity<TaxonomyLabelDTO> createLabel(@Valid @RequestBody TaxonomyLabelDTO labelDTO) {
        log.info("REST request to create taxonomy label: {}", labelDTO.getLabelName());
        UUID creatorUserId = SecurityUtils.getAuthenticatedUserId();
        TaxonomyLabelDTO createdLabel = labelTaxonomyService.createLabel(labelDTO, creatorUserId);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{idOrName}") 
                .buildAndExpand(createdLabel.getLabelId()) 
                .toUri();
        return ResponseEntity.created(location).body(createdLabel);
    }

    @GetMapping("/labels/{idOrName}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_DATA_ENGINEER', 'ROLE_DATA_SCIENTIST', 'ROLE_DATA_ANALYST')")
    public ResponseEntity<TaxonomyLabelDTO> getLabelByIdOrName(@PathVariable String idOrName) {
        log.info("REST request to get taxonomy label by id/name: {}", idOrName);
        TaxonomyLabelDTO labelDTO = labelTaxonomyService.getLabelByIdOrName(idOrName);
        return ResponseEntity.ok(labelDTO);
    }

    @PutMapping("/labels/{idOrName}")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER')")
    public ResponseEntity<TaxonomyLabelDTO> updateLabel(
            @PathVariable String idOrName,
            @Valid @RequestBody TaxonomyLabelDTO labelDTO) {
        log.info("REST request to update taxonomy label '{}': {}", idOrName, labelDTO.getLabelName());
        UUID updaterUserId = SecurityUtils.getAuthenticatedUserId();
        TaxonomyLabelDTO updatedLabel = labelTaxonomyService.updateLabel(idOrName, labelDTO, updaterUserId);
        return ResponseEntity.ok(updatedLabel);
    }

    @DeleteMapping("/labels/{idOrName}")
    @PreAuthorize("hasAuthority('ROLE_ADMIN') or hasAuthority('ROLE_POLICY_MANAGER')")
    public ResponseEntity<Void> deleteLabel(@PathVariable String idOrName) {
        log.info("REST request to delete taxonomy label by id/name: {}", idOrName);
        labelTaxonomyService.deleteLabel(idOrName);
        return ResponseEntity.noContent().build();
    }
} 