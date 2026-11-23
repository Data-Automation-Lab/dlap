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

package com.dlap.logingest.backfill;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of {@code dlap_logingest.backfill_job} (Flyway V3, design B6). The partial unique index
 * {@code uq_backfill_job_active} allows one {@code PENDING} or {@code RUNNING} job per binding.
 * Transitions go through the guarded updates of {@link BackfillJobRepository}, never through a
 * full-row save, so a cancel cannot be overwritten by a progress write.
 */
@Entity
@Table(name = "backfill_job")
@Getter
@Setter
@NoArgsConstructor
public class BackfillJobEntity {

    /** The job id. */
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /** The binding. */
    @Column(name = "binding_id", length = 128, nullable = false)
    private String bindingId;

    /** The stream of the binding that is read. */
    @Column(name = "stream_id", length = 128, nullable = false)
    private String streamId;

    /** Inclusive start of the window. */
    @Column(name = "from_ts", nullable = false)
    private Instant fromTs;

    /** Exclusive end of the window. */
    @Column(name = "to_ts", nullable = false)
    private Instant toTs;

    /** The state. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private BackfillStatus status;

    /** The job's cursor and counters, see {@link BackfillProgress#toMap()}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "progress", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> progress;

    /** Who asked. */
    @Column(name = "requested_by", length = 256, nullable = false)
    private String requestedBy;

    /** When it was accepted. */
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    /** When it first ran. */
    @Column(name = "started_at")
    private Instant startedAt;

    /** When it ended. */
    @Column(name = "finished_at")
    private Instant finishedAt;

    /** Why it failed or was cancelled. */
    @Column(name = "error_detail", length = 1024)
    private String errorDetail;

    /**
     * The API view of the row.
     *
     * @return the job
     */
    public BackfillJob toJob() {
        return new BackfillJob(id, bindingId, streamId, fromTs, toTs, status, BackfillProgress.fromMap(progress),
                requestedBy, requestedAt, startedAt, finishedAt, errorDetail);
    }
}
