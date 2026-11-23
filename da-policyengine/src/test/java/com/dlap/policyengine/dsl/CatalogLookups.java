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

package com.dlap.policyengine.dsl;

import java.time.Clock;
import java.time.Duration;

import com.dlap.policyengine.client.EventPathCatalogLookup;
import com.dlap.policyengine.client.ResilientCatalogAssetClient;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** Test helper: the event-path catalog lookup with its cache off, so a test sees every call on the mocked client. */
final class CatalogLookups {

    private CatalogLookups() {
    }

    static EventPathCatalogLookup uncached(ResilientCatalogAssetClient catalog, Clock clock) {
        return new EventPathCatalogLookup(catalog, clock, new SimpleMeterRegistry(), Duration.ZERO, 10_000);
    }
}
