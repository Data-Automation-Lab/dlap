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

package com.dlap.discovery.sd.health;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.dlap.discovery.common.util.health.HealthStatus;
import com.dlap.discovery.common.util.health.IHealthCheckService;

/**
 * Test implementation of IHealthCheckService.
 */
@Service
@Primary
@Profile("test")
public class TestHealthCheckService implements IHealthCheckService {

	private final Map<String, ServiceHealthCheck> healthChecks = new HashMap<>();

	private static class ServiceHealthCheck {
		final String displayName;
		final Supplier<HealthStatus> checker;

		ServiceHealthCheck(String displayName, Supplier<HealthStatus> checker) {
			this.displayName = displayName;
			this.checker = checker;
		}
	}

	@Override
	public boolean isServiceHealthy(String serviceId) {
		HealthStatus status = checkServiceHealth(serviceId);
		return status != null && status.getStatus() == HealthStatus.Status.UP;
	}

	@Override
	public List<HealthStatus> getSystemStatus() {
		List<HealthStatus> statuses = new ArrayList<>();

		for (Map.Entry<String, ServiceHealthCheck> entry : healthChecks.entrySet()) {
			try {
				HealthStatus status = entry.getValue().checker.get();
				if (status != null) {
					statuses.add(status);
				}
			} catch (Exception e) {
				statuses.add(HealthStatus.down(entry.getKey())
						.withDisplayName(entry.getValue().displayName)
						.withMessage("Health check failed: " + e.getMessage()));
			}
		}

		return statuses;
	}

	@Override
	public void registerHealthCheck(String serviceId, String displayName, Supplier<HealthStatus> checker) {
		healthChecks.put(serviceId, new ServiceHealthCheck(displayName, checker));
	}

	@Override
	public HealthStatus checkServiceHealth(String serviceName) {
		ServiceHealthCheck healthCheck = healthChecks.get(serviceName);
		if (healthCheck == null) {
			return HealthStatus.unknown(serviceName)
					.withMessage("No health check registered for service");
		}

		try {
			HealthStatus status = healthCheck.checker.get();
			return status != null ? status : HealthStatus.unknown(serviceName);
		} catch (Exception e) {
			return HealthStatus.down(serviceName)
					.withDisplayName(healthCheck.displayName)
					.withMessage("Health check failed: " + e.getMessage());
		}
	}
}