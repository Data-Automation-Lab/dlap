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

package com.dlap.discovery.crawler.service.aws;

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
import org.springframework.stereotype.Component;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.providers.aws.config.AWSConfigService;
import com.dlap.discovery.common.constants.AWSConstants;
import com.dlap.discovery.common.constants.DiscoveryConstants;
import com.dlap.discovery.common.model.ResourceChange.ChangeType;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.providers.aws.service.AWSFilterBuilder;
import com.dlap.discovery.crawler.model.aws.EC2Resource;
import com.dlap.discovery.event.service.type.ResourceEvent;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.Reservation;

@Component
@ConditionalOnProperty(name = "cloud.provider.aws.enabled", havingValue = "true", matchIfMissing = false)
public class EC2InstanceCrawler extends AWSResourceCrawler<EC2Resource> {

    private static final Logger log = LoggerFactory.getLogger(EC2InstanceCrawler.class);

    private static final String RESOURCE_TYPE_ID = AWSConstants.RESOURCE_TYPE_EC2_INSTANCE;

    private final AWSConfigService configService;
    private final CloudHierarchyRegistry hierarchyRegistry;
    private final KafkaTemplate<String, ResourceEvent> kafkaTemplate;

    @Value("${kafka.topics.resource-events:dlap.discovery.asset.discovered}")
    private String resourceEventsTopic;

    @Autowired
    public EC2InstanceCrawler(
            CloudHierarchyRegistry hierarchyRegistry,
            AWSConfigService configService,
            KafkaTemplate<String, ResourceEvent> kafkaTemplate) {
        super(List.of()); // Initialize with empty list

        this.hierarchyRegistry = hierarchyRegistry;
        this.configService = Objects.requireNonNull(configService, "configService cannot be null");
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate cannot be null");

        // Set supported types after registry is available
        ResourceType resourceType = lookupEc2Type();
        if (resourceType != null) {
            getSupportedResourceTypes().add(resourceType);
            log.info("EC2InstanceCrawler initialized for type: {}", resourceType.id());
        } else {
            log.error("ResourceType record not found in registry for ID: {} or {}. EC2InstanceCrawler may not function.",
                    RESOURCE_TYPE_ID, EC2Resource.HIERARCHY_TYPE_ID);
        }
    }

    /**
     * The EC2 instance type as the cloud-hierarchy registry declares it. The shipped configuration
     * (application.yml, cloud-hierarchy) names it {@code EC2_INSTANCE}, while this crawler's
     * constant is {@code aws_ec2_instance}; asking for the constant alone found nothing, so the
     * crawler registered no type and never produced a resource. Both ids are accepted, the
     * constant first.
     */
    private ResourceType lookupEc2Type() {
        ResourceType byConstant = hierarchyRegistry.getResourceType(RESOURCE_TYPE_ID);
        return byConstant != null ? byConstant : hierarchyRegistry.getResourceType(EC2Resource.HIERARCHY_TYPE_ID);
    }

    // This is the required abstract method implementation from parent class
    @Override
    public void discoverResourcesAsync(String accountId, Map<String, Object> context) {
        log.info("Triggering asynchronous discovery of EC2 instances for account: {}", accountId);
        performActualDiscovery(accountId, context);
    }

    @Async("discoveryAsyncExecutor")
    protected void performActualDiscovery(String accountId, Map<String, Object> context) {
        Map<String, Object> discoveryContext = (context != null) ? context : new HashMap<>();
        String regionString = configService.getRegion(); // Use configured region
        log.info(DiscoveryConstants.LOG_DISCOVERY_STARTING, "EC2", regionString, accountId);

        int discoveredCount = 0;
        Ec2Client ec2Client = null;
        try {
            Region region = Region.of(regionString);
            ec2Client = Ec2Client.builder()
                    .region(region)
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .build();
            log.info(DiscoveryConstants.LOG_CLIENT_INITIALIZED, "EC2", regionString);

            Map<String, String> stringContext = convertContextToStringMap(discoveryContext);
            List<Filter> filters = buildFilters(stringContext);

            String nextToken = null;
            do {
                DescribeInstancesRequest.Builder requestBuilder = DescribeInstancesRequest.builder()
                        .nextToken(nextToken);
                if (!filters.isEmpty()) {
                    requestBuilder.filters(filters);
                }
                DescribeInstancesResponse response = ec2Client.describeInstances(requestBuilder.build());

                for (Reservation reservation : response.reservations()) {
                    for (Instance instance : reservation.instances()) {
                        EC2Resource resource = mapInstanceToEC2Resource(instance, reservation, regionString,
                                findSupportedType());
                        if (resource != null && matchesFilters(resource, stringContext)) {
                            publishResourceEvent(accountId, resource, ChangeType.CREATE);
                            discoveredCount++;
                            log.debug("Published event for discovered EC2 instance: {}", resource.getResourceId());
                        }
                    }
                }
                nextToken = response.nextToken();
            } while (nextToken != null);

            log.info(DiscoveryConstants.LOG_DISCOVERY_COMPLETED, "EC2", regionString, discoveredCount);

        } catch (AwsServiceException e) {
            log.error("AWS API error during EC2 discovery: {} [Request ID: {}]",
                    e.awsErrorDetails().errorMessage(), e.requestId(), e);
            // Depending on requirements, could publish an error event or throw
        } catch (Exception e) {
            log.error(DiscoveryConstants.LOG_DISCOVERY_FAILED, "EC2", regionString, e.getMessage(), e);
            // Depending on requirements, could publish an error event or throw
        } finally {
            if (ec2Client != null) {
                ec2Client.close();
                log.debug("EC2 client closed");
            }
        }
    }

