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

import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adapter for the Azure Activity Logs API.
 *
 * <p>Azure log analysis is <strong>experimental / not yet implemented</strong>. Like the
 * AWS adapter (ADR-0017 / R-P0-3), this short-circuits with an empty result + warn log
 * rather than fabricate data. The prior implementation returned up to five hardcoded
 * sample entries ({@code Microsoft.Compute/...} operations with synthetic ARM resource IDs
 * and {@code UUID.randomUUID()} correlation IDs); that mock data was indistinguishable from
 * a real Azure Activity Log response to upstream callers and would flow fabricated assets
 * onto the catalog spine (violating the "no mock data on production paths" launch rule).
 *
 * <p>This adapter is gated behind {@code cloud.provider.azure.enabled} so it is not even
 * instantiated when Azure is off. The real integration (a {@code azure.monitor.query
 * LogsQueryClient} / Activity Log REST call mapping {@code EventData} to
 * {@link AzureLogEntryAdapter}) replaces the warn-and-return body when Azure ships.
 */
@Component
@ConditionalOnProperty(name = "cloud.provider.azure.enabled", havingValue = "true", matchIfMissing = false)
public class AzureLoggingAdapter {

	private static final Logger log = LoggerFactory.getLogger(AzureLoggingAdapter.class);

	public AzureLoggingAdapter() {
		log.warn("Azure log analysis is EXPERIMENTAL and emits NO data: the Azure Monitor / "
				+ "Activity Log integration is not implemented. 'cloud.provider.azure.enabled=true' "
				+ "enables the provider stack but queryLogs() returns an empty list until the real "
				+ "integration ships. Do not treat Azure as production-ready.");
	}

	/**
	 * Queries logs from Azure Activity Logs.
	 *
	 * <p>Returns an empty list. Real-data-or-nothing: an unimplemented provider must never
	 * emit synthetic entries. Real Azure Monitor/Activity Log integration is future work.
	 *
	 * @param subscriptionId The Azure subscription ID
	 * @param filter         The filter criteria for the query
	 * @param maxResults     The maximum number of entries to return
	 * @return Empty list until the real Azure integration ships.
	 */
	public List<AzureLogEntryAdapter> queryLogs(String subscriptionId, String filter, int maxResults) {
		if (subscriptionId == null || subscriptionId.isBlank()) {
			log.error("Subscription ID is required for Azure log analysis");
			return Collections.emptyList();
		}

		log.warn("Azure Activity Log integration is not implemented; queryLogs() called for "
				+ "subscription {} (filter='{}', maxResults={}) returns an empty list.",
				subscriptionId, filter, maxResults);
		return Collections.emptyList();
	}
}
