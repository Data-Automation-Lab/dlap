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

package com.dlap.autolabel.entity;

import com.dlap.autolabel.client.rest.dto.LabelingFeedbackRequest;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * JPA Entity representing labeling feedback provided by a user.
 */
@Entity
@Table(name = "dlap_autolabel_feedback")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabelingFeedbackEntity {

    @Id
    private String feedbackId;

    @Column(nullable = false)
    private String assetId;

    @Column(nullable = false)
    private String labelingJobId; // The job that generated the suggestions being corrected

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<LabelingFeedbackRequest.FeedbackItem> feedbackItems;

    private String userId; // User who provided the feedback

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> additionalContext; // e.g., UI version, session ID

    @Column(nullable = false)
    private LocalDateTime receivedAt;

    private LocalDateTime processedAt; // When the feedback was fully processed (e.g., catalog updated)
    
    private String processingStatus; // E.g., "RECEIVED", "PROCESSING", "PROCESSED", "ERROR"
} 