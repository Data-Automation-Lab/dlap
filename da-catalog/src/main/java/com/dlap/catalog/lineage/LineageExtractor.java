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

package com.dlap.catalog.lineage;

import java.util.List;
import java.util.Map;

/**
 * Derives data-lineage edges from a single normalized log event
 * ({@code dlap.log.event.normalized}, produced by da-logingest).
 *
 * <p>An extractor is intentionally cloud-tolerant: it reads whatever
 * structured source/target references the event carries and returns them as
 * URI-keyed {@link LineageEdge}s. Resolving those URIs to catalog assets and
 * persisting the edges is the caller's job ({@code LineageService}), so an
 * extractor never touches the database.
 *
 * @see DefaultLineageExtractor for the reference implementation and the
 *      supported event shapes.
 */
public interface LineageExtractor {

    /**
     * A directed lineage edge in URI space (before asset resolution).
     *
     * @param upstreamUri      source asset URI (the read side)
     * @param downstreamUri    target asset URI (the write side)
     * @param relationshipType coarse relationship kind (e.g. {@code QUERY},
     *                          {@code COPY}); stored on
     *                          {@code AssetLineage.relationshipType}
     * @param details          free-form provenance persisted to
     *                          {@code AssetLineage.detailsJson} (e.g. operation,
     *                          jobId); never {@code null}
     */
    record LineageEdge(String upstreamUri, String downstreamUri, String relationshipType,
                       Map<String, Object> details) {
    }

    /**
     * Extract zero or more lineage edges from a normalized event.
     *
     * @param normalizedEvent the event as a decoded JSON map (camelCase keys);
     *                        may be {@code null} or empty
     * @return the derived edges, never {@code null}; empty when the event
     *         carries no structured lineage
     */
    List<LineageEdge> extract(Map<String, Object> normalizedEvent);
}
