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

package com.dlap.discovery.log.service.azure;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.providers.azure.auth.AzureAuthenticationService;
import com.dlap.discovery.providers.azure.config.AzureConfigService;
import com.dlap.discovery.common.model.ResourceChange;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.event.service.type.LogEvent;
import com.dlap.discovery.log.service.ILogAnalyzer;

/**
 * Implementation of ILogAnalyzer for Azure Monitor and Activity Logs.
 * Uses an event-driven approach to publish changes to Kafka.
 */
@Service("azureLogAnalyzer")
@ConditionalOnProperty(name = "cloud.provider.azure.enabled", havingValue = "true", matchIfMissing = false)
public class AzureLogAnalyzer implements ILogAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(AzureLogAnalyzer.class);
    private static final String PROVIDER_NAME = "azure";

    // Define the supported Azure resource type IDs
    // TODO: Confirm these are the correct IDs used in your application.yml & Azure
    // logs
    private static final Set<String> SUPPORTED_AZURE_TYPE_IDS = Set.of(
            "azure_vm"
    // Add other Azure types if supported by this log analyzer
    // e.g., "azure_storage_account", "azure_blob_container"
    );

    private final AzureAuthenticationService authService;
    private final AzureConfigService configService;
    private final CloudHierarchyRegistry hierarchyRegistry;
    private final KafkaTemplate<String, LogEvent> kafkaTemplate;

    @Value("${kafka.topics.log-events:dlap.log.event.normalized}")
    private String logEventsTopic;

    /**
     * Creates a new AzureLogAnalyzer with the specified services.
     *
     * @param authService       The Azure authentication service
     * @param configService     The Azure configuration service
     * @param hierarchyRegistry The cloud hierarchy registry
     * @param kafkaTemplate     The Kafka template for publishing events
     */
    @Autowired
    public AzureLogAnalyzer(
            @Autowired(required = false) AzureAuthenticationService authService,
            @Autowired(required = false) AzureConfigService configService,
            CloudHierarchyRegistry hierarchyRegistry,
            KafkaTemplate<String, LogEvent> kafkaTemplate) {
        this.authService = authService;
        this.configService = configService;
        this.hierarchyRegistry = hierarchyRegistry;
        this.kafkaTemplate = kafkaTemplate;

        // Log warning if required Azure services are not available
        if (authService == null) {
            log.warn("AzureAuthenticationService is not available. Azure log analysis will be limited.");
        }
        if (configService == null) {
            log.warn("AzureConfigService is not available. Azure log analysis will be limited.");
        }
    }

    @Override
    public void triggerLogAnalysisAsync(String accountId, ZonedDateTime startTime,
            ZonedDateTime endTime, AnalysisOptions options) {
        // Check if required dependencies are available
        if (authService == null || configService == null) {
            log.error("Cannot trigger Azure log analysis - required dependencies are not available");
            return;
        }

        log.info("Triggering async log analysis for Azure subscription {} between {} and {} with options: {}",
                accountId, startTime, endTime, options);

        // Delegate to the async method
        doAnalyzeLogs(accountId, startTime, endTime, options);
    }

    @Async("discoveryAsyncExecutor")
    protected void doAnalyzeLogs(String accountId, ZonedDateTime startTime,
            ZonedDateTime endTime, AnalysisOptions options) {
        // Check if required dependencies are available
        if (authService == null || configService == null) {
            log.error("Cannot perform Azure log analysis - required dependencies are not available");
            return;
        }

        // Azure Monitor / Activity Log integration is NOT implemented. Real-data-or-nothing:
        // an unimplemented provider must never fabricate ResourceChange events (the prior
        // implementation published synthetic "azure-vm-<uuid>" CREATE events onto the catalog
        // spine, violating the "no mock data on production paths" launch rule). Emit nothing
        // and warn loudly until a real LogsQueryClient/Activity Log integration ships.
        log.warn("Azure log analysis is not implemented; analysis for subscription {} between {} and {} "
                + "emits NO events. Enable Azure only for experimental use - it is not production-ready.",
                accountId, startTime, endTime);
    }

    @Override
    public void processLogEventAsync(String accountId, Object logEvent, AnalysisOptions options) {
        // Check if required dependencies are available
        if (authService == null || configService == null) {
            log.error("Cannot process Azure log event - required dependencies are not available");
            return;
        }

        log.info("Processing async log event for Azure subscription {}", accountId);

        // Delegate to the async method
        doProcessLogEvent(accountId, logEvent, options);
    }

    @Async("discoveryAsyncExecutor")
    protected void doProcessLogEvent(String accountId, Object logEvent, AnalysisOptions options) {
        // Check if required dependencies are available
        if (authService == null || configService == null) {
            log.error("Cannot process Azure log event - required dependencies are not available");
            return;
        }

        // Not implemented - see doAnalyzeLogs. Emit nothing rather than fabricate a
        // synthetic "azure-vm-test" ResourceChange. A real implementation will map the
        // incoming Azure EventData (via AzureLoggingAdapter) to a ResourceChange here.
        log.warn("Azure log event processing is not implemented; event for subscription {} emits NO "
                + "events.", accountId);
    }

    @Override
    public boolean supportsResourceType(ResourceType type) {
        if (type == null || type.id() == null) {
            return false;
        }
        // Check if the type's ID is one of the defined supported Azure IDs
        return SUPPORTED_AZURE_TYPE_IDS.contains(type.id());
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public CloudProvider getProvider() {
        return CloudProvider.AZURE;
    }

    @Override
    public boolean hasNewLogs(String accountId, Instant since) {
        // Not implemented. Return false (not true) so no analysis is triggered against the
        // unimplemented Azure integration - returning true would drive the no-op analysis path.
        log.warn("hasNewLogs is not implemented for Azure; returning false (no logs).");
        return false;
    }

    /**
     * Helper method to publish log events to Kafka.
     * 
     * @param accountId The account/subscription ID
     * @param change    The resource change to publish
     */
    private void publishLogEvent(String accountId, ResourceChange change) {
        try {
            LogEvent event = new LogEvent(CloudProvider.AZURE.toString(), accountId, change);
            log.debug("Publishing log event: {}", event);
            kafkaTemplate.send(logEventsTopic, event.getEventId(), event);
            log.info("Published log event for resource: {}, change type: {}",
                    change.getResourceId(), change.getChangeType());
        } catch (Exception e) {
            log.error("Error publishing log event for account {}: {}",
                    accountId, e.getMessage(), e);
        }
    }
}