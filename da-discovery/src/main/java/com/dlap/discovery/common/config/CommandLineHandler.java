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

package com.dlap.discovery.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

/**
 * Command line handler that sets a property to indicate whether we're in shell
 * mode or not.
 * This is used by other parts of the application to determine the mode.
 */
@Configuration
public class CommandLineHandler {
	private static final Logger log = LoggerFactory.getLogger(CommandLineHandler.class);

	@Bean
	@Order(0) // Run before any other application runners
	public ApplicationRunner shellModeDetector(Environment env, ConfigurableApplicationContext context) {
		return args -> {
			// Get the command line arguments
			String[] sourceArgs = args.getSourceArgs();

			// If no command line arguments, just run in normal mode
			if (sourceArgs == null || sourceArgs.length == 0) {
				log.info("Running in normal application mode (no arguments)");
				System.setProperty("app.shell.mode", "false");
				return;
			}

			// Detect if first argument is a shell command
			boolean isShellCommand = sourceArgs.length > 0 && !sourceArgs[0].startsWith("-");

			if (isShellCommand) {
				// This is a shell command
				log.info("Running in shell mode: {}", sourceArgs[0]);
				System.setProperty("app.shell.mode", "true");
				System.setProperty("spring.shell.interactive.enabled", "true");
			} else {
				// These are Spring Boot arguments, disable shell to avoid interference
				log.info("Running in normal application mode with arguments");
				System.setProperty("app.shell.mode", "false");

				// The critical fix: set spring.shell.interactive.enabled to false
				// when processing normal Spring Boot arguments
				System.setProperty("spring.shell.interactive.enabled", "false");

				// Make sure NonInteractiveShellRunner doesn't try to process Spring Boot args
				System.setProperty("spring.shell.noninteractive.enabled", "false");
			}
		};
	}
}