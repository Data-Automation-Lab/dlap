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

package com.dlap.common.logevent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rebuilds the three producer shapes on {@code dlap.log.event.normalized}
 * as of feature/deployment 7984d1d, key for key, so the codec is tested
 * against what the wire carries today. Values are synthetic.
 *
 * <ul>
 *   <li>da-logingest: {@code LogIngestServiceImpl.java:98-127} builds the map,
 *       {@code LogEventPublisher.java:54-58} serializes it to a String and sends
 *       that String through {@code JsonSerializer} (double encoding).</li>
 *   <li>Crawler bridge: {@code CrawlerAssetSpineBridge.java:121-162}.</li>
 *   <li>Log analyzer: {@code LogAnalyzerSpinePublisher.java:94-117}.</li>
 *   <li>Dormant fourth shape: the AWS and Oracle log analyzers send a
 *       da-discovery {@code LogEvent} object through {@code JsonSerializer}
 *       ({@code AWSLogAnalyzer.java:295-299}, {@code OracleLogAnalyzer.java:296-300},
 *       {@code LogEvent.java:34-41}).</li>
 * </ul>
 */
final class ProducerShapes {

    static final ObjectMapper MAPPER = new ObjectMapper();

    static final String GCS_OBJECT = "projects/_/buckets/dlap-demo-bucket/objects/reports/q3.csv";
    static final String GCP_EVENT_ID = "7e7b787b46878c7dfa3d3a1c79be8a780a48d81380af5ae3c7458eff656d9864";
    static final String AWS_EVENT_ID = "99952aa076d4d73891f1d6315d576a3c3d272cb79bbdeaeacf0e194e36b05166";

    private ProducerShapes() {
    }

    /** What {@code JsonSerializer} puts on the wire for a pre-serialized String. */
    static byte[] doubleEncoded(String json) throws JsonProcessingException {
        return MAPPER.writeValueAsBytes(json);
    }

    /** da-logingest's normalized map for a GCS data-access read (legacy GcpLogSource fields). */
    static String logIngestGcsRead() throws JsonProcessingException {
        Map<String, Object> proto = new LinkedHashMap<>();
        proto.put("serviceName", "storage.googleapis.com");
        proto.put("methodName", "storage.objects.get");
        proto.put("resourceName", GCS_OBJECT);
        proto.put("authenticationInfo", Map.of("principalEmail", "alice@example.com"));
        proto.put("status", Map.of());

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("insertId", "1a2b3c4d5e");
        raw.put("logName", "projects/dlap-demo/logs/cloudaudit.googleapis.com%2Fdata_access");
        raw.put("severity", "INFO");
        raw.put("timestamp", "2026-10-18T09:15:02.123456Z");
        raw.put("protoPayload", proto);

        return MAPPER.writeValueAsString(logIngestMap(GCP_EVENT_ID, "2026-10-18T09:15:02.123456Z", "GCP",
                "storage.googleapis.com", "ACCESS", "storage.objects.get", "SUCCESS",
                "alice@example.com", "USER", "dlap-demo",
                "gcs_bucket", GCS_OBJECT, "dlap-demo", "", raw));
    }

    /** da-logingest's normalized map for a CloudTrail LookupEvents record, null principal type. */
    static String logIngestCloudTrail() throws JsonProcessingException {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("eventId", "3038ebd2-c98a-4c65-9b6e-e22506292313");
        raw.put("eventName", "DeleteBucket");
        raw.put("eventSource", "s3.amazonaws.com");
        raw.put("readOnly", "false");
        raw.put("accessKeyId", "ASIAEXAMPLEKEY");
        raw.put("eventTime", "2026-10-18T08:00:00Z");
        raw.put("cloudTrailEvent", "{\"eventVersion\":\"1.11\",\"eventID\":\"3038ebd2-c98a-4c65-9b6e-e22506292313\"}");

        return MAPPER.writeValueAsString(logIngestMap(AWS_EVENT_ID, "2026-10-18T08:00:00Z", "AWS",
                "s3.amazonaws.com", "DELETE", "DeleteBucket", "SUCCESS",
                "ASIAEXAMPLEKEY", null, "us-east-1",
                "AWS::S3::Bucket", "dlap-demo-bucket", null, "us-east-1", raw));
    }

