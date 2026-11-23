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

package com.dlap.logingest.service.registry;

import java.util.List;
import java.util.Optional;

/**
 * The outcome of one record on the registry path.
 *
 * @param outcomeCode  {@code PARSED}, {@code PARSED_WITH_WARNINGS}, {@code SKIPPED:<reason>} or
 *                     {@code QUARANTINED:<reason>}, as fixture {@code expected_outcome} spells it
 * @param events       the record's events in record order, with what happened to each
 * @param quarantineId the quarantine record's id when the record was quarantined
 */
public record RecordReport(String outcomeCode, List<EventReport> events, Optional<String> quarantineId) {

    /** The outcome code of a parsed record whose every event was filtered out. */
    public static final String SKIPPED_FILTERED = "SKIPPED:FILTERED_BY_BINDING";

    /**
     * One event of the record.
     *
     * @param eventId     the event id
     * @param disposition what happened to it
     */
    public record EventReport(String eventId, Disposition disposition) {
    }

    /**
     * Freezes the report.
     *
     * @param outcomeCode  see the record description
     * @param events       see the record description
     * @param quarantineId see the record description
     */
    public RecordReport {
        events = events == null ? List.of() : List.copyOf(events);
        quarantineId = quarantineId == null ? Optional.empty() : quarantineId;
    }

    /**
     * The id of the record's first event: the primary event, or the pass-through event of a
     * quarantined record.
     *
     * @return the event id, or empty when the record produced no event
     */
    public Optional<String> primaryEventId() {
        return events.isEmpty() ? Optional.empty() : Optional.of(events.get(0).eventId());
    }

    /**
     * Whether the record was quarantined.
     *
     * @return true for a {@code QUARANTINED:} outcome
     */
    public boolean quarantined() {
        return outcomeCode.startsWith("QUARANTINED");
    }

    /**
     * Whether the record was skipped.
     *
     * @return true for a {@code SKIPPED:} outcome
     */
    public boolean skipped() {
        return outcomeCode.startsWith("SKIPPED");
    }

    /**
     * The disposition of the primary event.
     *
     * @return the disposition, or empty when the record produced no event
     */
    public Optional<Disposition> primaryDisposition() {
        return events.isEmpty() ? Optional.empty() : Optional.of(events.get(0).disposition());
    }
}
