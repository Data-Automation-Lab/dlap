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

import com.dlap.logingest.transport.objects.ProcessedObjectStore;
import java.util.UUID;

/**
 * Object checkpoints for a history read. A streaming poll records the objects it read under the
 * stream's own id, so a file transport asked to read history in the stream's name would skip every
 * object the stream already processed, and a re-read of a quarantined record would find nothing.
 * A history read therefore keeps its checkpoints apart:
 * <ul>
 *   <li>{@link #forJob} writes them under the pseudo stream {@code backfill-<job id>} in
 *       {@code processed_object}, so a job that died resumes without re-reading finished objects
 *       and a finished job leaves the stream's own checkpoints untouched;</li>
 *   <li>{@link #none} keeps nothing, for a one-shot source re-read.</li>
 * </ul>
 */
final class ScopedObjects {

    private ScopedObjects() {
    }

    /**
     * The pseudo stream id of a job's checkpoints.
     *
     * @param jobId the job
     * @return {@code backfill-<job id>}
     */
    static String scopeOf(UUID jobId) {
        return "backfill-" + jobId;
    }

    /**
     * Checkpoints kept under a job's pseudo stream.
     *
     * @param delegate the real store
     * @param jobId    the job
     * @return the scoped store
     */
    static ProcessedObjectStore forJob(ProcessedObjectStore delegate, UUID jobId) {
        String scope = scopeOf(jobId);
        return new ProcessedObjectStore() {
            @Override
            public boolean isDone(String bindingId, String streamId, String objectKey, String etag) {
                return delegate.isDone(bindingId, scope, objectKey, etag);
            }

            @Override
            public void markDone(String bindingId, String streamId, String objectKey, String etag, long records,
                                 long quarantined) {
                delegate.markDone(bindingId, scope, objectKey, etag, records, quarantined);
            }

            @Override
            public void markFailed(String bindingId, String streamId, String objectKey, String etag) {
                delegate.markFailed(bindingId, scope, objectKey, etag);
            }

            @Override
            public int attempts(String bindingId, String streamId, String objectKey, String etag) {
                return delegate.attempts(bindingId, scope, objectKey, etag);
            }
        };
    }

    /**
     * A store that remembers nothing: every object is read.
     *
     * @return the store
     */
    static ProcessedObjectStore none() {
        return new ProcessedObjectStore() {
            @Override
            public boolean isDone(String bindingId, String streamId, String objectKey, String etag) {
                return false;
            }

            @Override
            public void markDone(String bindingId, String streamId, String objectKey, String etag, long records,
                                 long quarantined) {
                // nothing to remember
            }

            @Override
            public void markFailed(String bindingId, String streamId, String objectKey, String etag) {
                // nothing to remember
            }

            @Override
            public int attempts(String bindingId, String streamId, String objectKey, String etag) {
                return 0;
            }
        };
    }
}
