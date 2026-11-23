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

package com.dlap.logingest.transport.cursor;

import com.dlap.logingest.model.StreamCursorEntity;
import com.dlap.logingest.model.StreamCursorId;
import com.dlap.logingest.repository.StreamCursorRepository;
import com.dlap.logingest.transport.CursorPosition;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link StreamCursorStore} on {@code dlap_logingest.stream_cursor}.
 */
@Component
public class JpaStreamCursorStore implements StreamCursorStore {

    private final StreamCursorRepository repository;
    private final Clock clock;

    /**
     * Creates the store.
     *
     * @param repository the cursor table
     * @param clock      the application clock when unique, else the UTC system clock
     */
    public JpaStreamCursorStore(StreamCursorRepository repository, ObjectProvider<Clock> clock) {
        this.repository = repository;
        this.clock = clock.getIfUnique(Clock::systemUTC);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CursorPosition> load(String bindingId, String streamId, String partitionKey) {
        return repository.findById(new StreamCursorId(bindingId, streamId, partitionKey == null ? "" : partitionKey))
                .map(e -> new CursorPosition(e.getLowWatermark(), e.getHighWatermark(), e.getCursor()));
    }

    @Override
    @Transactional
    public void commit(String bindingId, String streamId, String partitionKey, CursorPosition position) {
        String partition = partitionKey == null ? "" : partitionKey;
        StreamCursorEntity e = repository.findById(new StreamCursorId(bindingId, streamId, partition))
                .orElseGet(() -> {
                    StreamCursorEntity n = new StreamCursorEntity();
                    n.setBindingId(bindingId);
                    n.setStreamId(streamId);
                    n.setPartitionKey(partition);
                    return n;
                });
        e.setLowWatermark(position.lowWatermark());
        e.setHighWatermark(later(e.getHighWatermark(), position.highWatermark()));
        e.setCursor(new LinkedHashMap<>(position.state()));
        e.setUpdatedAt(clock.instant());
        repository.save(e);
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }
}
