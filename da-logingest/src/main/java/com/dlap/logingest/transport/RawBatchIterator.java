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

import java.util.Iterator;

/**
 * The batches of one fetch, in the order the cursor may advance through them. Closed by the runner
 * when the poll ends, early or not.
 */
public interface RawBatchIterator extends Iterator<RawBatch>, AutoCloseable {

    /** Releases the transport's resources; the default holds none. */
    @Override
    default void close() {
    }

    /**
     * An iterator over a fixed list, for transports that page in memory and for tests.
     *
     * @param batches the batches
     * @return the iterator
     */
    static RawBatchIterator of(java.util.List<RawBatch> batches) {
        Iterator<RawBatch> it = java.util.List.copyOf(batches).iterator();
        return new RawBatchIterator() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public RawBatch next() {
                return it.next();
            }
        };
    }
}
