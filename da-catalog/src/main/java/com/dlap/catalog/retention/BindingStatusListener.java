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

package com.dlap.catalog.retention;

import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.dlap.catalog.config.DiscoveryKafkaConfiguration;
import com.dlap.catalog.retention.BindingStatusStore.BindingStatus;
import com.dlap.common.constants.DlapConstants;
import com.dlap.proto.log.v1.LogBindingStatus;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;

/**
 * Keeps {@link BindingStatusStore} current from {@code dlap.logingest.binding.status}
 * (docs/specs/log-event.md section 10): the latest {@code LogBindingStatus} per binding, the read
 * signal of the retention evaluator.
 *
 * <p>The topic is compacted and keyed by binding id, so a new group starting at the earliest
 * offset reads every binding's current state. A message is proto3 JSON; fields and enum values
 * this build does not know are ignored (forward compatible). A status reported earlier than the
 * stored one changes nothing. A null value (a tombstone) removes the binding: its coverage stops
 * counting. A message that is not a binding status goes through the error handler: three retries,
 * then {@code dlap.logingest.binding.status.DLT}.
 */
@Component
@ConditionalOnProperty(name = "dlap.catalog.retention.binding-status-listener-enabled", havingValue = "true",
        matchIfMissing = true)
public class BindingStatusListener {

    /** Consumer group of the listener. */
    public static final String GROUP = "da-catalog-binding-status";

    private static final JsonFormat.Parser PARSER = JsonFormat.parser().ignoringUnknownFields();

    private final BindingStatusStore store;
    private final RetentionMetrics metrics;

    /**
     * Creates the listener.
     *
     * @param store   the stored statuses
     * @param metrics the retention metrics
     */
    public BindingStatusListener(BindingStatusStore store, RetentionMetrics metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    /**
     * Applies one message.
     *
     * @param payload the proto3 JSON status, or {@code null} for a tombstone
     * @param key     the record key, the binding id
     * @param ack     the manual acknowledgment
     */
    @KafkaListener(topics = DlapConstants.KafkaTopic.LOGINGEST_BINDING_STATUS, groupId = GROUP,
            containerFactory = DiscoveryKafkaConfiguration.MANUAL_ACK_FACTORY)
    public void onBindingStatus(@Payload(required = false) String payload,
                                @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
                                Acknowledgment ack) {
        apply(payload, key);
        ack.acknowledge();
    }

    /**
     * Applies one message without acknowledging it.
     *
     * @param payload the proto3 JSON status, or {@code null} for a tombstone
     * @param key     the record key, the binding id
     * @throws IllegalArgumentException when the message is not a binding status
     */
    void apply(String payload, String key) {
        if (payload == null || payload.isBlank()) {
            if (key != null && !key.isBlank()) {
                metrics.bindingStatus(store.delete(key) ? "removed" : "ignored");
            } else {
                metrics.bindingStatus("ignored");
            }
            return;
        }
        LogBindingStatus status = parse(payload);
        String bindingId = !status.getBindingId().isBlank() ? status.getBindingId() : key;
        if (bindingId == null || bindingId.isBlank()) {
            throw new IllegalArgumentException("Binding status without a binding id");
        }
        if (!status.hasReportedTime()) {
            throw new IllegalArgumentException("Binding status without a reported time");
        }
        BindingStatus row = new BindingStatus(bindingId, status.getSourceProvider().name(),
                emptyToNull(status.getSourceKind()), emptyToNull(status.getScope().getScopeType()),
                emptyToNull(status.getScope().getScopeId()), status.getState().name(),
                status.getReadCoverage().name(),
                status.hasReadCoverageSince() ? instant(status.getReadCoverageSince()) : null,
                status.hasLowWatermark() ? instant(status.getLowWatermark()) : null,
                status.hasHighWatermark() ? instant(status.getHighWatermark()) : null,
                instant(status.getReportedTime()));
        metrics.bindingStatus(store.upsert(row, emptyToNull(status.getConfigHash())) ? "stored" : "stale");
    }

    private static LogBindingStatus parse(String payload) {
        LogBindingStatus.Builder builder = LogBindingStatus.newBuilder();
        try {
            PARSER.merge(payload, builder);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException("Not a dlap.log.v1.LogBindingStatus (proto3 JSON expected)", e);
        }
        return builder.build();
    }

    private static Instant instant(Timestamp ts) {
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
