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
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The event-time cursor of one stream partition ({@code dlap_logingest.stream_cursor}, Flyway V3,
 * design B5 "Cursors replace watermarks"). Written only by the poller that holds the stream's
 * advisory lock.
 */
@Entity
@Table(name = "stream_cursor")
@IdClass(StreamCursorId.class)
@Getter
@Setter
@NoArgsConstructor
public class StreamCursorEntity {

    /** The binding id. */
    @Id
    @Column(name = "binding_id", length = 128)
    private String bindingId;

    /** The stream id. */
    @Id
    @Column(name = "stream_id", length = 128)
    private String streamId;

    /** The partition key, empty for a single cursor. */
    @Id
    @Column(name = "partition_key", length = 256)
    private String partitionKey;

    /** Oldest event time not yet acknowledged; the next poll starts here minus the overlap. */
    @Column(name = "low_watermark")
    private Instant lowWatermark;

    /** Newest event time seen. */
    @Column(name = "high_watermark")
    private Instant highWatermark;

    /** Opaque transport state. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "cursor", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> cursor;

    /** When the cursor last moved, by the service clock. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
