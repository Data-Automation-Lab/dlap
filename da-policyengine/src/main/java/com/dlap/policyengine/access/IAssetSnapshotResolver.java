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

package com.dlap.policyengine.access;

import java.util.Map;

/**
 * Resolves an AuthZEN {@code resource} into the flat asset-snapshot map that
 * {@link com.dlap.policyengine.dsl.PolicyDslEvaluator#matches} evaluates
 * (enforcement-contract.md §2, resource-resolution rules).
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code resource.id} present → resolve the catalog asset (authoritative
 *       labels win over PEP-supplied hints).</li>
 *   <li>Otherwise → evaluate against {@code resource.properties} directly
 *       ({@code labels} hint + {@code assetUri}).</li>
 * </ol>
 *
 * <p>The default {@link CatalogAssetSnapshotResolver} implementation resolves by
 * catalog id via the existing Feign client and falls back to properties. There is
 * no {@code assetUri → asset} catalog endpoint today, so a URI-only resource is
 * evaluated against its supplied properties; wiring a {@code GET /assets/resolve}
 * lookup is a documented seam (see that class).
 */
public interface IAssetSnapshotResolver {

    /**
     * Build the asset-snapshot map for the given resource. Never returns null;
     * an unresolvable resource yields a properties-only (possibly empty) snapshot.
     *
     * @param resource the AuthZEN {@code resource} block (may be null)
     * @return snapshot keyed by the fields {@code PolicyDslEvaluator} reads
     *     ({@code labels}, {@code assetType}, {@code region}, …)
     */
    ResolvedResource resolve(AuthZenEvaluationRequest.Resource resource);

    /**
     * Resolution outcome: the evaluator snapshot plus the identifiers the
     * decision-log event records.
     *
     * @param snapshot flat map for {@link com.dlap.policyengine.dsl.PolicyDslEvaluator}
     * @param assetId  resolved catalog asset id, or the raw {@code resource.id} / null
     * @param assetUri the {@code assetUri} property when supplied, else null
     */
    record ResolvedResource(Map<String, Object> snapshot, String assetId, String assetUri) { }
}
