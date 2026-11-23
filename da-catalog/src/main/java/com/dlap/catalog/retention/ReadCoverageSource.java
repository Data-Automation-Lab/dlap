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

import java.util.Optional;

/**
 * Where the evaluator learns whether data reads are captured for an asset. The default
 * implementation reads the binding statuses ({@link BindingReadCoverageSource}); COV-01 (per-asset
 * coverage from CloudTrail selectors, GCP audit configs and Azure diagnostic settings) replaces it
 * by providing another bean.
 *
 * <p>A source is asked once per sweep for a {@link View}, a consistent snapshot, and the view is
 * then asked once per asset.
 */
public interface ReadCoverageSource {

    /**
     * Takes a snapshot of the coverage state.
     *
     * @return a view that answers per asset
     */
    View snapshot();

    /** A snapshot of the coverage state. */
    interface View {

        /**
         * The read coverage of an asset.
         *
         * @param asset the asset, with the location the snapshot is keyed by
         * @return the coverage, or empty when reads are not known to be captured for the asset
         */
        Optional<ReadCoverage> coverageFor(RetentionAssetStore.AssetRow asset);

        /**
         * A view that knows no coverage: every last-access rule stays {@code UNKNOWN}.
         *
         * @return the empty view
         */
        static View none() {
            return asset -> Optional.empty();
        }
    }
}
