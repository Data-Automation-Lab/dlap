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

package com.dlap.common.logevent;

import com.dlap.proto.log.v1.ChangeType;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.dlap.proto.log.v1.EventCategory;
import com.dlap.proto.log.v1.ParseStatus;
import com.dlap.proto.log.v1.ResultStatus;
import com.dlap.proto.log.v1.TargetResource;
import java.time.Instant;
import java.util.Optional;

/**
 * Consumer-side rules of the log contract (ADR-0045, spec section "Consumer
 * rules"), in one place so every service applies them the same way.
 */
public final class LogEventSemantics {

    private LogEventSemantics() {
    }

    /**
     * The event's business time: {@code event_time}, never {@code ingest_time}
     * or a consumer clock.
     *
     * @param event the event
     * @return the provider event time, or empty when the producer had none
     */
    public static Optional<Instant> businessTime(DlapLogEvent event) {
        if (!event.hasEventTime()) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochSecond(event.getEventTime().getSeconds(), event.getEventTime().getNanos()));
    }

    /**
     * The category a consumer acts on. Events from the legacy pipeline carry
     * none; their category is derived from {@code change_type}: ACCESS is a
     * data read, PERMISSION a permission change, CREATE, UPDATE, DELETE and
     * ARCHIVE are management. Reports flag derived categories as heuristic.
     *
     * @param event the event
     * @return the category, unspecified when neither field says anything
     */
    public static EventCategory effectiveCategory(DlapLogEvent event) {
        EventCategory category = event.getEventCategory();
        if (category != EventCategory.EVENT_CATEGORY_UNSPECIFIED && category != EventCategory.UNRECOGNIZED) {
            return category;
        }
        return switch (event.getChangeType()) {
            case ACCESS -> EventCategory.EVENT_CATEGORY_DATA_READ;
            case PERMISSION -> EventCategory.EVENT_CATEGORY_PERMISSION;
            case CREATE, UPDATE, DELETE, ARCHIVE -> EventCategory.EVENT_CATEGORY_MANAGEMENT;
            default -> EventCategory.EVENT_CATEGORY_UNSPECIFIED;
        };
    }

    /**
     * True when the event may change lifecycle state: it has a business time,
     * a known change type, and is not a minimal pass-through.
     *
     * @param event the event
     * @return whether a state-changing consumer may apply it
     */
    public static boolean mayChangeState(DlapLogEvent event) {
        ChangeType type = event.getChangeType();
        return event.hasEventTime()
                && event.getParseStatus() != ParseStatus.PARSE_STATUS_PARTIAL
                && type != ChangeType.CHANGE_TYPE_UNSPECIFIED
                && type != ChangeType.UNRECOGNIZED;
    }

    /**
     * True when the event proves a successful read of its target at
     * {@code event_time}: it may update {@code last_accessed_at}.
     *
     * @param event the event
     * @return whether the event is a successful data read or observed read
     */
    public static boolean isSuccessfulRead(DlapLogEvent event) {
        EventCategory category = effectiveCategory(event);
        return mayChangeState(event)
                && event.getResultStatus() == ResultStatus.SUCCESS
                && (category == EventCategory.EVENT_CATEGORY_DATA_READ
                        || category == EventCategory.EVENT_CATEGORY_DATA_READ_OBSERVED);
    }

    /**
     * True when the event counts toward usage totals: a successful recorded
     * read. Observed reads move {@code last_accessed_at} but are not requests.
     *
     * @param event the event
     * @return whether a usage counter may count it (after deduping on
     *         {@link LogEventIds#usageDedupKey})
     */
    public static boolean countsAsUsage(DlapLogEvent event) {
        return isSuccessfulRead(event) && effectiveCategory(event) == EventCategory.EVENT_CATEGORY_DATA_READ;
    }

    /**
     * The asset the event acts on, for catalog joins: the target's canonical
     * URI, else its native id.
     *
     * @param event the event
     * @return the key, or an empty string when the event names no target
     */
    public static String assetKey(DlapLogEvent event) {
        TargetResource target = event.getTargetResource();
        return !target.getCanonicalUri().isBlank() ? target.getCanonicalUri() : target.getId();
    }

    /**
     * Kafka key for the normalized topic: the asset key, else the event id,
     * so events for one asset stay on one partition.
     *
     * @param event the event
     * @return the partition key
     */
    public static String partitionKey(DlapLogEvent event) {
        String asset = assetKey(event);
        return asset.isBlank() ? event.getEventId() : asset;
    }
}
