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

package com.dlap.reporting.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import com.dlap.common.constants.DlapConstants;
import com.dlap.reporting.config.KafkaConsumerConfig;
import com.dlap.reporting.drift.DriftDetectionOrchestrator;
import com.dlap.reporting.drift.DriftEventProjector;
import com.dlap.reporting.drift.DriftEventSkips;
import com.dlap.reporting.drift.ReadRecord;

/**
 * Consumes actual asset reads from {@link
 * DlapConstants.KafkaTopic#DISCOVERY_ASSET_ACCESSED}, the typed lifecycle topic
 * onto which {@code DiscoveryLogEventProcessor} routes every {@code DlapLogEvent}
 * with {@code changeType=ACCESS} (log-event.md, ADR-0021). Each read drives the
 * drift join (ADR-0040, enforcement-contract.md section 6.1).
 *
 * <p>{@link DriftEventProjector#projectRead} decodes the event with the shared codec, keeps only
 * recorded successful data reads, takes the read time from {@code eventTime} (never the
 * consumer's clock), and builds the canonical asset key and the mapped subject. An event it
 * rejects is acknowledged, logged and counted on {@link DriftEventSkips#SKIPPED_COUNTER}.
 * Manual-ack + DLQ via {@link KafkaConsumerConfig#MANUAL_ACK_FACTORY}: persistence failures
 * rethrow so the error handler retries, then dead-letters.
 */
@Service
public class AssetAccessedConsumer {

    private static final Logger log = LoggerFactory.getLogger(AssetAccessedConsumer.class);

    private final DriftDetectionOrchestrator orchestrator;
    private final DriftEventProjector projector;
    private final DriftEventSkips skips;

    /**
     * Creates the consumer.
     *
     * @param orchestrator the drift join
     * @param projector    the event projection
     * @param skips        the skip counter
     */
    public AssetAccessedConsumer(DriftDetectionOrchestrator orchestrator,
                                 DriftEventProjector projector,
                                 DriftEventSkips skips) {
        this.orchestrator = orchestrator;
        this.projector = projector;
        this.skips = skips;
    }

    /**
     * Handles one read event.
     *
     * @param record the record; its value is a JSON object as a {@code Map}, JSON text, or {@code null}
     * @param ack   the manual acknowledgment
     */
    @KafkaListener(
            topics = DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED,
            groupId = "da-reporting-drift-read",
            containerFactory = KafkaConsumerConfig.MANUAL_ACK_FACTORY)
    public void onAssetAccessed(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        Object event = record.value();
        DriftEventProjector.Projection<ReadRecord> projection = projector.projectRead(event);
        if (!projection.isAccepted()) {
            skips.count(DriftEventSkips.Stream.READ, projection.skip());
            log.warn("Asset-accessed event {} skipped: {} (payload type {})", projection.ref(), projection.skip().tag(),
                    event == null ? "none" : event.getClass().getName());
            ack.acknowledge();
            return;
        }
        try {
            orchestrator.recordReadAndDetect(projection.record());
            ack.acknowledge();
        } catch (RuntimeException e) {
            log.error("Failed to process asset-accessed event {}: {}", projection.ref(), e.getMessage(), e);
            throw e;
        }
    }
}
