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

package com.dlap.discovery.client.rest.errors;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ControllerAdvice;

/**
 * This class is a controller advice for translating exceptions.
 * It extends CrawlerExceptionTranslator to ensure backward compatibility
 * while providing the @ControllerAdvice annotation expected by tests.
 */
@ControllerAdvice
@Component
public class ExceptionTranslator extends CrawlerExceptionTranslator {

	// Store our own reference to the environment
	private final Environment environment;

	/**
	 * Constructs the exception translator with the given environment.
	 * 
	 * @param env The Spring environment
	 */
	public ExceptionTranslator(Environment env) {
		super(env);
		this.environment = env;
	}

	/**
	 * Gets the environment instance.
	 * Useful for testing.
	 * 
	 * @return The environment
	 */
	public Environment getEnvironment() {
		return this.environment;
	}

	/**
	 * Returns a string representation of this translator.
	 * 
	 * @return A string identifying this translator
	 */
	@Override
	public String toString() {
		return "ExceptionTranslator [environment=" +
				(environment != null ? "configured" : "null") +
				"]";
	}
}