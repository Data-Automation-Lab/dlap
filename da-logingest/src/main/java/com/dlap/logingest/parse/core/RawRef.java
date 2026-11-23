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

package com.dlap.logingest.parse.core;

import com.dlap.proto.log.v1.RawRecordRef;
import java.util.Objects;

/**
 * Where a raw record lives at its source, for replay and audit. Mirrors
 * {@code dlap.log.v1.RawRecordRef}.
 *
 * @param transport   transport name, an open string such as {@code S3_TRAIL_FILES} or {@code LOGGING_API}
 * @param uri         the container: an object URI, a table, or an API scope such as {@code projects/p}
 * @param recordIndex zero-based index inside the container, or -1 when not applicable
 * @param cursor      opaque transport cursor when the record has no stable container; may be empty
 */
public record RawRef(String transport, String uri, long recordIndex, String cursor) {

    /**
     * Validates the reference.
     *
     * @param transport   see the record description
     * @param uri         see the record description
     * @param recordIndex see the record description
     * @param cursor      see the record description
     */
    public RawRef {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(uri, "uri");
        if (transport.isBlank() || uri.isBlank()) {
            throw new IllegalArgumentException("a raw reference needs a transport and a uri");
        }
        if (recordIndex < -1) {
            throw new IllegalArgumentException("recordIndex is -1 or a zero-based index");
        }
        cursor = cursor == null ? "" : cursor;
    }

    /**
     * A reference to one record of a container.
     *
     * @param transport   the transport
     * @param uri         the container
     * @param recordIndex the zero-based index
     * @return the reference
     */
    public static RawRef of(String transport, String uri, long recordIndex) {
        return new RawRef(transport, uri, recordIndex, "");
    }

    /**
     * Converts to the wire message.
     *
     * @return the {@code RawRecordRef}
     */
    public RawRecordRef toProto() {
        return RawRecordRef.newBuilder()
                .setTransport(transport)
                .setUri(uri)
                .setRecordIndex(recordIndex)
                .setCursor(cursor)
                .build();
    }
}
