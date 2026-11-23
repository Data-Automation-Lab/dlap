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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

/**
 * Adds {@code logSources} to {@code /actuator/info} (design B2): the effective configuration hash,
 * the pipeline, the validation counts and, per binding, its source kind, origin, state, hash and
 * streams. Scope values (account and project ids), locations and credential references are left
 * out; the binding ids are shown, so do not put anything secret in a binding id.
 */
public final class LogSourcesInfoContributor implements InfoContributor {

    private final EffectiveLogSources sources;

    /**
     * Creates the contributor.
     *
     * @param sources the effective configuration
     */
    public LogSourcesInfoContributor(EffectiveLogSources sources) {
        this.sources = sources;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("logSources", details());
    }

    /**
     * The {@code logSources} detail.
     *
     * @return an ordered map
     */
    public Map<String, Object> details() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("configHash", sources.configHash());
        d.put("pipeline", sources.pipeline().name().toLowerCase(Locale.ROOT));
        d.put("configMode", sources.configMode());
        d.put("validation", Map.of(
                "errors", sources.report().errors().size(),
                "warnings", sources.report().warnings().size()));
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (EffectiveBinding b : sources.bindings()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.id());
            m.put("sourceKind", b.sourceKind());
            m.put("origin", b.origin().name());
            m.put("state", b.state(sources.pipeline()).name());
            m.put("configHash", b.configHash());
            List<Map<String, Object>> streams = new ArrayList<>();
            for (EffectiveStream s : b.streams()) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("id", s.streamId());
                sm.put("transport", s.transport());
                sm.put("envelope", s.envelope() == null ? "" : s.envelope());
                sm.put("version", s.parse() == null ? "" : s.parse().pinnedVersion().orElse("auto"));
                streams.add(sm);
            }
            m.put("streams", streams);
            bindings.add(m);
        }
        d.put("bindings", bindings);
        return d;
    }
}
