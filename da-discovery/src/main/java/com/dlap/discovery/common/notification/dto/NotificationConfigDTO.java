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

package com.dlap.discovery.common.notification.dto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DTO for notification channel configuration.
 */
public class NotificationConfigDTO {
    private List<ChannelConfig> channels = new ArrayList<>();
    private Map<String, Object> defaultProperties = new HashMap<>();
    
    /**
     * Configuration for a notification channel.
     */
    public static class ChannelConfig {
        private String channelType;
        private boolean enabled;
        private Map<String, Object> properties = new HashMap<>();
        
        // Getters and setters
        public String getChannelType() {
            return channelType;
        }
        
        public void setChannelType(String channelType) {
            this.channelType = channelType;
        }
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public Map<String, Object> getProperties() {
            return properties;
        }
        
        public void setProperties(Map<String, Object> properties) {
            this.properties = properties != null ? properties : new HashMap<>();
        }
    }
    
    // Getters and setters
    public List<ChannelConfig> getChannels() {
        return channels;
    }
    
    public void setChannels(List<ChannelConfig> channels) {
        this.channels = channels != null ? channels : new ArrayList<>();
    }
    
    public Map<String, Object> getDefaultProperties() {
        return defaultProperties;
    }
    
    public void setDefaultProperties(Map<String, Object> defaultProperties) {
        this.defaultProperties = defaultProperties != null ? defaultProperties : new HashMap<>();
    }
} 