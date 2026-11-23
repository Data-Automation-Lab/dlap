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

import com.dlap.logingest.transport.CursorPosition;
import java.util.Optional;

/**
 * Persists stream cursors ({@code stream_cursor}, design B5). Only the poller holding the stream's
 * advisory lock writes a stream's cursors.
 */
public interface StreamCursorStore {

    /**
     * Reads a cursor.
     *
     * @param bindingId    the binding
     * @param streamId     the stream
     * @param partitionKey the partition, {@code ""} for a single cursor
     * @return the committed position, or empty before the first commit
     */
    Optional<CursorPosition> load(String bindingId, String streamId, String partitionKey);

    /**
     * Commits a cursor. The high watermark never moves backwards.
     *
     * @param bindingId    the binding
     * @param streamId     the stream
     * @param partitionKey the partition
     * @param position     the new position
     */
    void commit(String bindingId, String streamId, String partitionKey, CursorPosition position);
}
