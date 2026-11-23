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

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dlap.adminservice.dto.CloudConnectionDTO;
import com.dlap.adminservice.dto.CloudConnectionTestResultDTO;
import com.dlap.adminservice.exception.ConflictException;
import com.dlap.adminservice.exception.NotFoundException;
import com.dlap.adminservice.mapper.CloudConnectionMapper;
import com.dlap.adminservice.model.CloudConnectionEntity;
import com.dlap.adminservice.model.enums.CloudProviderType;
import com.dlap.adminservice.repository.CloudConnectionRepository;
import com.dlap.adminservice.credential.CredentialProvider;
import com.dlap.adminservice.service.IEncryptionService;

@ExtendWith(MockitoExtension.class)
class CloudConnectionServiceImplTest {

    @Mock
    private CloudConnectionRepository cloudConnectionRepository;
    @Mock
    private CloudConnectionMapper cloudConnectionMapper;
    @Mock
    private IEncryptionService encryptionService;
    @Mock
    private CredentialProvider credentialProvider;

    @InjectMocks
    private CloudConnectionServiceImpl cloudConnectionService;

    private CloudConnectionEntity connectionEntity;
    private CloudConnectionDTO connectionDTO;
    private String connectionId = "test-conn-id";
    private String sensitiveData = "supersecret";
    private String encryptedData = "encryptedSuperSecret";

    @BeforeEach
    void setUp() {
        connectionEntity = new CloudConnectionEntity();
        connectionEntity.setId(connectionId);
        connectionEntity.setName("TestConnection");
        connectionEntity.setProviderType(CloudProviderType.GCP);
        connectionEntity.setEncryptedCredentials(encryptedData);
        connectionEntity.setConnectionParameters(Map.of("projectId", "gcp-project"));
        connectionEntity.setEnabled(true);

        connectionDTO = CloudConnectionDTO.builder()
                .id(connectionId)
                .name("TestConnection")
                .providerType(CloudProviderType.GCP)
                .connectionParameters(Map.of("projectId", "gcp-project"))
                .sensitiveCredentials(sensitiveData) // DTO carries raw sensitive data for creation/update
                .enabled(true)
                .build();
    }

    @Test
    void getAllCloudConnections_shouldReturnDtoList() {
        when(cloudConnectionRepository.findAll()).thenReturn(Collections.singletonList(connectionEntity));
        when(cloudConnectionMapper.toDtoList(anyList())).thenReturn(Collections.singletonList(connectionDTO));

        List<CloudConnectionDTO> result = cloudConnectionService.getAllCloudConnections();

        assertThat(result).isNotNull().hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo(connectionDTO.getName());
        verify(cloudConnectionRepository).findAll();
    }

    @Test
    void getCloudConnectionById_whenFound_shouldReturnDto() {
        when(cloudConnectionRepository.findById(connectionId)).thenReturn(Optional.of(connectionEntity));
        when(cloudConnectionMapper.toDto(connectionEntity)).thenReturn(connectionDTO);

        Optional<CloudConnectionDTO> result = cloudConnectionService.getCloudConnectionById(connectionId);

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo(connectionDTO.getName());
    }

    @Test
    void createCloudConnection_shouldEncryptCredentialsAndSave() {
        when(cloudConnectionRepository.findByName(connectionDTO.getName())).thenReturn(Optional.empty());
        when(cloudConnectionMapper.toEntity(connectionDTO)).thenReturn(connectionEntity); // Assume mapper creates entity without encrypted creds
        when(encryptionService.encrypt(sensitiveData)).thenReturn(encryptedData);
        when(cloudConnectionRepository.save(any(CloudConnectionEntity.class))).thenReturn(connectionEntity);
        when(cloudConnectionMapper.toDto(connectionEntity)).thenReturn(connectionDTO); // DTO returned should not have sensitive creds

        CloudConnectionDTO result = cloudConnectionService.createCloudConnection(connectionDTO);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo(connectionDTO.getName());
        verify(encryptionService).encrypt(sensitiveData);
        verify(cloudConnectionRepository).save(argThat(entity -> entity.getEncryptedCredentials().equals(encryptedData)));
    }
    
