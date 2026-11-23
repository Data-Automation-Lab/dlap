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

package com.dlap.discovery.crawler.service.oracle;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.oracle.OracleResource;
import com.dlap.discovery.event.service.type.ResourceEvent;
import com.oracle.bmc.auth.AbstractAuthenticationDetailsProvider;
// Oracle Cloud SDK imports
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.requests.ListInstancesRequest;
import com.oracle.bmc.core.responses.ListInstancesResponse;

/**
 * Discovers Oracle Cloud Infrastructure (OCI) Compute Instances asynchronously
 * and publishes events to Kafka.
 */
@Service
@ConditionalOnProperty(name = "cloud.provider.oci.enabled", havingValue = "true", matchIfMissing = false)
public class OracleComputeInstanceCrawler extends OracleResourceCrawler<OracleResource> {

    private static final Logger log = LoggerFactory.getLogger(OracleComputeInstanceCrawler.class);

    private static final String RESOURCE_TYPE_ID = "OCI_COMPUTE_INSTANCE"; // Updated to match application.yml

    private final CloudHierarchyRegistry hierarchyRegistry;
    private final KafkaTemplate<String, ResourceEvent> kafkaTemplate;

    @Value("${kafka.topics.resource-events:dlap.discovery.asset.discovered}")
    private String resourceEventsTopic;

    @Autowired
    public OracleComputeInstanceCrawler(
            CloudHierarchyRegistry hierarchyRegistry,
            KafkaTemplate<String, ResourceEvent> kafkaTemplate) {
        super(List.of());

        this.hierarchyRegistry = hierarchyRegistry;
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate cannot be null");

        ResourceType resourceType = hierarchyRegistry.getResourceType(RESOURCE_TYPE_ID);
        if (resourceType != null) {
            getSupportedResourceTypes().add(resourceType);
            log.info("OracleComputeInstanceCrawler initialized for type: {}", RESOURCE_TYPE_ID);
        } else {
            log.error("ResourceType record not found for ID: {}. OracleComputeInstanceCrawler may not function.",
                    RESOURCE_TYPE_ID);
        }
    }

    /**
     * Initiates asynchronous discovery of Oracle Compute Instances.
     */
    @Override
    public void discoverResourcesAsync(String accountId, Map<String, Object> context) {
        log.info("Triggering asynchronous Oracle Compute Instance discovery for compartment: {}", accountId);
        performActualDiscovery(accountId, context);
    }

    /**
     * Performs the actual discovery asynchronously.
     */
    @Async("discoveryAsyncExecutor")
    protected void performActualDiscovery(String accountId, Map<String, Object> context) {
        log.info("Starting async Oracle Compute Instance discovery for compartment: {}", accountId);
        int discoveredCount = 0;
        String compartmentId = getCompartmentId(accountId, context);
        if (compartmentId == null)
            return; // Error logged in helper

        ComputeClient computeClient = null;
        try {
            computeClient = createComputeClient();
            String nextPageToken = null;

            ResourceType rt = findSupportedType(RESOURCE_TYPE_ID);
            if (rt == null) {
                log.error("ResourceType record not found for ID: {}. Cannot create OracleResource.", RESOURCE_TYPE_ID);
                return;
            }

            do {
                ListInstancesRequest request = ListInstancesRequest.builder()
                        .compartmentId(compartmentId)
                        .limit(100)
                        .page(nextPageToken)
                        .build();

                log.debug("Listing instances in compartment: {}, page token: {}", compartmentId, nextPageToken);
                ListInstancesResponse response = computeClient.listInstances(request);

                for (Instance instance : response.getItems()) {
                    OracleResource resource = createResourceFromInstance(instance, compartmentId, rt);

                    Map<String, String> stringContext = convertContextToStringMap(context);
                    if (resource != null && matchesFilters(resource, stringContext)) {
                        publishResourceEvent(compartmentId, resource, ChangeType.CREATE);
                        discoveredCount++;
                        log.debug("Published event for Oracle instance: {}", resource.getName());
                    }
                }
                nextPageToken = response.getOpcNextPage();
            } while (nextPageToken != null);

            log.info("Completed async Oracle Compute Instance discovery for compartment {}. Published {} resources.",
                    compartmentId, discoveredCount);

        } catch (Exception e) {
            log.error("Error during async Oracle Compute Instance discovery for compartment {}: {}",
                    compartmentId, e.getMessage(), e);
            // Consider publishing error event
        } finally {
            closeClient(computeClient);
        }
    }

