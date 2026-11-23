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

package com.dlap.discovery.log.service.gcp.web.dto;

import java.util.HashMap;
import java.util.Map;

/**
 * DTO for transferring GCP log source details in REST API requests/responses.
 */
public class SourceDetailsDto {

	private Map<String, String> details = new HashMap<>();

	/**
	 * Default constructor.
	 */
	public SourceDetailsDto() {
	}

	/**
	 * Constructor with details map.
	 * 
	 * @param details The source details map
	 */
	public SourceDetailsDto(Map<String, String> details) {
		if (details != null) {
			this.details = details;
		}
	}

	/**
	 * Gets the source details map.
	 * 
	 * @return The source details map
	 */
	public Map<String, String> getDetails() {
		return details;
	}

	/**
	 * Sets the source details map.
	 * 
	 * @param details The source details map
	 */
	public void setDetails(Map<String, String> details) {
		this.details = details != null ? details : new HashMap<>();
	}
}