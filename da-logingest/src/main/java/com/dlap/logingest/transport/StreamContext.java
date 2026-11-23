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

package com.dlap.logingest.transport;

import com.dlap.logingest.config.sources.EffectiveStream;
import com.dlap.logingest.parse.core.IngestMode;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.RawRef;
import com.dlap.logingest.transport.objects.ProcessedObjectStore;
import java.time.Clock;
import java.util.Objects;

/**
 * What a transport sees of the stream it fetches.
 *
 * @param stream     the stream with every setting resolved
 * @param sourceKind the binding's source kind
 * @param mode       {@code STREAM} for polling, {@code BACKFILL} for a bounded history read
 * @param clock      the service clock; operational time only
 * @param objects    the processed-object checkpoints, so a file transport skips objects it read
 */
public record StreamContext(EffectiveStream stream, String sourceKind, IngestMode mode, Clock clock,
                            ProcessedObjectStore objects) {

    /**
     * Validates the context.
     *
     * @param stream     see the record description
     * @param sourceKind see the record description
     * @param mode       see the record description
     * @param clock      see the record description
     * @param objects    see the record description
     */
    public StreamContext {
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(sourceKind, "sourceKind");
        mode = mode == null ? IngestMode.STREAM : mode;
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(objects, "objects");
    }

    /**
     * Starts a raw record of this stream: binding, source kind, envelope, mode and fetch time set.
     *
     * @param ref where the record lives
     * @return a builder; set the payload and build
     */
    public RawRecord.Builder record(RawRef ref) {
        return RawRecord.builder()
                .bindingId(stream.bindingId())
                .sourceKind(sourceKind)
                .envelope(stream.envelope())
                .ref(ref)
                .mode(mode)
                .fetchedAt(clock.instant());
    }

    /**
     * Whether an object version was already read to completion.
     *
     * @param key  the object key
     * @param etag the etag, {@code ""} when the store has none
     * @return true when the object can be skipped
     */
    public boolean alreadyProcessed(String key, String etag) {
        return objects.isDone(stream.bindingId(), stream.streamId(), key, etag);
    }
}
