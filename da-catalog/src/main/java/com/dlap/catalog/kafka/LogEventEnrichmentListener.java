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

package com.dlap.catalog.kafka;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.dlap.catalog.config.DiscoveryKafkaConfiguration;
import com.dlap.catalog.idempotency.ProcessedEventStore;
import com.dlap.common.constants.DlapConstants;

/**
 * Consumes the normalized log-event spine ({@code dlap.log.event.normalized}) for lineage and
 * harvested business metadata ({@link LogEventEnricher}).
 *
 * <p>Runs under its own consumer group ({@code da-catalog-lineage}) so it reads the full
 * stream independently of the typed-topic listeners. Each record is read through
 * {@code DlapLogEventCodec} and enriched in one transaction, then acknowledged. Nothing is
 * swallowed: an enrichment failure rolls the transaction back (no partial write is hidden),
 * the offset is not acknowledged, and the {@link DiscoveryKafkaConfiguration#MANUAL_ACK_FACTORY}
 * error handler retries the record and then sends it to {@code dlap.log.event.normalized.DLT}.
 * A payload that is not a log event goes to the DLT without retries.
 */
@Component
@ConditionalOnProperty(name = "dlap.catalog.lineage-listener-enabled", havingValue = "true", matchIfMissing = true)
public class LogEventEnrichmentListener {

    private final CatalogEventDecoder decoder;
    private final LogEventEnricher enricher;
    private final TransactionTemplate transaction;

    /**
     * Creates the listener.
     *
     * @param decoder            reads payloads through the codec
     * @param enricher           lineage and harvest
     * @param transactionManager the transaction manager
     */
    public LogEventEnrichmentListener(CatalogEventDecoder decoder, LogEventEnricher enricher,
                                      PlatformTransactionManager transactionManager) {
        this.decoder = decoder;
        this.enricher = enricher;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Enriches the catalog from one normalized event.
     *
     * @param payload the record value (single or double encoded)
     * @param ack     the manual acknowledgment
     */
    @KafkaListener(
            topics = DlapConstants.KafkaTopic.LOG_EVENT_NORMALIZED,
            groupId = "da-catalog-lineage",
            containerFactory = DiscoveryKafkaConfiguration.MANUAL_ACK_FACTORY)
    public void onLogEvent(String payload, Acknowledgment ack) {
        CatalogEvent event = decoder.decode(payload, ProcessedEventStore.CONSUMER_ENRICHMENT);
        enricher.awaitMatchIndex();
        transaction.executeWithoutResult(status -> enricher.enrich(event, payload));
        ack.acknowledge();
    }
}
