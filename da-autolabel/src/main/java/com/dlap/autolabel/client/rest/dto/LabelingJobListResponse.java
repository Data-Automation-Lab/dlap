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

package com.dlap.autolabel.client.rest.dto;

import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * Response DTO for listing auto-labeling jobs with pagination information.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabelingJobListResponse {

    /**
     * List of job statuses for the current page.
     */
    private List<LabelingJobStatusResponse> jobs;

    /**
     * Current page number (0-indexed).
     */
    private int pageNumber;

    /**
     * Number of jobs per page.
     */
    private int pageSize;

    /**
     * Total number of jobs matching the criteria.
     */
    private long totalElements;

    /**
     * Total number of pages.
     */
    private int totalPages;

    /**
     * Indicates if this is the last page.
     */
    private boolean last;
} 