    private static Map<String, Object> logIngestMap(String eventId, String eventTime, String provider,
                                                    String service, String changeType, String operation,
                                                    String result, String principalId, String principalType,
                                                    String principalAccount, String kind, String targetId,
                                                    String project, String region, Map<String, Object> raw) {
        // Same keys and nullToEmpty() treatment as LogIngestServiceImpl.ingest.
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("eventId", eventId);
        normalized.put("eventTime", eventTime);
        normalized.put("ingestTime", "2026-10-18T09:16:00.5Z");
        normalized.put("sourceProvider", provider);
        normalized.put("sourceService", service);
        normalized.put("changeType", changeType);
        normalized.put("operation", operation);
        normalized.put("resultStatus", result);
        normalized.put("principal", Map.of(
                "id", nullToEmpty(principalId),
                "type", nullToEmpty(principalType),
                "displayName", nullToEmpty(principalId),
                "accountId", nullToEmpty(principalAccount)));
        normalized.put("targetResource", Map.of(
                "kind", nullToEmpty(kind),
                "id", nullToEmpty(targetId),
                "name", nullToEmpty(targetId),
                "project", nullToEmpty(project),
                "region", nullToEmpty(region)));
        normalized.put("requestMetadata", Map.of(
                "callerIp", "",
                "userAgent", "",
                "requestId", "",
                "traceId", ""));
        normalized.put("rawEvent", raw);
        normalized.put("schemaVersion", "v1");
        return normalized;
    }

    /** The crawler bridge's synthetic event for a discovered Unity table with technical metadata. */
    static String crawlerBridgeUnityTable() throws JsonProcessingException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("eventId", "4f0c2a8e-3b7d-4c55-8a10-6d2e9b1f7c33");
        node.put("changeType", "CREATE");
        node.put("source", "crawler");
        node.put("operation", "DISCOVERY");
        node.put("sourceProvider", "UNITY");
        node.put("eventTime", "2026-10-18T07:30:00Z");
        ObjectNode target = node.putObject("targetResource");
        target.put("id", "main.sales.orders");
        target.put("name", "orders");
        target.put("kind", "UNITY_TABLE");
        target.set("technicalMetadata", MAPPER.valueToTree(Map.of(
                "tableType", "MANAGED", "dataSourceFormat", "DELTA")));
        return MAPPER.writeValueAsString(node);
    }

    /** The crawler bridge's fallback event id form and an UNKNOWN provider (discovery CloudProvider). */
    static String crawlerBridgeUnknownProvider() throws JsonProcessingException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("eventId", "s3://dlap-demo-bucket:UPDATE:2026-10-18T07:31:00Z");
        node.put("changeType", "UPDATE");
        node.put("source", "crawler");
        node.put("operation", "DISCOVERY");
        node.put("sourceProvider", "UNKNOWN");
        node.put("eventTime", "2026-10-18T07:31:00Z");
        ObjectNode target = node.putObject("targetResource");
        target.put("id", "s3://dlap-demo-bucket");
        target.put("kind", "S3_BUCKET");
        target.put("region", "us-east-1");
        return MAPPER.writeValueAsString(node);
    }

    /** The log-analyzer publisher's event; analyzers can emit changeType UNKNOWN. */
    static String logAnalyzerChange(String changeType) throws JsonProcessingException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("eventId", "0d6c1f4b-8e2a-4f7c-9b3d-5a1e7c9f2b40");
        node.put("changeType", changeType);
        node.put("source", "loganalyzer");
        node.put("operation", "DISCOVERY");
        node.put("sourceProvider", "GCP");
        node.put("eventTime", "2026-10-18T06:00:00.250Z");
        ObjectNode target = node.putObject("targetResource");
        target.put("id", "projects/dlap-demo/datasets/sales/tables/orders");
        target.put("kind", "BIGQUERY_TABLE");
        target.put("region", "US");
        return MAPPER.writeValueAsString(node);
    }

    /**
     * The dormant analyzers' {@code LogEvent}: no changeType and no eventTime at
     * the top level; {@code eventTimestamp} is the analyzer's own clock
     * ({@code Instant.now()} in the constructor), written as epoch seconds by
     * Spring Kafka's default mapper; the change itself sits in {@code payload}.
     */
    static String dormantAnalyzerLogEvent() throws JsonProcessingException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("eventId", "8a3c5e7f-1b2d-4e6f-8a9b-0c1d2e3f4a5b");
        node.put("eventTimestamp", 1760774400.125);
        node.put("source", "LogAnalyzer");
        node.put("cloudProvider", "AWS");
        node.put("accountId", "111111111111");
        ObjectNode payload = node.putObject("payload");
        payload.put("@class", "com.dlap.discovery.common.model.ResourceChange");
        payload.put("resourceId", "arn:aws:s3:::dlap-demo-bucket");
        payload.put("changeType", "DELETE");
        payload.put("timestamp", 1760770800.0);
        return MAPPER.writeValueAsString(node);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
