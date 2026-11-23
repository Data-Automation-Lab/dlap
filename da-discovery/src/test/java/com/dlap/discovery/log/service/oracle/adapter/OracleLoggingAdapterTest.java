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

package com.dlap.discovery.log.service.oracle.adapter;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * De-fabrication lock for {@link OracleLoggingAdapter}.
 *
 * <p>OCI log analysis was de-fabricated to real-data-or-nothing (ADR-0026).
 * This test pins {@code queryLogs} to an empty result so it can never again
 * return the old hardcoded CreateInstance/DeleteBucket sample entries.
 */
class OracleLoggingAdapterTest {

    private static final String VALID_TENANCY = "ocid1.tenancy.oc1..aaaa";

    @Test
    void queryLogsReturnsEmptyForValidTenancy() {
        OracleLoggingAdapter adapter = new OracleLoggingAdapter();

        List<OracleLogEntryAdapter> result = adapter.queryLogs(VALID_TENANCY, "filter", 5);

        assertNotNull(result, "queryLogs must never return null");
        assertTrue(result.isEmpty(),
                "OCI adapter is de-fabricated; queryLogs must emit no synthetic entries");
    }

    @Test
    void queryLogsReturnsEmptyForBlankTenancy() {
        OracleLoggingAdapter adapter = new OracleLoggingAdapter();

        assertTrue(adapter.queryLogs("", "filter", 5).isEmpty(),
                "blank tenancyId must yield an empty list");
        assertTrue(adapter.queryLogs(null, "filter", 5).isEmpty(),
                "null tenancyId must yield an empty list");
    }
}
