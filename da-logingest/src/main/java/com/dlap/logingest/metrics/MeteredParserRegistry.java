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

import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.EnvelopeKey;
import com.dlap.logingest.parse.core.ParseOutcome;
import com.dlap.logingest.parse.core.ParserRegistry;
import com.dlap.logingest.parse.core.RawRecord;
import com.dlap.logingest.parse.core.VersionRange;
import java.util.Optional;
import java.util.Set;

/**
 * Wraps the parser registry and records every outcome in {@link LogIngestMetrics}. It is the one
 * place that sees each record with its detected version, outcome, shape fingerprint and events, so
 * the metrics need no change to the parse path (frozen contract, ADR-0054) or the ingest service.
 *
 * <p>Registered as the primary {@link ParserRegistry} when the registry pipeline is active; the
 * {@code DefaultParserRegistry} bean stays what the configuration validator and the startup
 * self-test use, so neither pollutes the counters. The decorator returns the delegate's outcome
 * unchanged.
 */
public final class MeteredParserRegistry implements ParserRegistry {

    private final ParserRegistry delegate;
    private final LogIngestMetrics metrics;

    /**
     * Creates the decorator.
     *
     * @param delegate the registry that parses
     * @param metrics  where outcomes are recorded
     */
    public MeteredParserRegistry(ParserRegistry delegate, LogIngestMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    @Override
    public ParseOutcome parse(RawRecord record, BindingConfig binding) {
        ParseOutcome outcome = delegate.parse(record, binding);
        metrics.recordOutcome(outcome);
        return outcome;
    }

    @Override
    public Set<EnvelopeKey> envelopes() {
        return delegate.envelopes();
    }

    @Override
    public Optional<VersionRange> rangeFor(EnvelopeKey key, String version) {
        return delegate.rangeFor(key, version);
    }
}
