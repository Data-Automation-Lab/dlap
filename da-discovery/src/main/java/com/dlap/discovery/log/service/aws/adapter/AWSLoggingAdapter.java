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

package com.dlap.discovery.log.service.aws.adapter;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Adapter for AWS CloudTrail.
 *
 * <p>The live AWS CloudTrail API integration is deferred to v0.2.0 (ADR-0017 / R-P0-3), so the
 * default {@link #queryLogs} returns an empty list (real-data-or-nothing - never fabricated
 * sample data).
 *
 * <p><b>File replay ({@code dlap.discovery.aws.log-replay-file}).</b> When that property points
 * at a CloudTrail JSON file, {@code queryLogs} parses it into {@link AWSLogEntryAdapter}s and the
 * real {@code AWSLogAnalyzer} maps them to catalog assets exactly as it would a live fetch. This
 * is triple-duty: (1) an offline/air-gapped discovery source ("point DLAP at your exported
 * CloudTrail"), (2) the injection seam for the provider-parametrized end-to-end tests, and
 * (3) AWS's first real (offline) log integration until the live {@code CloudTrailClient}
 * fetch ships. The file is a CloudTrail export - either {@code {"Records":[...]}} or a bare
 * {@code [...]} array of records.
 */
@Component
@Profile("aws")
public class AWSLoggingAdapter {

	private static final Logger log = LoggerFactory.getLogger(AWSLoggingAdapter.class);

	private final ObjectMapper objectMapper;
	private final String replayFile;

	public AWSLoggingAdapter(ObjectMapper objectMapper,
			@Value("${dlap.discovery.aws.log-replay-file:}") String replayFile) {
		this.objectMapper = objectMapper;
		this.replayFile = replayFile;
	}

	/**
	 * Queries CloudTrail logs. Returns entries from the replay file when configured, otherwise an
	 * empty list (the deferred live integration).
	 */
	public List<AWSLogEntryAdapter> queryLogs(String accountId, String filter, int maxResults) {
		if (accountId == null || accountId.isBlank()) {
			log.error("Account ID is required for AWS log analysis");
			return Collections.emptyList();
		}
		if (replayFile != null && !replayFile.isBlank()) {
			return replayFromFile(replayFile, maxResults);
		}
		log.warn("AWS CloudTrail integration is deferred to v0.2.0; queryLogs() for account {} "
				+ "(filter='{}', maxResults={}) returns an empty list. Set "
				+ "dlap.discovery.aws.log-replay-file to discover from an exported CloudTrail file.",
				accountId, filter, maxResults);
		return Collections.emptyList();
	}

	private List<AWSLogEntryAdapter> replayFromFile(String path, int maxResults) {
		File file = new File(path);
		if (!file.isFile()) {
			log.warn("AWS log-replay file not found: {}; no entries.", path);
			return Collections.emptyList();
		}
		try {
			JsonNode root = objectMapper.readTree(file);
			JsonNode records = root.isArray() ? root : root.path("Records");
			List<AWSLogEntryAdapter> out = parseRecords(records, maxResults);
			log.info("AWS log-replay: parsed {} CloudTrail record(s) from {}", out.size(), path);
			return out;
		} catch (Exception e) {
			log.error("AWS log-replay: failed to read {}: {}", path, e.getMessage(), e);
			return Collections.emptyList();
		}
	}

	/** Parses a CloudTrail {@code Records} array node into adapter entries (package-visible for tests). */
	static List<AWSLogEntryAdapter> parseRecords(JsonNode records, int maxResults) {
		List<AWSLogEntryAdapter> out = new ArrayList<>();
		if (records == null || !records.isArray()) {
			return out;
		}
		for (JsonNode r : records) {
			if (maxResults > 0 && out.size() >= maxResults) {
				break;
			}
			AWSLogEntryAdapter entry = fromCloudTrailRecord(r);
			if (entry != null) {
				out.add(entry);
			}
		}
		return out;
	}

	/** Maps one CloudTrail record to an {@link AWSLogEntryAdapter}. */
	static AWSLogEntryAdapter fromCloudTrailRecord(JsonNode r) {
		String eventName = text(r, "eventName");
		String eventSource = text(r, "eventSource");
		if (eventName == null || eventSource == null) {
			return null; // not a usable CloudTrail record
		}
		AWSLogEntryAdapter.Builder b = new AWSLogEntryAdapter.Builder()
				.eventId(text(r, "eventID"))
				.eventName(eventName)
				.eventSource(eventSource)
				.eventType(text(r, "eventType"))
				.eventTime(parseInstant(text(r, "eventTime")))
				.awsRegion(text(r, "awsRegion"))
				.sourceIpAddress(text(r, "sourceIPAddress"))
				.userIdentity(flattenUserIdentity(r.path("userIdentity")))
				.userAgent(text(r, "userAgent"))
				.errorCode(text(r, "errorCode"))
				.errorMessage(text(r, "errorMessage"));

		// The analyzer reads resources.resourceName + resources.resourceId. Prefer the
		// container-level CloudTrail resource (shortest ARN - the bucket/instance, not the object);
		// fall back to requestParameters (bucketName / dBInstanceIdentifier / tableName).
		String resourceId = pickContainerArn(r.path("resources"));
		if (resourceId == null) {
			JsonNode rp = r.path("requestParameters");
			resourceId = firstNonBlank(text(rp, "bucketName"), text(rp, "dBInstanceIdentifier"),
					text(rp, "tableName"), text(rp, "instanceId"));
		}
		if (resourceId != null) {
			java.util.Map<String, String> resourceMap = new java.util.HashMap<>();
			resourceMap.put("resourceId", resourceId);
			resourceMap.put("resourceName", lastSegment(resourceId));
			b.resources(resourceMap);
		}
		return b.build();
	}

	private static String pickContainerArn(JsonNode resources) {
		if (resources == null || !resources.isArray() || resources.isEmpty()) {
			return null;
		}
		String best = null;
		for (JsonNode res : resources) {
			String arn = firstNonBlank(text(res, "ARN"), text(res, "arn"), text(res, "resourceName"));
			if (arn == null) {
				continue;
			}
			if (best == null || arn.length() < best.length()) {
				best = arn; // shortest ARN ~ the container (bucket/instance), not the object
			}
		}
		return best;
	}

	private static String flattenUserIdentity(JsonNode ui) {
		if (ui == null || ui.isMissingNode() || ui.isNull()) {
			return null;
		}
		return firstNonBlank(text(ui, "arn"), text(ui, "userName"), text(ui, "principalId"), text(ui, "type"));
	}

	private static String lastSegment(String id) {
		if (id == null) {
			return null;
		}
		int slash = id.lastIndexOf('/');
		int colon = id.lastIndexOf(':');
		int cut = Math.max(slash, colon);
		return cut >= 0 && cut < id.length() - 1 ? id.substring(cut + 1) : id;
	}

	private static Instant parseInstant(String s) {
		if (s == null || s.isBlank()) {
			return Instant.now();
		}
		try {
			return Instant.parse(s);
		} catch (Exception e) {
			return Instant.now();
		}
	}

	private static String text(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode v = node.get(field);
		return v == null || v.isNull() || !v.isValueNode() ? null : v.asText();
	}

	private static String firstNonBlank(String... vals) {
		for (String v : vals) {
			if (v != null && !v.isBlank()) {
				return v;
			}
		}
		return null;
	}
}
