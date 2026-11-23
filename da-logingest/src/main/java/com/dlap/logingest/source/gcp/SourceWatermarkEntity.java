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

package com.dlap.logingest.source.gcp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-(provider, accountId) watermark - the timestamp of the most
 * recent audit event {@code da-logingest} has successfully ingested.
 *
 * <p>The pull-based per-cloud sources read this on startup + each
 * tick to know where to resume from, and advance it after each
 * successful batch. ADR-0023.
 */
@Entity
@Table(
    name = "source_watermarks",
    uniqueConstraints = @UniqueConstraint(name = "uk_source_watermark_provider_account",
            columnNames = {"provider", "account_id"})
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceWatermarkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    /** GCP | AWS | AZURE | OCI. */
    @Column(name = "provider", length = 32, nullable = false)
    private String provider;

    /** GCP project id / AWS account id / Azure subscription id / OCI tenancy ocid. */
    @Column(name = "account_id", length = 256, nullable = false)
    private String accountId;

    /** Timestamp of the most recently ingested audit event for this (provider, accountId). */
    @Column(name = "last_event_time", nullable = false)
    private Instant lastEventTime;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
