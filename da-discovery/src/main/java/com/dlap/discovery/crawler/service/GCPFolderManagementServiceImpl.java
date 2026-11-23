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

package com.dlap.discovery.crawler.service;

import com.dlap.discovery.catalog.persistence.CloudHierarchyRegistry;
import com.dlap.discovery.catalog.persistence.IResourceCrawlerRegistry;
import com.dlap.discovery.catalog.service.ICatalogService;
import com.dlap.discovery.client.dto.FolderDTO;
import com.dlap.discovery.client.dto.ProjectDTO;
import com.dlap.discovery.common.auth.CloudAuthenticationService;
import com.dlap.discovery.providers.gcp.config.GCPConfigService;
import com.dlap.discovery.common.model.ResourceType;
import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.common.service.FolderManagementException;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.cloud.resourcemanager.v3.Folder;
import com.google.cloud.resourcemanager.v3.FoldersClient;
import com.google.cloud.resourcemanager.v3.FoldersSettings;
import com.google.cloud.resourcemanager.v3.ListFoldersRequest;
import com.google.cloud.resourcemanager.v3.ListProjectsRequest;
import com.google.cloud.resourcemanager.v3.Project;
import com.google.cloud.resourcemanager.v3.ProjectsClient;
import com.google.cloud.resourcemanager.v3.ProjectsSettings;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Implementation of IFolderManagementService for GCP.
 * Handles folder and project operations using GCP Resource Manager API.
 */
@Service
public class GCPFolderManagementServiceImpl implements IFolderManagementService {

    private static final Logger log = LoggerFactory.getLogger(GCPFolderManagementServiceImpl.class);
    private static final Pattern FOLDER_ID_PATTERN = Pattern.compile("folders/([0-9]+)");

    private final CloudAuthenticationService authService;
    private final ICatalogService catalogService;
    private final GCPConfigService configService;
    private final IResourceCrawlerRegistry crawlerRegistry;
    private final CloudHierarchyRegistry cloudHierarchyRegistry;

    @Autowired
    public GCPFolderManagementServiceImpl(
        GCPConfigService configService,
        ICatalogService catalogService,
        CloudAuthenticationService authService,
        IResourceCrawlerRegistry crawlerRegistry,
        CloudHierarchyRegistry cloudHierarchyRegistry
    ) {
        this.configService = configService;
        this.catalogService = catalogService;
        this.authService = authService;
        this.crawlerRegistry = crawlerRegistry;
        this.cloudHierarchyRegistry = cloudHierarchyRegistry;
    }

    // public List<FolderDTO> listFolders(String parentFolderId, List<String> resourceTypeIds) {
    //     return listFolders(parentFolderId, null); // Default to null resourceTypeIds
    // }

    /**
     * Lists folders recursively under a parent folder with optional resource type filtering.
     *
     * @param parentFolderId The ID of the parent folder
     * @param resourceTypeIds List of resource type IDs to filter crawlers (e.g., ["gcp_gcs_bucket"])
     * @return List of FolderDTOs including all sub-folders
     */
    @Override
    public List<FolderDTO> listFolders(String parentFolderId, List<String> resourceTypeIds) {
        log.debug("Listing folders recursively under parent folder: {} with resource types: {}", parentFolderId, resourceTypeIds);
        List<FolderDTO> folders = new ArrayList<>();

        try (FoldersClient foldersClient = createFoldersClient()) {
            folders.addAll(listAllFoldersRecursively(foldersClient, parentFolderId, resourceTypeIds));
            return folders;
        } catch (Exception e) {
            log.error("Error listing folders recursively under parent {}: {}", parentFolderId, e.getMessage());
            throw new FolderManagementException("Failed to list folders recursively", e);
        }
    }

