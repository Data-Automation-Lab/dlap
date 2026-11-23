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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link BackfillJobs} on {@code dlap_logingest.backfill_job}. The one-active-job-per-binding rule is
 * the partial unique index of Flyway V3, so it holds across replicas without a lock.
 */
@Component
public class JpaBackfillJobs implements BackfillJobs {

    private final BackfillJobRepository repository;

    /**
     * Creates the store.
     *
     * @param repository the job table
     */
    public JpaBackfillJobs(BackfillJobRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public BackfillJob insert(UUID id, String bindingId, String streamId, Instant from, Instant to,
                              String requestedBy, Instant at) {
        BackfillJobEntity e = new BackfillJobEntity();
        e.setId(id);
        e.setBindingId(bindingId);
        e.setStreamId(streamId);
        e.setFromTs(from);
        e.setToTs(to);
        e.setStatus(BackfillStatus.PENDING);
        e.setProgress(BackfillProgress.EMPTY.toMap());
        e.setRequestedBy(requestedBy);
        e.setRequestedAt(at);
        if (repository.findFirstByBindingIdAndStatusIn(bindingId, BackfillStatus.ACTIVE).isPresent()) {
            throw new ActiveJobExistsException(bindingId);
        }
        try {
            return repository.saveAndFlush(e).toJob();
        } catch (DataIntegrityViolationException ex) {
            // A racing request won: the only unique constraint besides the random primary key is
            // uq_backfill_job_active. PostgreSQL refuses further statements in this transaction, so
            // no second look is taken here.
            throw new ActiveJobExistsException(bindingId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BackfillJob> find(UUID id) {
        return repository.findById(id).map(BackfillJobEntity::toJob);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BackfillJob> activeFor(String bindingId) {
        return repository.findFirstByBindingIdAndStatusIn(bindingId, BackfillStatus.ACTIVE)
                .map(BackfillJobEntity::toJob);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BackfillJob> list(String bindingId, BackfillStatus status, int limit, long offset) {
        if (limit < 1 || offset < 0 || offset % limit != 0) {
            throw new IllegalArgumentException("offset is a whole number of pages");
        }
        return repository.find(bindingId, status, PageRequest.of((int) (offset / limit), limit)).stream()
                .map(BackfillJobEntity::toJob).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count(String bindingId, BackfillStatus status) {
        return repository.countMatching(bindingId, status);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BackfillJob> runnable(int limit) {
        return repository.runnable(BackfillStatus.ACTIVE, PageRequest.of(0, limit)).stream()
                .map(BackfillJobEntity::toJob).toList();
    }

    @Override
    @Transactional
    public boolean markRunning(UUID id, Instant at) {
        return repository.markRunning(id, BackfillStatus.RUNNING, BackfillStatus.ACTIVE, at) > 0;
    }

    @Override
    @Transactional
    public boolean saveProgress(UUID id, BackfillProgress progress) {
        return repository.saveProgress(id, BackfillStatus.RUNNING, progress.toMap()) > 0;
    }

    @Override
    @Transactional
    public boolean finish(UUID id, BackfillStatus terminal, Instant at, String error, BackfillProgress progress) {
        if (terminal.active()) {
            throw new IllegalArgumentException("finish needs a terminal state, not " + terminal);
        }
        return repository.finish(id, BackfillStatus.RUNNING, terminal, at, error == null ? "" : bound(error),
                progress.toMap()) > 0;
    }

    @Override
    @Transactional
    public boolean cancel(UUID id, Instant at, String reason) {
        return repository.cancel(id, BackfillStatus.CANCELLED, BackfillStatus.ACTIVE, at, bound(reason)) > 0;
    }

    private static String bound(String text) {
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }
}
