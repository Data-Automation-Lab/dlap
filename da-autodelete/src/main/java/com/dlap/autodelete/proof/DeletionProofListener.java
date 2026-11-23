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

package com.dlap.autodelete.proof;

import com.dlap.autodelete.config.KafkaReliabilityConfiguration;
import com.dlap.common.constants.DlapConstants;
import com.dlap.common.logevent.DlapLogEventCodec;
import com.dlap.proto.log.v1.DlapLogEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Reads the typed delete topic ({@code DlapConstants.KafkaTopic.DISCOVERY_ASSET_DELETED}: the
 * router's {@code change_type DELETE} events, docs/specs/log-event.md) and hands each event to
 * {@link ProofRecorder}. Manual acknowledgement; a record that cannot be decoded, or whose
 * recording fails, is retried and then dead-lettered by the {@code MANUAL_ACK_FACTORY} error handler.
 */
@Component
public class DeletionProofListener {

    private static final Logger LOG = LoggerFactory.getLogger(DeletionProofListener.class);

    private final ProofRecorder recorder;
    private final DlapLogEventCodec codec = DlapLogEventCodec.defaults();

    /**
     * Creates the listener.
     *
     * @param recorder the recorder
     */
    public DeletionProofListener(ProofRecorder recorder) {
        this.recorder = recorder;
    }

    /**
     * Handles one record.
     *
     * @param record the record; its value is the event, a JSON object (or the legacy quoted form). The
     *               listener takes the record itself because an {@code Object} parameter receives the
     *               {@code ConsumerRecord} whether or not it is annotated {@code @Payload}: the live
     *               harness run showed every record as an undecodable event (Q-637)
     * @param ack    the acknowledgement
     */
    @KafkaListener(
            topics = DlapConstants.KafkaTopic.DISCOVERY_ASSET_DELETED,
            groupId = "da-autodelete-deletion-proof",
            containerFactory = KafkaReliabilityConfiguration.MANUAL_ACK_FACTORY)
    public void onDeleted(ConsumerRecord<String, Object> record, Acknowledgment ack) {
        Object payload = record == null ? null : record.value();
        if (payload == null) {
            ack.acknowledge();
            return;
        }
        DlapLogEvent event = codec.decode(payload);
        int proved = recorder.onEvent(event);
        if (proved > 0) {
            LOG.info("Delete event {} proved {} disposition(s)", event.getEventId(), proved);
        }
        ack.acknowledge();
    }
}
