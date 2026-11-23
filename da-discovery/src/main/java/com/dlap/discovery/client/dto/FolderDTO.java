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

package com.dlap.discovery.client.dto;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Data Transfer Object for GCP folders.
 */
public class FolderDTO {
    private String folderId;
    private String displayName;
    private ZonedDateTime createTime;
    private ZonedDateTime updateTime;
    private String state;
    private String parentName;
    private Map<String, String> labels = new HashMap<>();
    
    // Getters and setters
    public String getFolderId() {
        return folderId;
    }
    
    public void setFolderId(String folderId) {
        this.folderId = folderId;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
    
    public ZonedDateTime getCreateTime() {
        return createTime;
    }
    
    public void setCreateTime(ZonedDateTime createTime) {
        this.createTime = createTime;
    }
    
    public ZonedDateTime getUpdateTime() {
        return updateTime;
    }
    
    public void setUpdateTime(ZonedDateTime updateTime) {
        this.updateTime = updateTime;
    }
    
    public String getState() {
        return state;
    }
    
    public void setState(String state) {
        this.state = state;
    }
    
    public String getParentName() {
        return parentName;
    }
    
    public void setParentName(String parentName) {
        this.parentName = parentName;
    }
    
    public Map<String, String> getLabels() {
        return labels;
    }
    
    public void setLabels(Map<String, String> labels) {
        this.labels = labels != null ? labels : new HashMap<>();
    }
    
    @Override
    public String toString() {
        return String.format("FolderDTO{folderId='%s', displayName='%s', state='%s'}", 
            folderId, displayName, state);
    }
} 