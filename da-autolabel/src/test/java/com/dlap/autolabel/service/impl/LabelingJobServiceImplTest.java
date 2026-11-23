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

package com.dlap.autolabel.service.impl;

import com.dlap.autolabel.client.dto.AssetIdentifier;
import com.dlap.autolabel.client.feign.AssetCatalogClient;
import com.dlap.autolabel.client.rest.dto.*;
import com.dlap.llm.client.config.MLConfigRequest;
import com.dlap.autolabel.entity.LabelingFeedbackEntity;
import com.dlap.autolabel.entity.LabelingJobEntity;
import com.dlap.autolabel.exception.AutoLabelingException;
import com.dlap.autolabel.mapper.LabelingJobMapper;
import com.dlap.autolabel.repository.LabelingFeedbackRepository;
import com.dlap.autolabel.repository.LabelingJobRepository;
import com.dlap.autolabel.service.IMLConfigService;
import com.dlap.autolabel.service.ILLMIntegrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LabelingJobServiceImplTest {

    @Mock
    private IMLConfigService mlConfigService;
    @Mock
    private LabelingJobRepository labelingJobRepository;
    @Mock
    private LabelingFeedbackRepository labelingFeedbackRepository;
    @Mock
    private LabelingJobMapper labelingJobMapper;
    @Mock
    private AssetCatalogClient assetCatalogClient; // Mocked, not used in these specific tests yet
    @Mock
    private ILLMIntegrationService llmIntegrationService; // Mocked for async part

    @InjectMocks
    private LabelingJobServiceImpl labelingJobService;

    private MLConfigRequest mlConfigRequest;
    private LabelingJobRequest labelingJobRequest;
    private LabelingJobEntity labelingJobEntity;
    private LabelingJobStatusResponse labelingJobStatusResponse;

    @BeforeEach
    void setUp() {
        mlConfigRequest = MLConfigRequest.builder().providerType("MOCK").modelName("mock-model").build();
        labelingJobRequest = LabelingJobRequest.builder()
                .jobName("Test Job")
                .scope(LabelingJobRequest.LabelingScope.builder().assetIds(List.of("asset1")).build())
                .build();
        
        String jobId = UUID.randomUUID().toString();
        labelingJobEntity = LabelingJobEntity.builder()
                .jobId(jobId)
                .jobName("Test Job")
                .status(LabelingJobEntity.JobStatus.SUBMITTED)
                .submittedAt(LocalDateTime.now())
                .build();
        labelingJobStatusResponse = LabelingJobStatusResponse.builder()
                .jobId(jobId)
                .jobName("Test Job")
                .status("SUBMITTED")
                .build();
    }

    @Test
    void submitLabelingJob_ValidRequest_ShouldSaveJobAndReturnResponse() throws AutoLabelingException {
        when(mlConfigService.getMlConfig()).thenReturn(Optional.of(mlConfigRequest));
        when(labelingJobRepository.save(any(LabelingJobEntity.class))).thenReturn(labelingJobEntity);
        // Mocking async method to avoid its execution. We are testing the synchronous part here.
        // A more robust way might involve a TestExecutionListener or checking side effects.
        LabelingJobServiceImpl spyService = spy(labelingJobService);
        doNothing().when(spyService).processLabelingJobAsync(anyString(), anyString(), any(LabelingJobRequest.LabelingScope.class), any(MLConfigRequest.class));

        LabelingJobResponse response = spyService.submitLabelingJob(labelingJobRequest);

        assertNotNull(response);
        assertEquals("SUBMITTED", response.getStatus());
        assertNotNull(response.getJobId());
        verify(labelingJobRepository).save(any(LabelingJobEntity.class));
        verify(spyService).processLabelingJobAsync(eq(response.getJobId()), eq(labelingJobRequest.getJobName()), eq(labelingJobRequest.getScope()), eq(mlConfigRequest));
    }

    @Test
    void submitLabelingJob_NoMlConfig_ShouldThrowAutoLabelingException() {
        when(mlConfigService.getMlConfig()).thenReturn(Optional.empty());
        assertThrows(AutoLabelingException.class, () -> {
            labelingJobService.submitLabelingJob(labelingJobRequest);
        });
        verify(labelingJobRepository, never()).save(any(LabelingJobEntity.class));
    }

    @Test
    void getJobStatus_ExistingJob_ShouldReturnStatusResponse() {
        when(labelingJobRepository.findById(anyString())).thenReturn(Optional.of(labelingJobEntity));
        when(labelingJobMapper.toStatusResponse(any(LabelingJobEntity.class))).thenReturn(labelingJobStatusResponse);

        LabelingJobStatusResponse response = labelingJobService.getJobStatus("some-job-id");

        assertNotNull(response);
        assertEquals(labelingJobEntity.getJobId(), response.getJobId());
        verify(labelingJobRepository).findById(eq("some-job-id"));
    }

    @Test
    void getJobStatus_NonExistingJob_ShouldReturnNull() {
        when(labelingJobRepository.findById(anyString())).thenReturn(Optional.empty());
        LabelingJobStatusResponse response = labelingJobService.getJobStatus("non-existent-id");
        assertNull(response);
        verify(labelingJobMapper, never()).toStatusResponse(any());
    }

    @Test
    void listJobs_ShouldReturnPaginatedResponse() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<LabelingJobEntity> page = new PageImpl<>(Collections.singletonList(labelingJobEntity), pageable, 1);
        LabelingJobListResponse expectedResponse = LabelingJobListResponse.builder().jobs(List.of(labelingJobStatusResponse)).build(); 
        // Mapper is complex, so we trust its unit tests and mock its output directly for listJobs

        when(labelingJobRepository.findAll(any(Pageable.class))).thenReturn(page);
        when(labelingJobMapper.toJobListResponse(page)).thenReturn(expectedResponse);

        LabelingJobListResponse actualResponse = labelingJobService.listJobs(pageable);

        assertNotNull(actualResponse);
        assertEquals(expectedResponse.getJobs().size(), actualResponse.getJobs().size());
        verify(labelingJobRepository).findAll(pageable);
        verify(labelingJobMapper).toJobListResponse(page);
    }

    @Test
    void processLabelingFeedback_ValidRequest_ShouldSaveFeedback() throws AutoLabelingException {
        UUID assetUuid = UUID.randomUUID();
        LabelingFeedbackRequest feedbackRequest = LabelingFeedbackRequest.builder()
                .assetId(assetUuid.toString())
                .labelingJobId(UUID.randomUUID().toString())
                .feedbackItems(List.of(LabelingFeedbackRequest.FeedbackItem.builder().suggestedLabel("Old").correctedLabel("New").type(LabelingFeedbackRequest.FeedbackType.CORRECTED).build()))
                .build();

        doNothing().when(assetCatalogClient).addLabelsToAsset(eq(assetUuid), any());
        doNothing().when(assetCatalogClient).removeLabelFromAsset(eq(assetUuid), anyString());
        
        ArgumentCaptor<LabelingFeedbackEntity> feedbackEntityCaptor = ArgumentCaptor.forClass(LabelingFeedbackEntity.class);
        when(labelingFeedbackRepository.save(any(LabelingFeedbackEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        LabelingFeedbackResponse response = labelingJobService.processLabelingFeedback(feedbackRequest);

        assertNotNull(response);
        assertEquals("PROCESSED", response.getStatus());
        assertNotNull(response.getFeedbackId());
        verify(labelingFeedbackRepository, times(2)).save(feedbackEntityCaptor.capture()); // Saved once for RECEIVED, once for PROCESSED
        
        LabelingFeedbackEntity savedEntity = feedbackEntityCaptor.getValue();
        assertEquals(feedbackRequest.getAssetId(), savedEntity.getAssetId());
        assertEquals("PROCESSED", savedEntity.getProcessingStatus());
    }

    @Test
    void processLabelingFeedback_EmptyItems_ShouldThrowException() {
        LabelingFeedbackRequest feedbackRequest = LabelingFeedbackRequest.builder().feedbackItems(Collections.emptyList()).build();
        assertThrows(AutoLabelingException.class, () -> {
            labelingJobService.processLabelingFeedback(feedbackRequest);
        });
        verify(labelingFeedbackRepository, never()).save(any());
    }
} 