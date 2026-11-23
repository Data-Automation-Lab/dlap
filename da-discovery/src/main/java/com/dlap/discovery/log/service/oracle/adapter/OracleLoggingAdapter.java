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

import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adapter for the Oracle Cloud (OCI) Audit API.
 *
 * <p>OCI log analysis is <strong>experimental / not implemented</strong> and OCI is not on
 * the v0.2.0/v0.3.0 roadmap (ADR-0026). Like the AWS adapter (ADR-0017 / R-P0-3), this
 * short-circuits with an empty result + warn log rather than fabricate data. The prior
 * implementation returned up to five hardcoded sample entries
 * ({@code CreateInstance}/{@code DeleteBucket}/… with synthetic OCIDs minted from
 * {@code UUID.randomUUID()}); that mock data was indistinguishable from a real OCI Audit
 * response to upstream callers and would flow fabricated assets onto the catalog spine
 * (violating the "no mock data on production paths" launch rule).
 *
 * <p>Gated by {@code cloud.provider.oci.enabled=true}; off by default. The real integration
 * (a {@code com.oracle.bmc.audit.AuditClient.listEvents} call mapping {@code AuditEvent}
 * to {@link OracleLogEntryAdapter}) replaces the warn-and-return body if/when OCI ships.
 */
@Component
@ConditionalOnProperty(name = "cloud.provider.oci.enabled", havingValue = "true", matchIfMissing = false)
public class OracleLoggingAdapter {

	private static final Logger log = LoggerFactory.getLogger(OracleLoggingAdapter.class);

	public OracleLoggingAdapter() {
		log.warn("OCI log analysis is EXPERIMENTAL and emits NO data (ADR-0026): the OCI Audit "
				+ "integration is not implemented. 'cloud.provider.oci.enabled=true' enables the "
				+ "provider stack but queryLogs() returns an empty list. Do not treat OCI as "
				+ "production-ready.");
	}

	/**
	 * Queries logs from Oracle Cloud Audit.
	 *
	 * <p>Returns an empty list. Real-data-or-nothing: an unimplemented provider must never
	 * emit synthetic entries. Real OCI Audit integration is future work.
	 *
	 * @param tenancyId  The Oracle Cloud tenancy ID
	 * @param filter     The filter criteria for the query
	 * @param maxResults The maximum number of entries to return
	 * @return Empty list until the real OCI integration ships.
	 */
	public List<OracleLogEntryAdapter> queryLogs(String tenancyId, String filter, int maxResults) {
		if (tenancyId == null || tenancyId.isBlank()) {
			log.error("Tenancy ID is required for Oracle Cloud log analysis");
			return Collections.emptyList();
		}

		log.warn("OCI Audit integration is not implemented; queryLogs() called for tenancy {} "
				+ "(filter='{}', maxResults={}) returns an empty list.", tenancyId, filter, maxResults);
		return Collections.emptyList();
	}
}