    /**
     * Converts context map from Object values to String values.
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
     * Builds AWS filters based on job context parameters.
     */
    private List<Filter> buildFilters(Map<String, String> context) {
        AWSFilterBuilder filterBuilder = new AWSFilterBuilder();
        // Add instance state filter if specified
        if (context.containsKey(DiscoveryConstants.CONTEXT_PARAM_RESOURCE_ID)) {
            filterBuilder.withFilter("instance-id", context.get(DiscoveryConstants.CONTEXT_PARAM_RESOURCE_ID));
        }
        // By default, show only running instances unless otherwise specified
        if (!context.containsKey("includeTerminated") && !context.containsKey("includeAll")) {
            filterBuilder.withRunningInstances();
        }
        // Add tag filters if specified
        if (context.containsKey(DiscoveryConstants.CONTEXT_PARAM_TAG_KEY) &&
                context.containsKey(DiscoveryConstants.CONTEXT_PARAM_TAG_VALUE)) {
            filterBuilder.withTag(
                    context.get(DiscoveryConstants.CONTEXT_PARAM_TAG_KEY),
                    context.get(DiscoveryConstants.CONTEXT_PARAM_TAG_VALUE));
        }
        return filterBuilder.build();
    }

    /**
     * Maps an AWS EC2 Instance object to our internal EC2Resource model.
     */
    EC2Resource mapInstanceToEC2Resource(Instance instance, Reservation reservation, String region,
            ResourceType rt) {
        if (rt == null) {
            log.error("ResourceType record not found for ID: {} or {}. Cannot create EC2Resource.",
                    RESOURCE_TYPE_ID, EC2Resource.HIERARCHY_TYPE_ID);
            return null;
        }

        String name = instance.tags().stream()
                .filter(tag -> AWSConstants.TAG_NAME.equalsIgnoreCase(tag.key()))
                .findFirst()
                .map(tag -> tag.value())
                .orElse(instance.instanceId());

        EC2Resource resource = new EC2Resource(rt, instance.instanceId(), name);
        resource.setAccountId(reservation.ownerId() != null ? reservation.ownerId() : configService.getAccountId());
        resource.setRegion(region);
        resource.setCreatedAt(instance.launchTime());
        resource.setUpdatedAt(Instant.now()); // Placeholder
        resource.setLastDiscoveredAt(Instant.now());
        resource.setStatus(instance.state().nameAsString()); // EC2Resource specific status
        resource.setInstanceType(instance.instanceTypeAsString());
        resource.setImageId(instance.imageId());
        resource.setPrivateIpAddress(instance.privateIpAddress());
        resource.setPublicIpAddress(instance.publicIpAddress());
        resource.setVpcId(instance.vpcId());
        resource.setSubnetId(instance.subnetId());
        Map<String, String> resourceTags = new HashMap<>();
        instance.tags().forEach(tag -> resourceTags.put(tag.key(), tag.value()));
        resource.setTags(resourceTags);

        return resource;
    }

    /**
     * Helper to find the EC2 ResourceType record among the supported types, whichever of the two
     * ids the hierarchy registry declared.
     */
    ResourceType findSupportedType() {
        for (ResourceType type : getSupportedResourceTypes()) {
            if (EC2Resource.isEc2TypeId(type.id())) {
                return type;
            }
        }
        log.warn("ResourceType not found in supported types: {}", RESOURCE_TYPE_ID);
        return null;
    }

    /**
     * Helper method to check if a resource matches context filters (if any).
     * Note: Most filtering is done via AWS API, this is for potential
     * post-filtering.
     */
    private boolean matchesFilters(EC2Resource resource, Map<String, String> context) {
        if (resource == null)
            return false;
        // Example post-filter: Only include if a specific tag key exists (if requested
        // in context)
        if (context.containsKey(DiscoveryConstants.CONTEXT_PARAM_TAG_KEY) &&
                !context.containsKey(DiscoveryConstants.CONTEXT_PARAM_TAG_VALUE)) {
            String tagKey = context.get(DiscoveryConstants.CONTEXT_PARAM_TAG_KEY);
            return resource.getTags() != null && resource.getTags().containsKey(tagKey);
        }
        // By default, assume API filters were sufficient
        return true;
    }

    /**
     * Helper method to publish resource events to Kafka.
     */
    protected void publishResourceEvent(String accountId, EC2Resource resource, ChangeType changeType) {
        try {
            ResourceEvent event = new ResourceEvent(
                    CloudProvider.AWS.toString(),
                    accountId,
                    changeType,
                    resource);
            log.debug("Publishing resource event: {}", event);
            kafkaTemplate.send(resourceEventsTopic, resource.getResourceId(), event); // Use String resourceId for key
        } catch (Exception e) {
            log.error("Error publishing resource event for EC2 {}: {}",
                    resource.getResourceId(), e.getMessage(), e);
        }
    }

    // Removed MockDiscoveryJob
    // Removed deprecated performDiscovery method
}