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

package com.dlap.autolabel.service;

import com.dlap.autolabel.client.rest.dto.LabelingJobRequest;
import com.dlap.autolabel.client.rest.dto.LabelingJobResponse;
import com.dlap.autolabel.client.rest.dto.LabelingJobStatusResponse;
import com.dlap.autolabel.client.rest.dto.LabelingJobListResponse;
import com.dlap.autolabel.client.rest.dto.LabelingFeedbackRequest;
import com.dlap.autolabel.client.rest.dto.LabelingFeedbackResponse;
import com.dlap.autolabel.exception.AutoLabelingException;
import org.springframework.data.domain.Pageable;

/**
 * Service interface for managing auto-labeling jobs.
 */
public interface ILabelingJobService {

    /**
     * Submits a new auto-labeling job.
     *
     * @param request The labeling job request DTO.
     * @return A response DTO containing the job ID and initial status.
     * @throws AutoLabelingException if ML config is not found or other issues.
     */
    LabelingJobResponse submitLabelingJob(LabelingJobRequest request) throws AutoLabelingException;

    /**
     * Retrieves the status of a specific labeling job.
     *
     * @param jobId The ID of the job.
     * @return A response DTO containing the job status, or null if not found.
     */
    LabelingJobStatusResponse getJobStatus(String jobId);

    /**
     * Lists all labeling jobs, with optional pagination.
     *
     * @param pageable Pagination information.
     * @return A paginated list of job statuses.
     */
    LabelingJobListResponse listJobs(Pageable pageable);

    /**
     * Processes user feedback on suggested labels for an asset.
     *
     * @param request The labeling feedback request DTO.
     * @return A response DTO indicating the result of processing the feedback.
     * @throws AutoLabelingException if there is an issue processing the feedback.
     */
    LabelingFeedbackResponse processLabelingFeedback(LabelingFeedbackRequest request) throws AutoLabelingException;

    // Potentially add other methods like listJobs, cancelJob, etc. in the future.
} 