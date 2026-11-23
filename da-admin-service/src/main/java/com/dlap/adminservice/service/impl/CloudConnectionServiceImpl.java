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

package com.dlap.adminservice.service.impl;

import com.dlap.adminservice.credential.CredentialProvider;
import com.dlap.adminservice.dto.CloudConnectionDTO;
import com.dlap.adminservice.dto.CloudConnectionTestResultDTO;
import com.dlap.adminservice.exception.ConflictException;
import com.dlap.adminservice.exception.NotFoundException;
import com.dlap.adminservice.mapper.CloudConnectionMapper;
import com.dlap.adminservice.model.CloudConnectionEntity;
import com.dlap.adminservice.repository.CloudConnectionRepository;
import com.dlap.adminservice.service.ICloudConnectionService;
import com.dlap.adminservice.service.IEncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CloudConnectionServiceImpl implements ICloudConnectionService {

    private final CloudConnectionRepository cloudConnectionRepository;
    private final CloudConnectionMapper cloudConnectionMapper;
    private final IEncryptionService encryptionService; // store side: encrypt on create/update
    private final CredentialProvider credentialProvider; // resolve side: encrypted-store | secret-manager | federated (ADR-0036)

    /** Build the SPI request from a stored connection (non-secret params + the encrypted blob). */
    private CredentialProvider.Request toCredentialRequest(CloudConnectionEntity entity) {
        return new CredentialProvider.Request(
                entity.getId(),
                entity.getProviderType() == null ? null : entity.getProviderType().name(),
                entity.getConnectionParameters(),
                entity.getEncryptedCredentials());
    }

    @Override
    @Transactional(readOnly = true)
    public List<CloudConnectionDTO> getAllCloudConnections() {
        log.debug("Fetching all cloud connections");
        return cloudConnectionMapper.toDtoList(cloudConnectionRepository.findAll());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CloudConnectionDTO> getCloudConnectionById(String id) {
        log.debug("Fetching cloud connection by ID: {}", id);
        return cloudConnectionRepository.findById(id)
                .map(cloudConnectionMapper::toDto);
    }

    @Override
    @Transactional
    public CloudConnectionDTO createCloudConnection(CloudConnectionDTO cloudConnectionDTO) {
        log.info("Creating new cloud connection with name: {}", cloudConnectionDTO.getName());
        cloudConnectionRepository.findByName(cloudConnectionDTO.getName()).ifPresent(entity -> {
            throw new ConflictException("Cloud connection with name '" + cloudConnectionDTO.getName() + "' already exists.");
        });

        CloudConnectionEntity entity = cloudConnectionMapper.toEntity(cloudConnectionDTO);
        if (StringUtils.hasText(cloudConnectionDTO.getSensitiveCredentials())) {
            entity.setEncryptedCredentials(encryptionService.encrypt(cloudConnectionDTO.getSensitiveCredentials()));
        }
        CloudConnectionEntity savedEntity = cloudConnectionRepository.save(entity);
        return cloudConnectionMapper.toDto(savedEntity);
    }

    @Override
    @Transactional
    public CloudConnectionDTO updateCloudConnection(String id, CloudConnectionDTO cloudConnectionDTO) {
        log.info("Updating cloud connection with ID: {}", id);
        CloudConnectionEntity existingEntity = cloudConnectionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Cloud connection with ID '" + id + "' not found."));

        // Check if name is being changed and if the new name already exists for another entity
        if (StringUtils.hasText(cloudConnectionDTO.getName()) && !existingEntity.getName().equals(cloudConnectionDTO.getName())) {
            cloudConnectionRepository.findByName(cloudConnectionDTO.getName()).ifPresent(entity -> {
                if (!entity.getId().equals(id)) { // If it's not the same entity
                    throw new ConflictException("Cloud connection with name '" + cloudConnectionDTO.getName() + "' already exists.");
                }
            });
        }

        cloudConnectionMapper.updateEntityFromDto(cloudConnectionDTO, existingEntity);
        if (StringUtils.hasText(cloudConnectionDTO.getSensitiveCredentials())) {
            log.debug("Updating encrypted credentials for cloud connection ID: {}", id);
            existingEntity.setEncryptedCredentials(encryptionService.encrypt(cloudConnectionDTO.getSensitiveCredentials()));
        } else {
            // If sensitiveCredentials is null or empty in DTO, it means user doesn't want to update them.
            // If user wants to clear them, an explicit mechanism should be used.
            // For now, we just don't update if it's not provided.
        }

        CloudConnectionEntity updatedEntity = cloudConnectionRepository.save(existingEntity);
        return cloudConnectionMapper.toDto(updatedEntity);
    }

    @Override
    @Transactional
    public void deleteCloudConnection(String id) {
        log.info("Deleting cloud connection with ID: {}", id);
        if (!cloudConnectionRepository.existsById(id)) {
            throw new NotFoundException("Cloud connection with ID '" + id + "' not found.");
        }
        cloudConnectionRepository.deleteById(id);
    }

    @Override
    @Transactional
    public CloudConnectionTestResultDTO testCloudConnection(String id) {
        log.info("Testing cloud connection with ID: {}", id);
        CloudConnectionEntity entity = cloudConnectionRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Cloud connection with ID '" + id + "' not found for testing."));

        // Resolve credentials through the CredentialProvider SPI (ADR-0036) to validate the
        // credential is usable, WITHOUT ever decrypting-and-logging the secret. This is a
        // credential-resolution test (catches a wrong key, a missing/blank secret, an
        // unconfigured secret-manager resolver, or a valid federated identity); a full live
        // provider-API call using the resolved identity is a further increment.
        boolean testSuccess;
        String status;
        String message;
        try {
            CredentialProvider.Resolved resolved = credentialProvider.resolve(toCredentialRequest(entity));
            if (resolved.isFederated()) {
                testSuccess = true;
                status = "SUCCESS";
                message = "Federated identity resolved for provider " + entity.getProviderType()
                        + " (no stored secret).";
            } else if (resolved.hasSecret()) {
                testSuccess = true;
                status = "SUCCESS";
                message = "Credentials resolved for provider " + entity.getProviderType()
                        + " via " + credentialProvider.mode() + ".";
            } else {
                testSuccess = false;
                status = "FAILED";
                message = "No credentials resolved for connection: " + entity.getName();
            }
        } catch (Exception e) {
            // Never surface secret material; only the failure reason.
            testSuccess = false;
            status = "FAILED";
            message = "Credential resolution failed: " + e.getMessage();
            log.warn("Connection test failed for {} ({}): {}", id, credentialProvider.mode(), e.getMessage());
        }

        entity.setLastConnectionTestAt(LocalDateTime.now());
        entity.setLastConnectionTestStatus(status);
        cloudConnectionRepository.save(entity);

        return CloudConnectionTestResultDTO.builder()
                .success(testSuccess)
                .message(message)
                .testedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME))
                .build();
    }
    
    @Override
    @Transactional(readOnly = true)
    public String getDecryptedCredentials(String id) {
        log.debug("Resolving credentials for cloud connection ID: {} (internal use)", id);
        CloudConnectionEntity entity = cloudConnectionRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Cloud connection with ID '" + id + "' not found."));

        // Resolve through the CredentialProvider SPI (ADR-0036). Returns null for a federated
        // connection (there is no stored secret to hand back - the caller uses the ambient
        // identity) and for an unresolvable/missing credential (preserving the prior
        // null-on-missing contract for internal callers).
        try {
            CredentialProvider.Resolved resolved = credentialProvider.resolve(toCredentialRequest(entity));
            if (resolved.isFederated()) {
                log.debug("Connection {} uses federated identity ({}); no decrypted secret to return.",
                        id, credentialProvider.mode());
                return null;
            }
            return resolved.secretMaterial();
        } catch (IllegalStateException e) {
            log.warn("No resolvable credentials for cloud connection ID {} ({}): {}",
                    id, credentialProvider.mode(), e.getMessage());
            return null;
        }
    }
} 