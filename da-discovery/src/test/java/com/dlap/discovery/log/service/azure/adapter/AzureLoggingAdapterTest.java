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

package com.dlap.discovery.log.service.azure.adapter;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * De-fabrication lock for {@link AzureLoggingAdapter}.
 *
 * <p>Azure log analysis was de-fabricated to real-data-or-nothing. This test
 * pins {@code queryLogs} to an empty result so it can never again return the
 * old hardcoded {@code Microsoft.Compute/...} sample entries.
 */
class AzureLoggingAdapterTest {

    @Test
    void queryLogsReturnsEmptyForValidSubscription() {
        AzureLoggingAdapter adapter = new AzureLoggingAdapter();

        List<AzureLogEntryAdapter> result = adapter.queryLogs("sub-123", "filter", 5);

        assertNotNull(result, "queryLogs must never return null");
        assertTrue(result.isEmpty(),
                "Azure adapter is de-fabricated; queryLogs must emit no synthetic entries");
    }

    @Test
    void queryLogsReturnsEmptyForBlankSubscription() {
        AzureLoggingAdapter adapter = new AzureLoggingAdapter();

        assertTrue(adapter.queryLogs("", "filter", 5).isEmpty(),
                "blank subscriptionId must yield an empty list");
        assertTrue(adapter.queryLogs(null, "filter", 5).isEmpty(),
                "null subscriptionId must yield an empty list");
    }
}
