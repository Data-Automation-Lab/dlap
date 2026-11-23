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

package com.dlap.catalog.lifecycle;

import java.time.Instant;
import java.util.Objects;

import com.dlap.catalog.kafka.CatalogEvent;
import com.dlap.common.model.entity.Asset;
import com.dlap.proto.log.v1.EventCategory;

/**
 * Published by the catalog's event applier, inside its transaction and under the row lock, after a
 * lifecycle-topic event was applied to one asset row, or found already applied (a redelivery or a
 * replay). {@link LifecycleEvidenceRecorder} reads it to keep the Log Event ids behind the
 * Lifecycle State fields.
 *
 * @param asset     the row, with the rules applied
 * @param event     the decoded event
 * @param eventTime the event's business time
 * @param category  the category the event has for this row (after roll-up for a container)
 * @param direct    {@code true} when the event targets this row itself, {@code false} when it is
 *                  an object event rolled up to this container
 */
public record AppliedLogEvent(Asset asset, CatalogEvent event, Instant eventTime, EventCategory category,
                              boolean direct) {

    /**
     * Checks the members that are never null.
     *
     * @throws NullPointerException when {@code asset}, {@code event} or {@code eventTime} is {@code null}
     */
    public AppliedLogEvent {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(eventTime, "eventTime");
    }
}
