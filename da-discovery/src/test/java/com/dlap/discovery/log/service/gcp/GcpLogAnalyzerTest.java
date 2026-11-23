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

package com.dlap.discovery.log.service.gcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.model.CloudService;
import com.dlap.discovery.common.model.ResourceChange;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.log.config.LogAnalyzerProperties;
import com.dlap.discovery.log.service.LogAnalyzerSpinePublisher;
import com.dlap.discovery.log.service.gcp.adapter.LogEntryAdapter;

/**
 * Real-mapping honesty test for {@link GcpLogAnalyzer} - the reference provider.
 *
 * <p>Proves the GCP log path maps a faithful GCS audit-log entry (flat/nested
 * protoPayload shape, the shape the analyzer actually reads) onto a
 * {@link ResourceChange} correctly, and hands that change to
 * {@link LogAnalyzerSpinePublisher} (the canonical flat-shape publisher). Every asserted
 * field traces to the synthetic fixture; the fixture lives only in this test.
 */
@ExtendWith(MockitoExtension.class)
class GcpLogAnalyzerTest {

    // The GCS ResourceType id the analyzer maps the GCP "gcs_bucket" type to.
    private static final String GCP_GCS_TYPE_ID = "gcp_gcs_bucket";

    @Mock
    private CloudHierarchyRegistry hierarchyRegistry;

    @Mock
    private LogAnalyzerSpinePublisher spinePublisher;

    /**
     * Faithful GCS bucket-create audit entry. protoPayload uses the flat
     * top-level keys ({@code methodName}, {@code resourceName}) plus the nested
     * {@code authenticationInfo}/{@code requestMetadata} maps that the analyzer's
     * getStringValue dotted-path reader walks - NOT the broken API-adapter shape.
     */
    private LogEntryAdapter syntheticGcsBucketCreate() {
        Map<String, Object> authInfo = new HashMap<>();
        authInfo.put("principalEmail", "alice@example.com");

        Map<String, Object> requestMetadata = new HashMap<>();
        requestMetadata.put("callerIp", "198.51.100.7");
        requestMetadata.put("callerSuppliedUserAgent", "google-cloud-sdk/470.0.0");

        Map<String, Object> protoPayload = new HashMap<>();
        protoPayload.put("methodName", "storage.buckets.create");
        protoPayload.put("resourceName", "projects/_/buckets/demo-bucket");
        protoPayload.put("authenticationInfo", authInfo);
        protoPayload.put("requestMetadata", requestMetadata);

        return new LogEntryAdapter.Builder()
                .logName("projects/demo/logs/cloudaudit.googleapis.com%2Factivity")
                .insertId("insert-abc-123")
                .severity("NOTICE")
                .resourceType("gcs_bucket")
                .timestamp(Instant.parse("2026-07-04T09:30:00Z"))
                .hasProtoPayload(true)
                .protoPayloadMap(protoPayload)
                .build();
    }

    @Test
    void mapsRealGcsBucketCreateAndHandsToSpinePublisher() {
        CloudService gcsService = new CloudService("gcp_gcs", "Cloud Storage", CloudProvider.GCP);
        ResourceType gcsType = new ResourceType(GCP_GCS_TYPE_ID, "GCS Bucket", gcsService);
        when(hierarchyRegistry.getResourceType(GCP_GCS_TYPE_ID)).thenReturn(gcsType);

        // Empty operation mappings -> determineChangeType falls through to the
        // contains("create") branch, giving CREATE without config coupling.
        LogAnalyzerProperties props = new LogAnalyzerProperties();

        // GCPConfigService, authService, BigQuery, Storage, IGcpLogConfigService are
        // unused by the single-entry mapping seam; pass null where the constructor
        // and the exercised path tolerate it.
        GcpLogAnalyzer analyzer = new GcpLogAnalyzer(
                null,   // gcpConfigService
                null,   // authService
                hierarchyRegistry,
                null,   // GCPLoggingAdapter
                null,   // BigQuery (optional)
                null,   // Storage (optional)
                null,   // IGcpLogConfigService
                spinePublisher,
                props);

        boolean published = analyzer.processSingleLogEntry("demo-project", syntheticGcsBucketCreate(), null);
        assertTrue(published, "a real GCS create entry must publish a change event");

        // The analyzer hands the mapped ResourceChange to the canonical spine publisher
        // (which emits the flat DlapLogEvent shape - covered by LogAnalyzerSpinePublisherTest).
        ArgumentCaptor<ResourceChange> changeCaptor = ArgumentCaptor.forClass(ResourceChange.class);
        verify(spinePublisher).publish(eq("GCP"), eq("demo-project"), changeCaptor.capture());

        ResourceChange change = changeCaptor.getValue();
        assertNotNull(change, "a ResourceChange must be published");
        // extractResourceId takes the last path segment of resourceName.
        assertEquals("demo-bucket", change.getResourceId());
        // "storage.buckets.create" -> contains("create") -> CREATE.
        assertEquals(ResourceChange.ChangeType.CREATE, change.getChangeType());
        // actor + actorEmail trace to authenticationInfo.principalEmail.
        assertEquals("alice@example.com", change.getActor());
        assertEquals("alice@example.com", change.getActorEmail());
        assertEquals("demo-project", change.getProjectId());
        assertEquals(Instant.parse("2026-07-04T09:30:00Z"), change.getTimestamp());
    }
}
