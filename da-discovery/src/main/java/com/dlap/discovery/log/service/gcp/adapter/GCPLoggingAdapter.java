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

package com.dlap.discovery.log.service.gcp.adapter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.google.api.gax.paging.Page;
import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.cloud.logging.LoggingOptions;
import com.google.cloud.logging.Payload;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

/**
 * Adapter for Google Cloud Logging API.
 * This class encapsulates interactions with the Google Cloud Logging API.
 */
@Component
public class GCPLoggingAdapter {

	private static final Logger log = LoggerFactory.getLogger(GCPLoggingAdapter.class);

	private final Gson gson = new Gson();

	/**
	 * Queries logs from Google Cloud Logging.
	 * 
	 * @param projectId The GCP project ID
	 * @param filter    The filter string for the query
	 * @param pageSize  The maximum number of entries to return
	 * @return List of LogEntryAdapter objects
	 */
	public List<LogEntryAdapter> queryLogs(String projectId, String filter, int pageSize) {
		if (projectId == null || projectId.isBlank()) {
			log.error("Project ID is required for GCP log analysis");
			return Collections.emptyList();
		}

		List<LogEntryAdapter> results = new ArrayList<>();

		try (Logging logging = LoggingOptions.newBuilder()
				.setProjectId(projectId)
				.build()
				.getService()) {

			// Execute the query
			Page<LogEntry> page = logging.listLogEntries(
					Logging.EntryListOption.filter(filter),
					Logging.EntryListOption.pageSize(pageSize));

			// Convert LogEntry objects to LogEntryAdapter objects
			for (LogEntry entry : page.iterateAll()) {
				LogEntryAdapter adapter = convertToAdapter(entry);
				if (adapter != null) {
					results.add(adapter);
				}

				if (results.size() >= pageSize) {
					break;
				}
			}

			log.info("Retrieved {} log entries for project {}", results.size(), projectId);

		} catch (Exception e) {
			log.error("Error retrieving logs for project {}: {}", projectId, e.getMessage(), e);
		}

		return results;
	}

	/**
	 * Converts a Google Cloud LogEntry to our LogEntryAdapter.
	 * 
	 * @param entry The Google Cloud LogEntry
	 * @return The LogEntryAdapter
	 */
	private LogEntryAdapter convertToAdapter(LogEntry entry) {
		if (entry == null) {
			return null;
		}

		try {
			LogEntryAdapter.Builder builder = new LogEntryAdapter.Builder()
					.logName(entry.getLogName())
					.insertId(entry.getInsertId())
					.severity(entry.getSeverity() != null ? entry.getSeverity().toString() : null);

			// Handle timestamp conversion
			if (entry.getTimestamp() != null) {
				Instant timestamp = Instant.ofEpochMilli(entry.getTimestamp());
				builder.timestamp(timestamp);
			}

			builder.trace(entry.getTrace());

			// Set resource type and labels
			if (entry.getResource() != null) {
				builder.resourceType(entry.getResource().getType());

				Map<String, String> resourceLabels = new HashMap<>();
				if (entry.getResource().getLabels() != null) {
					resourceLabels.putAll(entry.getResource().getLabels());
				}
				builder.resourceLabels(resourceLabels);
			}

			// Set labels
			if (entry.getLabels() != null) {
				builder.labels(entry.getLabels());
			}

			// Set operation info
			if (entry.getOperation() != null) {
				Map<String, String> operationInfo = new HashMap<>();
				if (entry.getOperation().getId() != null) {
					operationInfo.put("id", entry.getOperation().getId());
				}
				if (entry.getOperation().getProducer() != null) {
					operationInfo.put("producer", entry.getOperation().getProducer());
				}
				builder.operationInfo(operationInfo);
			}

			// Handle payload
			boolean hasProtoPayload = false;
			Map<String, Object> payloadMap = new HashMap<>();

			if (entry.getPayload() != null) {
				Payload payload = entry.getPayload();
				if (payload instanceof Payload.ProtoPayload) {
					hasProtoPayload = true;
					// Convert protobuf to map
					payloadMap = convertProtoPayloadToMap((Payload.ProtoPayload) payload);
				} else if (payload instanceof Payload.JsonPayload) {
					// Convert JSON payload to map
					payloadMap = ((Payload.JsonPayload) payload).getDataAsMap();
				} else if (payload instanceof Payload.StringPayload) {
					Payload.StringPayload stringPayload = (Payload.StringPayload) payload;
					payloadMap.put("message", stringPayload.getData());
				}
			}

			builder.hasProtoPayload(hasProtoPayload)
					.protoPayloadMap(payloadMap);

			return builder.build();

		} catch (Exception e) {
			log.error("Error converting LogEntry to LogEntryAdapter: {}", e.getMessage(), e);
			return null;
		}
	}

	/**
	 * Converts a ProtoPayload to a Map.
	 * 
	 * @param protoPayload The ProtoPayload
	 * @return The Map representation
	 */
	private Map<String, Object> convertProtoPayloadToMap(Payload.ProtoPayload protoPayload) {
		Map<String, Object> result = new HashMap<>();

		try {
			// Get the data from the proto payload
			Any anyData = protoPayload.getData();

			// Convert Any to canonical AuditLog JSON.
			String jsonString = JsonFormat.printer().print(anyData);

			// Flatten the AuditLog into TOP-LEVEL keys (methodName, resourceName,
			// authenticationInfo, requestMetadata, request, response, serviceName, ...) so
			// GcpLogAnalyzer.createResourceChangeFromLogEntry can read them - matching the
			// shape the GCS/BigQuery fetch strategies already produce. The prior version
			// stored only {data, typeUrl}, so the analyzer found no methodName/resourceName
			// and the API log source silently emitted zero ResourceChanges.
			Map<String, Object> flat =
					gson.fromJson(jsonString, new TypeToken<Map<String, Object>>() {}.getType());
			if (flat != null) {
				result.putAll(flat);
			}

			// Preserve the proto type URL for diagnostics (does not collide with AuditLog keys).
			result.put("typeUrl", anyData.getTypeUrl());

		} catch (InvalidProtocolBufferException e) {
			log.error("Error converting proto payload to JSON: {}", e.getMessage(), e);
			result.put("error", "Failed to parse proto payload: " + e.getMessage());
		} catch (Exception e) {
			log.error("Error converting proto payload to map: {}", e.getMessage(), e);
			result.put("error", "General error: " + e.getMessage());
		}

		return result;
	}
}