    @Test
    void createCloudConnection_withNullSensitiveCredentials_shouldNotEncrypt() {
        CloudConnectionDTO dtoWithNullCreds = CloudConnectionDTO.builder().name("NoCredsConn").sensitiveCredentials(null).build();
        CloudConnectionEntity entityFromMapper = new CloudConnectionEntity();
        entityFromMapper.setName("NoCredsConn");

        when(cloudConnectionRepository.findByName("NoCredsConn")).thenReturn(Optional.empty());
        when(cloudConnectionMapper.toEntity(dtoWithNullCreds)).thenReturn(entityFromMapper);
        when(cloudConnectionRepository.save(any(CloudConnectionEntity.class))).thenReturn(entityFromMapper);
        when(cloudConnectionMapper.toDto(entityFromMapper)).thenReturn(dtoWithNullCreds);

        cloudConnectionService.createCloudConnection(dtoWithNullCreds);

        verify(encryptionService, never()).encrypt(any());
        verify(cloudConnectionRepository).save(argThat(entity -> entity.getEncryptedCredentials() == null));
    }

    @Test
    void createCloudConnection_whenNameExists_shouldThrowConflictException() {
        when(cloudConnectionRepository.findByName(connectionDTO.getName())).thenReturn(Optional.of(new CloudConnectionEntity()));
        assertThrows(ConflictException.class, () -> cloudConnectionService.createCloudConnection(connectionDTO));
        verify(cloudConnectionRepository, never()).save(any());
    }

    @Test
    void updateCloudConnection_shouldUpdateAndEncryptIfCredentialsProvided() {
        CloudConnectionDTO updateDto = CloudConnectionDTO.builder()
                                        .name("UpdatedName")
                                        .sensitiveCredentials("newSecret")
                                        .build();
        String newEncryptedSecret = "newEncryptedSecret";

        when(cloudConnectionRepository.findById(connectionId)).thenReturn(Optional.of(connectionEntity));
        when(encryptionService.encrypt("newSecret")).thenReturn(newEncryptedSecret);
        when(cloudConnectionRepository.save(any(CloudConnectionEntity.class))).thenReturn(connectionEntity);
        when(cloudConnectionMapper.toDto(connectionEntity)).thenReturn(CloudConnectionDTO.builder().name("UpdatedName").build());
        doNothing().when(cloudConnectionMapper).updateEntityFromDto(updateDto, connectionEntity);

        CloudConnectionDTO result = cloudConnectionService.updateCloudConnection(connectionId, updateDto);

        assertThat(result.getName()).isEqualTo("UpdatedName");
        verify(cloudConnectionMapper).updateEntityFromDto(updateDto, connectionEntity);
        verify(encryptionService).encrypt("newSecret");
        verify(cloudConnectionRepository).save(argThat(entity -> entity.getEncryptedCredentials().equals(newEncryptedSecret)));
    }
    
    @Test
    void updateCloudConnection_whenNameChangedToExisting_shouldThrowConflict() {
        CloudConnectionDTO updateDto = CloudConnectionDTO.builder().name("ExistingOtherName").build();
        CloudConnectionEntity otherEntity = new CloudConnectionEntity();
        otherEntity.setId("other-id");
        otherEntity.setName("ExistingOtherName");
        
        when(cloudConnectionRepository.findById(connectionId)).thenReturn(Optional.of(connectionEntity));
        when(cloudConnectionRepository.findByName("ExistingOtherName")).thenReturn(Optional.of(otherEntity));

        assertThrows(ConflictException.class, () -> cloudConnectionService.updateCloudConnection(connectionId, updateDto));
        verify(cloudConnectionRepository, never()).save(any());
    }

    @Test
    void deleteCloudConnection_shouldDelete() {
        when(cloudConnectionRepository.existsById(connectionId)).thenReturn(true);
        doNothing().when(cloudConnectionRepository).deleteById(connectionId);
        cloudConnectionService.deleteCloudConnection(connectionId);
        verify(cloudConnectionRepository).deleteById(connectionId);
    }

    @Test
    void deleteCloudConnection_whenNotFound_shouldThrowNotFoundException() {
        when(cloudConnectionRepository.existsById(connectionId)).thenReturn(false);
        assertThrows(NotFoundException.class, () -> cloudConnectionService.deleteCloudConnection(connectionId));
    }

