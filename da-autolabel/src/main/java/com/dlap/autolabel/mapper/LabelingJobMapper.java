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

package com.dlap.autolabel.mapper;

import com.dlap.autolabel.client.rest.dto.LabelingJobStatusResponse;
import com.dlap.autolabel.client.rest.dto.LabelingJobListResponse;
import com.dlap.autolabel.entity.LabelingJobEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.springframework.data.domain.Page;

import java.util.List;

@Mapper(componentModel = "spring")
public interface LabelingJobMapper {

    LabelingJobMapper INSTANCE = Mappers.getMapper(LabelingJobMapper.class);

    @Mapping(target = "resultsLink", constant = "")
    LabelingJobStatusResponse toStatusResponse(LabelingJobEntity entity);

    List<LabelingJobStatusResponse> toStatusResponseList(List<LabelingJobEntity> entities);

    default LabelingJobListResponse toJobListResponse(Page<LabelingJobEntity> page) {
        if (page == null) {
            return null;
        }
        return LabelingJobListResponse.builder()
                .jobs(toStatusResponseList(page.getContent()))
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .last(page.isLast())
                .build();
    }
    // Add mappings from LabelingJobRequest to LabelingJobEntity if needed for creation
    // Add mappings from LabelingFeedbackRequest to LabelingFeedbackEntity
} 