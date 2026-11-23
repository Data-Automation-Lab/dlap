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

package com.dlap.logingest.parse.gcpaudit;

import com.dlap.fixtures.FixtureCase;
import com.dlap.fixtures.FixtureCatalog;
import com.dlap.logingest.parse.core.BindingConfig;
import com.dlap.logingest.parse.core.OnMismatch;
import com.dlap.logingest.parse.core.ParserRegistry;
import com.dlap.logingest.parse.core.contract.AbstractParserContractTest;
import com.dlap.logingest.parse.core.mapping.MappingSpec;
import java.util.Optional;

/**
 * The frozen parser contract (LOG-02a, ADR-0054) on every {@code gcpaudit} case of the
 * {@code da-log-fixtures} corpus: the generated json and api-proto cases, the hand-written negative
 * and bq-sink-row cases, and the FIX-02 seed cases, in all twelve cells, including the three BigQuery v2
 * cells the catalog does not claim yet ({@link GcpAuditTestSupport#allCellsCorpus()}). Cases whose name starts with
 * {@code binding-pinned-v1} are parsed through a binding pinned to {@code bq-auditdata-legacy} with
 * {@code on-mismatch: QUARANTINE} (coverage spec variant {@code bq1.binding-pinned-v1}).
 */
class GcpAuditContractTest extends AbstractParserContractTest {

    /** The case name prefix of the pinned-binding cases. */
    static final String PINNED_V1 = "binding-pinned-v1";

    @Override
    protected String family() {
        return GcpAuditFormat.FAMILY;
    }

    @Override
    protected String sourceKind() {
        return GcpAuditFormat.SOURCE_KIND;
    }

    @Override
    protected Optional<MappingSpec> mapping() {
        return Optional.of(GcpAuditTestSupport.mapping());
    }

    @Override
    protected ParserRegistry registry() {
        return GcpAuditTestSupport.registry();
    }

    /**
     * Every gcpaudit cell, including the ones the shipped catalog does not claim yet.
     *
     * @return the corpus with all gcpaudit cells supported
     */
    @Override
    protected FixtureCatalog corpus() {
        return GcpAuditTestSupport.allCellsCorpus();
    }

    @Override
    protected BindingConfig binding(FixtureCase c) {
        BindingConfig b = super.binding(c);
        return c.name().startsWith(PINNED_V1) ? b.pinned(GcpAuditFormat.BQ_AUDIT_DATA_LEGACY, OnMismatch.QUARANTINE) : b;
    }
}
