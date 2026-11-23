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

package com.dlap.discovery.log.service;

/**
 * Enumeration of error severity levels for Discovery exceptions.
 * These severity levels help categorize exceptions based on their impact
 * and determine appropriate logging and handling behavior.
 */
public enum Severity {
	INFO(0),
	WARNING(1),
	ERROR(2),
	CRITICAL(3);

	private final int level;

	Severity(int level) {
		this.level = level;
	}

	/**
	 * Gets the numeric severity level.
	 * 
	 * @return The severity level as an integer (higher means more severe)
	 */
	public int getLevel() {
		return level;
	}

	/**
	 * Compares the current severity level with another.
	 * 
	 * @param other The other severity level to compare with
	 * @return true if this severity is higher than the other severity
	 */
	public boolean isHigherThan(Severity other) {
		return this.level > other.level;
	}
}