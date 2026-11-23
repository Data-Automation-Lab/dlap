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
 * Consumes the typed asset-lifecycle topics the discovery router produces (ADR-0021):
 * MODIFIED, DELETED, ACCESSED, PERMISSION_CHANGED and ARCHIVED. The DISCOVERED topic is
 * {@link DiscoveryAssetDiscoveredListener}.
 *
 * <p>Each record is read through {@code DlapLogEventCodec} (either encoding), applied by
 * {@link AssetEventApplier} in one transaction with its processed-event claims, and
 * acknowledged only after that transaction commits. Business state follows event time
 * (ADR-0046): {@code last_accessed_at} is the maximum event time of successful reads, status
 * changes only when {@code event_time >= lifecycle_event_time}, and redeliveries change
 * nothing. A failure propagates: the transaction rolls back and the
 * {@link DiscoveryKafkaConfiguration#MANUAL_ACK_FACTORY} error handler retries the record and
 * then sends it to {@code <topic>.DLT}.
 */
@Component
@ConditionalOnProperty(name = "dlap.catalog.lifecycle-listener-enabled", havingValue = "true", matchIfMissing = true)
public class AssetLifecycleEventListener {

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
    public AssetLifecycleEventListener(CatalogEventDecoder decoder, AssetEventApplier applier,
                                       PlatformTransactionManager transactionManager) {
        this.decoder = decoder;
        this.applier = applier;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Metadata or content changes (UPDATE).
     *
     * @param payload the record value
     * @param ack     the manual acknowledgment
     */
    @KafkaListener(
            topics = DlapConstants.KafkaTopic.DISCOVERY_ASSET_MODIFIED,
            containerFactory = DiscoveryKafkaConfiguration.MANUAL_ACK_FACTORY,
            groupId = "${spring.kafka.consumer.group-id}")
    public void onModified(String payload, Acknowledgment ack) {
        handle(payload, ack);
    }

    /**
     * Deletions (DELETE).
     *
     * @param payload the record value
     * @param ack     the manual acknowledgment
     */
    @KafkaListener(
            topics = DlapConstants.KafkaTopic.DISCOVERY_ASSET_DELETED,
            containerFactory = DiscoveryKafkaConfiguration.MANUAL_ACK_FACTORY,
            groupId = "${spring.kafka.consumer.group-id}")
    public void onDeleted(String payload, Acknowledgment ack) {
        handle(payload, ack);
    }

    /**
     * Reads (ACCESS).
     *
     * @param payload the record value
     * @param ack     the manual acknowledgment
     */
    @KafkaListener(
            topics = DlapConstants.KafkaTopic.DISCOVERY_ASSET_ACCESSED,
            containerFactory = DiscoveryKafkaConfiguration.MANUAL_ACK_FACTORY,
            groupId = "${spring.kafka.consumer.group-id}")
    public void onAccessed(String payload, Acknowledgment ack) {
        handle(payload, ack);
    }

    /**
     * Permission changes (PERMISSION). da-autocompliance reads the same topic for IAM drift.
     *
     * @param payload the record value
     * @param ack     the manual acknowledgment
     */
    @KafkaListener(
            topics = DlapConstants.KafkaTopic.DISCOVERY_ASSET_PERMISSION_CHANGED,
            containerFactory = DiscoveryKafkaConfiguration.MANUAL_ACK_FACTORY,
            groupId = "${spring.kafka.consumer.group-id}")
    public void onPermissionChanged(String payload, Acknowledgment ack) {
        handle(payload, ack);
    }

    /**
     * Archive-tier moves (ARCHIVE).
     *
     * @param payload the record value
     * @param ack     the manual acknowledgment
     */
    @KafkaListener(
            topics = DlapConstants.KafkaTopic.DISCOVERY_ASSET_ARCHIVED,
            containerFactory = DiscoveryKafkaConfiguration.MANUAL_ACK_FACTORY,
            groupId = "${spring.kafka.consumer.group-id}")
    public void onArchived(String payload, Acknowledgment ack) {
        handle(payload, ack);
    }

    /**
     * Decode, wait for the match index outside the transaction, apply in one transaction, then
     * acknowledge. Exceptions propagate to the error handler.
     */
    private void handle(String payload, Acknowledgment ack) {
        CatalogEvent event = decoder.decode(payload, ProcessedEventStore.CONSUMER_ASSET_STATE);
        applier.awaitMatchIndex();
        transaction.executeWithoutResult(status -> applier.apply(event, false));
        ack.acknowledge();
    }
}