    @Test
    void testCloudConnection_whenCredentialsResolve_marksSuccess() {
        when(cloudConnectionRepository.findById(connectionId)).thenReturn(Optional.of(connectionEntity));
        when(credentialProvider.resolve(any())).thenReturn(
                CredentialProvider.Resolved.ofSecret(CredentialProvider.Mode.ENCRYPTED_STORE, sensitiveData));
        lenient().when(credentialProvider.mode()).thenReturn(CredentialProvider.Mode.ENCRYPTED_STORE);
        when(cloudConnectionRepository.save(any(CloudConnectionEntity.class))).thenReturn(connectionEntity);

        CloudConnectionTestResultDTO result = cloudConnectionService.testCloudConnection(connectionId);

        assertThat(result.isSuccess()).isTrue();
        verify(cloudConnectionRepository).save(argThat(e ->
                "SUCCESS".equals(e.getLastConnectionTestStatus()) && e.getLastConnectionTestAt() != null));
    }

    @Test
    void testCloudConnection_whenFederated_marksSuccess() {
        when(cloudConnectionRepository.findById(connectionId)).thenReturn(Optional.of(connectionEntity));
        when(credentialProvider.resolve(any())).thenReturn(CredentialProvider.Resolved.federated("federated"));
        when(cloudConnectionRepository.save(any(CloudConnectionEntity.class))).thenReturn(connectionEntity);

        CloudConnectionTestResultDTO result = cloudConnectionService.testCloudConnection(connectionId);

        assertThat(result.isSuccess()).isTrue();
        verify(cloudConnectionRepository).save(argThat(e -> "SUCCESS".equals(e.getLastConnectionTestStatus())));
    }

    @Test
    void testCloudConnection_whenResolveThrows_marksFailed() {
        when(cloudConnectionRepository.findById(connectionId)).thenReturn(Optional.of(connectionEntity));
        when(credentialProvider.resolve(any())).thenThrow(new IllegalStateException("no key configured"));
        lenient().when(credentialProvider.mode()).thenReturn(CredentialProvider.Mode.ENCRYPTED_STORE);
        when(cloudConnectionRepository.save(any(CloudConnectionEntity.class))).thenReturn(connectionEntity);

        CloudConnectionTestResultDTO result = cloudConnectionService.testCloudConnection(connectionId);

        assertThat(result.isSuccess()).isFalse();
        // The secret is never surfaced - only the failure reason.
        assertThat(result.getMessage()).doesNotContain(sensitiveData);
        verify(cloudConnectionRepository).save(argThat(e -> "FAILED".equals(e.getLastConnectionTestStatus())));
    }

    @Test
    void getDecryptedCredentials_returnsResolvedSecret() {
        when(cloudConnectionRepository.findById(connectionId)).thenReturn(Optional.of(connectionEntity));
        when(credentialProvider.resolve(any())).thenReturn(
                CredentialProvider.Resolved.ofSecret(CredentialProvider.Mode.ENCRYPTED_STORE, sensitiveData));

        String result = cloudConnectionService.getDecryptedCredentials(connectionId);

        assertThat(result).isEqualTo(sensitiveData);
    }

    @Test
    void getDecryptedCredentials_whenEntityNotFound_shouldThrowNotFound() {
        when(cloudConnectionRepository.findById("unknown")).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> cloudConnectionService.getDecryptedCredentials("unknown"));
    }

    @Test
    void getDecryptedCredentials_whenUnresolvable_returnsNull() {
        when(cloudConnectionRepository.findById(connectionId)).thenReturn(Optional.of(connectionEntity));
        when(credentialProvider.resolve(any())).thenThrow(new IllegalStateException("no stored credentials"));
        lenient().when(credentialProvider.mode()).thenReturn(CredentialProvider.Mode.ENCRYPTED_STORE);

        assertThat(cloudConnectionService.getDecryptedCredentials(connectionId)).isNull();
    }

    @Test
    void getDecryptedCredentials_whenFederated_returnsNull() {
        when(cloudConnectionRepository.findById(connectionId)).thenReturn(Optional.of(connectionEntity));
        when(credentialProvider.resolve(any())).thenReturn(CredentialProvider.Resolved.federated("federated"));
        lenient().when(credentialProvider.mode()).thenReturn(CredentialProvider.Mode.FEDERATED_IDENTITY);

        assertThat(cloudConnectionService.getDecryptedCredentials(connectionId)).isNull();
    }
} 