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

package com.dlap.catalog.integration;

import java.util.Collection;

import com.dlap.common.model.entity.Asset;

/**
 * SPI for publishing DLAP assets into an external catalog/metastore
 * (ADR-0029). da-catalog remains the system of record; an implementation
 * mirrors governed assets outward for interoperability.
 *
 * <p>The default wiring provides a no-op when no integration is enabled, so
 * call sites never need null checks and the feature is purely additive.
 */
public interface ExternalCatalogIntegration {

    /**
     * Register or update {@code asset} in the external catalog, attaching the
     * given DLAP label names as catalog metadata/properties. Implementations
     * must be idempotent and must never throw onto the caller's path - a
     * failure to mirror an asset must not break catalog ingestion.
     *
     * @param asset  the DLAP asset (da-protos entity)
     * @param labels DLAP label names currently assigned to the asset
     */
    void registerAsset(Asset asset, Collection<String> labels);

    /** @return true if a real external catalog is wired and enabled. */
    default boolean isEnabled() {
        return false;
    }
}
