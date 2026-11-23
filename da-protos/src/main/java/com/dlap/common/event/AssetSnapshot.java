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

package com.dlap.common.event;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Strongly-typed snapshot of an asset's user-visible fields, carried on
 * {@link AssetChangeEvent} so consumers don't need a catalog round-trip
 * just to read identity / classification.
 *
 * <p>Introduced by R-P2-3 (v0.2.0 N0) to retire the previous
 * {@code Map<String, Object>} payload, which had a producer in
 * {@code da-catalog/AssetService} but zero runtime readers - the only
 * reference to the Map fields was a Javadoc pointer in
 * {@code da-policyengine/PolicyEvaluationRequestDTO}.
 *
 * <p>Field set mirrors what {@code AssetService} previously stuffed
 * into the snapshot Map; future fields land here rather than as
 * arbitrary Map keys. Detail / business-metadata JSON blobs stay as
 * strings so downstream services that need their structure can parse
 * with their own Jackson typing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssetSnapshot(
        String assetName,
        String assetType,
        String cloudProvider,
        String region,
        String nativeAssetId,
        String detailsJson,
        String businessMetadataJson
) {
    /** Convenience builder for fluent producer-side construction. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String assetName;
        private String assetType;
        private String cloudProvider;
        private String region;
        private String nativeAssetId;
        private String detailsJson;
        private String businessMetadataJson;

        public Builder assetName(String v) { this.assetName = v; return this; }
        public Builder assetType(String v) { this.assetType = v; return this; }
        public Builder cloudProvider(String v) { this.cloudProvider = v; return this; }
        public Builder region(String v) { this.region = v; return this; }
        public Builder nativeAssetId(String v) { this.nativeAssetId = v; return this; }
        public Builder detailsJson(String v) { this.detailsJson = v; return this; }
        public Builder businessMetadataJson(String v) { this.businessMetadataJson = v; return this; }

        public AssetSnapshot build() {
            return new AssetSnapshot(assetName, assetType, cloudProvider, region,
                    nativeAssetId, detailsJson, businessMetadataJson);
        }
    }
}
