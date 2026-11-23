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

import java.util.EnumSet;
import java.util.Set;

/**
 * The state of a backfill job (Flyway V3 {@code backfill_job.status}).
 *
 * <p>{@code PENDING -> RUNNING -> SUCCEEDED | FAILED | CANCELLED}. A job stays {@code RUNNING}
 * while a replica works on it and also after that replica died: the next scheduler tick finds it,
 * takes its lock and resumes from the stored progress.
 */
public enum BackfillStatus {

    /** Accepted, not started. */
    PENDING,

    /** Started; resumable. */
    RUNNING,

    /** Every partition read to the end of the window. */
    SUCCEEDED,

    /** Stopped for good: a configuration problem, or too many failed attempts. */
    FAILED,

    /** Stopped by an administrator. */
    CANCELLED;

    /** The states in which a job occupies its binding (the partial unique index of V3). */
    public static final Set<BackfillStatus> ACTIVE = Set.copyOf(EnumSet.of(PENDING, RUNNING));

    /**
     * Whether the job still occupies its binding.
     *
     * @return true for {@code PENDING} and {@code RUNNING}
     */
    public boolean active() {
        return ACTIVE.contains(this);
    }
}
