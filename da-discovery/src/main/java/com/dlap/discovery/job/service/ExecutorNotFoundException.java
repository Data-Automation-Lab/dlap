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

package com.dlap.discovery.job.service;

import com.dlap.discovery.common.exception.DiscoveryException;
import com.dlap.discovery.crawler.exception.ErrorCode;
import com.dlap.discovery.job.ExecutionMode;

/**
 * Exception thrown when no suitable executor is found for a given execution
 * mode.
 */
public class ExecutorNotFoundException extends DiscoveryException {
	private static final long serialVersionUID = 1L;

	public ExecutorNotFoundException(ExecutionMode executionMode) {
		super(ErrorCode.EXECUTOR_NOT_FOUND, String.format("No executor found for execution mode: %s", executionMode));
	}

	public ExecutorNotFoundException(ExecutionMode executionMode, String message) {
		super(ErrorCode.EXECUTOR_NOT_FOUND,
				String.format("No executor found for execution mode %s: %s", executionMode, message));
	}
}