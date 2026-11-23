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
package com.dlap.reporting.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.dlap.reporting.client.CatalogServiceClient.ActivityPage;
import com.dlap.reporting.client.ResilientCatalogClient.ActivityRequest;

/**
 * The catalog activity call of {@link ResilientCatalogClient}: every field of the request,
 * including the keyset cursor (CAT-04, Q-445), reaches the Feign client unchanged.
 */
class ResilientCatalogClientActivityTest {

    private final CatalogServiceClient delegate = mock(CatalogServiceClient.class);
    private final ResilientCatalogClient client = new ResilientCatalogClient(delegate);

    @Test
    void theKeysetCursorReachesTheFeignClient() {
        ActivityPage page = new ActivityPage(List.of(), 0, 0, 0, 200, null, true);
        when(delegate.getAssetActivity(eq(0), eq(200), eq("assetUri,asc"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), eq("2026-10-19T12:00:00Z"), eq("gs://bucket-a"))).thenReturn(page);

        ActivityPage answer = client.getAssetActivity(new ActivityRequest(0, 200, "assetUri,asc", List.of("GCP"),
                null, null, null, null, null, null, null, null, "2026-10-19T12:00:00Z", "gs://bucket-a"));

        assertThat(answer).isSameAs(page);
        verify(delegate).getAssetActivity(0, 200, "assetUri,asc", List.of("GCP"), null, null, null, null, null, null,
                null, null, "2026-10-19T12:00:00Z", "gs://bucket-a");
    }

    @Test
    void aRequestBuiltWithoutACursorIsAnOffsetRequest() {
        ActivityRequest request = new ActivityRequest(3, 50, "assetUri,asc", null, null, null, null, null, null, null,
                null, null, "2026-10-19T12:00:00Z");

        assertThat(request.after()).isNull();
        assertThat(request.page()).isEqualTo(3);

        client.getAssetActivity(request);

        verify(delegate).getAssetActivity(3, 50, "assetUri,asc", null, null, null, null, null, null, null, null, null,
                "2026-10-19T12:00:00Z", null);
    }
}
