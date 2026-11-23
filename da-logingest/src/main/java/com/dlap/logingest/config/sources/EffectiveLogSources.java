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

package com.dlap.logingest.config.sources;

import com.dlap.logingest.parse.core.PipelineMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The effective log-source configuration of this process: every binding of the file plus the
 * built-in channels, each with inherited settings resolved, the validation report, and the
 * configuration hash shown under {@code /actuator/info} (design B2).
 *
 * <p>Immutable; built once at startup by {@link LogSourcesConfiguration}.
 */
public final class EffectiveLogSources {

    private final PipelineMode pipeline;
    private final String configMode;
    private final List<EffectiveBinding> bindings;
    private final Map<String, EffectiveBinding> byId;
    private final ValidationReport report;
    private final String configHash;

    /**
     * Assembles the configuration.
     *
     * @param pipeline   the selected pipeline
     * @param configMode the configured mode, {@code FILE}
     * @param bindings   file and built-in bindings; ids are unique
     * @param report     the validation report
     */
    public EffectiveLogSources(PipelineMode pipeline, String configMode, List<EffectiveBinding> bindings,
                               ValidationReport report) {
        this.pipeline = pipeline;
        this.configMode = configMode;
        List<EffectiveBinding> sorted = new ArrayList<>(bindings);
        sorted.sort((a, b) -> a.id().compareTo(b.id()));
        this.bindings = List.copyOf(sorted);
        Map<String, EffectiveBinding> m = new LinkedHashMap<>();
        for (EffectiveBinding b : this.bindings) {
            if (m.putIfAbsent(b.id(), b) != null) {
                throw new IllegalArgumentException("two effective bindings share id " + b.id());
            }
        }
        this.byId = Collections.unmodifiableMap(m);
        this.report = report == null ? ValidationReport.CLEAN : report;
        Map<String, Object> hashed = new TreeMap<>();
        hashed.put("config-mode", configMode);
        hashed.put("pipeline", pipeline.name());
        Map<String, Object> hashes = new TreeMap<>();
        this.bindings.forEach(b -> hashes.put(b.id(), b.configHash()));
        hashed.put("bindings", hashes);
        this.configHash = ConfigHashing.sha256(hashed);
    }

    /**
     * The selected pipeline.
     *
     * @return the pipeline
     */
    public PipelineMode pipeline() {
        return pipeline;
    }

    /**
     * The configuration mode.
     *
     * @return {@code FILE}
     */
    public String configMode() {
        return configMode;
    }

    /**
     * Every binding, sorted by id.
     *
     * @return the bindings
     */
    public List<EffectiveBinding> bindings() {
        return bindings;
    }

    /**
     * Finds a binding.
     *
     * @param bindingId the id
     * @return the binding, or empty
     */
    public Optional<EffectiveBinding> binding(String bindingId) {
        return Optional.ofNullable(byId.get(bindingId));
    }

    /**
     * The stream a record of a binding and envelope belongs to, when the binding runs.
     *
     * @param bindingId the binding
     * @param envelope  the envelope
     * @return the stream, or empty when the binding is unknown, invalid, disabled, inactive or
     *         has no stream of that envelope
     */
    public Optional<EffectiveStream> runningStream(String bindingId, String envelope) {
        return binding(bindingId).filter(b -> b.runs(pipeline)).flatMap(b -> b.streamFor(envelope));
    }

    /**
     * The streams a poller runs: streams of running bindings that are not push channels.
     *
     * @return the streams
     */
    public List<EffectiveStream> pollableStreams() {
        List<EffectiveStream> out = new ArrayList<>();
        for (EffectiveBinding b : bindings) {
            if (b.runs(pipeline)) {
                b.streams().stream().filter(s -> !s.push()).forEach(out::add);
            }
        }
        return List.copyOf(out);
    }

    /**
     * The validation report.
     *
     * @return the report
     */
    public ValidationReport report() {
        return report;
    }

    /**
     * The configuration hash: SHA-256 over the mode, the pipeline and every binding's hash.
     *
     * @return 64 lowercase hex characters
     */
    public String configHash() {
        return configHash;
    }
}
