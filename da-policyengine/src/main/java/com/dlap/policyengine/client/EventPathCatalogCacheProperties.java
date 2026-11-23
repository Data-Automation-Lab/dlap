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

package com.dlap.policyengine.client;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code dlap.policyengine.catalog-cache.*}: the short-lived cache of the catalog lookups on the policy
 * event path (POL-05, decision D45, Q-437). See {@link EventPathCatalogLookup}.
 *
 * @param ttl        how long an answer may be served; an entry as old as this is looked up again; zero turns
 *                   the cache off
 * @param maxEntries the most entries held over both kinds of lookup, at least 1
 */
@ConfigurationProperties(prefix = "dlap.policyengine.catalog-cache")
public record EventPathCatalogCacheProperties(
        @DefaultValue("PT30S") Duration ttl,
        @DefaultValue("10000") int maxEntries) {

    /**
     * Validates the settings.
     */
    public EventPathCatalogCacheProperties {
        ttl = ttl == null ? Duration.ofSeconds(30) : ttl;
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("dlap.policyengine.catalog-cache.ttl must not be negative");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("dlap.policyengine.catalog-cache.max-entries must be at least 1, was "
                    + maxEntries);
        }
    }
}
