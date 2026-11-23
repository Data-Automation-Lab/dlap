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

package com.dlap.autoarchival.service;

/**
 * A real, resolved storage location for one asset: where the data actually lives and
 * which cloud owns it. Only ever built from catalog metadata or from a policy event's
 * own match snapshot - never synthesised.
 *
 * @param cloudProvider normalised provider name ({@code AWS} / {@code GCP} /
 *                      {@code AZURE} / …), never blank
 * @param uri           canonical cloud locator (e.g. {@code gs://bucket/key}), never blank
 */
public record AssetLocation(String cloudProvider, String uri) {

    /**
     * Compact constructor rejecting blank components, so an {@code AssetLocation} that
     * exists is always usable for a real cloud call.
     */
    public AssetLocation {
        if (cloudProvider == null || cloudProvider.isBlank()) {
            throw new IllegalArgumentException("cloudProvider must not be blank");
        }
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("uri must not be blank");
        }
    }
}
