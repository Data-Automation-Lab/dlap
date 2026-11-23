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

package com.dlap.logingest.service;

import com.dlap.logingest.dto.IngestRequestDTO;

/**
 * Public contract for the log-ingest pipeline. One method covers the
 * full happy path: take a normalized cloud event (already mapped from
 * its provider-native shape into {@link IngestRequestDTO}), persist
 * it for replay, and publish the canonical {@code DlapLogEvent} to
 * the {@code dlap.log.event.normalized} Kafka topic.
 *
 * <p>Per-cloud audit-log subscribers (GCP Audit, AWS CloudTrail,
 * Azure Activity, OCI Audit) call this method after they finish
 * normalizing their native payload. The HTTP {@code IngestController}
 * also calls it for replay and synthetic-event injection.
 */
public interface ILogIngestService {

    /**
     * Normalize, persist, and publish a single log event.
     *
     * @param request normalized event from any source.
     * @return the stable event id used for dedupe + publishing.
     */
    String ingest(IngestRequestDTO request);
}
