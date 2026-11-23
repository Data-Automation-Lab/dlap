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

package com.dlap.discovery.client.cli;

import java.util.Collections;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API that exposes Spring Shell commands for remote access.
 */
@RestController
@RequestMapping("/api/shell")
public class ShellController {

	@Value("${spring.shell.interactive.enabled:true}")
	private boolean shellEnabled;

	private final DiscoveryCommands discoveryCommands;
	private final ApplicationCommands applicationCommands;

	@Autowired
	public ShellController(
			DiscoveryCommands discoveryCommands,
			ApplicationCommands applicationCommands) {
		this.discoveryCommands = discoveryCommands;
		this.applicationCommands = applicationCommands;
	}

	@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
	@GetMapping("/status")
	public Map<String, Object> getStatus() {
		if (discoveryCommands == null || !shellEnabled) {
			return Collections.singletonMap("status", "Shell commands are not available");
		}
		return Map.of(
				"status", "Shell commands are available",
				"enabled", shellEnabled);
	}

	@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
	@GetMapping("/app-status")
	public String appStatus() {
		if (applicationCommands == null || !shellEnabled) {
			return "Shell commands are not available";
		}
		return applicationCommands.appStatus();
	}

	@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
	@GetMapping("/profiles")
	public String profiles() {
		if (applicationCommands == null || !shellEnabled) {
			return "Shell commands are not available";
		}
		return applicationCommands.profiles();
	}

	@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
	@GetMapping("/list-crawlers")
	public String listCrawlers() {
		if (discoveryCommands == null || !shellEnabled) {
			return "Shell commands are not available";
		}
		return discoveryCommands.listCrawlers();
	}

	@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
	@PostMapping("/create-job")
	public String createJob(
			@RequestParam String provider,
			@RequestParam String accountId,
			@RequestParam(required = false, defaultValue = "") String jobName,
			@RequestParam(required = false, defaultValue = "") String resourceIds) {
		if (discoveryCommands == null || !shellEnabled) {
			return "Shell commands are not available";
		}
		return discoveryCommands.createJob(provider, accountId, jobName, resourceIds);
	}

	@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
	@PostMapping("/exec-job/{jobId}")
	public String executeJob(@PathVariable String jobId) {
		if (discoveryCommands == null || !shellEnabled) {
			return "Shell commands are not available";
		}
		return discoveryCommands.executeJob(jobId);
	}

	@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
	@GetMapping("/get-job/{jobId}")
	public String getJob(@PathVariable String jobId) {
		if (discoveryCommands == null || !shellEnabled) {
			return "Shell commands are not available";
		}
		return discoveryCommands.getJob(jobId);
	}

	@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_POLICY_MANAGER', 'ROLE_DIRECTOR')")
	@GetMapping("/list-jobs")
	public String listJobs(
			@RequestParam(required = false, defaultValue = "") String provider,
			@RequestParam(required = false, defaultValue = "") String status) {
		if (discoveryCommands == null || !shellEnabled) {
			return "Shell commands are not available";
		}
		return discoveryCommands.listJobs(provider, status);
	}
}