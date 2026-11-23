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

package com.dlap.reporting.drift;

import java.util.Locale;

/**
 * Why a drift consumer acknowledged an event without joining it (enforcement-contract.md section
 * 6.1). Each skip is counted on {@link DriftEventSkips#SKIPPED_COUNTER} with the reason's
 * {@link #tag()}; none is silent.
 */
public enum DriftSkipReason {

    /** The record has no value (a tombstone). */
    NULL_PAYLOAD,
    /** The value is not a JSON object the reader understands. */
    UNDECODABLE,
    /** A read without {@code eventId}. */
    MISSING_EVENT_ID,
    /** A decision without {@code decisionId}. */
    MISSING_DECISION_ID,
    /** No business time: a read without {@code eventTime} or a decision without {@code timestamp}. */
    MISSING_EVENT_TIME,
    /** A decision {@code timestamp} that is not an ISO-8601 instant. */
    UNPARSABLE_EVENT_TIME,
    /** A business time further ahead of the service clock than {@code dlap.drift.max-future-skew-seconds}. */
    FUTURE_EVENT_TIME,
    /** No asset: the read names no target, the decision neither {@code assetUri} nor {@code assetId}. */
    MISSING_ASSET,
    /** A read with no {@code principal.id}. */
    MISSING_PRINCIPAL,
    /** A decision without {@code subjectId}. */
    MISSING_SUBJECT,
    /** A decision that is neither {@code ALLOW} nor {@code DENY}. */
    UNKNOWN_DECISION,
    /** The event is not a recorded data read (management, write, permission, or an observed read). */
    NOT_A_DATA_READ,
    /** The read failed or is pending ({@code resultStatus} FAILURE or PENDING): no data was read. */
    NOT_SUCCESSFUL,
    /** The parser could not read the record fully ({@code PARSE_STATUS_PARTIAL}). */
    PARTIAL_PARSE,
    /** An id longer than its buffer column (event and decision ids 200, asset and subject keys 512). */
    FIELD_TOO_LONG;

    /**
     * The metric tag value: the name in lower case.
     *
     * @return for example {@code missing_event_time}
     */
    public String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
