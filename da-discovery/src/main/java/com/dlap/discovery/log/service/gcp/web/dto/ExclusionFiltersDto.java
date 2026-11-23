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

import java.util.ArrayList;
import java.util.List;

/**
 * DTO for transferring GCP log exclusion filters in REST API
 * requests/responses.
 */
public class ExclusionFiltersDto {

	private List<String> filters = new ArrayList<>();

	/**
	 * Default constructor.
	 */
	public ExclusionFiltersDto() {
	}

	/**
	 * Constructor with filters list.
	 * 
	 * @param filters The exclusion filters list
	 */
	public ExclusionFiltersDto(List<String> filters) {
		if (filters != null) {
			this.filters = filters;
		}
	}

	/**
	 * Gets the exclusion filters list.
	 * 
	 * @return The exclusion filters list
	 */
	public List<String> getFilters() {
		return filters;
	}

	/**
	 * Sets the exclusion filters list.
	 * 
	 * @param filters The exclusion filters list
	 */
	public void setFilters(List<String> filters) {
		this.filters = filters != null ? filters : new ArrayList<>();
	}
}