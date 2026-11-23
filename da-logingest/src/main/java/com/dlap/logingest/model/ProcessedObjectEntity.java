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

package com.dlap.logingest.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The checkpoint of one object of a file transport ({@code dlap_logingest.processed_object},
 * Flyway V3, design B5): an S3 trail file, a GCS sink shard, a blob. A {@code DONE} object with the
 * same etag is never read again; a rewritten object (new etag) is.
 */
@Entity
@Table(name = "processed_object")
@IdClass(ProcessedObjectId.class)
@Getter
@Setter
@NoArgsConstructor
public class ProcessedObjectEntity {

    /** The binding id. */
    @Id
    @Column(name = "binding_id", length = 128)
    private String bindingId;

    /** The stream id. */
    @Id
    @Column(name = "stream_id", length = 128)
    private String streamId;

    /** Lowercase hex SHA-256 of the UTF-8 object key. */
    @Id
    @Column(name = "object_key_sha256", length = 64)
    private String objectKeySha256;

    /** The etag or generation, empty when the store has none. */
    @Id
    @Column(name = "etag", length = 256)
    private String etag;

    /** The object key as the store names it. */
    @Column(name = "object_key", length = 2048, nullable = false)
    private String objectKey;

    /** The last outcome. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private ProcessedObjectStatus status;

    /** Records read from the object. */
    @Column(name = "records", nullable = false)
    private long records;

    /** Records of the object that were quarantined. */
    @Column(name = "quarantined", nullable = false)
    private long quarantined;

    /** How many times the object was read. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** First read, by the service clock. */
    @Column(name = "first_processed_at", nullable = false)
    private Instant firstProcessedAt;

    /** Last read, by the service clock. */
    @Column(name = "last_processed_at", nullable = false)
    private Instant lastProcessedAt;
}