    /**
     * Helper to get compartmentId, prioritizing context, then accountId, then
     * config.
     */
    private String getCompartmentId(String accountId, Map<String, Object> context) {
        String compartmentId = accountId; // Default to accountId
        if (context != null && context.containsKey("compartmentId")) {
            Object ctxCompartmentId = context.get("compartmentId");
            if (ctxCompartmentId != null) {
                compartmentId = ctxCompartmentId.toString();
            }
        }
        if (compartmentId == null || compartmentId.isEmpty()) {
            compartmentId = getConfigService().getCompartmentId();
            log.debug("Using compartment OCID from configuration: {}", compartmentId);
        }
        if (compartmentId == null || compartmentId.isEmpty()) {
            log.error("Oracle Compartment OCID not found in parameters or configuration.");
            return null;
        }
        return compartmentId;
    }

    /**
     * Helper to convert context map.
     */
    private Map<String, String> convertContextToStringMap(Map<String, Object> context) {
        Map<String, String> stringMap = new HashMap<>();
        if (context != null) {
            context.forEach((key, value) -> {
                if (value != null) {
                    stringMap.put(key, value.toString());
                }
            });
        }
        return stringMap;
    }

    /**
     * Helper to safely close the OCI client.
     */
    private void closeClient(ComputeClient client) {
        if (client != null) {
            try {
                client.close();
                log.debug("Oracle ComputeClient closed");
            } catch (Exception e) {
                log.error("Failed to close Oracle ComputeClient: {}", e.getMessage());
            }
        }
    }

    /**
     * Helper to find the ResourceType record based on ID.
     */
    private ResourceType findSupportedType(String typeId) {
        for (ResourceType type : getSupportedResourceTypes()) {
            if (typeId.equals(type.id())) {
                return type;
            }
        }
        log.warn("ResourceType not found in supported types: {}", typeId);
        return null;
    }

    /**
     * Helper method to publish resource events to Kafka.
     */
    private void publishResourceEvent(String compartmentId, OracleResource resource, ChangeType changeType) {
        try {
            ResourceEvent event = new ResourceEvent(
                    CloudProvider.OCI.toString(),
                    compartmentId, // Use compartmentId as the accountId for the event
                    changeType,
                    resource);
            log.debug("Publishing resource event: {}", event);
            kafkaTemplate.send(resourceEventsTopic, resource.getResourceId(), event); // Use OCID as key
        } catch (Exception e) {
            log.error("Error publishing resource event for OCI Instance {}: {}",
                    resource.getResourceId(), e.getMessage(), e);
        }
    }

    /**
     * Creates a ComputeClient using the inherited Oracle authentication service.
     */
    private ComputeClient createComputeClient() {
        try {
            String regionId = getConfigService().getRegion();
            if (regionId == null || regionId.isBlank()) {
                throw new IllegalStateException("Oracle region is not configured in OracleConfigService.");
            }

            Object rawProvider = getAuthService().getAuthenticationDetailsProvider();
            if (!(rawProvider instanceof AbstractAuthenticationDetailsProvider)) {
                throw new IllegalStateException(
                        "AuthenticationDetailsProvider is not of expected type AbstractAuthenticationDetailsProvider. Got: "
                                + (rawProvider != null ? rawProvider.getClass().getName() : "null"));
            }
            AbstractAuthenticationDetailsProvider provider = (AbstractAuthenticationDetailsProvider) rawProvider;

            return ComputeClient.builder()
                    .region(regionId)
                    .build(provider);

        } catch (Exception e) {
            log.error("Failed to create Oracle ComputeClient: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Oracle ComputeClient", e);
        }
    }

    /**
     * Maps an OCI SDK Instance object to our internal OracleResource model.
     */
    private OracleResource createResourceFromInstance(Instance instance, String compartmentId, ResourceType rt) {
        // rt validated by caller

        // Use the public OracleResource constructor
        OracleResource resource = new OracleResource(rt, instance.getId(), instance.getDisplayName());

        // Set common fields (some already set by constructor)
        resource.setAccountId(getConfigService().getTenancyId()); // Set root account ID
        resource.setCompartmentId(compartmentId);
        resource.setRegion(instance.getRegion());
        resource.setLifecycleState(instance.getLifecycleState().getValue());
        if (instance.getTimeCreated() != null) {
            resource.setCreatedAt(instance.getTimeCreated().toInstant());
        }
        resource.setUpdatedAt(
                instance.getTimeCreated() != null ? instance.getTimeCreated().toInstant() : Instant.now()); // Placeholder
        resource.setLastDiscoveredAt(Instant.now());

        // Set tags
        if (instance.getDefinedTags() != null) {
            instance.getDefinedTags().forEach((namespace, tags) -> tags
                    .forEach((key, value) -> resource.addTag(namespace + "/" + key, value.toString())));
        }
        if (instance.getFreeformTags() != null) {
            instance.getFreeformTags().forEach(resource::addTag);
        }
        // Set metadata (using Oracle specific method)
        resource.addOracleMetadata("availabilityDomain", instance.getAvailabilityDomain());
        resource.addOracleMetadata("shape", instance.getShape());
        if (instance.getImageId() != null)
            resource.addOracleMetadata("imageId", instance.getImageId());
        // Add more properties as needed

        return resource;
    }
}