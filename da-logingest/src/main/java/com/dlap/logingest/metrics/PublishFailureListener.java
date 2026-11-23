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

package com.dlap.logingest.metrics;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.kafka.support.LoggingProducerListener;

/**
 * Counts every Kafka send that failed, by topic ({@code dlap_logingest_publish_failures_total}),
 * then logs it as Spring's default listener does, without the record contents: a quarantine record
 * carries a redacted excerpt of provider data, which has no place in an error log (CLAUDE.md
 * section 11).
 *
 * <p>Spring Boot's Kafka auto-configuration backs off its own logging listener when a
 * {@code ProducerListener} bean exists and wires this one into the {@code KafkaTemplate}, so every
 * producer of the service is covered: normalized events, quarantine records, binding statuses.
 */
public class PublishFailureListener extends LoggingProducerListener<Object, Object> {

    private final LogIngestMetrics metrics;

    /**
     * Creates the listener.
     *
     * @param metrics where failures are counted
     */
    public PublishFailureListener(LogIngestMetrics metrics) {
        this.metrics = metrics;
        setIncludeContents(false);
    }

    @Override
    public void onError(ProducerRecord<Object, Object> producerRecord, RecordMetadata recordMetadata,
                        Exception exception) {
        metrics.publishFailure(producerRecord == null ? null : producerRecord.topic());
        if (producerRecord != null) {
            super.onError(producerRecord, recordMetadata, exception);
        }
    }
}
