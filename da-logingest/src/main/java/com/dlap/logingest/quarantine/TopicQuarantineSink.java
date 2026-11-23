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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The notification sink: publishes each quarantined record to {@code dlap.log.event.quarantine}
 * (key {@code binding_id}) so consumers and operators see a parser break as it happens.
 *
 * <p>Runs after the table sink, so the durable row exists before the notice goes out. A
 * synchronous send failure propagates and fails the ingest of the record; an asynchronous one is
 * counted ({@code dlap_logingest_publish_failures_total}) and logged by the producer listener, and
 * the table row remains. Consumers deduplicate on {@code quarantine_id}: the same record
 * quarantined again publishes the same id.
 */
@Component
@Order(20)
@ConditionalOnProperty(name = "dlap.logingest.quarantine.topic-enabled", havingValue = "true", matchIfMissing = true)
public class TopicQuarantineSink implements QuarantineSink {

    private final QuarantineTopicPublisher publisher;
    private final QuarantineProperties props;

    /**
     * Creates the sink.
     *
     * @param publisher the topic publisher
     * @param props     the settings
     */
    public TopicQuarantineSink(QuarantineTopicPublisher publisher, QuarantineProperties props) {
        this.publisher = publisher;
        this.props = props;
    }

    @Override
    public void accept(LogQuarantineRecord record) {
        publisher.publish(QuarantineBounds.bounded(record, props.maxExcerptBytes()));
    }
}
