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

package com.dlap.discovery.crawler.service.azure;

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
import org.springframework.stereotype.Component;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.providers.azure.auth.AzureAuthenticationService;
import com.dlap.discovery.providers.azure.config.AzureConfigService;
import com.dlap.discovery.common.model.DiscoveryJob;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.model.azure.AzureVMResource;
import com.dlap.discovery.event.service.type.ResourceEvent;

/**
 * Crawler for Azure Virtual Machines.
 *
 * <p><b>Not implemented.</b> The Azure Compute integration does not exist yet, so this
 * crawler discovers and publishes <em>nothing</em>. Real-data-or-nothing (ADR-0017): an
 * unimplemented provider must never fabricate resources onto the discovery spine. The
 * prior implementation published three synthetic {@code sample-vm-*} CREATE events per
 * run, which reached {@code dlap_assets} through the crawler→catalog bridge (ADR-0033)
 * and were indistinguishable from real discoveries. It now warns and emits nothing,
 * mirroring {@code com.dlap.discovery.log.service.azure.AzureLogAnalyzer}.
 *
 * <p>The Kafka publish plumbing below is retained and is ready for a real Azure Compute
 * listing implementation.
 */
@Component
@ConditionalOnProperty(name = "cloud.provider.azure.enabled", havingValue = "true", matchIfMissing = false)
public class AzureVirtualMachineCrawler extends AbstractAzureResourceCrawler<AzureVMResource> {
    private static final Logger log = LoggerFactory.getLogger(AzureVirtualMachineCrawler.class);

    // Define the ResourceType ID this crawler supports
    private static final String RESOURCE_TYPE_ID = "azure_vm"; // Example ID, use actual from YAML

    private final AzureConfigService configService;
    private final AzureAuthenticationService authService;
    private final KafkaTemplate<String, ResourceEvent> kafkaTemplate;

    @Value("${kafka.topics.resource-events:dlap.discovery.asset.discovered}")
    private String resourceEventsTopic;

    /**
     * Creates a new Azure Virtual Machine Crawler.
     *
     * @param hierarchyRegistry The registry to look up ResourceType records.
     * @param authService       The Azure authentication service
     * @param configService     The Azure configuration service
     * @param kafkaTemplate     The Kafka template for publishing events
     */
    @Autowired
    public AzureVirtualMachineCrawler(
            CloudHierarchyRegistry hierarchyRegistry,
            AzureAuthenticationService authService,
            AzureConfigService configService,
            KafkaTemplate<String, ResourceEvent> kafkaTemplate) {
        // Call super first with an empty list
        super(List.of());

        // Get resource type from registry and add to supported types
        ResourceType resourceType = hierarchyRegistry.getResourceType(RESOURCE_TYPE_ID);
        if (resourceType != null) {
            getSupportedResourceTypes().add(resourceType);
        }

        // Now initialize other fields
        this.configService = Objects.requireNonNull(configService, "configService cannot be null");
        this.authService = Objects.requireNonNull(authService, "authService cannot be null");
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate cannot be null");
        log.info("AzureVirtualMachineCrawler initialized for type: {}", RESOURCE_TYPE_ID);
    }

    /**
     * Prepare for resource discovery.
     * 
     * @param job The discovery job configuration
     */
    @Override
    public void prepareDiscovery(DiscoveryJob job) {
        log.info("Preparing discovery for Azure VM job: {}", job.getJobId());
        // Specific preparation for VM discovery if needed
    }

    /**
     * Initiate asynchronous discovery of Azure VMs.
     * Publishes discovered resources to Kafka.
     * 
     * @param accountId The Azure subscription ID
     * @param context   The discovery context
     */
    @Override
    public void discoverResourcesAsync(String accountId, Map<String, Object> context) {
        log.info("Triggering asynchronous discovery of Azure VMs for subscription: {}", accountId);
        performActualDiscovery(accountId, context);
    }

    /**
     * Perform the actual discovery of Azure VMs asynchronously.
     *
     * <p>Azure Compute VM listing is NOT implemented. Emits no resources and warns,
     * rather than publishing synthetic {@code sample-vm-*} records that would land in
     * the catalog as real discoveries (ADR-0017 de-fabrication).
     *
     * @param accountId The Azure subscription ID
     * @param context   The discovery context
     */
    @Async("discoveryAsyncExecutor")
    protected void performActualDiscovery(String accountId, Map<String, Object> context) {
        log.warn("Azure VM discovery is not implemented; discovery for subscription {} publishes "
                + "NO resources. Enable Azure VM discovery only for experimental use - it is not "
                + "production-ready.", accountId);
    }

    /**
     * Helper to find the ResourceType record based on ID. Retained for the real Azure
     * Compute implementation; unused while discovery emits nothing.
     */
    private ResourceType findSupportedType(String typeId) {
        for (ResourceType type : getSupportedResourceTypes()) {
            if (typeId.equals(type.id())) {
                return type;
            }
        }
        return null;
    }

    /**
     * Helper method to publish resource events to Kafka. Retained for the real Azure
     * Compute implementation; unused while discovery emits nothing.
     */
    private void publishResourceEvent(String accountId, AzureVMResource resource, ChangeType changeType) {
        try {
            ResourceEvent event = new ResourceEvent(
                    CloudProvider.AZURE.toString(),
                    accountId,
                    changeType,
                    resource);
            log.debug("Publishing resource event: {}", event);
            kafkaTemplate.send(resourceEventsTopic, resource.getResourceId(), event); // Use String resourceId for key
        } catch (Exception e) {
            log.error("Error publishing resource event for VM {}: {}",
                    resource.getName(), e.getMessage(), e);
        }
    }

}