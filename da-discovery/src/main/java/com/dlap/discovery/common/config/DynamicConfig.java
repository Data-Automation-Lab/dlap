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

package com.dlap.discovery.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
@ConfigurationProperties()
public class DynamicConfig {

    @Value("${google.cloud.projectId}")
    private String projectId;

    @Value("${google.cloud.folderId}")
    private String folderId;

    @Value("${google.cloud.orgId}")
    private String orgId;

    @Value("${google.cloud.searchText}")
    private String searchText;

    @Value("${google.cloud.parent}")
    private String parent;

    @Value("${google.cloud.folderName}")
    private String folderName;


    // Getter and Setter methods
    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getFolderId() {
        return folderId;
    }

    public void setFolderId(String folderId) {
        this.folderId = folderId;
    }

    public String getOrgId() {
        return orgId;
    }

    public void setOrgId(String orgId) {
        this.orgId = orgId;
    }

    public String getSearchText() {
        return searchText;
    }

    public void setSearchText(String searchText) {
        this.searchText = searchText;
    }

    public String getParent() {
        return parent;
    }

    public void setParent(String parent) {
        this.parent = parent;
    }

    public String getFolderName() {
        return folderName;
    }

    public void setFolderName(String folderName) {
        this.folderName = folderName;
    }

    @Override
    public String toString() {
        return (
            "DynamicConfig{" +
            "projectId='" +
            projectId +
            '\'' +
            ", folderId='" +
            folderId +
            '\'' +
            ", orgId='" +
            orgId +
            '\'' +
            ", searchText='" +
            searchText +
            '\'' +
            ", parent='" +
            parent +
            '\'' +
            ", folderName='" +
            folderName +
            '}'
        );
    }
}
