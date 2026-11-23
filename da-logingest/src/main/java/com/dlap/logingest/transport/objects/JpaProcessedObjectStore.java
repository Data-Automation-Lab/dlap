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

package com.dlap.logingest.transport.objects;

import com.dlap.common.logevent.LogEventIds;
import com.dlap.logingest.model.ProcessedObjectEntity;
import com.dlap.logingest.model.ProcessedObjectId;
import com.dlap.logingest.model.ProcessedObjectStatus;
import com.dlap.logingest.repository.ProcessedObjectRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ProcessedObjectStore} on {@code dlap_logingest.processed_object}. Rows are keyed by the
 * SHA-256 of the object key, which keeps long multi-byte keys inside the index limit.
 */
@Component
public class JpaProcessedObjectStore implements ProcessedObjectStore {

    private final ProcessedObjectRepository repository;
    private final Clock clock;

    /**
     * Creates the store.
     *
     * @param repository the checkpoint table
     * @param clock      the application clock when unique, else the UTC system clock
     */
    public JpaProcessedObjectStore(ProcessedObjectRepository repository, ObjectProvider<Clock> clock) {
        this.repository = repository;
        this.clock = clock.getIfUnique(Clock::systemUTC);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isDone(String bindingId, String streamId, String objectKey, String etag) {
        return repository.existsByBindingIdAndStreamIdAndObjectKeySha256AndEtagAndStatus(bindingId, streamId,
                LogEventIds.sha256Hex(objectKey), etag == null ? "" : etag, ProcessedObjectStatus.DONE);
    }

    @Override
    @Transactional
    public void markDone(String bindingId, String streamId, String objectKey, String etag, long records,
                         long quarantined) {
        ProcessedObjectEntity e = load(bindingId, streamId, objectKey, etag);
        e.setStatus(ProcessedObjectStatus.DONE);
        e.setRecords(records);
        e.setQuarantined(quarantined);
        repository.save(e);
    }

    @Override
    @Transactional
    public void markFailed(String bindingId, String streamId, String objectKey, String etag) {
        ProcessedObjectEntity e = load(bindingId, streamId, objectKey, etag);
        e.setStatus(ProcessedObjectStatus.FAILED);
        repository.save(e);
    }

    @Override
    @Transactional(readOnly = true)
    public int attempts(String bindingId, String streamId, String objectKey, String etag) {
        return repository.findById(new ProcessedObjectId(bindingId, streamId, LogEventIds.sha256Hex(objectKey),
                etag == null ? "" : etag)).map(ProcessedObjectEntity::getAttempts).orElse(0);
    }

    private ProcessedObjectEntity load(String bindingId, String streamId, String objectKey, String etag) {
        String tag = etag == null ? "" : etag;
        String hash = LogEventIds.sha256Hex(objectKey);
        Instant now = clock.instant();
        ProcessedObjectEntity e = repository.findById(new ProcessedObjectId(bindingId, streamId, hash, tag))
                .orElseGet(() -> {
                    ProcessedObjectEntity n = new ProcessedObjectEntity();
                    n.setBindingId(bindingId);
                    n.setStreamId(streamId);
                    n.setObjectKeySha256(hash);
                    n.setEtag(tag);
                    n.setObjectKey(objectKey);
                    n.setFirstProcessedAt(now);
                    return n;
                });
        e.setAttempts(e.getAttempts() + 1);
        e.setLastProcessedAt(now);
        return e;
    }
}
