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

package com.dlap.logingest.quarantine;

import com.dlap.logingest.service.QuarantineSink;
import com.dlap.proto.log.v1.LogQuarantineRecord;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The durable sink: one row per quarantined record in {@code dlap_logingest.quarantine_record},
 * masked and bounded, kept 30 days by default ({@link QuarantineRetentionSweeper}). This table is
 * the system of record that replay reads; the topic is the notification.
 *
 * <p>Runs before the topic sink. A database failure propagates, so the ingest of the record fails
 * and a transport does not move its cursor past it ({@link QuarantineSink} contract): a record is
 * never dropped because its quarantine row could not be written. Writing the same record again
 * updates one row (the id is a hash of the record's identity).
 */
@Component
@Order(10)
@ConditionalOnProperty(name = "dlap.logingest.quarantine.table-enabled", havingValue = "true", matchIfMissing = true)
public class TableQuarantineSink implements QuarantineSink {

    private static final Logger log = LoggerFactory.getLogger(TableQuarantineSink.class);

    private final QuarantineRecordRepository repository;
    private final QuarantineProperties props;
    private final Clock clock;

    /**
     * Creates the sink.
     *
     * @param repository the quarantine table
     * @param props      the settings
     * @param clock      the application clock when unique, else the UTC system clock
     */
    public TableQuarantineSink(QuarantineRecordRepository repository, QuarantineProperties props,
                               ObjectProvider<Clock> clock) {
        this.repository = repository;
        this.props = props;
        this.clock = clock.getIfUnique(Clock::systemUTC);
    }

    @Override
    public void accept(LogQuarantineRecord record) {
        LogQuarantineRecord bounded = QuarantineBounds.bounded(record, props.maxExcerptBytes());
        repository.upsert(bounded, clock.instant());
        log.debug("Stored quarantine record {} of binding {} ({})", bounded.getQuarantineId(),
                bounded.getBindingId(), QuarantineBounds.reasonCode(bounded.getReason()));
    }
}
