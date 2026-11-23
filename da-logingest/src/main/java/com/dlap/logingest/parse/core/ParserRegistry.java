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

package com.dlap.logingest.parse.core;

import java.util.Optional;
import java.util.Set;

/**
 * The one parse path (ADR-0045 decision 8): every record, from every transport, is parsed here.
 *
 * <p>The registry is stateless and thread safe. It never throws for a record; every record ends
 * in exactly one {@link ParseResult}. Deduplication, persistence and publishing happen after it,
 * in the ingest service, by {@code event_id} and {@link IngestMode#shouldPublish(boolean)}.
 */
public interface ParserRegistry {

    /**
     * Parses one record.
     *
     * @param record  the record
     * @param binding the effective settings of the binding the record came through; its id and
     *                source kind must match the record's
     * @return the outcome
     * @throws IllegalArgumentException when the binding does not belong to the record (a caller
     *                                  defect, not a record problem)
     */
    ParseOutcome parse(RawRecord record, BindingConfig binding);

    /**
     * Returns the envelopes that have a detector.
     *
     * @return envelope keys
     */
    Set<EnvelopeKey> envelopes();

    /**
     * Returns the version range a registered parser reads for a version, if any.
     *
     * @param key     the envelope
     * @param version the version
     * @return the parser's range, or empty when no parser reads the version
     */
    Optional<VersionRange> rangeFor(EnvelopeKey key, String version);
}
