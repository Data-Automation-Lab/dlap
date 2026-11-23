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

import java.io.IOException;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;

/**
 * Configuration for Spring Shell terminal support.
 * Provides a fallback terminal when running in environments where
 * a proper terminal cannot be detected automatically.
 */
@Configuration
@Lazy // Only create when needed
public class ShellTerminalConfig {

	/**
	 * Creates a dumb terminal that works in any environment.
	 * This helps Spring Shell work properly in containers and CI environments.
	 *
	 * @return A simple terminal instance that works in any environment
	 * @throws IOException If terminal creation fails
	 */
	@Bean
	@Primary
	public Terminal terminal() throws IOException {
		// Don't force interactive mode here - let CommandLineHandler control that
		return TerminalBuilder.builder()
				.dumb(true) // Use dumb terminal that works anywhere
				.jansi(true) // Enable ANSI color support
				.system(false) // Don't try to use system streams
				.build();
	}
}  