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

package com.dlap.discovery.catalog.service.impl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dlap.discovery.catalog.service.ICatalogService;
import com.dlap.discovery.common.model.CloudResource;
import com.dlap.discovery.common.model.CloudResourceFactory;
import com.dlap.discovery.common.model.CloudResourceUsageStats;
import com.dlap.discovery.common.model.ResourceChange;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.common.model.repository.CloudResourceRepository;
import com.dlap.discovery.common.model.repository.CloudResourceUsageStatsRepository;
import com.dlap.discovery.common.model.repository.ResourceChangeRepository;

/**
 * Implementation of the consolidated ICatalogService.
 * Handles querying and updating the catalog based on the sd.domain model.
 */
@Service
@Transactional
public class CatalogServiceImpl implements ICatalogService {

	private static final Logger log = LoggerFactory.getLogger(CatalogServiceImpl.class);

	private final CloudResourceRepository cloudResourceRepository;
	private final ResourceChangeRepository resourceChangeRepository;
	private final CloudResourceUsageStatsRepository usageStatsRepository;
	private final CloudResourceFactory cloudResourceFactory;

	@Autowired
	public CatalogServiceImpl(CloudResourceRepository cloudResourceRepository,
			ResourceChangeRepository resourceChangeRepository,
			CloudResourceUsageStatsRepository usageStatsRepository,
			CloudResourceFactory cloudResourceFactory) {
		this.cloudResourceRepository = cloudResourceRepository;
		this.resourceChangeRepository = resourceChangeRepository;
		this.usageStatsRepository = usageStatsRepository;
		this.cloudResourceFactory = cloudResourceFactory;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CloudResource> getResource(UUID resourceDbId) {
		log.debug("Fetching resource by DB ID: {}", resourceDbId);
		return cloudResourceRepository.findById(resourceDbId);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CloudResource> getResourceByKey(String resourceId, CloudProvider provider, String typeId) {
		log.debug("Fetching resource by key: resourceId={}, provider={}, typeId={}", resourceId, provider, typeId);
		return cloudResourceRepository.findByResourceIdAndCloudProviderAndTypeId(resourceId, provider, typeId);
	}

	@Override
	@Transactional(readOnly = true)
	public Page<CloudResource> getResourcesByType(ResourceType type, CloudProvider provider, Pageable pageable) {
		if (type == null || provider == null || type.service() == null) {
			log.warn("ResourceType, Provider, or Service within ResourceType is null, cannot query by type.");
			return Page.empty(pageable);
		}
		log.debug("Fetching resources by type: provider={}, serviceId={}, typeId={}, pageable={}",
				provider, type.service().id(), type.id(), pageable);

		return cloudResourceRepository.findByCloudProviderAndServiceIdAndTypeId(
				provider, type.service().id(), type.id(), pageable);
	}

	@Override
	@Transactional(readOnly = true)
	public Page<CloudResource> getResourcesByAccount(String accountId, Pageable pageable) {
		log.debug("Fetching resources by account: {}, pageable={}", accountId, pageable);

		return cloudResourceRepository.findByAccountId(accountId, pageable);
	}

	@Override
	public void processResourceChanges(List<ResourceChange> changes) {
		if (changes == null || changes.isEmpty()) {
			log.debug("No resource changes to process.");
			return;
		}
		log.info("Processing {} resource change(s)...", changes.size());

		for (ResourceChange change : changes) {
			try {
				processResourceChange(change);
			} catch (Exception e) {
				log.error("Failed to process resource change for resourceId: {} - {}",
						change.getResourceId(), e.getMessage(), e);
			}
		}
		log.info("Finished processing resource changes.");
	}

	@Override
	public void processResourceChange(ResourceChange change) {
		if (change == null) {
			log.debug("No resource change to process.");
			return;
		}

		try {
			resourceChangeRepository.save(change);
			Optional<CloudResource> existingResourceOpt = cloudResourceRepository
					.findByResourceIdAndCloudProviderAndTypeId(
							change.getResourceId(),
							change.getCloudProvider(),
							change.getTypeId());

			switch (change.getChangeType()) {
				case CREATE:
					handleCreate(change, existingResourceOpt);
					break;
				case UPDATE:
					handleUpdate(change, existingResourceOpt);
					break;
				case DELETE:
					handleDelete(change, existingResourceOpt);
					break;
				case ACCESS:
					handleAccess(change, existingResourceOpt);
					break;
				case PERMISSION:
					handlePermission(change, existingResourceOpt);
					break;
				default:
					handleUnknown(change, existingResourceOpt);
					break;
			}
		} catch (Exception e) {
			log.error("Failed to process resource change for resourceId: {} - {}",
					change.getResourceId(), e.getMessage(), e);
			throw e; // Re-throw to be consistent with interface expectations
		}
	}

	@Override
	@Transactional(readOnly = true)
	public Page<ResourceChange> getResourceChanges(UUID resourceDbId, Pageable pageable) {
		log.debug("Fetching changes for resource DB ID: {}, pageable={}", resourceDbId, pageable);

		// Get the resource first to determine its business key
		return cloudResourceRepository.findById(resourceDbId)
				.map(resource -> resourceChangeRepository.findByResourceIdOrderByTimestampDesc(
						resource.getResourceId(), pageable))
				.orElse(Page.empty(pageable));
	}

	@Override
	@Transactional(readOnly = true)
	public Page<ResourceChange> getChangesByTimeRange(Instant start, Instant end, Pageable pageable) {
		log.debug("Fetching changes between {} and {}, pageable={}", start, end, pageable);

		return resourceChangeRepository.findByTimestampBetweenOrderByTimestampDesc(start, end, pageable);
	}

	@Override
	public Optional<CloudResource> updateResourceTags(UUID resourceDbId, Map<String, String> tags) {
		log.info("Updating tags for resource DB ID: {}", resourceDbId);
		return cloudResourceRepository.findById(resourceDbId).map(resource -> {
			resource.setTags(tags);
			resource.setUpdatedAt(Instant.now());
			// Track change? Maybe handled by other flows.
			return cloudResourceRepository.save(resource);
		});
	}

	@Override
	public Optional<CloudResource> updateTechnicalMetadata(UUID resourceDbId, Map<String, String> metadata) {
		log.info("Updating technical metadata for resource DB ID: {}", resourceDbId);
		return cloudResourceRepository.findById(resourceDbId).map(resource -> {
			resource.setTechnicalMetadata(metadata);
			resource.setUpdatedAt(Instant.now());
			return cloudResourceRepository.save(resource);
		});
	}

	@Override
	public Optional<CloudResource> updateBusinessMetadata(UUID resourceDbId, Map<String, String> metadata) {
		log.info("Updating business metadata for resource DB ID: {}", resourceDbId);
		return cloudResourceRepository.findById(resourceDbId).map(resource -> {
			resource.setBusinessMetadata(metadata);
			resource.setUpdatedAt(Instant.now());
			return cloudResourceRepository.save(resource);
		});
	}

	@Override
	public void updateResources(List<CloudResource> resources) {
		if (resources == null || resources.isEmpty()) {
			log.debug("No resources to update.");
			return;
		}
		
		log.info("Updating {} resources in catalog...", resources.size());
		
		for (CloudResource resource : resources) {
			try {
				// Check if resource already exists
				Optional<CloudResource> existingResource = cloudResourceRepository
						.findByResourceIdAndCloudProviderAndTypeId(
								resource.getResourceId(),
								resource.getCloudProviderEnum(),
								resource.getTypeId());
				
				if (existingResource.isPresent()) {
					CloudResource current = existingResource.get();
					
					// Update fields that should be preserved
					current.setName(resource.getName());
					current.setRegion(resource.getRegion());
					current.setLocation(resource.getLocation());
					current.setParentId(resource.getParentId());
					current.setUri(resource.getUri());
					current.setDescription(resource.getDescription());
					current.setTags(resource.getTags());
					current.setTechnicalMetadata(resource.getTechnicalMetadata());
					current.setBusinessMetadata(resource.getBusinessMetadata());
					current.setLastDiscoveredAt(Instant.now());
					current.setUpdatedAt(Instant.now());
					
					cloudResourceRepository.save(current);
					log.debug("Updated existing resource: {} ({})", current.getId(), current.getResourceId());
				} else {
					// createdAt is the provider's creation time, as the crawler read it (ADR-0046, R6).
					// It is left as it arrived: when the crawler did not know it, it stays absent and
					// is never replaced by the processing time. Only the module's own stamps are set.
					resource.setUpdatedAt(Instant.now());
					resource.setLastDiscoveredAt(Instant.now());
					
					cloudResourceRepository.save(resource);
					log.debug("Added new resource: {}", resource.getResourceId());
				}
			} catch (Exception e) {
				log.error("Error updating resource {}: {}", resource.getResourceId(), e.getMessage(), e);
			}
		}
		
		log.info("Finished updating resources in catalog.");
	}

	private void handleCreate(ResourceChange change, Optional<CloudResource> existingResourceOpt) {
		if (existingResourceOpt.isPresent()) {
			log.warn("CREATE event for existing resource: {}. Treating as UPDATE.", change.getResourceId());
			handleUpdate(change, existingResourceOpt);
		} else {
			log.info("Processing CREATE for resource: {}", change.getResourceId());

			// Create appropriate CloudResource subclass using factory
			CloudResource newResource = cloudResourceFactory.createResource(
					change.getCloudProvider(),
					change.getResourceId(),
					change.getServiceId(),
					change.getTypeId(),
					change.getDetails().getOrDefault(
							change.getCloudProvider().name().toLowerCase() + ".resourceNameShort",
							change.getResourceId()));

			if (newResource == null) {
				log.error("Failed to create resource instance for provider: {}", change.getCloudProvider());
				return;
			}

			// Set Account ID (example assumes GCP uses projectId, generalize for others)
			if (change.getCloudProvider() == CloudProvider.GCP) {
				newResource.setAccountId(change.getProjectId());
			} else {
				// Extract account ID for other providers from change details or context
				newResource.setAccountId(change.getDetails().get("accountId")); // Example placeholder
			}

			newResource.setCreatedAt(change.getTimestamp());
			newResource.setUpdatedAt(change.getTimestamp());
			newResource.setLastDiscoveredAt(Instant.now());

			// Extract region etc. from details map
			newResource.setRegion(
					change.getDetails().get(change.getCloudProvider().name().toLowerCase() + ".request.region"));
			newResource.setLocation(
					change.getDetails().get(change.getCloudProvider().name().toLowerCase() + ".request.location"));
			// ... populate other common fields like description, uri, parentId if available
			// in details

			mapMetadata(newResource, change.getDetails());

			// Create and link initial usage stats
			Instant windowStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
			Instant windowEnd = windowStart.plus(30, ChronoUnit.DAYS); // Example: 30 day window
			CloudResourceUsageStats stats = new CloudResourceUsageStats(newResource, windowStart, windowEnd);
			stats.setLastAccessedAt(change.getTimestamp());
			stats.incrementEditCount(); // Treat creation as 1 edit/write
			newResource.setUsageStats(stats); // Link stats via the setter

			cloudResourceRepository.save(newResource); // Cascade should save stats
			log.info("Created resource: {} ({})", newResource.getId(), newResource.getResourceId());
		}
	}

	private void handleUpdate(ResourceChange change, Optional<CloudResource> existingResourceOpt) {
		existingResourceOpt.ifPresentOrElse(resource -> {
			log.info("Processing UPDATE for resource: {} ({})", resource.getId(), resource.getResourceId());
			resource.setUpdatedAt(change.getTimestamp());
			resource.setLastDiscoveredAt(Instant.now());
			// Optionally update name, region, etc. based on details
			String nameKey = change.getCloudProvider().name().toLowerCase() + ".resourceNameShort";
			if (change.getDetails().containsKey(nameKey)) {
				resource.setName(change.getDetails().get(nameKey));
			}
			// ... other field updates
			mapMetadata(resource, change.getDetails());
			updateUsageStats(resource, ResourceChange.ChangeType.UPDATE, change.getTimestamp());
			cloudResourceRepository.save(resource);
			log.debug("Updated resource: {} ({})", resource.getId(), resource.getResourceId());
		}, () -> log.warn("UPDATE event for non-existent resource: {}. Ignoring.", change.getResourceId()));
	}

	private void handleDelete(ResourceChange change, Optional<CloudResource> existingResourceOpt) {
		existingResourceOpt.ifPresentOrElse(resource -> {
			UUID resourceDbId = resource.getId();
			String resourceId = resource.getResourceId();
			log.info("Processing DELETE for resource: {} ({})", resourceDbId, resourceId);
			// Delete associated stats first (if relation isn't cascading delete properly)
			CloudResourceUsageStats stats = resource.getUsageStats();
			if (stats != null) {
				try {
					usageStatsRepository.deleteById(stats.getId());
				} catch (Exception e) {
					log.error("Error deleting usage stats for resource {}: {}", resourceDbId, e.getMessage());
				}
			}
			cloudResourceRepository.delete(resource);
			log.info("Deleted resource: {} ({})", resourceDbId, resourceId);
		}, () -> log.warn("DELETE event for non-existent resource: {}. Ignoring.", change.getResourceId()));
	}

	private void handleAccess(ResourceChange change, Optional<CloudResource> existingResourceOpt) {
		existingResourceOpt.ifPresentOrElse(resource -> {
			log.info("Processing ACCESS for resource: {} ({})", resource.getId(), resource.getResourceId());
			updateUsageStats(resource, ResourceChange.ChangeType.ACCESS, change.getTimestamp());
		}, () -> log.warn("ACCESS event for non-existent resource: {}. Ignoring.", change.getResourceId()));
	}

	private void handlePermission(ResourceChange change, Optional<CloudResource> existingResourceOpt) {
		existingResourceOpt.ifPresentOrElse(resource -> {
			log.info("Processing PERMISSION for resource: {} ({})", resource.getId(), resource.getResourceId());
			resource.setUpdatedAt(change.getTimestamp());
			resource.setLastDiscoveredAt(Instant.now());
			mapMetadata(resource, change.getDetails());
			updateUsageStats(resource, ResourceChange.ChangeType.PERMISSION, change.getTimestamp()); // Count as edit
			cloudResourceRepository.save(resource);
			log.debug("Updated resource due to PERMISSION change: {} ({})", resource.getId(), resource.getResourceId());
		}, () -> log.warn("PERMISSION event for non-existent resource: {}. Ignoring.", change.getResourceId()));
	}

	private void handleUnknown(ResourceChange change, Optional<CloudResource> existingResourceOpt) {
		existingResourceOpt.ifPresentOrElse(resource -> {
			log.info("Processing UNKNOWN change for resource: {} ({}) as potential update.", resource.getId(),
					resource.getResourceId());
			resource.setUpdatedAt(change.getTimestamp());
			resource.setLastDiscoveredAt(Instant.now());
			mapMetadata(resource, change.getDetails());
			cloudResourceRepository.save(resource);
			// Optionally update usage stats?
		}, () -> log.warn("UNKNOWN event for non-existent resource: {}. Ignoring.", change.getResourceId()));
	}

	private void updateUsageStats(CloudResource resource, ResourceChange.ChangeType changeType, Instant eventTime) {
		CloudResourceUsageStats stats = resource.getUsageStats();
		boolean needsSave = false;

		if (stats == null) {
			log.warn("Usage stats not found for resource {}, creating new.", resource.getId());
			Instant windowStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
			Instant windowEnd = windowStart.plus(30, ChronoUnit.DAYS);
			stats = new CloudResourceUsageStats(resource, windowStart, windowEnd);
			resource.setUsageStats(stats);
			// Rely on cascade for initial save
		} else {
			needsSave = true; // Existing stats need explicit save

			// Check if we need to roll over the stats window
			if (eventTime.isAfter(stats.getTimeWindowEnd())) {
				log.info("Rolling over usage stats window for resource {}", resource.getId());

				// Create a new window starting from the end of the previous window
				Instant newWindowStart = stats.getTimeWindowEnd();
				Instant newWindowEnd = newWindowStart.plus(30, ChronoUnit.DAYS);

				// Reset window counters but preserve total cumulative counts
				long totalReadCount = stats.getTotalReadCount();
				long totalWriteCount = stats.getTotalWriteCount();
				long totalEditCount = stats.getTotalEditCount();

				// Create new stats with the new window
				stats = new CloudResourceUsageStats(resource, newWindowStart, newWindowEnd);

				// Preserve the cumulative totals
				stats.setTotalReadCount(totalReadCount);
				stats.setTotalWriteCount(totalWriteCount);
				stats.setTotalEditCount(totalEditCount);

				// Link to resource
				resource.setUsageStats(stats);
			}
		}

		stats.setLastAccessedAt(eventTime);

		switch (changeType) {
			case ACCESS:
				stats.incrementReadCount();
				break;
			case UPDATE:
			case PERMISSION: // Treat permission change as an edit
				stats.incrementEditCount();
				break;
			default:
				break; // CREATE/DELETE handled elsewhere
		}

		if (needsSave) {
			try {
				usageStatsRepository.save(stats);
				log.debug("Updated usage stats for resource: {}", resource.getId());
			} catch (Exception e) {
				log.error("Failed to save usage stats for resource {}: {}", resource.getId(), e.getMessage());
			}
		}
	}

	private void mapMetadata(CloudResource resource, Map<String, String> details) {
		if (details == null || details.isEmpty())
			return;
		Map<String, String> techMetadata = resource.getTechnicalMetadata();
		Map<String, String> bizMetadata = resource.getBusinessMetadata();

		details.forEach((key, value) -> {
			if (value == null || value.isBlank())
				return;
			String lowerCaseKey = key.toLowerCase(); // Use lowercase for easier matching
			String providerPrefix = resource.getCloudProviderEnum().name().toLowerCase() + ".";

			// --- Business Metadata Rules ---
			if (lowerCaseKey.equals(providerPrefix + "actoremail")
					|| lowerCaseKey.equals(providerPrefix + "authenticationinfo.principalemail")) {
				bizMetadata.put("owner", value);
			} else if (lowerCaseKey.startsWith(providerPrefix + "resourcelabel.cost-center")) {
				bizMetadata.put("costCenter", value);
			} else if (lowerCaseKey.startsWith(providerPrefix + "resourcelabel.environment")) {
				bizMetadata.put("environment", value);
			} else if (lowerCaseKey.startsWith(providerPrefix + "resourcelabel.application")) {
				bizMetadata.put("applicationName", value);
			} else if (lowerCaseKey.startsWith(providerPrefix + "request.label.owner")
					|| lowerCaseKey.startsWith(providerPrefix + "request.label.team")) {
				bizMetadata.put(key.substring((providerPrefix + "request.label.").length()), value);
			}
			// Add more business rules...

			// --- Technical Metadata Rules ---
			else if (lowerCaseKey.startsWith(providerPrefix + "resourcelabel.")) {
				techMetadata.put(key.substring((providerPrefix + "resourcelabel.").length()), value);
			} else if (lowerCaseKey.startsWith(providerPrefix + "request.label.")) { // Other request labels
				techMetadata.put(key.substring((providerPrefix + "request.label.").length()), value);
			} else if (lowerCaseKey.startsWith(providerPrefix + "loglabel.")) {
				techMetadata.put(key.substring((providerPrefix + "loglabel.").length()), value);
			} else if (lowerCaseKey.startsWith(providerPrefix + "request.")) {
				String requestKey = key.substring((providerPrefix + "request.").length());
				if (requestKey.equals("machineType") || requestKey.equals("zone") ||
						requestKey.equals("region") || requestKey.equals("location") ||
						requestKey.equals("storageClass") || requestKey.equals("datasetId")) {
					techMetadata.put(requestKey, value);
				}
			} else if (lowerCaseKey.startsWith(providerPrefix + "response.")) {
				String responseKey = key.substring((providerPrefix + "response.").length());
				if (responseKey.equals("selfLink") || responseKey.equals("id")) {
					techMetadata.put(responseKey, value);
				}
			} else if (lowerCaseKey.equals(providerPrefix + "resourcetype")
					|| lowerCaseKey.equals(providerPrefix + "sourceip")
					|| lowerCaseKey.equals(providerPrefix + "useragent")) {
				techMetadata.put(key.substring(providerPrefix.length()), value);
			}
		});
		log.trace("Mapped metadata for resource: {}", resource.getId());
		// Maps are modified directly on the managed entity
	}
}