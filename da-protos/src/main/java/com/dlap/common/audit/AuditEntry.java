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

package com.dlap.common.audit;

/**
 * One row of {@code dlap_common.audit_log}: the chained, stored form of an event.
 *
 * @param streamId  the stream
 * @param seq       the position in the stream, from 1 without gaps
 * @param prevHash  the {@code entryHash} of entry {@code seq - 1}, or {@link AuditChain#GENESIS_HASH}
 * @param entryHash SHA-256 over the hash input ({@link AuditChain#entryHash})
 * @param record    the canonical record text, exactly as stored
 */
public record AuditEntry(String streamId, long seq, String prevHash, String entryHash, String record) {
}
