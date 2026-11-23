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

import java.util.Arrays;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.core.env.Environment;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.stereotype.Component;

/**
 * Basic application commands available in Spring Shell.
 */
@Component
public class ApplicationCommands {

	private final Environment environment;
	private final Optional<BuildProperties> buildProperties;
	private final Optional<GitProperties> gitProperties;

	@Autowired
	public ApplicationCommands(
			Environment environment,
			Optional<BuildProperties> buildProperties,
			Optional<GitProperties> gitProperties) {
		this.environment = environment;
		this.buildProperties = buildProperties;
		this.gitProperties = gitProperties;
	}

	/**
	 * Display application status and configuration information.
	 * 
	 * @return Application status string
	 */
	@Command(name = "app-status", description = "Show application status and configuration")
	public String appStatus() {
		StringBuilder status = new StringBuilder();
		status.append("=== Application Status ===\n");

		buildProperties.ifPresent(props -> {
			status.append("Name: ").append(props.getName()).append("\n");
			status.append("Version: ").append(props.getVersion()).append("\n");
			status.append("Build Time: ").append(props.getTime()).append("\n");
		});

		status.append("Active Profiles: ").append(Arrays.toString(environment.getActiveProfiles())).append("\n");

		gitProperties.ifPresent(props -> {
			status.append("Git Branch: ").append(props.getBranch()).append("\n");
			status.append("Git Commit: ").append(props.getShortCommitId()).append("\n");
		});

		status.append("Shell Mode: ").append(System.getProperty("app.shell.mode", "false")).append("\n");

		return status.toString();
	}

	/**
	 * Show active Spring profiles.
	 * 
	 * @return Active profiles string
	 */
	@Command(name = "profiles", description = "Show active Spring profiles")
	public String profiles() {
		return "Active Profiles: " + Arrays.toString(environment.getActiveProfiles());
	}
}