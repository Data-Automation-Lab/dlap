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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The job table as the service and the runner see it. Every write names the state it expects, so a
 * cancelled job cannot be revived and two replicas cannot both finish one job.
 */
public interface BackfillJobs {

    /** The binding already has a {@code PENDING} or {@code RUNNING} job. */
    final class ActiveJobExistsException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String bindingId;

        /**
         * Creates the exception.
         *
         * @param bindingId the binding
         */
        public ActiveJobExistsException(String bindingId) {
            super("binding " + bindingId + " already has an active backfill job");
            this.bindingId = bindingId;
        }

        /**
         * The binding.
         *
         * @return the binding id
         */
        public String bindingId() {
            return bindingId;
        }
    }

    /**
     * Inserts a new {@code PENDING} job.
     *
     * @param id          the job id
     * @param bindingId   the binding
     * @param streamId    the stream
     * @param from        start of the window
     * @param to          end of the window
     * @param requestedBy who asked
     * @param at          when
     * @return the stored job
     * @throws ActiveJobExistsException when the binding has an active job
     */
    BackfillJob insert(UUID id, String bindingId, String streamId, Instant from, Instant to, String requestedBy,
                       Instant at);

    /**
     * Finds a job.
     *
     * @param id the id
     * @return the job, or empty
     */
    Optional<BackfillJob> find(UUID id);

    /**
     * The job occupying a binding.
     *
     * @param bindingId the binding
     * @return the active job, or empty
     */
    Optional<BackfillJob> activeFor(String bindingId);

    /**
     * One page of jobs, newest request first.
     *
     * @param bindingId only this binding, or {@code null}
     * @param status    only this state, or {@code null}
     * @param limit     the page size
     * @param offset    rows to skip
     * @return the jobs
     */
    List<BackfillJob> list(String bindingId, BackfillStatus status, int limit, long offset);

    /**
     * Counts the jobs a list would return.
     *
     * @param bindingId only this binding, or {@code null}
     * @param status    only this state, or {@code null}
     * @return the count
     */
    long count(String bindingId, BackfillStatus status);

    /**
     * Jobs that are {@code PENDING} or {@code RUNNING}, oldest request first.
     *
     * @param limit the most jobs
     * @return the jobs
     */
    List<BackfillJob> runnable(int limit);

    /**
     * Moves a job to {@code RUNNING} (or keeps it there), stamping {@code started_at} once.
     *
     * @param id the job
     * @param at the time
     * @return false when the job has ended
     */
    boolean markRunning(UUID id, Instant at);

    /**
     * Stores the progress of a running job.
     *
     * @param id       the job
     * @param progress the progress
     * @return false when the job is no longer running (cancelled or ended): the caller must stop
     */
    boolean saveProgress(UUID id, BackfillProgress progress);

    /**
     * Ends a running job.
     *
     * @param id       the job
     * @param terminal {@code SUCCEEDED} or {@code FAILED}
     * @param at       the time
     * @param error    the reason, empty on success
     * @param progress the last progress
     * @return false when the job was cancelled meanwhile
     */
    boolean finish(UUID id, BackfillStatus terminal, Instant at, String error, BackfillProgress progress);

    /**
     * Cancels a job that has not ended.
     *
     * @param id     the job
     * @param at     the time
     * @param reason the reason
     * @return false when the job had already ended
     */
    boolean cancel(UUID id, Instant at, String reason);
}
