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

package com.dlap.discovery.common.model;

import org.springframework.stereotype.Component;

import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.aws.AwsResource;
import com.dlap.discovery.crawler.model.azure.AzureResource;
import com.dlap.discovery.crawler.model.gcp.GcpResource;
import com.dlap.discovery.crawler.model.oracle.OracleResource;

/**
 * Factory for creating appropriate CloudResource subclasses based on cloud
 * provider.
 */
@Component
public class CloudResourceFactory {

	/**
	 * Creates a new CloudResource instance of the appropriate subclass for the
	 * given cloud provider and resource details.
	 *
	 * @param provider   The cloud provider for which to create a resource.
	 * @param resourceId The provider-specific ID for the resource.
	 * @param serviceId  The service ID (e.g., compute, storage).
	 * @param typeId     The type ID within the service.
	 * @param name       The resource name.
	 * @return A new CloudResource instance of the appropriate subclass.
	 */
	public CloudResource createResource(CloudProvider provider, String resourceId, String serviceId,
			String typeId, String name) {
		if (provider == null) {
			throw new IllegalArgumentException("Cloud provider cannot be null");
		}

		// Create ResourceType instance
		CloudService service = new CloudService(serviceId, serviceId, provider);
		ResourceType resourceType = new ResourceType(typeId, typeId, service);

		switch (provider) {
			case GCP:
				return new GcpResource(resourceType, resourceId, name);
			case AWS:
				return new AwsResource(resourceType, resourceId, name);
			case AZURE:
				return new AzureResource(resourceType, resourceId, name);
			case OCI:
				return new OracleResource(resourceType, resourceId, name);
			default:
				throw new IllegalArgumentException("Unsupported cloud provider: " + provider);
		}
	}
}