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

/**
 * Checkpoints of file transports ({@code processed_object}, design B5): which object versions were
 * read to completion.
 */
public interface ProcessedObjectStore {

    /**
     * Whether an object version was read to completion.
     *
     * @param bindingId the binding
     * @param streamId  the stream
     * @param objectKey the object key
     * @param etag      the etag, {@code ""} when the store has none
     * @return true for a {@code DONE} checkpoint
     */
    boolean isDone(String bindingId, String streamId, String objectKey, String etag);

    /**
     * Records that every record of an object version reached an outcome.
     *
     * @param bindingId   the binding
     * @param streamId    the stream
     * @param objectKey   the object key
     * @param etag        the etag
     * @param records     records read
     * @param quarantined records quarantined
     */
    void markDone(String bindingId, String streamId, String objectKey, String etag, long records, long quarantined);

    /**
     * Records that reading or ingesting an object version failed; it is retried.
     *
     * @param bindingId the binding
     * @param streamId  the stream
     * @param objectKey the object key
     * @param etag      the etag
     */
    void markFailed(String bindingId, String streamId, String objectKey, String etag);

    /**
     * How many times reading or ingesting an object version was recorded, successful or not
     * ({@code processed_object.attempts}). The read-failure cap of {@code StreamRunner} and the backfill
     * runner counts with it (Q-665, Q-702). The method is abstract on purpose: a store that returned a
     * constant would never reach the cap and the unreadable object would be retried forever (Q-706), so
     * every store must say what it counts.
     *
     * @param bindingId the binding
     * @param streamId  the stream
     * @param objectKey the object key
     * @param etag      the etag
     * @return the recorded attempts; 0 when none was recorded
     */
    int attempts(String bindingId, String streamId, String objectKey, String etag);
}
