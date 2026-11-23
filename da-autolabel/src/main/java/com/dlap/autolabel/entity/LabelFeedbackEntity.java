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

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Minimal feedback record persisted each time a reviewer accepts, rejects, or
 * corrects an auto-labeling suggestion. Designed to be a flat, append-only
 * table so an M2 active-learning pipeline can later aggregate it without
 * touching the M1 labeling flow.
 */
@Entity
@Table(name = "dlap_autolabel_label_feedback")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabelFeedbackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private String assetId;

    @Column(name = "suggested_label", nullable = false)
    private String suggestedLabel;

    @Column(name = "accepted", nullable = false)
    private boolean accepted;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "ts", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "comment", length = 2048)
    private String comment;
}
