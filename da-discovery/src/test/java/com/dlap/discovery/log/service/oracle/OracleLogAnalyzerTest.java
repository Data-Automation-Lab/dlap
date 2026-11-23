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

package com.dlap.discovery.log.service.oracle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.auth.impl.oracle.IOracleAuthenticationService;
import com.dlap.discovery.common.config.cloud.impl.oracle.OracleConfigService;
import com.dlap.discovery.event.service.type.LogEvent;
import com.dlap.discovery.log.service.ILogAnalyzer.AnalysisOptions;
import com.dlap.discovery.log.service.oracle.adapter.OracleLoggingAdapter;

/**
 * De-fabrication lock for {@link OracleLogAnalyzer}.
 *
 * <p>configService/authService/loggingAdapter are mocked non-null so the
 * null-guard early returns do NOT fire - this exercises the real analysis body.
 * The de-fabricated adapter returns an empty list, so the analyzer must publish
 * NOTHING.
 */
@ExtendWith(MockitoExtension.class)
class OracleLogAnalyzerTest {

    @Mock
    private OracleConfigService configService;

    @Mock
    private IOracleAuthenticationService authService;

    @Mock
    private CloudHierarchyRegistry hierarchyRegistry;

    @Mock
    private OracleLoggingAdapter loggingAdapter;

    @Mock
    private KafkaTemplate<String, LogEvent> kafkaTemplate;

    private OracleLogAnalyzer newAnalyzer() {
        return new OracleLogAnalyzer(
                configService, authService, hierarchyRegistry, loggingAdapter, kafkaTemplate);
    }

    @Test
    void analysisWithEmptyAdapterPublishesNothing() {
        when(loggingAdapter.queryLogs(anyString(), anyString(), anyInt()))
                .thenReturn(Collections.emptyList());

        OracleLogAnalyzer analyzer = newAnalyzer();

        analyzer.triggerLogAnalysisAsync(
                "ocid1.tenancy.oc1..aaaa",
                ZonedDateTime.parse("2026-07-04T00:00:00Z"),
                ZonedDateTime.parse("2026-07-04T01:00:00Z"),
                new AnalysisOptions());

        // De-fabricated adapter -> no entries -> no ResourceChange ever published.
        verify(kafkaTemplate, never()).send(any(), any(), any());
    }

    @Test
    void hasNewLogsIsFalseWhenAdapterEmpty() {
        when(loggingAdapter.queryLogs(anyString(), anyString(), anyInt()))
                .thenReturn(Collections.emptyList());

        OracleLogAnalyzer analyzer = newAnalyzer();

        assertFalse(analyzer.hasNewLogs("ocid1.tenancy.oc1..aaaa", Instant.parse("2026-07-04T00:00:00Z")),
                "empty audit response must report no new logs");
    }
}
