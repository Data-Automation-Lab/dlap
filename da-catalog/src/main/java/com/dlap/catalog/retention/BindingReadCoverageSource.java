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

package com.dlap.catalog.retention;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.dlap.catalog.retention.BindingStatusStore.BindingStatus;
import com.dlap.catalog.retention.RetentionAssetStore.AssetRow;

/**
 * The read signal from the log bindings (docs/specs/log-event.md section 10): an asset's reads
 * are captured when every enabled binding of its provider and scope reports
 * {@code READ_COVERAGE_CAPTURED}.
 *
 * <p>The rule is deliberately strict because a binding says nothing about which kinds of asset
 * its source can see (a BigQuery jobs binding sees no bucket reads): with several bindings in one
 * scope, all of them must capture reads, the coverage starts at the latest start among them, and
 * it is complete only up to the earliest low watermark. A binding that is {@code DISABLED} is
 * ignored; a scope with no enabled binding, a binding without a start or a watermark, and any
 * state other than {@code READ_COVERAGE_CAPTURED} give no coverage. The binding is found through
 * {@link AssetScopeResolver}; an asset that cannot be placed in a scope has none.
 *
 * <p>This is the binding-level signal. COV-01's per-asset coverage replaces it by supplying
 * another {@link ReadCoverageSource}.
 */
@Component
public class BindingReadCoverageSource implements ReadCoverageSource {

    /** {@code dlap.log.v1.ReadCoverage.READ_COVERAGE_CAPTURED}. */
    static final String CAPTURED = "READ_COVERAGE_CAPTURED";
    /** {@code dlap.log.v1.BindingState.BINDING_STATE_DISABLED}. */
    static final String DISABLED = "BINDING_STATE_DISABLED";

    private final BindingStatusStore store;

    /**
     * Creates the source.
     *
     * @param store the stored binding statuses
     */
    public BindingReadCoverageSource(BindingStatusStore store) {
        this.store = store;
    }

    @Override
    public View snapshot() {
        return view(store.findAll());
    }

    /**
     * Builds a view over a list of statuses.
     *
     * @param statuses the binding statuses
     * @return the view
     */
    static View view(List<BindingStatus> statuses) {
        Map<String, List<BindingStatus>> byScope = new HashMap<>();
        for (BindingStatus s : statuses) {
            if (s.sourceProvider() == null || s.scopeId() == null || s.scopeId().isBlank()) {
                continue;
            }
            byScope.computeIfAbsent(key(s.sourceProvider(), s.scopeId()), k -> new java.util.ArrayList<>()).add(s);
        }
        return (AssetRow asset) -> {
            Optional<String> scope = AssetScopeResolver.scopeId(asset);
            if (scope.isEmpty() || asset.cloudProvider() == null) {
                return Optional.empty();
            }
            List<BindingStatus> bindings = byScope.get(key(asset.cloudProvider(), scope.get()));
            return bindings == null ? Optional.empty() : combine(bindings);
        };
    }

    private static Optional<ReadCoverage> combine(List<BindingStatus> bindings) {
        Instant since = null;
        Instant validThrough = null;
        int enabled = 0;
        for (BindingStatus b : bindings) {
            if (DISABLED.equals(b.state())) {
                continue;
            }
            enabled++;
            if (!CAPTURED.equals(b.readCoverage()) || b.readCoverageSince() == null || b.lowWatermark() == null) {
                return Optional.empty();
            }
            since = since == null || b.readCoverageSince().isAfter(since) ? b.readCoverageSince() : since;
            validThrough = validThrough == null || b.lowWatermark().isBefore(validThrough)
                    ? b.lowWatermark() : validThrough;
        }
        return enabled == 0 ? Optional.empty() : Optional.of(new ReadCoverage(since, validThrough));
    }

    private static String key(String provider, String scopeId) {
        return provider.toUpperCase(Locale.ROOT) + "|" + scopeId.trim().toLowerCase(Locale.ROOT);
    }
}
