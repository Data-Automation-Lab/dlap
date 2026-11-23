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

import com.dlap.proto.log.v1.LogQuarantineRecord;

/**
 * Receives every quarantined record of the registry pipeline (design B3, frozen design for wave 3:
 * "a QuarantineSink interface with a logging default").
 *
 * <p>Implementations self-register as beans; every registered sink receives every record. With
 * none registered the pipeline logs the record ({@code LoggingQuarantineSink}). LOG-03 adds the
 * {@code dlap.log.event.quarantine} topic and the {@code quarantine_record} table as sinks. A sink
 * that throws fails the ingest of the record, so a transport does not move its cursor past it.
 */
public interface QuarantineSink {

    /**
     * Accepts one quarantined record.
     *
     * @param record the record, as {@code CanonicalEventMapper} built it: bounded, redacted excerpt,
     *               SHA-256 of the raw bytes, never raw values in {@code reason_detail}
     */
    void accept(LogQuarantineRecord record);
}
