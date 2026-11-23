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

package com.dlap.policyengine.projection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.dlap.policyengine.dsl.PolicyDsl;

import lombok.extern.slf4j.Slf4j;

/**
 * Resolves the securables (asset identifiers) a {@code kind: AccessPolicy}'s
 * {@code match.labels} selector targets, so the push-down projection can name
 * concrete objects to grant on.
 *
 * <p><b>Seam.</b> da-catalog exposes no "assets matching this label selector"
 * query today ({@link com.dlap.policyengine.client.ICatalogAssetClient} only
 * resolves a single asset by UUID - the same gap {@code CatalogAssetSnapshotResolver}
 * documents). Rather than fabricate that endpoint, the default resolver returns
 * the selector's label tokens as <em>label-securable</em> placeholders of the
 * form {@code label:<LABEL>}. A platform sink that understands label-based
 * securables (e.g. Unity Catalog tag-based grants) can consume these directly;
 * a deployment that wants concrete per-asset grants supplies its own resolver
 * bean once a catalog label-query endpoint exists.
 *
 * <p>This keeps push-down projection honest (ADR-0040: detection-grade,
 * best-effort) without inventing catalog capability.
 */
@Component
@Slf4j
public class ProjectionSecurableResolver {

    static final String LABEL_SECURABLE_PREFIX = "label:";

    /**
     * @param dsl a parsed {@code kind: AccessPolicy} document
     * @return best-effort securable identifiers for the policy's selector; empty
     *         when the policy has no label selector
     */
    @SuppressWarnings("unchecked")
    public List<String> resolveSecurables(PolicyDsl dsl) {
        List<String> out = new ArrayList<>();
        if (dsl == null || dsl.getSpec() == null || dsl.getSpec().getMatch() == null) {
            return out;
        }
        Object labels = dsl.getSpec().getMatch().get("labels");
        if (!(labels instanceof Map<?, ?> labelMap)) {
            return out;
        }
        // anyOf / allOf name the labels an asset must carry to be in scope.
        addTokens(out, labelMap.get("anyOf"));
        addTokens(out, labelMap.get("allOf"));
        if (out.isEmpty()) {
            log.debug("AccessPolicy {} has a labels clause with no anyOf/allOf; no securables",
                    dsl.getMetadata() != null ? dsl.getMetadata().getId() : "?");
        }
        return out;
    }

    private void addTokens(List<String> out, Object value) {
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) {
                    String token = LABEL_SECURABLE_PREFIX + o;
                    if (!out.contains(token)) {
                        out.add(token);
                    }
                }
            }
        }
    }
}
