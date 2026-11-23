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

package com.dlap.autocompliance.signal;

/**
 * Applies a compliance label onto a catalog asset. The second pluggable point of
 * the compliance-via-labels pattern (ADR-0035): {@link ComplianceSignalProvider}
 * is the <i>source</i> (external tool → signals), this is the <i>sink</i>
 * (signals → asset labels).
 *
 * <p>The default {@link LoggingComplianceLabelSink} records what it would apply, so
 * the pattern is safe to enable before a write path is wired. A production sink
 * resolves {@code assetUri → assetId} and calls the catalog label API
 * ({@code POST /api/v1/catalog/assets/{id}/labels}, allow-listed for the M2M
 * service account) or publishes an {@code addLabel} policy-action event.
 */
public interface ComplianceLabelSink {

    /**
     * Apply {@code labelName} to the asset identified by {@code assetUri}.
     *
     * @param assetUri  canonical asset identifier
     * @param labelName canonical compliance label (see {@link ComplianceLabelMapper})
     * @param detail    optional provenance carried from the source signal
     */
    void applyLabel(String assetUri, String labelName, String detail);
}
