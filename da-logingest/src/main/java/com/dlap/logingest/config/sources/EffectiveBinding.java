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

import com.dlap.logingest.model.BindingState;
import com.dlap.logingest.model.ConfigOrigin;
import com.dlap.logingest.parse.core.PersistRaw;
import com.dlap.logingest.parse.core.PipelineMode;
import com.dlap.proto.log.v1.SourceProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One binding with every inherited setting resolved, plus its canonical form and hash.
 *
 * @param id         the binding id
 * @param sourceId   the source id
 * @param sourceKind the source kind
 * @param provider   the provider of the source kind, when a parser or mapping declares one
 * @param origin     where the binding comes from
 * @param enabled    whether every level enables it
 * @param valid      false when validation found an error in it
 * @param scope      the scope, by YAML key
 * @param streams    the streams
 * @param canonical  the canonical form that is hashed and mirrored; never holds a secret value
 * @param configHash lowercase hex SHA-256 of the canonical form
 */
public record EffectiveBinding(
        String id,
        String sourceId,
        String sourceKind,
        Optional<SourceProvider> provider,
        ConfigOrigin origin,
        boolean enabled,
        boolean valid,
        Map<String, Object> scope,
        List<EffectiveStream> streams,
        Map<String, Object> canonical,
        String configHash) {

    /**
     * Freezes the binding.
     *
     * @param id         see the record description
     * @param sourceId   see the record description
     * @param sourceKind see the record description
     * @param provider   see the record description
     * @param origin     see the record description
     * @param enabled    see the record description
     * @param valid      see the record description
     * @param scope      see the record description
     * @param streams    see the record description
     * @param canonical  see the record description
     * @param configHash see the record description
     */
    public EffectiveBinding {
        provider = provider == null ? Optional.empty() : provider;
        scope = scope == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(scope));
        streams = streams == null ? List.of() : List.copyOf(streams);
        canonical = canonical == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(canonical));
    }

    /**
     * Builds a binding and computes its canonical form and hash.
     *
     * @param id         the binding id
     * @param sourceId   the source id
     * @param sourceKind the source kind
     * @param provider   the provider
     * @param origin     the origin
     * @param enabled    whether it is enabled
     * @param valid      whether it validated
     * @param scope      the scope
     * @param streams    the streams
     * @return the binding
     */
    public static EffectiveBinding of(String id, String sourceId, String sourceKind, Optional<SourceProvider> provider,
                                      ConfigOrigin origin, boolean enabled, boolean valid, Map<String, Object> scope,
                                      List<EffectiveStream> streams) {
        Map<String, Object> canonical = canonical(id, sourceId, sourceKind, provider, origin, enabled, scope, streams);
        return new EffectiveBinding(id, sourceId, sourceKind, provider, origin, enabled, valid, scope, streams,
                canonical, ConfigHashing.sha256(canonical));
    }

    /**
     * The same binding marked invalid.
     *
     * @return a copy with {@code valid=false}
     */
    public EffectiveBinding invalid() {
        return valid ? new EffectiveBinding(id, sourceId, sourceKind, provider, origin, enabled, false, scope, streams,
                canonical, configHash) : this;
    }

    /**
     * Whether the binding runs on a pipeline.
     *
     * @param pipeline the selected pipeline
     * @return true when valid, enabled and the registry pipeline is selected
     */
    public boolean runs(PipelineMode pipeline) {
        return valid && enabled && pipeline == PipelineMode.REGISTRY;
    }

    /**
     * The state the synchronizer records.
     *
     * @param pipeline the selected pipeline
     * @return the state
     */
    public BindingState state(PipelineMode pipeline) {
        if (!valid) {
            return BindingState.INVALID;
        }
        if (!enabled) {
            return BindingState.DISABLED;
        }
        return pipeline == PipelineMode.REGISTRY ? BindingState.ACTIVE : BindingState.INACTIVE;
    }

    /**
     * The first stream whose records arrive in an envelope.
     *
     * @param envelope the envelope
     * @return the stream, or empty
     */
    public Optional<EffectiveStream> streamFor(String envelope) {
        return streams.stream().filter(s -> envelope.equals(s.envelope())).findFirst();
    }

    private static Map<String, Object> canonical(String id, String sourceId, String sourceKind,
                                                 Optional<SourceProvider> provider, ConfigOrigin origin,
                                                 boolean enabled, Map<String, Object> scope,
                                                 List<EffectiveStream> streams) {
        Map<String, Object> m = new TreeMap<>();
        m.put("id", id);
        m.put("source", sourceId);
        m.put("kind", sourceKind);
        m.put("provider", provider.map(Enum::name).orElse(""));
        m.put("origin", origin.name());
        m.put("enabled", enabled);
        m.put("scope", new TreeMap<>(scope == null ? Map.of() : scope));
        List<Object> s = new ArrayList<>();
        for (EffectiveStream stream : streams) {
            s.add(canonical(stream));
        }
        m.put("streams", s);
        return m;
    }

    private static Map<String, Object> canonical(EffectiveStream s) {
        Map<String, Object> m = new TreeMap<>();
        m.put("id", s.streamId());
        m.put("transport", s.transport());
        m.put("envelope", s.envelope() == null ? "" : s.envelope());
        m.put("location", new TreeMap<>(s.location()));
        if (s.parse() != null) {
            m.put("version", s.parse().pinnedVersion().orElse("auto"));
            m.put("on-mismatch", s.parse().onMismatch().name());
            PersistRaw raw = s.parse().persistRaw();
            m.put("persist-raw", raw.configured() == null ? "DEFAULT" : raw.configured().name());
            m.put("persist-raw-sample-one-in", raw.sampleOneIn());
            m.put("max-record-bytes", s.parse().maxRecordBytes());
            Map<String, Object> overrides = new TreeMap<>();
            s.parse().fieldOverrides().forEach((field, paths) ->
                    overrides.put(field.key(), paths.stream().map(p -> p.pointer()).toList()));
            m.put("field-overrides", overrides);
            List<Object> ops = new ArrayList<>();
            s.parse().operationOverrides().forEach(r -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("service", r.service());
                row.put("operation", r.operation() == null ? "" : r.operation());
                row.put("operation-prefix", r.operationPrefix() == null ? "" : r.operationPrefix());
                row.put("change-type", r.changeType().name());
                row.put("category", r.category().name());
                row.put("asset-kind", r.assetKind() == null ? "" : r.assetKind().name());
                row.put("extractor", r.extractor() == null ? "" : r.extractor());
                ops.add(new TreeMap<>(row));
            });
            m.put("operation-overrides", ops);
        }
        Map<String, Object> filters = new TreeMap<>();
        filters.put("include-services", s.filters().includeServices());
        filters.put("exclude-services", s.filters().excludeServices());
        filters.put("include-operations", s.filters().includeOperations());
        filters.put("exclude-operations", s.filters().excludeOperations());
        m.put("filters", filters);
        m.put("overlap", s.overlap().toString());
        m.put("poll-interval", s.pollInterval().toString());
        m.put("rate-limit-requests-per-second", s.requestsPerSecond() == null ? "" : s.requestsPerSecond().toString());
        m.put("credentials-ref", s.credentialsRef() == null ? "" : s.credentialsRef());
        m.put("backfill-allowed", s.backfillAllowed());
        m.put("dedup", s.crossStreamDedup() ? "CROSS_STREAM" : "NONE");
        m.put("push", s.push());
        return m;
    }
}
