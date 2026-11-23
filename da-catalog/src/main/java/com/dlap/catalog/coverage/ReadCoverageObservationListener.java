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

package com.dlap.catalog.coverage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.dlap.catalog.config.DiscoveryKafkaConfiguration;
import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.proto.log.v1.DlapLogEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads read-coverage observations from the normalized log spine ({@code dlap.log.event.normalized},
 * ADR text in the COV-01 result) and applies them through {@link ReadCoverageApplier}.
 *
 * <p>Runs in its own consumer group ({@value #GROUP}) next to the lineage listener. Records that
 * are not observations are acknowledged untouched; a cheap text test skips most of them before
 * decoding. An observation is applied in one transaction and acknowledged after it commits. A
 * record that claims to be an observation and is malformed, or a write that fails, goes to the
 * {@link DiscoveryKafkaConfiguration#MANUAL_ACK_FACTORY} error handler: retries, then
 * {@code dlap.log.event.normalized.DLT}. Nothing is swallowed.
 */
@Component
@ConditionalOnProperty(name = "dlap.catalog.coverage.listener-enabled", havingValue = "true", matchIfMissing = true)
public class ReadCoverageObservationListener {

    /** Consumer group of this listener. */
    public static final String GROUP = "da-catalog-read-coverage";

    private final ReadCoverageApplier applier;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();

    /**
     * Creates the listener.
     *
     * @param applier            writes the coverage
     * @param mapper             reads the reasons array
     * @param transactionManager the transaction manager
     */
    public ReadCoverageObservationListener(ReadCoverageApplier applier, ObjectMapper mapper,
                                           PlatformTransactionManager transactionManager) {
        this.applier = applier;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Handles one spine record.
     *
     * @param payload the record value, single or double encoded
     * @param ack     the manual acknowledgment
     */
    @KafkaListener(
            topics = DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED,
            groupId = GROUP,
            containerFactory = DiscoveryKafkaConfiguration.MANUAL_ACK_FACTORY)
    public void onLogEvent(String payload, Acknowledgment ack) {
        handle(payload);
        ack.acknowledge();
    }

    /**
     * Applies the record when it is an observation.
     *
     * @param payload the record value
     * @return the outcome, or {@code null} when the record is not an observation
     */
    ReadCoverageApplier.Outcome handle(String payload) {
        if (!ReadCoverageWire.mightBeObservation(payload)) {
            return null;
        }
        DlapLogEvent event = codec.decode(payload);
        if (!ReadCoverageWire.isObservation(event)) {
            return null;
        }
        ReadCoverageObservation observation = ReadCoverageWire.decode(event, mapper);
        return transaction.execute(status -> applier.apply(observation));
    }
}
