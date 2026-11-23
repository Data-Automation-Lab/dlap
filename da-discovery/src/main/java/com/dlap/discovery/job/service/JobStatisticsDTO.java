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

import java.util.List;

import com.dlap.discovery.common.service.CloudResourceDTO;

/**
 * Data Transfer Object for job statistics.
 * This class is used in the discovery layer to avoid direct dependency on the
 * domain model.
 */
public class JobStatisticsDTO {
	private final int resourcesDiscovered;
	private final int resourcesAdded;
	private final int resourcesSkipped;
	private final int resourcesUpdated;
	private final int resourcesFailed;
	private final List<CloudResourceDTO> resources;

	private JobStatisticsDTO(Builder builder) {
		this.resourcesDiscovered = builder.resourcesDiscovered;
		this.resourcesAdded = builder.resourcesAdded;
		this.resourcesSkipped = builder.resourcesSkipped;
		this.resourcesUpdated = builder.resourcesUpdated;
		this.resourcesFailed = builder.resourcesFailed;
		this.resources = builder.resources;
	}

	public int getResourcesDiscovered() {
		return resourcesDiscovered;
	}

	public int getResourcesAdded() {
		return resourcesAdded;
	}

	public int getResourcesSkipped() {
		return resourcesSkipped;
	}

	public int getResourcesUpdated() {
		return resourcesUpdated;
	}

	public int getResourcesFailed() {
		return resourcesFailed;
	}

	public List<CloudResourceDTO> getResources() {
		return resources;
	}

	public static class Builder {
		private int resourcesDiscovered = 0;
		private int resourcesAdded = 0;
		private int resourcesSkipped = 0;
		private int resourcesUpdated = 0;
		private int resourcesFailed = 0;
		private List<CloudResourceDTO> resources = List.of();

		public Builder resourcesDiscovered(int resourcesDiscovered) {
			this.resourcesDiscovered = resourcesDiscovered;
			return this;
		}

		public Builder resourcesAdded(int resourcesAdded) {
			this.resourcesAdded = resourcesAdded;
			return this;
		}

		public Builder resourcesSkipped(int resourcesSkipped) {
			this.resourcesSkipped = resourcesSkipped;
			return this;
		}

		public Builder resourcesUpdated(int resourcesUpdated) {
			this.resourcesUpdated = resourcesUpdated;
			return this;
		}

		public Builder resourcesFailed(int resourcesFailed) {
			this.resourcesFailed = resourcesFailed;
			return this;
		}

		public Builder resources(List<CloudResourceDTO> resources) {
			this.resources = resources;
			return this;
		}

		public JobStatisticsDTO build() {
			return new JobStatisticsDTO(this);
		}
	}
}