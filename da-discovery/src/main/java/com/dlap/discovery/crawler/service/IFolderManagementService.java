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

import java.util.List;
import java.util.Map;

import com.dlap.discovery.client.dto.FolderDTO;
import com.dlap.discovery.client.dto.ProjectDTO;

/**
 * Interface for managing cloud folder resources and related operations.
 */
public interface IFolderManagementService {
    /**
     * Retrieves all folders under a parent folder.
     * @param parentFolderId The ID of the parent folder
     * @return List of folder DTOs
     */
   public List<FolderDTO> listFolders(String parentFolderId, List<String> resourceTypeIds);

    /**
     * Lists all projects within a folder.
     * @param folderId The folder ID to list projects from
     * @return List of project DTOs
     */
    List<ProjectDTO> listProjects(String folderId);

    /**
     * Checks if a folder has been previously processed.
     * @param folderId The folder ID to check
     * @param schemaName The schema name where folder tracking is stored
     * @return true if the folder has been processed
     */
    boolean isFolderProcessed(String folderId, String schemaName);

    /**
     * Updates the folder processing status.
     * @param folderId The folder ID that was processed
     * @param schemaName The schema name where folder tracking is stored
     */
    void markFolderAsProcessed(String folderId, String schemaName);

    /**
     * Extracts the folder ID from a full folder name.
     * @param folderName Full folder name (e.g., "folders/123456")
     * @return Extracted folder ID
     */
    String extractFolderId(String folderName);
} 