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

import java.util.Optional;

/**
 * The instant a retention period counts from (ADR-0046).
 *
 * <p>A retention rule is kept at its source, never on the asset row: a policy's
 * {@code setRetention} action, the label-default configuration, a provider setting recorded in
 * {@code technical_metadata}, or the catalog's retention rule store for operator rules (spec
 * {@code docs/specs/asset-descriptor.md}, "Retention &gt; Rule"). The evaluator records the basis
 * of the rule that decided its last run in {@code dlap_common.dlap_assets.retention_basis}, as the
 * enum name. That column is output only, and its CHECK constraint lists exactly these values.
 *
 * <p>The expiry is the basis time plus the rule's days, except for {@link #FIXED_DATE}, where the
 * rule carries the expiry instant itself. Every basis time is a business time taken from provider
 * evidence (ADR-0045 business-time rule), never a DLAP clock.
 */
public enum RetentionBasis {

    /** Counts from the asset's creation by the provider, {@code cloud_created_at}. */
    CREATED("cloud_created_at"),

    /** Counts from the last content write, {@code last_modified_at}. */
    LAST_MODIFIED("last_modified_at"),

    /** Counts from the last data read, {@code last_accessed_at}. Needs captured read coverage. */
    LAST_ACCESSED("last_accessed_at"),

    /**
     * The rule carries its expiry instant instead of a period, for example a BigQuery table
     * expiration time (source {@code PROVIDER}) or an operator's end date (source {@code MANUAL}).
     * The instant lives with the rule at its source. The evaluator copies it into
     * {@code retention_expires_at} as the result of an evaluation and never reads it back from
     * there. The rule has no days, so {@code retention_days} stays {@code null}.
     */
    FIXED_DATE(null);

    private final String basisColumn;

    RetentionBasis(String basisColumn) {
        this.basisColumn = basisColumn;
    }

    /**
     * The {@code dlap_assets} column that holds the basis time, which the evaluator reads as
     * evidence.
     *
     * @return the column name, or empty for {@link #FIXED_DATE}
     */
    public Optional<String> basisColumn() {
        return Optional.ofNullable(basisColumn);
    }

    /**
     * Whether a rule with this basis needs {@code retention_days} to compute the expiry.
     *
     * @return {@code false} for {@link #FIXED_DATE}, {@code true} otherwise
     */
    public boolean requiresDays() {
        return basisColumn != null;
    }
}
