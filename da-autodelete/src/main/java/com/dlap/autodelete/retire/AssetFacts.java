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

package com.dlap.autodelete.retire;

import java.util.List;
import java.util.UUID;

/**
 * What the catalog says about an asset when the executor checks a delete.
 *
 * @param assetId             the catalog id
 * @param assetUri            the catalog's asset URI as stored (canonical since ID-03)
 * @param cloudProvider       the provider, for example {@code GCP}
 * @param serviceType         the asset kind, for example {@code GCS_BUCKET}
 * @param legalHold           the legal hold; null when the catalog did not say
 * @param readCoverage        {@code FULL}, {@code PARTIAL}, {@code NONE}, or null when unknown
 * @param readCoverageReasons the reasons, or an empty list
 * @param sizeBytes           the latest observed size, or null when unknown
 */
public record AssetFacts(UUID assetId, String assetUri, String cloudProvider, String serviceType,
        Boolean legalHold, String readCoverage, List<String> readCoverageReasons, Long sizeBytes) {

    /** The coverage value that allows a destructive action (COV-01: PARTIAL counts as unknown). */
    public static final String FULL_COVERAGE = "FULL";

    /** Copies the reasons. */
    public AssetFacts {
        readCoverageReasons = readCoverageReasons == null ? List.of() : List.copyOf(readCoverageReasons);
    }

    /** @return true unless the catalog said the asset is not on legal hold */
    public boolean heldOrUnknown() {
        return !Boolean.FALSE.equals(legalHold);
    }

    /** @return true only for {@code FULL} read coverage */
    public boolean fullReadCoverage() {
        return FULL_COVERAGE.equalsIgnoreCase(readCoverage);
    }
}
