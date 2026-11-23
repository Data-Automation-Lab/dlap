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

import com.dlap.logingest.parse.core.RawRecord;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A unit a transport delivers and the runner commits as one: an API page or one object.
 *
 * @param partitionKey the stream partition, {@code ""} for a single cursor
 * @param records      the records, already split from their container
 * @param object       the object the records came from, for file transports
 * @param advanceTo    where the partition's cursor stands once every record reached its outcome
 */
public record RawBatch(String partitionKey, List<RawRecord> records, Optional<ObjectRef> object,
                       CursorPosition advanceTo) {

    /**
     * One object of a file transport.
     *
     * @param key  the object key
     * @param etag the etag or generation, {@code ""} when the store has none
     */
    public record ObjectRef(String key, String etag) {

        /**
         * Validates the reference.
         *
         * @param key  see the record description
         * @param etag see the record description
         */
        public ObjectRef {
            if (key == null || key.isEmpty()) {
                throw new IllegalArgumentException("an object reference needs a key");
            }
            etag = etag == null ? "" : etag;
        }
    }

    /**
     * Validates the batch.
     *
     * @param partitionKey see the record description
     * @param records      see the record description
     * @param object       see the record description
     * @param advanceTo    see the record description
     */
    public RawBatch {
        partitionKey = partitionKey == null ? "" : partitionKey;
        records = List.copyOf(records);
        object = object == null ? Optional.empty() : object;
        Objects.requireNonNull(advanceTo, "advanceTo");
    }
}
