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

package com.dlap.logingest.transport.lock;

import java.util.Optional;

/**
 * Cluster-wide try-locks for stream polls (design B5 "HA": a PostgreSQL advisory lock per
 * (binding, stream) guards each poll, so a second replica cannot poll the same stream at once).
 */
public interface AdvisoryLocks {

    /** A held lock; closing releases it. */
    interface Lease extends AutoCloseable {

        /** Releases the lock. Never throws. */
        @Override
        void close();
    }

    /**
     * Tries to take the lock of one stream without waiting.
     *
     * @param bindingId the binding
     * @param streamId  the stream
     * @return the lease, or empty when another holder has it
     */
    Optional<Lease> tryLockStream(String bindingId, String streamId);
}
