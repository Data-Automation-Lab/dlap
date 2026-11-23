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

package com.dlap.logingest.parse.core;

import com.dlap.common.logevent.LogEventIds;
import com.dlap.proto.log.v1.EventCategory;
import java.util.Objects;

/**
 * Whether da-logingest keeps a parsed event's provider record in its {@code log_events} row
 * (binding key {@code persist-raw}, design B2 and risk B9.4).
 *
 * <p>Data events can outnumber management events by orders of magnitude, so the default keeps the
 * raw record of a data event only inside its quarantine record: {@code QUARANTINE_ONLY} for
 * {@code DATA_READ}, {@code DATA_WRITE} and {@code DATA_READ_OBSERVED}, {@code ALL} for every
 * other category. A configured policy applies to every category. The policy governs storage only;
 * quarantine records always keep their redacted excerpt and hash.
 *
 * @param configured  the binding's explicit policy, or {@code null} for the default by category
 * @param sampleOneIn for {@link Policy#SAMPLED}: keep the raw record of one event in this many,
 *                    chosen from the event id so the choice is the same on every replica and replay
 */
public record PersistRaw(Policy configured, int sampleOneIn) {

    /** Default sampling rate of {@link Policy#SAMPLED}: one event in a hundred. */
    public static final int DEFAULT_SAMPLE_ONE_IN = 100;

    /** No explicit policy: the default by category. */
    public static final PersistRaw DEFAULT = new PersistRaw(null, DEFAULT_SAMPLE_ONE_IN);

    /** The three policies. */
    public enum Policy {
        /** Keep every raw record. */
        ALL,
        /** Keep raw records only in quarantine records. */
        QUARANTINE_ONLY,
        /** Keep a deterministic sample of raw records, {@code 1 in sampleOneIn} by event id. */
        SAMPLED
    }

    /**
     * Validates the rate.
     *
     * @param configured  see the record description
     * @param sampleOneIn see the record description
     */
    public PersistRaw {
        if (sampleOneIn < 1) {
            throw new IllegalArgumentException("sampleOneIn must be at least 1");
        }
    }

    /**
     * An explicit policy with the default sampling rate.
     *
     * @param policy the policy
     * @return the setting
     */
    public static PersistRaw of(Policy policy) {
        return new PersistRaw(Objects.requireNonNull(policy, "policy"), DEFAULT_SAMPLE_ONE_IN);
    }

    /**
     * The policy that applies to an event category.
     *
     * @param category the event's category
     * @return the configured policy, or the default for the category
     */
    public Policy effective(EventCategory category) {
        if (configured != null) {
            return configured;
        }
        return isDataCategory(category) ? Policy.QUARANTINE_ONLY : Policy.ALL;
    }

    /**
     * Whether to keep the raw record of a parsed event.
     *
     * @param category the event's category
     * @param eventId  the event id, which drives sampling
     * @return true to store {@code raw_event} in {@code log_events}
     */
    public boolean persists(EventCategory category, String eventId) {
        return switch (effective(category)) {
            case ALL -> true;
            case QUARANTINE_ONLY -> false;
            case SAMPLED -> sampleBucket(eventId) % sampleOneIn == 0;
        };
    }

    /**
     * The sampling bucket of an event: the first 60 bits of {@code SHA-256(eventId)}, so the choice
     * is uniform, stable across replicas and replays, and independent of how ids are spelled.
     *
     * @param eventId the event id
     * @return a non-negative number
     */
    static long sampleBucket(String eventId) {
        return Long.parseLong(LogEventIds.sha256Hex(Objects.requireNonNull(eventId, "eventId")).substring(0, 15), 16);
    }

    /**
     * Whether a category records data-plane activity.
     *
     * @param category the category
     * @return true for {@code DATA_READ}, {@code DATA_WRITE} and {@code DATA_READ_OBSERVED}
     */
    public static boolean isDataCategory(EventCategory category) {
        return category == EventCategory.EVENT_CATEGORY_DATA_READ
                || category == EventCategory.EVENT_CATEGORY_DATA_WRITE
                || category == EventCategory.EVENT_CATEGORY_DATA_READ_OBSERVED;
    }
}