    /**
     * Recursively lists all folders under a parent folder and triggers resource discovery for projects.
     *
     * @param foldersClient The FoldersClient instance
     * @param parentFolderId The ID of the parent folder
     * @param resourceTypeIds List of resource type IDs to filter crawlers (e.g., ["gcp_gcs_bucket"])
     * @return List of FolderDTOs including all sub-folders
     */
    private List<FolderDTO> listAllFoldersRecursively(FoldersClient foldersClient, String parentFolderId, List<String> resourceTypeIds) {
        List<FolderDTO> folders = new ArrayList<>();
        ListFoldersRequest request = ListFoldersRequest.newBuilder().setParent("folders/" + parentFolderId).build();

        for (Folder folder : foldersClient.listFolders(request).iterateAll()) {
            FolderDTO folderDTO = convertToFolderDTO(folder);
            folders.add(folderDTO);
            log.debug("Processing folder: {} (ID: {})", folderDTO.getDisplayName(), folderDTO.getFolderId());

            // Recursively fetch sub-folders
            String folderId = folderDTO.getFolderId();
            folders.addAll(listAllFoldersRecursively(foldersClient, folderId, resourceTypeIds));

            // For each project in this folder, trigger only crawlers matching resourceTypeIds
            List<ProjectDTO> projects = listProjects(folderId);
            for (ProjectDTO project : projects) {
                String projectId = project.getProjectId();
                log.debug("Found project in folder {}: {}", folderId, projectId);

                // Skip discovery if no resourceTypeIds are provided
                if (resourceTypeIds == null || resourceTypeIds.isEmpty()) {
                    log.warn("No resourceTypeIds provided for project {}. Skipping resource discovery.", projectId);
                    continue;
                }

                try {
                    // Create context map
                    Map<String, Object> context = new HashMap<>();
                    context.put("idType", "project"); // Indicate this is a project-based job
                    context.put("folderId", folderId); // Optional: include folderId for context
                    context.put("resourceTypesToCrawl", resourceTypeIds); // Pass resourceTypeIds to context

                    // Convert resourceTypeIds to ResourceType objects
                    List<ResourceType> resourceTypes = resourceTypeIds
                        .stream()
                        .map(cloudHierarchyRegistry::getResourceType)
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toList());

                    if (resourceTypes.isEmpty()) {
                        log.error(
                            "No valid ResourceType objects found for resourceTypeIds {} in project {}. Skipping discovery.",
                            resourceTypeIds,
                            projectId
                        );
                        continue;
                    }

                    // Get crawlers for the specified resource types
                    List<IResourceCrawler<?>> crawlers = new ArrayList<>(
                        crawlerRegistry.getCrawlersForTypes(CloudProvider.GCP, resourceTypes)
                    );

                    if (crawlers.isEmpty()) {
                        log.error(
                            "No crawlers found for provider {} and resource types {} in project {}",
                            CloudProvider.GCP,
                            resourceTypeIds,
                            projectId
                        );
                        // Log all registered crawlers for debugging
                        List<IResourceCrawler<?>> allCrawlers = crawlerRegistry.getAllCrawlers();
                        log.error("Registered crawlers and their supported resource types:");
                        for (IResourceCrawler<?> registeredCrawler : allCrawlers) {
                            log.error(
                                "Crawler: {}, Provider: {}, Supported Types: {}",
                                registeredCrawler.getClass().getSimpleName(),
                                registeredCrawler.getProvider(),
                                registeredCrawler.getSupportedResourceTypes()
                            );
                        }
                        continue;
                    }

                    // Trigger crawlers
                    boolean triggeredSuccessfully = false;
                    for (IResourceCrawler<?> crawler : crawlers) {
                        try {
                            log.info(
                                "Triggering async crawler: {} for project: {} and resourceTypes: {}",
                                crawler.getClass().getSimpleName(),
                                projectId,
                                resourceTypeIds
                            );
                            crawler.discoverResourcesAsync(projectId, context);
                            triggeredSuccessfully = true;
                        } catch (Exception e) {
                            log.error(
                                "Error triggering crawler: {} for project: {}",
                                crawler.getClass().getSimpleName(),
                                projectId,
                                e.getMessage()
                            );
                        }
                    }

                    if (!triggeredSuccessfully) {
                        log.warn("No crawlers were successfully triggered for project {}", projectId);
                    }
                } catch (Exception e) {
                    log.error("Error setting up resource discovery for project {}: {}", projectId, e.getMessage());
                }
            }
        }
        return folders;
    }

    @Override
    public List<ProjectDTO> listProjects(String folderId) {
        log.debug("Listing projects in folder: {}", folderId);
        List<ProjectDTO> projects = new ArrayList<>();

        try (ProjectsClient projectsClient = createProjectsClient()) {
            ListProjectsRequest request = ListProjectsRequest.newBuilder().setParent("folders/" + folderId).build();

            for (Project project : projectsClient.listProjects(request).iterateAll()) {
                projects.add(convertToProjectDTO(project));
            }

            return projects;
        } catch (Exception e) {
            log.error("Error listing projects in folder {}: {}", folderId, e.getMessage());
            throw new FolderManagementException("Failed to list projects", e);
        }
    }

    @Override
    public boolean isFolderProcessed(String folderId, String schemaName) {
        log.info("Checking if folder {} is processed in schema {}", folderId, schemaName);
        // Stub implementation until proper integration with ICatalogService is implemented
        return false;
    }

    @Override
    public void markFolderAsProcessed(String folderId, String schemaName) {
        log.info("Marking folder {} as processed in schema {}", folderId, schemaName);
        // Stub implementation until proper integration with ICatalogService is implemented
        String tableName = "_run_time_" + sanitizeFolderId(folderId);
        Map<String, String> properties = createProcessingProperties();

        // Log properties that would be set
        log.debug("Would set folder processing properties: {}", properties);
    }

    @Override
    public String extractFolderId(String folderName) {
        if (folderName == null) {
            return null;
        }

        Matcher matcher = FOLDER_ID_PATTERN.matcher(folderName);
        if (matcher.find()) {
            return matcher.group(1);
        }

        return folderName; // Return as-is if no match
    }

    /**
     * Creates a FoldersClient instance.
     *
     * @return Configured FoldersClient
     */
    private FoldersClient createFoldersClient() throws Exception {
        return FoldersClient.create(
            FoldersSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(authService.getCredentials())).build()
        );
    }

    /**
     * Creates a ProjectsClient instance.
     *
     * @return Configured ProjectsClient
     */
    private ProjectsClient createProjectsClient() throws Exception {
        return ProjectsClient.create(
            ProjectsSettings.newBuilder().setCredentialsProvider(FixedCredentialsProvider.create(authService.getCredentials())).build()
        );
    }

    /**
     * Converts a GCP Folder to FolderDTO.
     *
     * @param folder The GCP Folder object
     * @return FolderDTO representation
     */
    private FolderDTO convertToFolderDTO(Folder folder) {
        FolderDTO dto = new FolderDTO();
        dto.setFolderId(extractFolderId(folder.getName()));
        dto.setDisplayName(folder.getDisplayName());

        if (folder.getCreateTime() != null) {
            dto.setCreateTime(convertToZonedDateTime(folder.getCreateTime()));
        }

        if (folder.getUpdateTime() != null) {
            dto.setUpdateTime(convertToZonedDateTime(folder.getUpdateTime()));
        }

        dto.setState(folder.getState().name());
        dto.setParentName(folder.getParent());

        return dto;
    }

    /**
     * Converts a GCP Project to ProjectDTO.
     *
     * @param project The GCP Project object
     * @return ProjectDTO representation
     */
    private ProjectDTO convertToProjectDTO(Project project) {
        ProjectDTO dto = new ProjectDTO();
        dto.setProjectId(project.getProjectId());
        // For a v3 Project, we need to extract the number from the name
        // which is in format "projects/123456789"
        String projectName = project.getName();
        if (projectName != null && projectName.startsWith("projects/")) {
            try {
                String numberStr = projectName.substring("projects/".length());
                dto.setProjectNumber(Long.parseLong(numberStr));
            } catch (NumberFormatException e) {
                log.warn("Could not parse project number from name: {}", projectName);
            }
        }
        dto.setDisplayName(project.getDisplayName());

        if (project.getCreateTime() != null) {
            dto.setCreateTime(convertToZonedDateTime(project.getCreateTime()));
        }

        dto.setState(project.getState().name());

        if (project.getLabels() != null) {
            project.getLabels().forEach(dto.getLabels()::put);
        }

        return dto;
    }

    /**
     * Converts a com.google.protobuf.Timestamp to ZonedDateTime.
     *
     * @param timestamp Google protocol buffer timestamp
     * @return ZonedDateTime
     */
    private ZonedDateTime convertToZonedDateTime(com.google.protobuf.Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return ZonedDateTime.ofInstant(Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos()), ZoneId.systemDefault());
    }

    /**
     * Sanitizes a folder ID for use in table names.
     *
     * @param folderId The folder ID to sanitize
     * @return Sanitized folder ID
     */
    private String sanitizeFolderId(String folderId) {
        return folderId.replaceAll("[^a-zA-Z0-9]", "_");
    }

    /**
     * Creates properties for folder processing status.
     *
     * @return Map of properties
     */
    private Map<String, String> createProcessingProperties() {
        Map<String, String> properties = new HashMap<>();
        String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        properties.put("last_run_date", timestamp);
        properties.put("last_updated", timestamp);
        return properties;
    }
}
