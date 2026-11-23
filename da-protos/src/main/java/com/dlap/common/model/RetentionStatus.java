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

package com.dlap.common.model;

/**
 * Retention state of an asset as of its last evaluation (ADR-0046). Stored as the enum name in
 * {@code dlap_common.dlap_assets.retention_status}; the column's CHECK constraint lists exactly
 * these values and defaults to {@link #UNKNOWN}.
 *
 * <p>The model never treats an unknown value as stale or as fresh. {@link #DUE} and
 * {@link #RETAINED} each need evidence that proves them; anything less is {@link #UNKNOWN}.
 * Only {@link #DUE} lets a retire action (archive or delete) proceed, and only when the asset is
 * not on legal hold.
 */
public enum RetentionStatus {

    /**
     * Not evaluated yet, or a retention rule applies but the evidence cannot prove whether the
     * expiry has passed (for example a last-access rule while read logging is not captured).
     * The default for every asset.
     */
    UNKNOWN,

    /** Evaluated: no retention rule applies to the asset. */
    NO_POLICY,

    /** Evaluated: the evidence proves the expiry is still in the future. */
    RETAINED,

    /** Evaluated: the evidence proves the expiry has passed. The asset may be retired. */
    DUE,

    /** The asset is on legal hold. Overrides every other state; nothing may retire it. */
    ON_HOLD;

    /**
     * Whether this status was reached from evidence, as opposed to being the default or an
     * inconclusive evaluation.
     *
     * @return {@code false} for {@link #UNKNOWN} only
     */
    public boolean isKnown() {
        return this != UNKNOWN;
    }

    /**
     * Whether this status alone lets a retire action proceed. Callers must still check the
     * asset's legal hold, because a hold placed after the last evaluation wins.
     *
     * @return {@code true} for {@link #DUE} only
     */
    public boolean permitsRetire() {
        return this == DUE;
    }
}
