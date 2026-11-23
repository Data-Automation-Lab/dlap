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

import java.util.Collection;
import org.springframework.beans.factory.annotation.Autowired;

import com.dlap.discovery.common.model.enums.CloudProvider;
import com.dlap.discovery.crawler.service.IFolderManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;



@Component
public class GcpHandler implements CloudProviderHandler {

    private final IFolderManagementService folderManagementService;
    private static final Logger log = LoggerFactory.getLogger(GcpHandler.class);     

    @Autowired
    public GcpHandler(IFolderManagementService folderManagementService) {
        this.folderManagementService = folderManagementService;
    }

     @Override
    public boolean supports(CloudProvider provider) {
        return provider == CloudProvider.GCP;
    }
    
    // ...existing code...
    public void process(DiscoveryJob job) {
        // Use accountId from the job as folderId
        String folderId = job.getAccountId();
        log.info("Processing GCP resources for folder/account: " + folderId);

        // Extract resourceTypesToCrawl from job parameters if present
        java.util.List<String> resourceTypeIds = null;
        if (job.getParameters() != null) {
            Object typesParam = job.getParameters().get("resourceTypesToCrawl");
            if (typesParam instanceof java.util.List<?>) {
                // Safe unchecked cast
                resourceTypeIds = ((java.util.List<?>) typesParam).stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
            }
        }

        if (resourceTypeIds != null && !resourceTypeIds.isEmpty()) {
            folderManagementService.listFolders(folderId, resourceTypeIds);                     

        }
    }
// ...existing code...
}
   