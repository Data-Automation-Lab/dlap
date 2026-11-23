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
 * Consumes {@code dlap.discovery.asset.discovered} (CREATE events from crawlers and from
 * audit logs) and creates or refreshes catalog rows through {@link AssetEventApplier}.
 *
 * <p>The record is read through {@code DlapLogEventCodec}, so the legacy double encoding and
 * the pre-ADR-0045 crawler shape are accepted. Identity, provider and service type come from
 * the canonical ID (ADR-0047): an identifier that does not normalize creates no row and is
 * counted, and there is no default provider. The record is acknowledged only after the
 * transaction commits; a failure propagates to the error handler (retry, then
 * {@code <topic>.DLT}).
 */
@Component
@ConditionalOnProperty(name = "dlap.catalog.discovery-listener-enabled", havingValue = "true", matchIfMissing = true)
public class DiscoveryAssetDiscoveredListener {

    private final CatalogEventDecoder decoder;
    private final AssetEventApplier applier;
    private final TransactionTemplate transaction;

    /**
     * Creates the listener.
     *
     * @param decoder            reads payloads through the codec
     * @param applier            applies events under the event-time rules
     * @param transactionManager the transaction manager
     */
    public DiscoveryAssetDiscoveredListener(CatalogEventDecoder decoder, AssetEventApplier applier,
                                            PlatformTransactionManager transactionManager) {
        this.decoder = decoder;
        this.applier = applier;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Applies one discovery event.
     *
     * @param payload         the record value
     * @param acknowledgment  the manual acknowledgment
     */
    @KafkaListener(
            topics = DlapConstants.KafkaTopic.DISCOVERY_ASSET_DISCOVERED,
            containerFactory = DiscoveryKafkaConfiguration.MANUAL_ACK_FACTORY,
            groupId = "${spring.kafka.consumer.group-id}")
    public void onMessage(String payload, Acknowledgment acknowledgment) {
        CatalogEvent event = decoder.decode(payload, ProcessedEventStore.CONSUMER_ASSET_STATE);
        applier.awaitMatchIndex();
        transaction.executeWithoutResult(status -> applier.apply(event, true));
        acknowledgment.acknowledge();
    }
}
