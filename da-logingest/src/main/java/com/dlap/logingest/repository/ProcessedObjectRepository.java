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

package com.dlap.logingest.repository;

import com.dlap.logingest.model.ProcessedObjectEntity;
import com.dlap.logingest.model.ProcessedObjectId;
import com.dlap.logingest.model.ProcessedObjectStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence layer for {@link ProcessedObjectEntity}.
 */
@Repository
public interface ProcessedObjectRepository extends JpaRepository<ProcessedObjectEntity, ProcessedObjectId> {

    /**
     * Whether an object version reached a given outcome.
     *
     * @param bindingId       the binding id
     * @param streamId        the stream id
     * @param objectKeySha256 SHA-256 of the object key
     * @param etag            the etag, empty when the store has none
     * @param status          the outcome
     * @return true when such a checkpoint exists
     */
    boolean existsByBindingIdAndStreamIdAndObjectKeySha256AndEtagAndStatus(
            String bindingId, String streamId, String objectKeySha256, String etag, ProcessedObjectStatus status);
}
